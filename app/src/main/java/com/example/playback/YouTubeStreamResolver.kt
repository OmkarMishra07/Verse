package com.example.playback

import android.util.Log
import com.example.data.network.NetworkClient
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves a YouTube video ID into a playable audio stream URL.
 *
 * Uses YouTube's InnerTube API (Android client context) to fetch adaptive
 * streaming data, then selects the highest-quality audio-only stream.
 *
 * Flow:  videoId → InnerTube API → streamingData.adaptiveFormats → best audio URL
 *
 * Audio-only streams are preferred to avoid wasting bandwidth on video.
 */
object YouTubeStreamResolver {

    private const val TAG = "YouTubeStreamResolver"
    private const val INNER_TUBE_URL = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
    private const val CLIENT_VERSION = "19.02.39"
    private const val ANDROID_SDK_VERSION = 34

    /** In-memory cache: videoId → ResolvedStream */
    private val cache = ConcurrentHashMap<String, ResolvedStream>()

    data class ResolvedStream(
        val streamUrl: String,
        val mimeType: String,
        val bitrate: Int,
        val expiresAt: Long,
        val contentLength: Long = 0L
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() > expiresAt - 60_000L // 1min buffer
    }

    /**
     * Resolve a video ID to the best playable audio stream URL.
     *
     * @param videoId YouTube video ID (11 chars).
     * @param forceRefresh Skip cache and fetch fresh URL.
     * @return The audio stream URL, or null if resolution failed.
     */
    suspend fun resolve(videoId: String, forceRefresh: Boolean = false): ResolvedStream? = withContext(Dispatchers.IO) {
        // Check cache first
        if (!forceRefresh) {
            cache[videoId]?.let { cached ->
                if (!cached.isExpired()) {
                    Log.d(TAG, "Cache hit for $videoId")
                    return@withContext cached
                }
                Log.d(TAG, "Cache expired for $videoId, refreshing")
            }
        }

        var lastException: Exception? = null

        // Attempt 1: InnerTube API (Android client — most reliable)
        try {
            val result = resolveViaInnerTube(videoId)
            if (result != null) {
                cache[videoId] = result
                Log.d(TAG, "Resolved $videoId → ${result.mimeType} @ ${result.bitrate}bps")
                FirebaseCrashlytics.getInstance().log("StreamResolver: $videoId resolved via InnerTube")
                return@withContext result
            }
        } catch (e: Exception) {
            lastException = e
            Log.w(TAG, "InnerTube failed for $videoId: ${e.message}")
        }

        // Attempt 2: InnerTube API (iOS client — fallback)
        try {
            val result = resolveViaInnerTube(videoId, useIosClient = true)
            if (result != null) {
                cache[videoId] = result
                Log.d(TAG, "Resolved $videoId via iOS client fallback")
                FirebaseCrashlytics.getInstance().log("StreamResolver: $videoId resolved via iOS fallback")
                return@withContext result
            }
        } catch (e: Exception) {
            Log.w(TAG, "iOS fallback also failed for $videoId: ${e.message}")
        }

        Log.e(TAG, "All resolution attempts failed for $videoId", lastException)
        FirebaseCrashlytics.getInstance().log("StreamResolver: ALL methods failed for $videoId")
        FirebaseCrashlytics.getInstance().recordException(
            lastException ?: RuntimeException("Stream resolution failed: $videoId")
        )
        null
    }

    /**
     * Invalidate cache for a video ID (e.g., on 403/expired URL error).
     */
    fun invalidate(videoId: String) {
        cache.remove(videoId)
    }

    /**
     * Clear all cached stream URLs.
     */
    fun clearCache() {
        cache.clear()
    }

    private fun resolveViaInnerTube(videoId: String, useIosClient: Boolean = false): ResolvedStream? {
        val clientContext = if (useIosClient) buildIosContext() else buildAndroidContext()
        val userAgent = if (useIosClient) {
            "com.google.ios.youtube/19.02.39 (iPhone14,3; U; CPU iOS 17_3_1 like Mac OS X)"
        } else {
            "com.google.android.youtube/19.02.39 (Linux; U; Android 14; en_US) gzip"
        }

        val body = JSONObject().apply {
            put("videoId", videoId)
            put("context", clientContext)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }

        val request = Request.Builder()
            .url(INNER_TUBE_URL)
            .header("User-Agent", userAgent)
            .header("Content-Type", "application/json")
            .header("X-YouTube-Client-Name", if (useIosClient) "5" else "3")
            .header("X-YouTube-Client-Version", CLIENT_VERSION)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = NetworkClient.client.newCall(request).execute()
        val responseBody = response.body?.string() ?: return null

        if (response.code != 200) {
            Log.w(TAG, "InnerTube returned ${response.code} for $videoId")
            return null
        }

        val json = JSONObject(responseBody)

        // Check playability status
        val playability = json.optJSONObject("playabilityStatus")
        val status = playability?.optString("status", "")
        if (status != "OK") {
            val reason = playability?.optString("reason", "unknown")
            Log.w(TAG, "Video not playable: status=$status reason=$reason videoId=$videoId")
            return null
        }

        // Extract streaming data
        val streamingData = json.optJSONObject("streamingData") ?: return null
        val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats") ?: return null

        return selectBestAudioStream(adaptiveFormats, videoId)
    }

    private fun buildAndroidContext(): JSONObject {
        return JSONObject().apply {
            put("client", JSONObject().apply {
                put("clientName", "ANDROID")
                put("clientVersion", CLIENT_VERSION)
                put("androidSdkVersion", ANDROID_SDK_VERSION)
                put("hl", "en")
                put("gl", "US")
                put("osName", "Android")
                put("osVersion", "14")
                put("platform", "MOBILE")
            })
        }
    }

    private fun buildIosContext(): JSONObject {
        return JSONObject().apply {
            put("client", JSONObject().apply {
                put("clientName", "IOS")
                put("clientVersion", CLIENT_VERSION)
                put("deviceMake", "Apple")
                put("deviceModel", "iPhone14,3")
                put("hl", "en")
                put("gl", "US")
                put("osName", "iPhone")
                put("osVersion", "17.3.1.21D61")
                put("platform", "MOBILE")
            })
        }
    }

    /**
     * Select the best audio-only stream from adaptive formats.
     * Preference: highest bitrate audio stream (opus or m4a).
     */
    private fun selectBestAudioStream(formats: JSONArray, videoId: String): ResolvedStream? {
        val audioStreams = mutableListOf<Pair<JSONObject, Int>>() // (format, bitrate)

        for (i in 0 until formats.length()) {
            val format = formats.optJSONObject(i) ?: continue
            val mimeType = format.optString("mimeType", "")

            // Only consider audio-only streams (no video)
            if (!mimeType.startsWith("audio/")) continue

            val bitrate = format.optInt("bitrate", 0)
            val url = format.optString("url", "")

            // Some formats use signatureCipher instead of direct URL
            if (url.isBlank()) continue

            audioStreams.add(format to bitrate)
        }

        if (audioStreams.isEmpty()) {
            Log.w(TAG, "No audio streams found for $videoId")
            return null
        }

        // Sort by bitrate descending — pick the highest quality
        val best = audioStreams.sortedByDescending { it.second }.first()
        val format = best.first
        val bitrate = best.second
        val url = format.getString("url")
        val mimeType = format.optString("mimeType", "audio/mp4")
        val contentLength = format.optLong("contentLength", 0L)

        // Calculate expiration — YouTube URLs typically expire in ~6 hours
        // Parse the 'expire' parameter from the URL
        val expiresAt = extractExpiration(url)

        return ResolvedStream(
            streamUrl = url,
            mimeType = mimeType,
            bitrate = bitrate,
            expiresAt = expiresAt,
            contentLength = contentLength
        )
    }

    /**
     * Extract the expiration timestamp from a YouTube stream URL.
     * YouTube URLs contain an 'expire' query parameter with Unix timestamp.
     */
    private fun extractExpiration(url: String): Long {
        return try {
            val uri = android.net.Uri.parse(url)
            val expireStr = uri.getQueryParameter("expire")
            if (expireStr != null) {
                expireStr.toLong() * 1000L
            } else {
                // Default: assume 5 hours from now if no expire parameter found
                System.currentTimeMillis() + 5 * 60 * 60 * 1000L
            }
        } catch (e: Exception) {
            System.currentTimeMillis() + 5 * 60 * 60 * 1000L
        }
    }

    /**
     * Get the correct MIME type for ExoPlayer based on the stream's mimeType.
     */
    fun getExoPlayerMimeType(youtubeMimeType: String): String {
        return when {
            youtubeMimeType.contains("opus") -> "audio/opus"
            youtubeMimeType.contains("mp4a") || youtubeMimeType.contains("audio/mp4") -> "audio/mp4"
            youtubeMimeType.contains("webm") -> "audio/webm"
            else -> "audio/mp4" // safe default
        }
    }
}
