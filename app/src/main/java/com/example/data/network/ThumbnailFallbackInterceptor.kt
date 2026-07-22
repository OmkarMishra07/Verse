package com.example.data.network

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response

/**
 * OkHttp interceptor that implements YouTube thumbnail quality fallback.
 *
 * When a thumbnail URL like `maxresdefault.jpg` returns 404 (video has no HD thumbnail),
 * this interceptor automatically tries the next lower quality tier:
 *
 *   maxresdefault → sddefault → hqdefault → mqdefault → default
 *
 * This ensures every video has a thumbnail, even if it doesn't have an HD version.
 *
 * Installed on the OkHttpClient used by Coil's ImageLoader.
 */
class ThumbnailFallbackInterceptor : Interceptor {

    companion object {
        private const val TAG = "ThumbnailFallback"
        private val QUALITY_TIERS = listOf(
            "maxresdefault",  // 1920x1080
            "sddefault",      // 640x480
            "hqdefault",      // 480x360
            "mqdefault",      // 320x180
            "default"         // 120x90 — always exists
        )
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()

        // Only intercept YouTube thumbnail URLs
        if (!url.contains("i.ytimg.com/vi/") && !url.contains("img.youtube.com/vi/")) {
            return chain.proceed(request)
        }

        // Extract videoId and current tier from URL
        val videoId = extractVideoId(url) ?: return chain.proceed(request)
        val currentTier = QUALITY_TIERS.firstOrNull { url.contains("/$it.jpg") }

        // If already at 'default' or unknown tier, just proceed normally
        if (currentTier == null || currentTier == "default") {
            return chain.proceed(request)
        }

        // Try the current URL first
        val response = chain.proceed(request)
        if (response.isSuccessful) {
            return response
        }

        // Current tier failed — close it and try fallback tiers
        response.close()
        val currentIndex = QUALITY_TIERS.indexOf(currentTier)
        for (i in (currentIndex + 1) until QUALITY_TIERS.size) {
            val fallbackTier = QUALITY_TIERS[i]
            val fallbackUrl = "https://i.ytimg.com/vi/$videoId/$fallbackTier.jpg"
            Log.d(TAG, "Fallback: $currentTier → $fallbackTier for $videoId")

            val fallbackRequest = request.newBuilder()
                .url(fallbackUrl)
                .build()

            val fallbackResponse = chain.proceed(fallbackRequest)
            if (fallbackResponse.isSuccessful) {
                return fallbackResponse
            }
            fallbackResponse.close()
        }

        // All tiers failed — return the last attempt (will show placeholder)
        return chain.proceed(request)
    }

    private fun extractVideoId(url: String): String? {
        val regex = Regex("i\\.ytimg\\.com/vi/([a-zA-Z0-9_-]{11})/")
        return regex.find(url)?.groupValues?.get(1)
            ?: Regex("img\\.youtube\\.com/vi/([a-zA-Z0-9_-]{11})/").find(url)?.groupValues?.get(1)
    }
}
