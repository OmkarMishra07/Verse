package com.example.data.remote

import android.util.Log
import com.example.data.model.Track
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

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
    val participants: List<String> = emptyList()
)

data class ChatMessage(
    val id: String = "",
    val senderName: String = "",
    val message: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val replyToMessageId: String? = null,
    val reactions: Map<String, List<String>> = emptyMap(),
    val isSystemMessage: Boolean = false
)

object JammingService {
    private val db = FirebaseFirestore.getInstance()
    private val rtdb = FirebaseDatabase.getInstance()
    private const val TAG = "JammingService"

    private fun roomsCol() = db.collection("jamming_rooms")
    private fun messagesCol(roomId: String) = roomsCol().document(roomId).collection("messages")

    suspend fun createRoom(roomId: String, hostId: String, hostName: String): Boolean {
        return try {
            val room = JammingRoom(roomId = roomId, hostId = hostId, hostName = hostName, participants = listOf(hostName))
            roomsCol().document(roomId).set(room).await()
            true
        } catch (e: Exception) {
            Log.e(TAG, "createRoom failed", e)
            false
        }
    }

    suspend fun joinRoom(roomId: String, participantName: String): Boolean {
        return try {
            roomsCol().document(roomId).update("participants", com.google.firebase.firestore.FieldValue.arrayUnion(participantName)).await()
            sendMessage(roomId, "System", "$participantName joined the jam", isSystemMessage = true)
            true
        } catch (e: Exception) {
            Log.e(TAG, "joinRoom failed", e)
            false
        }
    }

    suspend fun leaveRoom(roomId: String, participantName: String): Boolean {
        return try {
            roomsCol().document(roomId).update("participants", com.google.firebase.firestore.FieldValue.arrayRemove(participantName)).await()
            sendMessage(roomId, "System", "$participantName left the jam", isSystemMessage = true)
            true
        } catch (e: Exception) {
            Log.e(TAG, "leaveRoom failed", e)
            false
        }
    }

    suspend fun updateRoomState(
        roomId: String,
        track: Track?,
        isPlaying: Boolean,
        positionMs: Long
    ) {
        try {
            val updates = mutableMapOf<String, Any>(
                "playing" to isPlaying,
                "positionMs" to positionMs,
                "updatedAt" to System.currentTimeMillis()
            )
            track?.let {
                updates["currentTrackId"] = it.id
                updates["currentTrackTitle"] = it.title
                updates["currentTrackArtist"] = it.artist
                updates["currentTrackThumbnail"] = it.thumbnailUrl
                updates["currentTrackDuration"] = it.duration
            }
            roomsCol().document(roomId).set(updates, SetOptions.merge()).await()
        } catch (e: Exception) {
            Log.e(TAG, "updateRoomState failed", e)
        }
    }

    fun listenToRoom(roomId: String): Flow<JammingRoom?> = callbackFlow {
        val listener = roomsCol().document(roomId).addSnapshotListener { snapshot, error ->
            if (error != null) {
                close(error)
                return@addSnapshotListener
            }
            if (snapshot != null && snapshot.exists()) {
                val room = snapshot.toObject(JammingRoom::class.java)
                trySend(room)
            } else {
                trySend(null)
            }
        }
        awaitClose { listener.remove() }
    }

    suspend fun sendMessage(roomId: String, senderName: String, message: String, replyToMessageId: String? = null, isSystemMessage: Boolean = false) {
        try {
            val docRef = messagesCol(roomId).document()
            val msgSys = isSystemMessage || senderName == "System"
            val chatMsg = ChatMessage(id = docRef.id, senderName = senderName, message = message, replyToMessageId = replyToMessageId, isSystemMessage = msgSys)
            docRef.set(chatMsg).await()
        } catch (e: Exception) {
            Log.e(TAG, "sendMessage failed", e)
        }
    }

    suspend fun addReaction(roomId: String, messageId: String, emoji: String, userName: String) {
        try {
            val docRef = messagesCol(roomId).document(messageId)
            db.runTransaction { transaction ->
                val snapshot = transaction.get(docRef)
                val msg = snapshot.toObject(ChatMessage::class.java) ?: return@runTransaction
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
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val typists = mutableListOf<String>()
                snapshot.children.forEach {
                    val name = it.key
                    if (name != null && name != currentUserName) {
                        typists.add(name)
                    }
                }
                trySend(typists)
            }
            override fun onCancelled(error: DatabaseError) {
                close(error.toException())
            }
        }
        typingRef.addValueEventListener(listener)
        awaitClose { typingRef.removeEventListener(listener) }
    }

    fun listenToMessages(roomId: String): Flow<List<ChatMessage>> = callbackFlow {
        val listener = messagesCol(roomId).orderBy("timestamp").addSnapshotListener { snapshot, error ->
            if (error != null) {
                close(error)
                return@addSnapshotListener
            }
            if (snapshot != null) {
                val messages = snapshot.documents.mapNotNull { it.toObject(ChatMessage::class.java) }
                trySend(messages)
            }
        }
        awaitClose { listener.remove() }
    }
}
