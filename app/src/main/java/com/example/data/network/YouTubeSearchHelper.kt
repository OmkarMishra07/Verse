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
            val rawResults = mutableListOf<Pair<YouTubeVideo, Int>>()
            
            for (i in 1 until parts.size) {
                if (rawResults.size >= 25) break
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

                // Exclude videos whose title contains common compilation keywords
                val lowerTitle = title.lowercase()
                val isCompilation = lowerTitle.contains("jukebox") || 
                                    lowerTitle.contains("playlist") || 
                                    (lowerTitle.contains("mix") && !lowerTitle.contains("remix")) || 
                                    lowerTitle.contains("mashup") || 
                                    lowerTitle.contains("nonstop") || 
                                    lowerTitle.contains("full album") || 
                                    lowerTitle.contains("full movie songs") || 
                                    lowerTitle.contains("collection") || 
                                    lowerTitle.contains("compilation") || 
                                    lowerTitle.contains("best songs") || 
                                    lowerTitle.contains("top songs") || 
                                    lowerTitle.contains("all songs") || 
                                    lowerTitle.contains("medley") || 
                                    lowerTitle.contains("dj mix") || 
                                    lowerTitle.contains("live stream") || 
                                    lowerTitle.contains("live-stream")
                if (isCompilation) continue

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

                // Exclude videos longer than 10 minutes (playlists/long mixes)
                val cleanDuration = duration.trim()
                val durationParts = cleanDuration.split(":")
                if (durationParts.size > 2) {
                    val hours = durationParts[0].toIntOrNull() ?: 0
                    val mins = durationParts[1].toIntOrNull() ?: 0
                    val totalMinutes = (hours * 60) + mins
                    if (totalMinutes > 10) continue
                } else if (durationParts.size == 2) {
                    val mins = durationParts[0].toIntOrNull() ?: 0
                    if (mins > 10) continue
                }

                val thumbnailUrl = "https://img.youtube.com/vi/$videoId/maxresdefault.jpg"
                val video = YouTubeVideo(
                    videoId = videoId,
                    title = title,
                    artist = artist,
                    thumbnailUrl = thumbnailUrl,
                    duration = duration
                )
                val score = getPriorityScore(title, artist, duration)
                rawResults.add(Pair(video, score))
            }
            
            // Sort by priority score in descending order and populate final results
            results.addAll(rawResults.sortedByDescending { it.second }.map { it.first })
        } catch (e: Exception) {
            Log.e(TAG, "Error searching YouTube: ${e.message}", e)
            FirebaseCrashlytics.getInstance().log("YouTubeSearch failed for query='${query.take(50)}'")
            FirebaseCrashlytics.getInstance().recordException(e)
        }
        
        // Return results, falling back to empty if failed
        results
    }

    private fun getPriorityScore(title: String, channelName: String, duration: String): Int {
        var score = 0
        val lowerTitle = title.lowercase()
        val lowerChannel = channelName.lowercase()
        
        val officialLabels = listOf(
            "t-series", "sony music", "saregama", "zee music", "universal music", 
            "warner music", "yrf", "vevo", "official artist channel", "soundtrack",
            "tseries", "zee music company", "yash raj films", "saregama music"
        )
        if (officialLabels.any { lowerChannel.contains(it) }) {
            score += 100
        }
        
        if (lowerTitle.contains("official video") || lowerTitle.contains("official audio") || lowerTitle.contains("lyric video")) {
            score += 50
        }
        
        val parts = duration.split(":")
        if (parts.size == 2) {
            val mins = parts[0].toIntOrNull() ?: 0
            if (mins in 2..6) {
                score += 30
            }
        }
        
        return score
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
