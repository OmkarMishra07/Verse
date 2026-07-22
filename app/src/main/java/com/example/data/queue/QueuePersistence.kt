package com.example.data.queue

import android.content.SharedPreferences
import android.util.Log
import com.example.data.model.Track
import org.json.JSONObject

/**
 * Handles serialization and deserialization of [PlaybackSession] to/from SharedPreferences.
 *
 * Design decisions:
 * - Uses SharedPreferences (not Room) for speed — queue is small and read/write is frequent.
 * - Debounces writes to avoid disk thrashing on rapid state changes.
 * - Stores both the full session and individual track fields for fast UI restoration.
 */
class QueuePersistence(private val prefs: SharedPreferences) {

    companion object {
        private const val TAG = "QueuePersistence"
        private const val KEY_SESSION = "playback_session_v2"
        private const val KEY_LEGACY_SESSION = "queue_manager_state_v1"

        // Legacy individual fields (kept for backward compatibility)
        private const val KEY_TRACK_ID = "last_track_id"
        private const val KEY_TRACK_TITLE = "last_track_title"
        private const val KEY_TRACK_ARTIST = "last_track_artist"
        private const val KEY_TRACK_ALBUM = "last_track_album"
        private const val KEY_TRACK_THUMB = "last_track_thumb"
        private const val KEY_TRACK_DURATION = "last_track_duration"
        private const val KEY_POSITION = "last_position_ms"
        private const val KEY_QUEUE_JSON = "last_queue_json"
        private const val KEY_QUEUE_INDEX = "last_queue_index"

        // Recommendation cache persistence
        private const val KEY_RECOMMENDATION_CACHE = "recommendation_cache_v1"

        // Skip tracker persistence
        private const val KEY_SKIP_TRACKER = "skip_tracker_v1"
    }

    // Debounce: only write to disk at most every 3 seconds
    private var lastWriteTime = 0L
    private val writeDebounceMs = 3000L

    /**
     * Save the current playback session to SharedPreferences.
     * Includes debouncing to avoid excessive disk I/O.
     */
    fun saveSession(session: PlaybackSession) {
        val now = System.currentTimeMillis()
        if (now - lastWriteTime < writeDebounceMs) return
        lastWriteTime = now

        try {
            prefs.edit().putString(KEY_SESSION, session.toJson().toString()).apply()

            // Also save individual fields for fast UI restoration on next launch
            val editor = prefs.edit()
            if (session.current != null) {
                editor.putString(KEY_TRACK_ID, session.current.id)
                editor.putString(KEY_TRACK_TITLE, session.current.title)
                editor.putString(KEY_TRACK_ARTIST, session.current.artist)
                editor.putString(KEY_TRACK_ALBUM, session.current.album)
                editor.putString(KEY_TRACK_THUMB, session.current.thumbnailUrl)
                editor.putString(KEY_TRACK_DURATION, session.current.duration)
            }
            editor.putLong(KEY_POSITION, session.positionMs)
            editor.apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save session: ${e.message}")
        }
    }

    /**
     * Force-save the session immediately (bypasses debounce).
     * Use for critical saves like app foreground/background transitions.
     */
    fun saveSessionImmediate(session: PlaybackSession) {
        lastWriteTime = 0L  // Reset debounce
        saveSession(session)
    }

    /**
     * Restore the playback session from SharedPreferences.
     * Falls back to legacy format if v2 session is not found.
     */
    fun restoreSession(): PlaybackSession? {
        // Try v2 format first
        prefs.getString(KEY_SESSION, null)?.let { json ->
            PlaybackSession.fromJson(json)?.let { session ->
                Log.d(TAG, "Restored v2 session: ${session.current?.title ?: "idle"}")
                return session
            }
        }

        // Fall back to legacy v1 format (QueueManager state)
        prefs.getString(KEY_LEGACY_SESSION, null)?.let { json ->
            return migrateLegacySession(json)
        }

        // Fall back to individual fields
        return restoreFromIndividualFields()
    }

    /**
     * Save recommendation cache to SharedPreferences.
     */
    fun saveRecommendationCache(cache: RecommendationCache) {
        try {
            prefs.edit().putString(KEY_RECOMMENDATION_CACHE, cache.serialize()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save recommendation cache: ${e.message}")
        }
    }

    /**
     * Restore recommendation cache from SharedPreferences.
     */
    fun restoreRecommendationCache(): RecommendationCache {
        val cache = RecommendationCache()
        cache.restore(prefs.getString(KEY_RECOMMENDATION_CACHE, null))
        return cache
    }

    /**
     * Clear all persisted queue data.
     */
    fun clear() {
        prefs.edit().remove(KEY_SESSION).remove(KEY_LEGACY_SESSION)
            .remove(KEY_TRACK_ID).remove(KEY_TRACK_TITLE).remove(KEY_TRACK_ARTIST)
            .remove(KEY_TRACK_ALBUM).remove(KEY_TRACK_THUMB).remove(KEY_TRACK_DURATION)
            .remove(KEY_POSITION).remove(KEY_QUEUE_JSON).remove(KEY_QUEUE_INDEX)
            .apply()
    }

    /**
     * Migrate from legacy v1 QueueManager format to v2 PlaybackSession.
     */
    private fun migrateLegacySession(json: String): PlaybackSession? {
        return try {
            val obj = JSONObject(json)
            val session = PlaybackSession(
                sessionId = obj.optString("sessionId", ""),
                source = runCatching { QueueSource.valueOf(obj.optString("source")) }.getOrDefault(QueueSource.UNKNOWN),
                currentBucket = runCatching { QueueBucket.valueOf(obj.optString("bucket")) }.getOrDefault(QueueBucket.CONTEXT),
                current = obj.optJSONObject("current")?.let { trackFromJson(it) },
                history = tracksFromJson(obj.optJSONArray("history")),
                manual = tracksFromJson(obj.optJSONArray("manual")),
                context = tracksFromJson(obj.optJSONArray("context")),
                autoplay = tracksFromJson(obj.optJSONArray("autoplay")),
                shuffle = obj.optBoolean("shuffle", false),
                repeat = obj.optString("repeat", "ALL"),
                positionMs = 0L
            )
            Log.d(TAG, "Migrated v1 session to v2")
            session
        } catch (e: Exception) {
            Log.w(TAG, "Failed to migrate legacy session: ${e.message}")
            null
        }
    }

    /**
     * Restore from individual SharedPreferences fields (oldest format).
     */
    private fun restoreFromIndividualFields(): PlaybackSession? {
        val trackId = prefs.getString(KEY_TRACK_ID, null) ?: return null
        val track = Track(
            id = trackId,
            title = prefs.getString(KEY_TRACK_TITLE, "") ?: "",
            artist = prefs.getString(KEY_TRACK_ARTIST, "") ?: "",
            album = prefs.getString(KEY_TRACK_ALBUM, "") ?: "",
            thumbnailUrl = prefs.getString(KEY_TRACK_THUMB, "") ?: "",
            duration = prefs.getString(KEY_TRACK_DURATION, "") ?: ""
        )
        val positionMs = prefs.getLong(KEY_POSITION, 0L)
        return PlaybackSession(
            current = track,
            positionMs = positionMs,
            source = QueueSource.UNKNOWN
        )
    }

    private fun trackFromJson(o: JSONObject): Track = Track(
        id = o.optString("id"), title = o.optString("title"), artist = o.optString("artist"),
        album = o.optString("album"), thumbnailUrl = o.optString("thumbnail"), duration = o.optString("duration")
    )

    private fun tracksFromJson(arr: org.json.JSONArray?): List<Track> = buildList {
        if (arr != null) for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { add(trackFromJson(it)) }
    }
}
