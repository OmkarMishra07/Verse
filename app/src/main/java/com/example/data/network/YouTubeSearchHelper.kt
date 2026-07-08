package com.example.data.network

import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.regex.Pattern

data class YouTubeVideo(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String,
    val duration: String
)

object YouTubeSearchHelper {
    private const val TAG = "YouTubeSearchHelper"
    private val client = OkHttpClient.Builder()
        .cookieJar(object : okhttp3.CookieJar {
            private val cookieStore = java.util.concurrent.ConcurrentHashMap<String, List<okhttp3.Cookie>>()
            override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
                cookieStore[url.host] = cookies
            }
            override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> {
                return cookieStore[url.host] ?: emptyList()
            }
        })
        .build()

    suspend fun search(query: String): List<YouTubeVideo> = withContext(Dispatchers.IO) {
        val results = mutableListOf<YouTubeVideo>()
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            // sp=EgIQAQ%253D%253D filters search results to show only videos
            val url = "https://www.youtube.com/results?search_query=$encodedQuery&sp=EgIQAQ%253D%253D"
            
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()

            var response = client.newCall(request).execute()
            var html = response.body?.string() ?: ""
            
            // If YouTube served a consent page or challenge on the first try, retry once seamlessly.
            if (!html.contains("\"videoRenderer\":")) {
                response = client.newCall(request).execute()
                html = response.body?.string() ?: ""
            }
            
            // We split the HTML page by "videoRenderer" to isolate each search result block
            val parts = html.split("\"videoRenderer\":")
            for (i in 1 until parts.size) {
                if (results.size >= 15) break
                val part = parts[i].take(3500)
                
                // Extract videoId
                val videoId = extractValue(part, "\"videoId\":\"([a-zA-Z0-9_-]{11})\"") ?: continue
                
                // Extract Title
                var title = extractValue(part, "\"title\":\\{\"runs\":\\[\\{\"text\":\"([^\"]+)\"\\}\\]")
                if (title == null) {
                    title = extractValue(part, "\"title\":\\{\"accessibility\":\\{\"accessibilityData\":\\{\"label\":\"([^\"]+)\"\\}\\}")
                }
                if (title == null) {
                    title = extractValue(part, "\"title\":.*?\"text\":\"([^\"]+)\"")
                }
                title = title?.replace("\\u0026", "&")?.replace("\\\"", "\"") ?: "Unknown Video"

                // Extract Artist / Channel Name
                var artist = extractValue(part, "\"ownerText\":\\{\"runs\":\\[\\{\"text\":\"([^\"]+)\"\\}\\]")
                if (artist == null) {
                    artist = extractValue(part, "\"longBylineText\":\\{\"runs\":\\[\\{\"text\":\"([^\"]+)\"\\}\\]")
                }
                if (artist == null) {
                    artist = extractValue(part, "\"ownerText\":.*?\"text\":\"([^\"]+)\"")
                }
                artist = artist?.replace("\\u0026", "&") ?: "Unknown Artist"

                // Extract Duration
                val duration = extractValue(part, "\"lengthText\":\\{\"accessibility\":\\{\"accessibilityData\":\\{\"label\":\"[^\"]+\"\\}\\},\"simpleText\":\"([0-9:]+)\"\\}") 
                    ?: extractValue(part, "\"simpleText\":\"([0-9:]{2,5})\"")
                    ?: "3:30"

                val thumbnailUrl = "https://img.youtube.com/vi/$videoId/hqdefault.jpg"
                
                results.add(
                    YouTubeVideo(
                        videoId = videoId,
                        title = title,
                        artist = artist,
                        thumbnailUrl = thumbnailUrl,
                        duration = duration
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error searching YouTube: ${e.message}", e)
            FirebaseCrashlytics.getInstance().log("YouTubeSearch failed for query='${query.take(50)}'")
            FirebaseCrashlytics.getInstance().recordException(e)
        }
        
        // Return results, falling back to empty if failed
        results
    }

    private fun extractValue(text: String, regex: String): String? {
        try {
            val pattern = Pattern.compile(regex)
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                return matcher.group(1)
            }
        } catch (e: Exception) {
            // Ignore
        }
        return null
    }
}
