package com.example.data.network

import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class ITunesEntry(
    val id: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String
)

object ITunesHelper {
    private const val TAG = "ITunesHelper"
    private val client = OkHttpClient.Builder().build()

    suspend fun getTopSongs(isIndia: Boolean): List<ITunesEntry> {
        val region = if (isIndia) "in" else "us"
        val url = "https://itunes.apple.com/$region/rss/topsongs/limit=50/json"
        return fetchEntries(url)
    }

    suspend fun getTopAlbums(isIndia: Boolean): List<ITunesEntry> {
        val region = if (isIndia) "in" else "us"
        val url = "https://itunes.apple.com/$region/rss/topalbums/limit=50/json"
        return fetchEntries(url)
    }

    suspend fun getNewReleases(isIndia: Boolean): List<ITunesEntry> {
        val region = if (isIndia) "in" else "us"
        val url = "https://itunes.apple.com/$region/rss/newreleases/limit=50/json"
        return fetchEntries(url)
    }

    private suspend fun fetchEntries(url: String): List<ITunesEntry> = withContext(Dispatchers.IO) {
        val results = mutableListOf<ITunesEntry>()
        try {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            val jsonStr = response.body?.string() ?: return@withContext emptyList()
            
            val root = JSONObject(jsonStr)
            val feed = root.optJSONObject("feed") ?: return@withContext emptyList()
            val entries = feed.optJSONArray("entry") ?: return@withContext emptyList()

            for (i in 0 until entries.length()) {
                val entry = entries.optJSONObject(i) ?: continue
                
                val titleObj = entry.optJSONObject("im:name")
                val title = titleObj?.optString("label") ?: "Unknown"

                val artistObj = entry.optJSONObject("im:artist")
                val artist = artistObj?.optString("label") ?: "Unknown"

                val images = entry.optJSONArray("im:image")
                var thumbnailUrl = ""
                if (images != null && images.length() > 0) {
                    thumbnailUrl = images.optJSONObject(images.length() - 1)?.optString("label") ?: ""
                }
                
                // Make the artwork bigger by replacing dimensions in the URL
                thumbnailUrl = thumbnailUrl.replace("55x55bb", "600x600bb").replace("170x170bb", "600x600bb")

                val idObj = entry.optJSONObject("id")
                val attributes = idObj?.optJSONObject("attributes")
                val itunesId = attributes?.optString("im:id") ?: "id_$i"

                results.add(
                    ITunesEntry(
                        id = "itunes_$itunesId",
                        title = title,
                        artist = artist,
                        thumbnailUrl = thumbnailUrl
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching from iTunes: ${e.message}", e)
            FirebaseCrashlytics.getInstance().log("iTunesFetch failed url=${url.take(80)}")
            FirebaseCrashlytics.getInstance().recordException(e)
        }
        results
    }
}
