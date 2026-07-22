package com.example.data.remote

import android.util.Log
import com.example.BuildConfig
import com.example.data.model.Track
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import com.google.firebase.database.ServerValue
import com.google.firebase.database.Transaction
import com.google.firebase.database.MutableData
import com.google.firebase.database.DatabaseReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import com.google.firebase.firestore.Source
import com.example.VerseApplication

// ─── Data Models (identical to GOATU) ────────────────────────────────────────

data class JammingRoom(
    val roomId: String = "",
    val hostId: String = "",
    val hostName: String = "",
    val currentTrackId: String = "",
    val currentTrackTitle: String = "",
    val currentTrackArtist: String = "",
    val currentTrackThumbnail: String = "",
    val currentTrackDuration: String = "",
    val playing: Boolean = false,
    val positionMs: Long = 0L,
    val updatedAt: Long = System.currentTimeMillis(),
    val createdAt: Long = 0L, // Used for 2-hour room limit
    val participants: List<String> = emptyList(),
    val kicked: List<String> = emptyList(),
    val lastActivityTimestamp: com.google.firebase.Timestamp? = null,
    val roomVersion: Int = 1,
    val lastReadMessage: Map<String, Long> = emptyMap(),
    val blocked: List<String> = emptyList()
)

data class ChatMessage(
    val id: String = "",
    val senderName: String = "",
    val message: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val replyToMessageId: String? = null,
    val reactions: Map<String, List<String>> = emptyMap(), // GOATU format
    val isSystemMessage: Boolean = false
)

// ─── JammingService ───────────────────────────────────────────────────────────

object JammingService {
    private val db   = FirebaseFirestore.getInstance()
    private val rtdb = FirebaseDatabase.getInstance(BuildConfig.RTDB_URL)
    private const val TAG = "JammingService"

    private const val ROOM_COMPATIBILITY_VERSION = 1
    private val deviceId = java.util.UUID.randomUUID().toString()

    private var serverTimeOffset: Long = 0L

    init {
        // Force RTDB to stay offline by default so we don't consume concurrent connections
        // when users are just browsing their local library.
        rtdb.goOffline()
        
        // Track true server time to prevent local clock manipulation
        rtdb.getReference(".info/serverTimeOffset").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                serverTimeOffset = snapshot.getValue(Long::class.java) ?: 0L
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    fun isRtdbConnected(): Flow<Boolean> = callbackFlow {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                trySend(snapshot.getValue(Boolean::class.java) ?: false)
            }
            override fun onCancelled(error: DatabaseError) {
                trySend(false)
            }
        }
        rtdb.getReference(".info/connected").addValueEventListener(listener)
        awaitClose { rtdb.getReference(".info/connected").removeEventListener(listener) }
    }

    fun getTrueTime() = System.currentTimeMillis() + serverTimeOffset

    // Helper extension function to execute DatabaseReference transactions using coroutines
    private suspend fun DatabaseReference.runTransactionAwait(
        handler: (MutableData) -> Transaction.Result
    ): DataSnapshot = suspendCancellableCoroutine { continuation ->
        runTransaction(object : Transaction.Handler {
            override fun doTransaction(currentData: MutableData): Transaction.Result {
                return try {
                    handler(currentData)
                } catch (e: Exception) {
                    Transaction.abort()
                }
            }

            override fun onComplete(
                error: DatabaseError?,
                committed: Boolean,
                currentData: DataSnapshot?
            ) {
                if (error != null) {
                    continuation.resumeWithException(error.toException())
                } else {
                    if (currentData != null) {
                        continuation.resume(currentData)
                    } else {
                        continuation.resumeWithException(Exception("Transaction completed with null snapshot"))
                    }
                }
            }
        })
    }

    private class JoinResultHolder {
        var status: String = "SUCCESS"
    }

    private class LeaveResultHolder {
        var status: String = "SUCCESS"
        var isEmpty: Boolean = false
        var newHostName: String? = null
    }

    // Firestore — room metadata (participants, host) + chat messages
    private fun roomsCol()                       = db.collection("jamming_rooms")
    private fun messagesCol(roomId: String)       = roomsCol().document(roomId).collection("messages")

    // RTDB — real-time playback state only (replaces Firestore room doc for state fields)
    private fun rtdbStateRef(roomId: String)      = rtdb.getReference("jamming_rooms/$roomId/state")

    // ─── Room Lifecycle ───────────────────────────────────────────────────────

    fun forceDisconnect() {
        rtdb.goOffline()
    }

    suspend fun createRoom(roomId: String, hostId: String, hostName: String): Boolean {
        rtdb.goOnline()
        return try {
            val room = JammingRoom(
                roomId   = roomId,
                hostId   = hostId,
                hostName = hostName,
                participants = emptyList(), // Ghost users fixed: Participants exclusively managed in RTDB!
                lastActivityTimestamp = com.google.firebase.Timestamp.now(),
                createdAt = getTrueTime(),
                roomVersion = ROOM_COMPATIBILITY_VERSION
            )
            // Firestore — persistent room metadata
            roomsCol().document(roomId).set(room).await()
            roomsCol().document(roomId).update("roomVersion", ROOM_COMPATIBILITY_VERSION).await()

            // RTDB — seed participants + hostName so listenToRoom can serve kick-out check
            rtdbStateRef(roomId).updateChildren(mapOf<String, Any>(
                "hostName" to hostName,
                "createdAt" to ServerValue.TIMESTAMP,
                "roomVersion" to ROOM_COMPATIBILITY_VERSION,
                "participants/${hostName}/${deviceId}" to true
            )).await()
            rtdbStateRef(roomId).child("participants/${hostName}/${deviceId}").onDisconnect().removeValue()
            rtdbStateRef(roomId).child("emptySince").onDisconnect().setValue(ServerValue.TIMESTAMP)
            true
        } catch (e: Exception) {
            Log.e(TAG, "createRoom failed", e)
            rtdb.goOffline()
            false
        }
    }

    suspend fun joinRoom(roomId: String, participantName: String): String {
        rtdb.goOnline()
        val roomRef = roomsCol().document(roomId)
        return try {
            // Check if room exists in Firestore, forcing server fetch (Concern 10)
            val snapshot = roomRef.get(Source.SERVER).await()
            if (!snapshot.exists()) {
                // Concern 15: Clean up RTDB if Firestore is deleted
                rtdb.getReference("jamming_rooms/$roomId").removeValue()
                rtdb.goOffline()
                return "NOT_FOUND"
            }
            
            // Concern 11: Compatibility check
            val roomVersion = snapshot.getLong("roomVersion") ?: 1L
            if (roomVersion != ROOM_COMPATIBILITY_VERSION.toLong()) {
                rtdb.goOffline()
                return "VERSION_MISMATCH"
            }

            val firestoreHostName = snapshot.getString("hostName") ?: "Host"
            val firestoreCreatedAt = snapshot.getLong("createdAt") ?: getTrueTime()

            val resultHolder = JoinResultHolder()
            val stateRef = rtdbStateRef(roomId)

            // Run atomic RTDB transaction (Concern 1, 6, 7, 8, 14)
            stateRef.runTransactionAwait { currentData ->
                // Concern 14: Recreate RTDB state if Firestore exists but RTDB is missing
                if (currentData.value == null || !currentData.hasChild("createdAt")) {
                    currentData.child("hostName").value = firestoreHostName
                    currentData.child("createdAt").value = firestoreCreatedAt
                    currentData.child("roomVersion").value = ROOM_COMPATIBILITY_VERSION
                }

                // Check if the current user is blocked or kicked
                Log.d(TAG, "joinRoom transaction: user=$participantName, hasBlocked=${currentData.hasChild("blocked/$participantName")}, hasKicked=${currentData.hasChild("kicked/$participantName")}")
                if (currentData.hasChild("blocked/$participantName")) {
                    resultHolder.status = "BLOCKED"
                    return@runTransactionAwait Transaction.abort()
                }
                if (currentData.hasChild("kicked/$participantName")) {
                    resultHolder.status = "KICKED"
                    return@runTransactionAwait Transaction.abort()
                }

                // Check emptySince & expiration (Concern 1, 2, 7)
                val emptySince = currentData.child("emptySince").getValue(Long::class.java) ?: 0L
                if (emptySince > 0L) {
                    val elapsed = getTrueTime() - emptySince
                    if (elapsed > 20 * 60 * 1000L) {
                        resultHolder.status = "EXPIRED"
                        return@runTransactionAwait Transaction.abort()
                    }
                }

                // Count unique users (Concern 6 & 13)
                val participantsNode = currentData.child("participants")
                val uniqueUsers = participantsNode.children
                    .filter { it.hasChildren() || it.value != null }
                    .mapNotNull { it.key }

                if (!uniqueUsers.contains(participantName)) {
                    if (uniqueUsers.size >= 10) {
                        resultHolder.status = "FULL"
                        return@runTransactionAwait Transaction.abort()
                    }
                }

                // Clear emptySince inside transaction (Concern 8)
                if (emptySince > 0L) {
                    currentData.child("emptySince").value = null
                }

                // Add participant device (Concern 19: two devices same account)
                currentData.child("participants/$participantName/$deviceId").value = true
                if (currentData.child("joinedAt/$participantName").value == null) {
                    currentData.child("joinedAt/$participantName").value = getTrueTime()
                }
                resultHolder.status = "SUCCESS"

                Transaction.success(currentData)
            }

            val status = resultHolder.status
            if (status == "SUCCESS") {
                // Setup presence onDisconnect
                rtdbStateRef(roomId).child("participants/$participantName/$deviceId").onDisconnect().removeValue()
                rtdbStateRef(roomId).child("emptySince").onDisconnect().setValue(ServerValue.TIMESTAMP)

                // Cancel WorkManager cleanup since room is active (Concern 3, 9, 18)
                try {
                    androidx.work.WorkManager.getInstance(VerseApplication.instance)
                        .cancelUniqueWork("cleanup_room_$roomId")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to cancel cleanup work: ${e.message}")
                }

                // Keep Firestore room alive
                roomRef.update("lastActivityTimestamp", com.google.firebase.Timestamp.now()).await()

                // Send system message only if joining first time (no active devices before)
                val pSnapshot = rtdbStateRef(roomId).child("participants/$participantName").get().await()
                if (pSnapshot.childrenCount <= 1) {
                    sendMessage(roomId, "System", "$participantName joined the jam", isSystemMessage = true)
                }

                "SUCCESS"
            } else if (status == "EXPIRED") {
                // Delete atomically
                roomRef.delete().await()
                rtdb.getReference("jamming_rooms/$roomId").removeValue().await()
                rtdb.goOffline()
                "NOT_FOUND"
            } else {
                rtdb.goOffline()
                status
            }
        } catch (e: Exception) {
            Log.e(TAG, "joinRoom failed", e)
            rtdb.goOffline()
            if (e is com.google.firebase.firestore.FirebaseFirestoreException && e.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.UNAVAILABLE) {
                "OFFLINE"
            } else {
                "ERROR_UNKNOWN"
            }
        }
    }

    fun rejoinRoom(roomId: String, participantName: String) {
        val pRef = rtdbStateRef(roomId).child("participants/$participantName/$deviceId")
        pRef.setValue(true)
        pRef.onDisconnect().removeValue()
        rtdbStateRef(roomId).child("emptySince").onDisconnect().setValue(ServerValue.TIMESTAMP)

        // Cancel WorkManager cleanup
        try {
            androidx.work.WorkManager.getInstance(VerseApplication.instance)
                .cancelUniqueWork("cleanup_room_$roomId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel cleanup work: ${e.message}")
        }
    }

    fun updateLastReadMessage(roomId: String, participantName: String, timestamp: Long) {
        rtdbStateRef(roomId).child("lastReadMessage/$participantName").setValue(timestamp)
    }

    suspend fun kickUser(roomId: String, requesterName: String, targetName: String) {
        val stateRef = rtdbStateRef(roomId)
        stateRef.runTransactionAwait { currentData ->
            if (currentData.value == null) return@runTransactionAwait Transaction.success(currentData)
            val currentHost = currentData.child("hostName").getValue(String::class.java)
            if (currentHost != requesterName) {
                return@runTransactionAwait Transaction.abort()
            }
            currentData.child("kicked/$targetName").value = true
            // Remove participant devices so they're fully disconnected
            currentData.child("participants/$targetName").value = null
            currentData.child("joinedAt/$targetName").value = null
            // Clear typing status
            currentData.child("typing/$targetName").value = null
            Transaction.success(currentData)
        }
    }

    suspend fun blockUser(roomId: String, requesterName: String, targetName: String) {
        val stateRef = rtdbStateRef(roomId)
        stateRef.runTransactionAwait { currentData ->
            if (currentData.value == null) return@runTransactionAwait Transaction.success(currentData)
            val currentHost = currentData.child("hostName").getValue(String::class.java)
            if (currentHost != requesterName) {
                return@runTransactionAwait Transaction.abort()
            }
            currentData.child("blocked/$targetName").value = true
            currentData.child("kicked/$targetName").value = true
            // Remove participant devices so they're fully disconnected
            currentData.child("participants/$targetName").value = null
            currentData.child("joinedAt/$targetName").value = null
            // Clear typing status
            currentData.child("typing/$targetName").value = null
            Transaction.success(currentData)
        }
    }

    suspend fun unbanUser(roomId: String, requesterName: String, targetName: String) {
        Log.d(TAG, "unbanUser called: room=$roomId, requester=$requesterName, target=$targetName")
        val stateRef = rtdbStateRef(roomId)
        stateRef.runTransactionAwait { currentData ->
            if (currentData.value == null) return@runTransactionAwait Transaction.success(currentData)
            val currentHost = currentData.child("hostName").getValue(String::class.java)
            if (currentHost != requesterName) {
                return@runTransactionAwait Transaction.abort()
            }
            currentData.child("blocked/$targetName").value = null
            currentData.child("kicked/$targetName").value = null
            Transaction.success(currentData)
        }
    }

    suspend fun transferHost(roomId: String, requesterName: String, targetName: String): Boolean {
        Log.d(TAG, "transferHost: room=$roomId, requester=$requesterName, target=$targetName")
        val stateRef = rtdbStateRef(roomId)
        var success = false
        stateRef.runTransactionAwait { currentData ->
            if (currentData.value == null) return@runTransactionAwait Transaction.success(currentData)
            val currentHost = currentData.child("hostName").getValue(String::class.java)
            if (currentHost != requesterName) {
                Log.w(TAG, "transferHost rejected: requester $requesterName is not current host $currentHost")
                return@runTransactionAwait Transaction.abort()
            }
            currentData.child("hostName").value = targetName
            success = true
            Transaction.success(currentData)
        }
        return success
    }

    suspend fun destroyRoom(roomId: String) {
        try {
            // Cancel WorkManager cleanup since room is being destroyed
            try {
                androidx.work.WorkManager.getInstance(VerseApplication.instance)
                    .cancelUniqueWork("cleanup_room_$roomId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cancel cleanup work: ${e.message}")
            }

            roomsCol().document(roomId).delete().await()
            rtdb.getReference("jamming_rooms/$roomId").removeValue().await()
        } catch (e: Exception) {
            Log.e(TAG, "destroyRoom failed", e)
        }
    }

    suspend fun leaveRoom(roomId: String, participantName: String): Boolean {
        val roomRef = roomsCol().document(roomId)
        return try {
            val resultHolder = LeaveResultHolder()
            val stateRef = rtdbStateRef(roomId)

            // Run atomic RTDB transaction (Concern 5, 12, 16)
            stateRef.runTransactionAwait { currentData ->
                if (currentData.value == null) {
                    return@runTransactionAwait Transaction.success(currentData)
                }

                // Remove this device from participant
                currentData.child("participants/$participantName/$deviceId").value = null

                // Check if user has other devices left
                val userNode = currentData.child("participants/$participantName")
                if (!userNode.hasChildren()) {
                    userNode.value = null
                    // Clear joinedAt timestamp
                    currentData.child("joinedAt/$participantName").value = null
                    
                    // Clear temporary kicked status so they can rejoin (but do not clear blocked status)
                    if (currentData.hasChild("kicked/$participantName") && !currentData.hasChild("blocked/$participantName")) {
                        currentData.child("kicked/$participantName").value = null
                    }
                }

                // Count remaining participants (Concern 5 & 12)
                val participantsNode = currentData.child("participants")
                val remainingUsers = participantsNode.children
                    .filter { it.hasChildren() || it.value != null }
                    .mapNotNull { it.key }
                    .filter { it != participantName }

                if (remainingUsers.isEmpty()) {
                    resultHolder.isEmpty = true
                    currentData.child("emptySince").value = getTrueTime()
                } else {
                    // Host migration (Concern 12 & 5)
                    val currentHost = currentData.child("hostName").getValue(String::class.java)
                    if (currentHost == participantName) {
                        // Find the oldest remaining user based on joinedAt timestamp
                        val sortedRemaining = remainingUsers.sortedBy { 
                            currentData.child("joinedAt/$it").getValue(Long::class.java) ?: Long.MAX_VALUE 
                        }
                        val newHost = sortedRemaining.firstOrNull() ?: remainingUsers.first()
                        currentData.child("hostName").value = newHost
                        resultHolder.newHostName = newHost
                    }
                }
                resultHolder.status = "SUCCESS"
                Transaction.success(currentData)
            }

            // Wipe typing status
            rtdb.getReference("jamming_rooms/$roomId/typing/$participantName").removeValue().await()

            if (resultHolder.status == "SUCCESS") {
                if (resultHolder.isEmpty) {
                    // Schedule WorkManager cleanup check (Concern 3, 9, 18)
                    try {
                        val inputData = androidx.work.Data.Builder()
                            .putString("roomId", roomId)
                            .build()
                        val cleanupRequest = androidx.work.OneTimeWorkRequestBuilder<RoomCleanupWorker>()
                            .setInitialDelay(20, java.util.concurrent.TimeUnit.MINUTES)
                            .setInputData(inputData)
                            .build()
                        androidx.work.WorkManager.getInstance(VerseApplication.instance)
                            .enqueueUniqueWork(
                                "cleanup_room_$roomId",
                                androidx.work.ExistingWorkPolicy.REPLACE,
                                cleanupRequest
                            )
                        Log.d(TAG, "Scheduled RoomCleanupWorker for $roomId")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to schedule RoomCleanupWorker: ${e.message}")
                    }
                } else {
                    val newHost = resultHolder.newHostName
                    if (newHost != null) {
                        // Atomic host migration in Firestore
                        roomRef.update(
                            "hostName", newHost,
                            "lastActivityTimestamp", com.google.firebase.Timestamp.now()
                        ).await()
                        sendMessage(roomId, "System", "👑 $newHost is now the Host!", isSystemMessage = true)
                    } else {
                        // Keep Firestore room alive
                        roomRef.update("lastActivityTimestamp", com.google.firebase.Timestamp.now()).await()
                    }
                    sendMessage(roomId, "System", "$participantName left the jam", isSystemMessage = true)
                }
            }

            // Only drop the RTDB connection if the CURRENT user is the one leaving.
            val currentUser = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.displayName
            if (participantName == currentUser) {
                rtdb.goOffline()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "leaveRoom failed", e)
            val currentUser = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.displayName
            if (participantName == currentUser) {
                rtdb.goOffline()
            }
            false
        }
    }

    // ─── Playback State → RTDB (replaces Firestore writes) ───────────────────

    /**
     * Writes real-time playback state to RTDB instead of Firestore.
     * Fire-and-forget — no suspend, no await needed.
     * Saves ~4-6 Firestore writes/min per user during jam sessions.
     */
    fun updateRoomState(roomId: String, track: Track?, isPlaying: Boolean, positionMs: Long) {
        try {
            val state = mutableMapOf<String, Any>(
                "playing"    to isPlaying,
                "positionMs" to positionMs,
                "updatedAt"  to getTrueTime()
            )
            track?.let {
                state["currentTrackId"]        = it.id
                state["currentTrackTitle"]     = it.title
                state["currentTrackArtist"]    = it.artist
                state["currentTrackThumbnail"] = it.thumbnailUrl
                state["currentTrackDuration"]  = it.duration
            }
            rtdbStateRef(roomId).updateChildren(state) { error, _ ->
                if (error != null) {
                    Log.e(TAG, "RTDB updateRoomState failed: ${error.message}")
                } else {
                    Log.d(TAG, "RTDB state pushed: playing=$isPlaying pos=${positionMs}ms")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "updateRoomState failed", e)
        }
    }

    // ─── Room Listener → RTDB (replaces Firestore snapshot listener) ─────────

    /**
     * Listens to RTDB for real-time playback state.
     * Returns a Flow<JammingRoom?> with same shape as before so ViewModel is unchanged.
     */
    fun listenToRoom(roomId: String): Flow<JammingRoom?> = callbackFlow {
        val ref      = rtdbStateRef(roomId)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) {
                    trySend(null)
                    return
                }
                // Participants stored as { userName: { deviceId: true } } in RTDB
                val participants = snapshot.child("participants").children
                    .mapNotNull { it.key }
                    .toList()

                val emptySince = snapshot.child("emptySince").getValue(Long::class.java) ?: 0L
                if (emptySince > 0L && participants.isNotEmpty()) {
                    ref.child("emptySince").removeValue()
                }
                
                val kicked = snapshot.child("kicked").children
                    .mapNotNull { it.key }
                    .toList()

                val blocked = snapshot.child("blocked").children
                    .mapNotNull { it.key }
                    .toList()

                val lastReadMessage = mutableMapOf<String, Long>()
                snapshot.child("lastReadMessage").children.forEach { child ->
                    val key = child.key
                    val value = child.getValue(Long::class.java)
                    if (key != null && value != null) {
                        lastReadMessage[key] = value
                    }
                }

                val room = JammingRoom(
                    roomId                = roomId,
                    hostName              = snapshot.child("hostName").getValue(String::class.java) ?: "",
                    currentTrackId        = snapshot.child("currentTrackId").getValue(String::class.java) ?: "",
                    currentTrackTitle     = snapshot.child("currentTrackTitle").getValue(String::class.java) ?: "",
                    currentTrackArtist    = snapshot.child("currentTrackArtist").getValue(String::class.java) ?: "",
                    currentTrackThumbnail = snapshot.child("currentTrackThumbnail").getValue(String::class.java) ?: "",
                    currentTrackDuration  = snapshot.child("currentTrackDuration").getValue(String::class.java) ?: "",
                    playing               = snapshot.child("playing").getValue(Boolean::class.java) ?: false,
                    positionMs            = snapshot.child("positionMs").getValue(Long::class.java) ?: 0L,
                    updatedAt             = snapshot.child("updatedAt").getValue(Long::class.java) ?: getTrueTime(),
                    createdAt             = snapshot.child("createdAt").getValue(Long::class.java) ?: 0L,
                    participants          = participants,
                    kicked                = kicked,
                    lastReadMessage       = lastReadMessage,
                    blocked               = blocked
                )
                Log.d(TAG, "RTDB room update: track=${room.currentTrackId.take(8)}, playing=${room.playing}, pos=${room.positionMs}ms, participants=$participants")
                trySend(room)
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "listenToRoom RTDB cancelled: ${error.message}")
                close(error.toException())
            }
        }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    // ─── Chat Messages (Firestore — identical to GOATU) ──────────────────────

    suspend fun sendMessage(
        roomId: String,
        senderName: String,
        message: String,
        replyToMessageId: String? = null,
        isSystemMessage: Boolean = false
    ) {
        try {
            val chatRef = rtdb.getReference("jamming_rooms/$roomId/chats").push()
            val msgSys  = isSystemMessage || senderName == "System"
            val chatMsg = ChatMessage(
                id               = chatRef.key ?: "",
                senderName       = senderName,
                message          = message,
                timestamp        = getTrueTime(),
                replyToMessageId = replyToMessageId,
                isSystemMessage  = msgSys
            )
            chatRef.setValue(chatMsg).await()
        } catch (e: Exception) {
            Log.e(TAG, "sendMessage failed", e)
        }
    }

    // ─── Reactions (Firestore transaction — identical to GOATU) ──────────────

    suspend fun addReaction(roomId: String, messageId: String, emoji: String, userName: String) {
        try {
            val reactRef = rtdb.getReference("jamming_rooms/$roomId/chats/$messageId/reactions/$emoji")
            val snapshot = reactRef.get().await()
            val typeIndicator = object : com.google.firebase.database.GenericTypeIndicator<List<String>>() {}
            val usersList = snapshot.getValue(typeIndicator)?.toMutableList() ?: mutableListOf()
            
            if (!usersList.contains(userName)) {
                usersList.add(userName)
                reactRef.setValue(usersList).await()
            }
        } catch (e: Exception) {
            Log.e(TAG, "addReaction failed", e)
        }
    }

    // ─── Typing Status (RTDB — identical to GOATU) ────────────────────────────

    fun setTypingStatus(roomId: String, userName: String, isTyping: Boolean) {
        val typingRef = rtdb.getReference("jamming_rooms/$roomId/typing/$userName")
        if (isTyping) {
            typingRef.setValue(true)
            typingRef.onDisconnect().removeValue()
        } else {
            typingRef.removeValue()
        }
    }

    fun listenToTypingStatus(roomId: String, currentUserName: String): Flow<List<String>> = callbackFlow {
        val typingRef = rtdb.getReference("jamming_rooms/$roomId/typing")
        val listener  = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val typists = mutableListOf<String>()
                snapshot.children.forEach {
                    val name = it.key
                    if (name != null && name != currentUserName) typists.add(name)
                }
                trySend(typists)
            }
            override fun onCancelled(error: DatabaseError) { close(error.toException()) }
        }
        typingRef.addValueEventListener(listener)
        awaitClose { typingRef.removeEventListener(listener) }
    }

    // ─── Message Listener (Firestore — identical to GOATU) ───────────────────

    fun listenToMessages(roomId: String): Flow<List<ChatMessage>> = callbackFlow {
        val chatRef = rtdb.getReference("jamming_rooms/$roomId/chats").orderByChild("timestamp").limitToLast(50)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val messages = mutableListOf<ChatMessage>()
                for (child in snapshot.children) {
                    val msg = child.getValue(ChatMessage::class.java)
                    if (msg != null) messages.add(msg)
                }
                trySend(messages)
            }
            override fun onCancelled(error: DatabaseError) {
                close(error.toException())
            }
        }
        chatRef.addValueEventListener(listener)
        awaitClose { chatRef.removeEventListener(listener) }
    }
}
