package com.example.data.remote

import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.example.data.local.LikedSong
import com.example.data.local.Playlist
import com.example.data.local.PlaylistSong
import com.example.data.local.RecentlyPlayed
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await

/**
 * Firestore Schema (human-readable in Firebase console):
 *
 * users/{userId}
 *   ├── displayName : String      ← top-level, visible immediately
 *   ├── email       : String
 *   ├── photoUrl    : String
 *   ├── createdAt   : Timestamp
 *   └── lastSeen    : Timestamp
 *
 *   likedSongs/ (subcollection)
 *     {videoId}/
 *       videoId, title, artist, thumbnailUrl, duration, durationMs, likedAt
 *
 *   playHistory/ (subcollection — max 50 entries, oldest pruned)
 *     {videoId}/
 *       videoId, title, artist, thumbnailUrl, duration, durationMs, playedAt
 *
 *   playlists/ (subcollection)
 *     {playlistId}/ (Room ID as string for stable key)
 *       playlistId, name, createdAt
 *       songs/ (sub-subcollection)
 *         {videoId}/
 *           videoId, title, artist, thumbnailUrl, duration, durationMs, displayOrder
 */
object FirestoreService {

    private val db = FirebaseFirestore.getInstance()
    private const val TAG = "FirestoreService"
    private const val MAX_HISTORY = 50

    // ──────────────────────────────────────────────────────────────
    //  User Document
    // ──────────────────────────────────────────────────────────────

    private fun userDoc(userId: String) = db.collection("users").document(userId)

    /** Upsert top-level profile fields (name + email always at the top of the doc). */
    suspend fun saveUserProfile(user: FirebaseUser) {
        try {
            val data = mapOf(
                "displayName" to (user.displayName ?: ""),
                "email"       to (user.email ?: ""),
                "photoUrl"    to (user.photoUrl?.toString() ?: ""),
                "lastSeen"    to com.google.firebase.Timestamp.now()
            )
            userDoc(user.uid).set(data, SetOptions.merge()).await()
        } catch (e: Exception) {
            Log.e(TAG, "saveUserProfile failed: ${e.message}")
            FirebaseCrashlytics.getInstance().recordException(e)
        }
    }

    /** Called only on first sign-up — sets createdAt. */
    suspend fun initUserProfile(user: FirebaseUser) {
        try {
            val doc = userDoc(user.uid).get().await()
            if (!doc.exists()) {
                val data = mapOf(
                    "displayName" to (user.displayName ?: ""),
                    "email"       to (user.email ?: ""),
                    "photoUrl"    to (user.photoUrl?.toString() ?: ""),
                    "createdAt"   to com.google.firebase.Timestamp.now(),
                    "lastSeen"    to com.google.firebase.Timestamp.now(),
                    "customNickname" to (user.displayName?.split(" ")?.firstOrNull() ?: ""),
                    "customWelcomeMessage" to "Welcome,"
                )
                userDoc(user.uid).set(data).await()
            } else {
                saveUserProfile(user)
            }
        } catch (e: Exception) {
            Log.e(TAG, "initUserProfile failed: ${e.message}")
            FirebaseCrashlytics.getInstance().recordException(e)
        }
    }

    suspend fun saveUserPreferences(userId: String, isModernMode: Boolean, hasSeenWheelTutorial: Boolean) {
        try {
            val data = mapOf(
                "preferredMode" to if (isModernMode) "modern" else "classic",
                "hasSeenWheelTutorial" to hasSeenWheelTutorial
            )
            userDoc(userId).set(data, SetOptions.merge()).await()
        } catch (e: Exception) {
            Log.e(TAG, "saveUserPreferences failed: ${e.message}")
            FirebaseCrashlytics.getInstance().recordException(e)
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  Liked Songs
    // ──────────────────────────────────────────────────────────────

    private fun likedSongsCol(userId: String) = userDoc(userId).collection("likedSongs")

    suspend fun upsertLikedSong(userId: String, song: LikedSong) {
        try {
            val data = mapOf(
                "videoId"      to song.videoId,
                "title"        to song.title,
                "artist"       to song.artist,
                "thumbnailUrl" to song.thumbnailUrl,
                "duration"     to song.duration,
                "likedAt"      to com.google.firebase.Timestamp(song.addedAt / 1000, 0),
                "updatedAt"    to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                "deleted"      to false
            )
            likedSongsCol(userId).document(song.videoId).set(data).await()
        } catch (e: Exception) {
            Log.e(TAG, "upsertLikedSong failed: ${e.message}")
            FirebaseCrashlytics.getInstance().recordException(e)
        }
    }

    suspend fun deleteLikedSong(userId: String, videoId: String) {
        try {
            val data = mapOf(
                "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                "deleted"   to true
            )
            likedSongsCol(userId).document(videoId).set(data, com.google.firebase.firestore.SetOptions.merge()).await()
        } catch (e: Exception) {
            Log.e(TAG, "deleteLikedSong failed: ${e.message}")
            FirebaseCrashlytics.getInstance().recordException(e)
        }
    }

    /** Returns all liked songs from Firestore updated since a timestamp. */
    suspend fun fetchLikedSongsUpdates(userId: String, sinceMs: Long): Pair<List<LikedSong>, List<String>> {
        return try {
            val query = if (sinceMs > 0) {
                val timestamp = com.google.firebase.Timestamp(sinceMs / 1000, ((sinceMs % 1000) * 1000000).toInt())
                likedSongsCol(userId).whereGreaterThan("updatedAt", timestamp)
            } else {
                likedSongsCol(userId)
            }
            
            val upserted = mutableListOf<LikedSong>()
            val deletedIds = mutableListOf<String>()
            
            query.get().await().documents.forEach { doc ->
                if (doc.getBoolean("deleted") == true) {
                    deletedIds.add(doc.id)
                } else {
                    val song = LikedSong(
                        videoId      = doc.getString("videoId") ?: return@forEach,
                        title        = doc.getString("title") ?: "",
                        artist       = doc.getString("artist") ?: "",
                        thumbnailUrl = doc.getString("thumbnailUrl") ?: "",
                        duration     = doc.getString("duration") ?: "",
                        addedAt      = (doc.getTimestamp("likedAt")?.seconds ?: 0L) * 1000L
                    )
                    upserted.add(song)
                }
            }
            upserted to deletedIds
        } catch (e: Exception) {
            Log.e(TAG, "fetchLikedSongsUpdates failed: ${e.message}")
            FirebaseCrashlytics.getInstance().recordException(e)
            emptyList<LikedSong>() to emptyList<String>()
        }
    }
    // ──────────────────────────────────────────────────────────────
    //  Play History (max 50)
    // ──────────────────────────────────────────────────────────────

    private fun historyCol(userId: String) = userDoc(userId).collection("playHistory")

    suspend fun upsertHistory(userId: String, entry: RecentlyPlayed) {
        try {
            val data = mapOf(
                "videoId"      to entry.videoId,
                "title"        to entry.title,
                "artist"       to entry.artist,
                "thumbnailUrl" to entry.thumbnailUrl,
                "duration"     to entry.duration,
                "playedAt"     to com.google.firebase.Timestamp(entry.playedAt / 1000, 0)
            )
            historyCol(userId).document(entry.videoId).set(data).await()
            // Removed pruneHistory(userId) here because fetching the entire history
            // on every track change causes exponential Firestore READ quota consumption.
            // Client already limits fetchHistory to MAX_HISTORY.
        } catch (e: Exception) {
            Log.e(TAG, "upsertHistory failed: ${e.message}")
            FirebaseCrashlytics.getInstance().recordException(e)
        }
    }

    suspend fun fetchHistory(userId: String): List<RecentlyPlayed> {
        return try {
            historyCol(userId)
                .orderBy("playedAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
                .limit(MAX_HISTORY.toLong())
                .get().await().documents.mapNotNull { doc ->
                    RecentlyPlayed(
                        videoId      = doc.getString("videoId") ?: return@mapNotNull null,
                        title        = doc.getString("title") ?: "",
                        artist       = doc.getString("artist") ?: "",
                        thumbnailUrl = doc.getString("thumbnailUrl") ?: "",
                        duration     = doc.getString("duration") ?: "",
                        playedAt     = (doc.getTimestamp("playedAt")?.seconds ?: 0L) * 1000L
                    )
                }
        } catch (e: Exception) {
            Log.e(TAG, "fetchHistory failed: ${e.message}")
            FirebaseCrashlytics.getInstance().recordException(e)
            emptyList()
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  Playlists
    // ──────────────────────────────────────────────────────────────

    private fun playlistsCol(userId: String) = userDoc(userId).collection("playlists")
    private fun playlistSongsCol(userId: String, playlistId: Long) =
        playlistsCol(userId).document(playlistId.toString()).collection("songs")

    suspend fun upsertPlaylist(userId: String, playlist: Playlist) {
        try {
            val data = mapOf(
                "playlistId" to playlist.id,
                "name"       to playlist.name,
                "createdAt"  to com.google.firebase.Timestamp(playlist.createdAt / 1000, 0),
                "updatedAt"  to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                "sharedUserId" to (playlist.sharedUserId ?: ""),
                "sharedPlaylistId" to (playlist.sharedPlaylistId ?: 0L),
                "deleted"    to false
            )
            playlistsCol(userId).document(playlist.id.toString()).set(data, com.google.firebase.firestore.SetOptions.merge()).await()
        } catch (e: Exception) {
            Log.e(TAG, "upsertPlaylist failed: ${e.message}")
            FirebaseCrashlytics.getInstance().recordException(e)
        }
    }

    suspend fun deletePlaylist(userId: String, playlistId: Long) {
        try {
            // Soft delete the playlist. Local DB cascade will handle songs.
            val data = mapOf(
                "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                "deleted"   to true
            )
            playlistsCol(userId).document(playlistId.toString()).set(data, com.google.firebase.firestore.SetOptions.merge()).await()
        } catch (e: Exception) {
            Log.e(TAG, "deletePlaylist failed: ${e.message}")
            FirebaseCrashlytics.getInstance().recordException(e)
        }
    }

    suspend fun upsertPlaylistSong(userId: String, song: PlaylistSong) {
        try {
            val data = mapOf(
                "videoId"      to song.videoId,
                "title"        to song.title,
                "artist"       to song.artist,
                "thumbnailUrl" to song.thumbnailUrl,
                "duration"     to song.duration,
                "displayOrder" to song.displayOrder
            )
            playlistSongsCol(userId, song.playlistId).document(song.videoId).set(data).await()
            // Touch parent playlist so it gets picked up by Delta Sync
            val parentUpdate = mapOf("updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp())
            playlistsCol(userId).document(song.playlistId.toString()).set(parentUpdate, com.google.firebase.firestore.SetOptions.merge()).await()
        } catch (e: Exception) {
            Log.e(TAG, "upsertPlaylistSong failed: ${e.message}")
            FirebaseCrashlytics.getInstance().recordException(e)
        }
    }

    suspend fun deletePlaylistSong(userId: String, playlistId: Long, videoId: String) {
        try {
            playlistSongsCol(userId, playlistId).document(videoId).delete().await()
            // Touch parent playlist so it gets picked up by Delta Sync
            val parentUpdate = mapOf("updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp())
            playlistsCol(userId).document(playlistId.toString()).set(parentUpdate, com.google.firebase.firestore.SetOptions.merge()).await()
        } catch (e: Exception) {
            Log.e(TAG, "deletePlaylistSong failed: ${e.message}")
            FirebaseCrashlytics.getInstance().recordException(e)
        }
    }

    /** Fetch playlists updated since timestamp. Returns pair of (upserted, deletedIds) */
    suspend fun fetchPlaylistUpdates(userId: String, sinceMs: Long): Pair<List<Pair<Playlist, List<PlaylistSong>>>, List<Long>> {
        return try {
            val query = if (sinceMs > 0) {
                val timestamp = com.google.firebase.Timestamp(sinceMs / 1000, ((sinceMs % 1000) * 1000000).toInt())
                playlistsCol(userId).whereGreaterThan("updatedAt", timestamp)
            } else {
                playlistsCol(userId)
            }
            
            val upserted = mutableListOf<Pair<Playlist, List<PlaylistSong>>>()
            val deletedIds = mutableListOf<Long>()
            
            query.get().await().documents.forEach { doc ->
                val id = doc.getLong("playlistId") ?: return@forEach
                if (doc.getBoolean("deleted") == true) {
                    deletedIds.add(id)
                } else {
                    val name = doc.getString("name") ?: ""
                    val createdAt = (doc.getTimestamp("createdAt")?.seconds ?: 0L) * 1000L
                    val sharedUserId = doc.getString("sharedUserId")?.takeIf { it.isNotBlank() }
                    val sharedPlaylistId = doc.getLong("sharedPlaylistId")?.takeIf { it > 0L }
                    val playlist = Playlist(id = id, name = name, createdAt = createdAt, sharedUserId = sharedUserId, sharedPlaylistId = sharedPlaylistId)

                    // If playlist was updated, just fetch all its current songs (usually a small number)
                    val songs = playlistSongsCol(userId, id)
                        .orderBy("displayOrder")
                        .get().await().documents.mapNotNull { sdoc ->
                            PlaylistSong(
                                playlistId   = id,
                                videoId      = sdoc.getString("videoId") ?: return@mapNotNull null,
                                title        = sdoc.getString("title") ?: "",
                                artist       = sdoc.getString("artist") ?: "",
                                thumbnailUrl = sdoc.getString("thumbnailUrl") ?: "",
                                duration     = sdoc.getString("duration") ?: "",
                                displayOrder = (sdoc.getLong("displayOrder") ?: 0L).toInt()
                            )
                        }
                    upserted.add(playlist to songs)
                }
            }
            upserted to deletedIds
        } catch (e: Exception) {
            Log.e(TAG, "fetchPlaylistUpdates failed: ${e.message}")
            FirebaseCrashlytics.getInstance().recordException(e)
            emptyList<Pair<Playlist, List<PlaylistSong>>>() to emptyList<Long>()
        }
    }

    /** Fetch a shared playlist from a specific user's Firestore by ID */
    suspend fun fetchSharedPlaylist(ownerId: String, playlistId: Long): Pair<Playlist, List<PlaylistSong>>? {
        return try {
            val doc = playlistsCol(ownerId).document(playlistId.toString()).get().await()
            if (!doc.exists() || doc.getBoolean("deleted") == true) return null

            val name = doc.getString("name") ?: ""
            val createdAt = (doc.getTimestamp("createdAt")?.seconds ?: 0L) * 1000L
            val playlist = Playlist(name = name, createdAt = createdAt, sharedUserId = ownerId, sharedPlaylistId = playlistId)

            val songsList = mutableListOf<PlaylistSong>()
            val songsQuery = playlistSongsCol(ownerId, playlistId).get().await()
            songsQuery.documents.forEach { songDoc ->
                val song = PlaylistSong(
                    playlistId   = playlist.id, // This will be the new local ID after insertion, we can set it to 0 for now and update later
                    videoId      = songDoc.id,
                    title        = songDoc.getString("title") ?: "",
                    artist       = songDoc.getString("artist") ?: "",
                    thumbnailUrl = songDoc.getString("thumbnailUrl") ?: "",
                    duration     = songDoc.getString("duration") ?: "",
                    displayOrder = songDoc.getLong("displayOrder")?.toInt() ?: 0
                )
                songsList.add(song)
            }
            playlist to songsList
        } catch (e: Exception) {
            Log.e(TAG, "fetchSharedPlaylist failed: ${e.message}")
            FirebaseCrashlytics.getInstance().recordException(e)
            null
        }
    }
}
