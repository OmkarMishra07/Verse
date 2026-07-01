package com.example.data.remote

import android.util.Log
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
                    "lastSeen"    to com.google.firebase.Timestamp.now()
                )
                userDoc(user.uid).set(data).await()
            } else {
                saveUserProfile(user)
            }
        } catch (e: Exception) {
            Log.e(TAG, "initUserProfile failed: ${e.message}")
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
                "likedAt"      to com.google.firebase.Timestamp(song.addedAt / 1000, 0)
            )
            likedSongsCol(userId).document(song.videoId).set(data).await()
        } catch (e: Exception) {
            Log.e(TAG, "upsertLikedSong failed: ${e.message}")
        }
    }

    suspend fun deleteLikedSong(userId: String, videoId: String) {
        try {
            likedSongsCol(userId).document(videoId).delete().await()
        } catch (e: Exception) {
            Log.e(TAG, "deleteLikedSong failed: ${e.message}")
        }
    }

    /** Returns all liked songs from Firestore. */
    suspend fun fetchLikedSongs(userId: String): List<LikedSong> {
        return try {
            likedSongsCol(userId).get().await().documents.mapNotNull { doc ->
                LikedSong(
                    videoId      = doc.getString("videoId") ?: return@mapNotNull null,
                    title        = doc.getString("title") ?: "",
                    artist       = doc.getString("artist") ?: "",
                    thumbnailUrl = doc.getString("thumbnailUrl") ?: "",
                    duration     = doc.getString("duration") ?: "",
                    addedAt      = (doc.getTimestamp("likedAt")?.seconds ?: 0L) * 1000L
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchLikedSongs failed: ${e.message}")
            emptyList()
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
            pruneHistory(userId)
        } catch (e: Exception) {
            Log.e(TAG, "upsertHistory failed: ${e.message}")
        }
    }

    /** Keep only the most recent MAX_HISTORY entries. */
    private suspend fun pruneHistory(userId: String) {
        try {
            val all = historyCol(userId)
                .orderBy("playedAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
                .get().await().documents
            if (all.size > MAX_HISTORY) {
                all.drop(MAX_HISTORY).forEach { it.reference.delete() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "pruneHistory failed: ${e.message}")
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
                "createdAt"  to com.google.firebase.Timestamp(playlist.createdAt / 1000, 0)
            )
            playlistsCol(userId).document(playlist.id.toString()).set(data).await()
        } catch (e: Exception) {
            Log.e(TAG, "upsertPlaylist failed: ${e.message}")
        }
    }

    suspend fun deletePlaylist(userId: String, playlistId: Long) {
        try {
            // Delete all songs first
            val songs = playlistSongsCol(userId, playlistId).get().await()
            songs.documents.forEach { it.reference.delete() }
            playlistsCol(userId).document(playlistId.toString()).delete().await()
        } catch (e: Exception) {
            Log.e(TAG, "deletePlaylist failed: ${e.message}")
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
        } catch (e: Exception) {
            Log.e(TAG, "upsertPlaylistSong failed: ${e.message}")
        }
    }

    suspend fun deletePlaylistSong(userId: String, playlistId: Long, videoId: String) {
        try {
            playlistSongsCol(userId, playlistId).document(videoId).delete().await()
        } catch (e: Exception) {
            Log.e(TAG, "deletePlaylistSong failed: ${e.message}")
        }
    }

    /** Fetch all playlists + their songs for a user. */
    suspend fun fetchPlaylists(userId: String): List<Pair<Playlist, List<PlaylistSong>>> {
        return try {
            val playlistDocs = playlistsCol(userId).get().await().documents
            playlistDocs.mapNotNull { doc ->
                val id   = doc.getLong("playlistId") ?: return@mapNotNull null
                val name = doc.getString("name") ?: ""
                val createdAt = (doc.getTimestamp("createdAt")?.seconds ?: 0L) * 1000L
                val playlist = Playlist(id = id, name = name, createdAt = createdAt)

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
                playlist to songs
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchPlaylists failed: ${e.message}")
            emptyList()
        }
    }
}
