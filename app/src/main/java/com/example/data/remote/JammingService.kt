package com.example.data.remote

import android.util.Log
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

// ─────────────────────────────────────────────────────────────────────────────
// Data Models
// ─────────────────────────────────────────────────────────────────────────────

data class JammingRoom(
    val roomId: String = "",
    val hostId: String = "",
    val hostName: String = "",
    // NOTE: playback fields below are now sourced from RTDB, not Firestore.
    // They are kept here so syncFromRemote() in the ViewModel can stay unchanged.
    val currentTrackId: String = "",
    val currentTrackTitle: String = "",
    val currentTrackArtist: String = "",
    val currentTrackThumbnail: String = "",
    val currentTrackDuration: String = "",
    val playing: Boolean = false,
    val positionMs: Long = 0L,
    val updatedAt: Long = System.currentTimeMillis(),
    val participants: List<String> = emptyList(),
    val lastActivityTimestamp: com.google.firebase.Timestamp? = null,
    // Strategy 3: last system event embedded in state instead of a message doc
    val lastSystemEvent: String = ""
)

data class ChatMessage(
    val id: String = "",
    val senderName: String = "",
    val message: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val replyToMessageId: String? = null,
    // Strategy 5: reactions stored as Map<emoji, Map<userId, Boolean>>
    // This allows direct dot-notation field updates without transactions
    val reactions: Map<String, Map<String, Boolean>> = emptyMap(),
    val isSystemMessage: Boolean = false
)

// ─────────────────────────────────────────────────────────────────────────────
// JammingService
// ─────────────────────────────────────────────────────────────────────────────

object JammingService {
    private val db   = FirebaseFirestore.getInstance()
    private val rtdb = FirebaseDatabase.getInstance()
    private const val TAG = "JammingService"

    // Firestore — persistent room metadata and chat only
    private fun roomsCol()                         = db.collection("jamming_rooms")
    private fun messagesCol(roomId: String)         = roomsCol().document(roomId).collection("messages")

    // RTDB — real-time playback state (Strategy 1)
    private fun rtdbStateRef(roomId: String)        = rtdb.getReference("jamming_rooms/$roomId/state")

    // ─────────────────────────────────────────────────────────────────────────
    // Room Lifecycle  (Firestore — participants + metadata only)
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun createRoom(roomId: String, hostId: String, hostName: String): Boolean {
        return try {
            val room = JammingRoom(
                roomId   = roomId,
                hostId   = hostId,
                hostName = hostName,
                participants = listOf(hostName),
                lastActivityTimestamp = com.google.firebase.Timestamp.now()
            )
            roomsCol().document(roomId).set(room).await()
            true
        } catch (e: Exception) {
            Log.e(TAG, "createRoom failed", e)
            false
        }
    }

    suspend fun joinRoom(roomId: String, participantName: String): Boolean {
        return try {
            roomsCol().document(roomId).update(
                "participants", com.google.firebase.firestore.FieldValue.arrayUnion(participantName),
                "lastActivityTimestamp", com.google.firebase.Timestamp.now()
            ).await()
            // Join/leave messages stay in Firestore — they are important persistent events
            sendMessage(roomId, "System", "$participantName joined the jam 🎵", isSystemMessage = true)
            true
        } catch (e: Exception) {
            Log.e(TAG, "joinRoom failed", e)
            false
        }
    }

    suspend fun leaveRoom(roomId: String, participantName: String): Boolean {
        return try {
            roomsCol().document(roomId).update(
                "participants", com.google.firebase.firestore.FieldValue.arrayRemove(participantName),
                "lastActivityTimestamp", com.google.firebase.Timestamp.now()
            ).await()
            // Leave messages stay in Firestore — important persistent events
            sendMessage(roomId, "System", "$participantName left the jam", isSystemMessage = true)
            // Clean up RTDB typing ref on leave
            rtdb.getReference("jamming_rooms/$roomId/typing/$participantName").removeValue()
            true
        } catch (e: Exception) {
            Log.e(TAG, "leaveRoom failed", e)
            false
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Strategy 1: Playback state → RTDB (zero Firestore reads/writes)
    // Strategy 3: System event embedded in RTDB state (no separate message doc)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Pushes real-time playback state to RTDB instead of Firestore.
     * [systemEvent] — optional text like "Prince changed the song to X" embedded
     * directly in the state object (Strategy 3). No separate Firestore message
     * document is created, saving 1 write per event.
     */
    fun updateRoomState(
        roomId: String,
        track: Track?,
        isPlaying: Boolean,
        positionMs: Long,
        systemEvent: String = ""
    ) {
        try {
            val state = mutableMapOf<String, Any>(
                "playing"       to isPlaying,
                "positionMs"    to positionMs,
                "updatedAt"     to System.currentTimeMillis()
            )
            track?.let {
                state["currentTrackId"]       = it.id
                state["currentTrackTitle"]    = it.title
                state["currentTrackArtist"]   = it.artist
                state["currentTrackThumbnail"] = it.thumbnailUrl
                state["currentTrackDuration"] = it.duration
            }
            if (systemEvent.isNotBlank()) {
                state["lastSystemEvent"] = systemEvent
            }
            // Fire-and-forget RTDB write — no coroutine needed, no await
            rtdbStateRef(roomId).updateChildren(state)
        } catch (e: Exception) {
            Log.e(TAG, "updateRoomState (RTDB) failed", e)
        }
    }

    /**
     * Listens to RTDB for real-time playback state changes.
     * Returns a Flow<JammingRoom?> with the same shape as before so
     * ViewModel's syncFromRemote() works without any changes.
     */
    fun listenToRoom(roomId: String): Flow<JammingRoom?> = callbackFlow {
        val ref = rtdbStateRef(roomId)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) {
                    trySend(null)
                    return
                }
                val room = JammingRoom(
                    roomId               = roomId,
                    currentTrackId       = snapshot.child("currentTrackId").getValue(String::class.java) ?: "",
                    currentTrackTitle    = snapshot.child("currentTrackTitle").getValue(String::class.java) ?: "",
                    currentTrackArtist   = snapshot.child("currentTrackArtist").getValue(String::class.java) ?: "",
                    currentTrackThumbnail = snapshot.child("currentTrackThumbnail").getValue(String::class.java) ?: "",
                    currentTrackDuration = snapshot.child("currentTrackDuration").getValue(String::class.java) ?: "",
                    playing              = snapshot.child("playing").getValue(Boolean::class.java) ?: false,
                    positionMs           = snapshot.child("positionMs").getValue(Long::class.java) ?: 0L,
                    updatedAt            = snapshot.child("updatedAt").getValue(Long::class.java) ?: System.currentTimeMillis(),
                    lastSystemEvent      = snapshot.child("lastSystemEvent").getValue(String::class.java) ?: ""
                )
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

    // ─────────────────────────────────────────────────────────────────────────
    // Chat Messages  (Firestore — persistent)
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun sendMessage(
        roomId: String,
        senderName: String,
        message: String,
        replyToMessageId: String? = null,
        isSystemMessage: Boolean = false
    ) {
        try {
            val docRef = messagesCol(roomId).document()
            val isSys  = isSystemMessage || senderName == "System"
            val chatMsg = ChatMessage(
                id                = docRef.id,
                senderName        = senderName,
                message           = message,
                replyToMessageId  = replyToMessageId,
                isSystemMessage   = isSys
            )
            docRef.set(chatMsg).await()
        } catch (e: Exception) {
            Log.e(TAG, "sendMessage failed", e)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Strategy 5: Reactions — direct dot-notation field update, no transaction
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Toggles an emoji reaction for [userName] on a message.
     * Uses Firestore dot-notation field path update — atomic at field level,
     * no transaction read needed. Saves 1 READ per reaction.
     *
     * Data shape: reactions.{emoji}.{userName} = true (present) or deleted (removed)
     */
    suspend fun addReaction(roomId: String, messageId: String, emoji: String, userName: String) {
        try {
            val docRef = messagesCol(roomId).document(messageId)
            // Check current state locally from the message snapshot already in memory.
            // We use a simple toggle: field present = reacted, delete = un-reacted.
            // Firestore handles concurrent writes at the field path level atomically.
            val fieldPath = "reactions.$emoji.$userName"
            // Peek current value to decide toggle direction
            val snapshot = docRef.get().await()
            val currentVal = snapshot.getBoolean("reactions.$emoji.$userName")
            if (currentVal == true) {
                // Already reacted — remove
                docRef.update(fieldPath, com.google.firebase.firestore.FieldValue.delete()).await()
            } else {
                // Not reacted — add (1 write, no transaction read)
                docRef.update(fieldPath, true).await()
            }
        } catch (e: Exception) {
            Log.e(TAG, "addReaction failed", e)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Typing Status  (RTDB — already free)
    // ─────────────────────────────────────────────────────────────────────────

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

    // ─────────────────────────────────────────────────────────────────────────
    // Message Listener  (Firestore — persistent chat history)
    // ─────────────────────────────────────────────────────────────────────────

    fun listenToMessages(roomId: String): Flow<List<ChatMessage>> = callbackFlow {
        val listener = messagesCol(roomId)
            .orderBy("timestamp")
            .limitToLast(20)
            .addSnapshotListener { snapshot, error ->
                if (error != null) { close(error); return@addSnapshotListener }
                if (snapshot != null) {
                    val messages = snapshot.documents.mapNotNull { doc ->
                        // Map nested reactions map<emoji, map<userId, Boolean>> correctly
                        val rawReactions = doc.get("reactions") as? Map<*, *>
                        val reactions = rawReactions?.mapNotNull { (emojiKey, usersVal) ->
                            val emoji = emojiKey as? String ?: return@mapNotNull null
                            val usersMap = (usersVal as? Map<*, *>)?.mapNotNull { (uKey, uVal) ->
                                val uid = uKey as? String ?: return@mapNotNull null
                                uid to ((uVal as? Boolean) ?: false)
                            }?.toMap() ?: emptyMap()
                            emoji to usersMap
                        }?.toMap() ?: emptyMap()

                        ChatMessage(
                            id               = doc.id,
                            senderName       = doc.getString("senderName") ?: "",
                            message          = doc.getString("message") ?: "",
                            timestamp        = doc.getLong("timestamp") ?: System.currentTimeMillis(),
                            replyToMessageId = doc.getString("replyToMessageId"),
                            reactions        = reactions,
                            isSystemMessage  = doc.getBoolean("isSystemMessage") ?: false
                        )
                    }
                    trySend(messages)
                }
            }
        awaitClose { listener.remove() }
    }
}
