package com.example.data.queue

import com.example.data.model.Track
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory LRU cache for recommendation results.
 * Keyed by the seed track ID. Entries expire after [ttlMs] milliseconds.
 *
 * Thread-safe: uses ConcurrentHashMap for reads/writes.
 * Not persisted to disk — cache is warm for the duration of the process.
 */
class RecommendationCache(private val ttlMs: Long = 30 * 60 * 1000L) {

    private data class Entry(
        val tracks: List<Track>,
        val createdAt: Long = System.currentTimeMillis(),
        val ttlMs: Long = 30 * 60 * 1000L
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() - createdAt > ttlMs
    }

    private val cache = ConcurrentHashMap<String, Entry>()

    /** Get cached recommendations for a seed track, or null if expired/missing. */
    fun get(seedTrackId: String): List<Track>? {
        val entry = cache[seedTrackId] ?: return null
        if (entry.isExpired()) {
            cache.remove(seedTrackId)
            return null
        }
        return entry.tracks
    }

    /** Store recommendations for a seed track. */
    fun put(seedTrackId: String, tracks: List<Track>) {
        cache[seedTrackId] = Entry(tracks, ttlMs = ttlMs)
    }

    /** Invalidate cache for a specific seed track. */
    fun invalidate(seedTrackId: String) {
        cache.remove(seedTrackId)
    }

    /** Clear all cached recommendations. */
    fun clear() {
        cache.clear()
    }

    /** Remove expired entries. Returns number of entries removed. */
    fun evictExpired(): Int {
        val before = cache.size
        val iterator = cache.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.isExpired()) iterator.remove()
        }
        return before - cache.size
    }

    /** Number of active (non-expired) cache entries. */
    fun size(): Int = cache.values.count { !it.isExpired() }

    /** Serialize to JSON for persistence across process restarts. */
    fun serialize(): String = JSONObject().apply {
        val entries = JSONArray()
        cache.forEach { (key, entry) ->
            if (!entry.isExpired()) {
                entries.put(JSONObject().apply {
                    put("seed", key)
                    put("createdAt", entry.createdAt)
                    put("tracks", JSONArray().apply {
                        entry.tracks.forEach { t ->
                            put(JSONObject().apply {
                                put("id", t.id); put("title", t.title); put("artist", t.artist)
                                put("album", t.album); put("thumbnail", t.thumbnailUrl); put("duration", t.duration)
                            })
                        }
                    })
                })
            }
        }
        put("entries", entries)
        put("ttlMs", ttlMs)
    }.toString()

    /** Restore from serialized JSON. Entries older than [ttlMs] are discarded. */
    fun restore(json: String?) {
        if (json.isNullOrBlank()) return
        try {
            val obj = JSONObject(json)
            val entries = obj.optJSONArray("entries") ?: return
            for (i in 0 until entries.length()) {
                val entryObj = entries.optJSONObject(i) ?: continue
                val seed = entryObj.optString("seed")
                val createdAt = entryObj.optLong("createdAt", 0L)
                if (System.currentTimeMillis() - createdAt > ttlMs) continue
                val tracksArr = entryObj.optJSONArray("tracks") ?: continue
                val tracks = (0 until tracksArr.length()).mapNotNull { j ->
                    val t = tracksArr.optJSONObject(j) ?: return@mapNotNull null
                    Track(
                        id = t.optString("id"), title = t.optString("title"), artist = t.optString("artist"),
                        album = t.optString("album"), thumbnailUrl = t.optString("thumbnail"), duration = t.optString("duration")
                    )
                }
                if (tracks.isNotEmpty()) cache[seed] = Entry(tracks, createdAt)
            }
        } catch (_: Exception) { }
    }
}
