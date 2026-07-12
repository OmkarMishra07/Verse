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
    val participants: List<String> = emptyList(),
    val lastActivityTimestamp: com.google.firebase.Timestamp? = null
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
    // RTDB URL hardcoded because google-services.json doesn't include firebase_database_url
    private val rtdb = FirebaseDatabase.getInstance("https://verse-d9583-default-rtdb.asia-southeast1.firebasedatabase.app")
    private const val TAG = "JammingService"

    // Firestore — room metadata (participants, host) + chat messages
    private fun roomsCol()                       = db.collection("jamming_rooms")
    private fun messagesCol(roomId: String)       = roomsCol().document(roomId).collection("messages")

    // RTDB — real-time playback state only (replaces Firestore room doc for state fields)
    private fun rtdbStateRef(roomId: String)      = rtdb.getReference("jamming_rooms/$roomId/state")

    // ─── Room Lifecycle ───────────────────────────────────────────────────────

    suspend fun createRoom(roomId: String, hostId: String, hostName: String): Boolean {
        return try {
            val room = JammingRoom(
                roomId   = roomId,
                hostId   = hostId,
                hostName = hostName,
                participants = listOf(hostName),
                lastActivityTimestamp = com.google.firebase.Timestamp.now()
            )
            // Firestore — persistent room metadata
            roomsCol().document(roomId).set(room).await()
            // RTDB — seed participants + hostName so listenToRoom can serve kick-out check
            rtdbStateRef(roomId).updateChildren(mapOf<String, Any>(
                "hostName" to hostName,
                "participants/${hostName}" to true
            ))
            true
        } catch (e: Exception) {
            Log.e(TAG, "createRoom failed", e)
            false
        }
    }

    suspend fun joinRoom(roomId: String, participantName: String): String {
        val roomRef = roomsCol().document(roomId)
        return try {
            val result = db.runTransaction { transaction ->
                val snapshot = transaction.get(roomRef)
                if (!snapshot.exists()) {
                    return@runTransaction "NOT_FOUND"
                }
                val room = snapshot.toObject(JammingRoom::class.java)
                val participants = room?.participants ?: emptyList()
                
                // If they are already in the list, allow seamless rejoin
                if (participants.contains(participantName)) {
                    return@runTransaction "SUCCESS"
                }
                
                if (participants.size >= 10) {
                    return@runTransaction "FULL"
                }
                
                transaction.update(
                    roomRef,
                    "participants", com.google.firebase.firestore.FieldValue.arrayUnion(participantName),
                    "lastActivityTimestamp", com.google.firebase.Timestamp.now()
                )
                "SUCCESS"
            }.await()
            
            if (result == "SUCCESS") {
                // Mirror participant in RTDB for kick-out check in listenToRoom
                rtdbStateRef(roomId).child("participants/$participantName").setValue(true)
                sendMessage(roomId, "System", "$participantName joined the jam", isSystemMessage = true)
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "joinRoom failed", e)
            "ERROR"
        }
    }

    suspend fun leaveRoom(roomId: String, participantName: String): Boolean {
        return try {
            roomsCol().document(roomId).update(
                "participants", com.google.firebase.firestore.FieldValue.arrayRemove(participantName),
                "lastActivityTimestamp", com.google.firebase.Timestamp.now()
            ).await()
            sendMessage(roomId, "System", "$participantName left the jam", isSystemMessage = true)
            // Clean up RTDB
            rtdbStateRef(roomId).child("participants/$participantName").removeValue()
            rtdb.getReference("jamming_rooms/$roomId/typing/$participantName").removeValue()
            true
        } catch (e: Exception) {
            Log.e(TAG, "leaveRoom failed", e)
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
                "updatedAt"  to System.currentTimeMillis()
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
                // Participants stored as { userName: true } map in RTDB
                val participants = snapshot.child("participants").children
                    .mapNotNull { it.key }
                    .toList()

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
                    updatedAt             = snapshot.child("updatedAt").getValue(Long::class.java) ?: System.currentTimeMillis(),
                    participants          = participants
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
            val docRef  = messagesCol(roomId).document()
            val msgSys  = isSystemMessage || senderName == "System"
            val chatMsg = ChatMessage(
                id               = docRef.id,
                senderName       = senderName,
                message          = message,
                replyToMessageId = replyToMessageId,
                isSystemMessage  = msgSys
            )
            docRef.set(chatMsg).await()
        } catch (e: Exception) {
            Log.e(TAG, "sendMessage failed", e)
        }
    }

    // ─── Reactions (Firestore transaction — identical to GOATU) ──────────────

    suspend fun addReaction(roomId: String, messageId: String, emoji: String, userName: String) {
        try {
            val docRef = messagesCol(roomId).document(messageId)
            db.runTransaction { transaction ->
                val snapshot  = transaction.get(docRef)
                val msg       = snapshot.toObject(ChatMessage::class.java) ?: return@runTransaction
                val newReactions = msg.reactions.toMutableMap()
                val usersList = newReactions[emoji]?.toMutableList() ?: mutableListOf()
                if (!usersList.contains(userName)) {
                    usersList.add(userName)
                    newReactions[emoji] = usersList
                    transaction.update(docRef, "reactions", newReactions)
                }
            }.await()
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
        val listener = messagesCol(roomId)
            .orderBy("timestamp")
            .limitToLast(20)
            .addSnapshotListener { snapshot, error ->
                if (error != null) { close(error); return@addSnapshotListener }
                if (snapshot != null) {
                    val messages = snapshot.documents.mapNotNull { it.toObject(ChatMessage::class.java) }
                    trySend(messages)
                }
            }
        awaitClose { listener.remove() }
    }
}
