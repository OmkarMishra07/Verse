package com.example.data.remote

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.local.ExploreCache
import com.example.data.local.SongDatabase
import com.example.data.model.Track
import com.example.data.model.toTrack
import com.example.data.network.ITunesHelper
import com.example.data.network.YouTubeSearchHelper
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types

class ExploreRefreshWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val moshi = Moshi.Builder()
        .add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
        .build()
    private val trackListAdapter = moshi.adapter<List<Track>>(
        Types.newParameterizedType(List::class.java, Track::class.java)
    )

    override suspend fun doWork(): Result {
        Log.d("ExploreRefreshWorker", "Starting background Explore content refresh...")
        val db = SongDatabase.getDatabase(applicationContext)
        val dao = db.songDao()

        val sections = listOf(
            "trending", "albums", "releases", "global_local",
            "top10", "bollywood", "mood", "party", "hiphop", "pop", "rock", "rb"
        )
        val regions = listOf(true, false) // true = INDIA, false = GLOBAL

        var successCount = 0
        var failCount = 0

        for (section in sections) {
            for (isIndia in regions) {
                val regionName = if (isIndia) "INDIA" else "GLOBAL"
                val cacheKey = "${section}_$regionName"
                
                try {
                    val tracks = fetchSectionDataDirectly(section, isIndia)
                    if (tracks.isNotEmpty()) {
                        val json = trackListAdapter.toJson(tracks)
                        dao.insertExploreCache(ExploreCache(cacheKey, json, System.currentTimeMillis()))
                        successCount++
                    } else {
                        failCount++
                    }
                } catch (e: Exception) {
                    Log.e("ExploreRefreshWorker", "Failed to refresh section: $cacheKey", e)
                    failCount++
                }
            }
        }

        Log.d("ExploreRefreshWorker", "Finished refresh. Success: $successCount, Failed: $failCount")
        return if (successCount > 0) Result.success() else Result.retry()
    }

    private suspend fun fetchSectionDataDirectly(section: String, isIndia: Boolean): List<Track> {
        return when (section) {
            "trending" -> {
                ITunesHelper.getTopSongs(isIndia).take(10).map {
                    Track(id = it.id, title = it.title, artist = it.artist, thumbnailUrl = it.thumbnailUrl, duration = "3:00", album = "")
                }
            }
            "albums" -> {
                ITunesHelper.getTopAlbums(isIndia).take(10).map {
                    Track(id = it.id, title = it.title, artist = it.artist, thumbnailUrl = it.thumbnailUrl, duration = "3:00", album = "")
                }
            }
            "releases" -> {
                ITunesHelper.getNewReleases(isIndia).take(10).map {
                    Track(id = it.id, title = it.title, artist = it.artist, thumbnailUrl = it.thumbnailUrl, duration = "3:00", album = "")
                }
            }
            "global_local" -> {
                ITunesHelper.getTopSongs(!isIndia).take(10).map {
                    Track(id = it.id, title = it.title, artist = it.artist, thumbnailUrl = it.thumbnailUrl, duration = "3:00", album = "")
                }
            }
            "top10" -> {
                val query = if (isIndia) "top 10 trending hit songs india official music video" else "top 10 trending hit songs global official music video"
                YouTubeSearchHelper.search(query).take(10).map { it.toTrack("YouTube Music Top 10") }
            }
            "bollywood" -> {
                YouTubeSearchHelper.search("latest trending bollywood hit songs").take(10).map { it.toTrack("Bollywood Hits") }
            }
            "mood" -> {
                val query = if (isIndia) "latest romantic lo-fi chill indian songs" else "latest chill vibes lofi pop songs"
                YouTubeSearchHelper.search(query).take(10).map { it.toTrack("Mood & Chill") }
            }
            "party" -> {
                val query = if (isIndia) "latest party dance hits punjabi bollywood" else "latest EDM dance club hits"
                YouTubeSearchHelper.search(query).take(10).map { it.toTrack("Party & Dance") }
            }
            "hiphop" -> {
                val query = if (isIndia) "latest desi hip hop rap hits" else "latest global hip hop rap hit songs official music video"
                YouTubeSearchHelper.search(query).take(10).map { it.toTrack("Hip-Hop & Rap") }
            }
            "pop" -> {
                val query = if (isIndia) "top hit indie pop songs bollywood" else "top pop anthems hits official music video"
                YouTubeSearchHelper.search(query).take(10).map { it.toTrack("Pop Anthems") }
            }
            "rock" -> {
                YouTubeSearchHelper.search("top rock classics alternative indie hits").take(10).map { it.toTrack("Rock & Indie") }
            }
            "rb" -> {
                YouTubeSearchHelper.search("top trending r&b soul neo soul hits").take(10).map { it.toTrack("R&B & Soul") }
            }
            else -> emptyList()
        }
    }
}
