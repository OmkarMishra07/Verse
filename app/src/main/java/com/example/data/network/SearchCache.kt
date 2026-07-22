package com.example.data.network

import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory + SharedPreferences cache for YouTube search results.
 *
 * Prevents redundant YouTube HTML scraping when the same query is requested
 * multiple times (e.g., recommendation engine re-fetching for the same seed track).
 *
 * Thread-safe: uses ConcurrentHashMap for in-memory reads/writes.
 */
class SearchCache(private val prefs: SharedPreferences) {

    private data class CacheEntry(
        val results: List<YouTubeVideo>,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() - timestamp > TTL_MS
    }

    companion object {
        private const val TAG = "SearchCache"
        private const val TTL_MS = 15 * 60 * 1000L  // 15 minutes
        private const val MAX_ENTRIES = 100
        private const val KEY_CACHE = "search_cache_v1"
    }

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    init {
        loadFromPrefs()
    }

    /**
     * Get cached search results for a query, or null if expired/missing.
     */
    fun get(query: String): List<YouTubeVideo>? {
        val key = normalizeQuery(query)
        val entry = cache[key] ?: return null
        if (entry.isExpired()) {
            cache.remove(key)
            return null
        }
        return entry.results
    }

    /**
     * Store search results for a query.
     */
    fun put(query: String, results: List<YouTubeVideo>) {
        val key = normalizeQuery(query)
        cache[key] = CacheEntry(results)
        // Evict oldest entries if cache is too large
        if (cache.size > MAX_ENTRIES) {
            val oldest = cache.entries.sortedBy { it.value.timestamp }.take(cache.size - MAX_ENTRIES)
            oldest.forEach { cache.remove(it.key) }
        }
        saveToPrefs()
    }

    /**
     * Check if a query is cached (not expired).
     */
    fun isCached(query: String): Boolean = get(query) != null

    /**
     * Clear all cached results.
     */
    fun clear() {
        cache.clear()
        prefs.edit().remove(KEY_CACHE).apply()
    }

    /**
     * Remove expired entries. Returns number removed.
     */
    fun evictExpired(): Int {
        val before = cache.size
        val iterator = cache.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value.isExpired()) iterator.remove()
        }
        return before - cache.size
    }

    private fun normalizeQuery(query: String): String = query.trim().lowercase()

    private fun saveToPrefs() {
        try {
            val json = JSONObject()
            cache.forEach { (key, entry) ->
                if (!entry.isExpired()) {
                    val resultsArray = JSONArray()
                    entry.results.forEach { video ->
                        resultsArray.put(JSONObject().apply {
                            put("videoId", video.videoId)
                            put("title", video.title)
                            put("artist", video.artist)
                            put("thumbnailUrl", video.thumbnailUrl)
                            put("duration", video.duration)
                        })
                    }
                    json.put(key, JSONObject().apply {
                        put("timestamp", entry.timestamp)
                        put("results", resultsArray)
                    })
                }
            }
            prefs.edit().putString(KEY_CACHE, json.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save search cache: ${e.message}")
        }
    }

    private fun loadFromPrefs() {
        try {
            val jsonStr = prefs.getString(KEY_CACHE, null) ?: return
            val json = JSONObject(jsonStr)
            json.keys().forEach { key ->
                val entryObj = json.optJSONObject(key) ?: return@forEach
                val timestamp = entryObj.optLong("timestamp", 0L)
                if (System.currentTimeMillis() - timestamp > TTL_MS) return@forEach
                val resultsArray = entryObj.optJSONArray("results") ?: return@forEach
                val results = (0 until resultsArray.length()).mapNotNull { i ->
                    val obj = resultsArray.optJSONObject(i) ?: return@mapNotNull null
                    YouTubeVideo(
                        videoId = obj.optString("videoId"),
                        title = obj.optString("title"),
                        artist = obj.optString("artist"),
                        thumbnailUrl = obj.optString("thumbnailUrl"),
                        duration = obj.optString("duration")
                    )
                }
                if (results.isNotEmpty()) cache[key] = CacheEntry(results, timestamp)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load search cache: ${e.message}")
        }
    }
}
