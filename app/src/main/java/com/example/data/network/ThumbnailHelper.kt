package com.example.data.network

import android.util.Log
import coil.request.ImageRequest
import coil.size.Size
import android.content.Context

/**
 * Utility for YouTube thumbnail URLs with automatic quality fallback.
 *
 * YouTube thumbnail URL patterns:
 *   https://i.ytimg.com/vi/{videoId}/maxresdefault.jpg  (1920x1080)
 *   https://i.ytimg.com/vi/{videoId}/sddefault.jpg      (640x480)
 *   https://i.ytimg.com/vi/{videoId}/hqdefault.jpg      (480x360)
 *   https://i.ytimg.com/vi/{videoId}/mqdefault.jpg      (320x180)
 *   https://i.ytimg.com/vi/{videoId}/default.jpg        (120x90)
 *
 * Strategy: Start with maxresdefault. Coil handles HTTP errors automatically —
 * if the URL returns 404 (video has no maxres thumbnail), Coil will fail and
 * we fall back via the [fallbackThumbnailUrl] property.
 */
object ThumbnailHelper {

    private const val TAG = "ThumbnailHelper"

    /** Quality tiers from highest to lowest. */
    private val QUALITY_TIERS = listOf(
        "maxresdefault",  // 1920x1080 — best quality
        "sddefault",      // 640x480
        "hqdefault",      // 480x360
        "mqdefault",      // 320x180
        "default"         // 120x90 — always exists
    )

    /**
     * Get the highest quality thumbnail URL for a YouTube video ID.
     * Returns maxresdefault by default. Use [getFallbackUrl] if it fails.
     */
    fun bestThumbnailUrl(videoId: String): String {
        return "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg"
    }

    /**
     * Get the next lower quality thumbnail URL for fallback.
     * Given a failed URL, returns the next tier down.
     * Returns null if already at the lowest tier.
     */
    fun getFallbackUrl(failedUrl: String): String? {
        val currentTier = QUALITY_TIERS.firstOrNull { failedUrl.contains("/$it.jpg") } ?: return null
        val currentIndex = QUALITY_TIERS.indexOf(currentTier)
        if (currentIndex < 0 || currentIndex >= QUALITY_TIERS.size - 1) return null

        // Extract videoId from the URL
        val videoId = extractVideoId(failedUrl) ?: return null
        val nextTier = QUALITY_TIERS[currentIndex + 1]
        return "https://i.ytimg.com/vi/$videoId/$nextTier.jpg"
    }

    /**
     * Get all thumbnail URLs ordered from highest to lowest quality.
     * Useful for pre-fetching or building a complete fallback chain.
     */
    fun allQualities(videoId: String): List<String> {
        return QUALITY_TIERS.map { tier ->
            "https://i.ytimg.com/vi/$videoId/$tier.jpg"
        }
    }

    /**
     * Build a Coil ImageRequest with the best thumbnail URL.
     * The caller can use this for pre-loading thumbnails into Coil's cache.
     */
    fun buildImageRequest(
        context: Context,
        videoId: String,
        size: Size = Size.ORIGINAL
    ): ImageRequest {
        return ImageRequest.Builder(context)
            .data(bestThumbnailUrl(videoId))
            .size(size)
            .crossfade(true)
            .build()
    }

    /**
     * Extract YouTube video ID from a thumbnail URL.
     * Returns null if the URL doesn't match expected patterns.
     */
    fun extractVideoId(url: String): String? {
        val regex = Regex("i\\.ytimg\\.com/vi/([a-zA-Z0-9_-]{11})/")
        return regex.find(url)?.groupValues?.get(1)
    }

    /**
     * Check if a URL is a YouTube thumbnail.
     */
    fun isYouTubeThumbnail(url: String): Boolean {
        return url.contains("i.ytimg.com/vi/") || url.contains("img.youtube.com/vi/")
    }
}
