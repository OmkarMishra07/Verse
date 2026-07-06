package com.example.data.remote

import android.util.Log
import com.example.data.model.Track
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
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
    val timestamp: Long = System.currentTimeMillis()
)

object JammingService {
    private val db = FirebaseFirestore.getInstance()
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
            true
        } catch (e: Exception) {
            Log.e(TAG, "joinRoom failed", e)
            false
        }
    }

    suspend fun leaveRoom(roomId: String, participantName: String): Boolean {
        return try {
            roomsCol().document(roomId).update("participants", com.google.firebase.firestore.FieldValue.arrayRemove(participantName)).await()
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

    suspend fun sendMessage(roomId: String, senderName: String, message: String) {
        try {
            val docRef = messagesCol(roomId).document()
            val chatMsg = ChatMessage(id = docRef.id, senderName = senderName, message = message)
            docRef.set(chatMsg).await()
        } catch (e: Exception) {
            Log.e(TAG, "sendMessage failed", e)
        }
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
