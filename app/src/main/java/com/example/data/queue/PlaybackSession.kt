package com.example.data.queue

import com.example.data.model.Track
import org.json.JSONObject
import java.util.UUID

/**
 * Immutable snapshot of the entire playback session.
 * This is the single source of truth that gets serialized to disk and restored on app restart.
 */
data class PlaybackSession(
    val sessionId: String = UUID.randomUUID().toString(),
    val source: QueueSource = QueueSource.UNKNOWN,
    val current: Track? = null,
    val currentBucket: QueueBucket = QueueBucket.CONTEXT,
    val history: List<Track> = emptyList(),
    val manual: List<Track> = emptyList(),
    val context: List<Track> = emptyList(),
    val autoplay: List<Track> = emptyList(),
    val shuffle: Boolean = false,
    val repeat: String = "ALL",
    val positionMs: Long = 0L,
    val shuffleOrder: List<String>? = null,
    val startedAt: Long = System.currentTimeMillis(),
    val lastEvent: String = "IDLE",
    val lastValidation: String = "NOT_VALIDATED"
) {
    /** Everything the user sees in the queue UI: history + current + up next. */
    val visible: List<Track>
        get() = buildList {
            addAll(history)
            current?.let { add(it) }
            addAll(manual)
            addAll(context)
            addAll(autoplay)
        }

    /** The user's position index in the visible list. */
    val position: Int get() = history.size

    /** Total tracks available for next/previous navigation. */
    val totalSize: Int get() = visible.size

    /** Whether there are any tracks to play. */
    val hasContent: Boolean get() = current != null || manual.isNotEmpty() || context.isNotEmpty() || autoplay.isNotEmpty()

    /** Up next: manual + context + autoplay combined. */
    val upNext: List<Track>
        get() = buildList {
            addAll(manual)
            addAll(context)
            addAll(autoplay)
        }

    /** Number of tracks available before autoplay runs out. */
    val upcomingCount: Int get() = manual.size + context.size + autoplay.size

    fun toJson(): JSONObject = JSONObject().apply {
        put("sessionId", sessionId)
        put("source", source.name)
        put("bucket", currentBucket.name)
        put("current", current?.let { trackToJson(it) })
        put("history", tracksToJson(history))
        put("manual", tracksToJson(manual))
        put("context", tracksToJson(context))
        put("autoplay", tracksToJson(autoplay))
        put("shuffle", shuffle)
        put("repeat", repeat)
        put("positionMs", positionMs)
        put("shuffleOrder", shuffleOrder?.let { org.json.JSONArray(it) })
        put("startedAt", startedAt)
    }

    companion object {
        fun fromJson(json: String): PlaybackSession? = try {
            val obj = JSONObject(json)
            PlaybackSession(
                sessionId = obj.optString("sessionId", UUID.randomUUID().toString()),
                source = runCatching { QueueSource.valueOf(obj.optString("source")) }.getOrDefault(QueueSource.UNKNOWN),
                currentBucket = runCatching { QueueBucket.valueOf(obj.optString("bucket")) }.getOrDefault(QueueBucket.CONTEXT),
                current = obj.optJSONObject("current")?.let { trackFromJson(it) },
                history = tracksFromJson(obj.optJSONArray("history")),
                manual = tracksFromJson(obj.optJSONArray("manual")),
                context = tracksFromJson(obj.optJSONArray("context")),
                autoplay = tracksFromJson(obj.optJSONArray("autoplay")),
                shuffle = obj.optBoolean("shuffle", false),
                repeat = obj.optString("repeat", "ALL"),
                positionMs = obj.optLong("positionMs", 0L),
                shuffleOrder = obj.optJSONArray("shuffleOrder")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                },
                startedAt = obj.optLong("startedAt", System.currentTimeMillis())
            )
        } catch (e: Exception) {
            null
        }

        private fun trackToJson(t: Track): JSONObject = JSONObject().apply {
            put("id", t.id); put("title", t.title); put("artist", t.artist)
            put("album", t.album); put("thumbnail", t.thumbnailUrl); put("duration", t.duration)
        }

        private fun tracksToJson(tracks: List<Track>): org.json.JSONArray =
            org.json.JSONArray().apply { tracks.forEach { put(trackToJson(it)) } }

        private fun trackFromJson(o: JSONObject): Track = Track(
            id = o.optString("id"), title = o.optString("title"), artist = o.optString("artist"),
            album = o.optString("album"), thumbnailUrl = o.optString("thumbnail"), duration = o.optString("duration")
        )

        private fun tracksFromJson(arr: org.json.JSONArray?): List<Track> = buildList {
            if (arr != null) for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { add(trackFromJson(it)) }
        }
    }
}
