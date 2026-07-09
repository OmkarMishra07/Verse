package com.example.data.network

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.concurrent.TimeUnit
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

data class LRCLibResponse(
    val id: Long,
    val trackName: String?,
    val artistName: String?,
    val albumName: String?,
    val duration: Double?,
    val instrumental: Boolean?,
    val plainLyrics: String?,
    val syncedLyrics: String?
)

interface LRCLibService {
    @GET("api/search")
    suspend fun searchLyrics(
        @Query("q") query: String
    ): List<LRCLibResponse>
}

object LRCLibHelper {
    private const val BASE_URL = "https://lrclib.net/"
    
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val client = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.NONE })
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(client)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()

    private val service = retrofit.create(LRCLibService::class.java)

    private val lyricsCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    suspend fun fetchLyrics(trackName: String, artistName: String): String? {
        val cacheKey = "${trackName}_${artistName}"
        lyricsCache[cacheKey]?.let { return it }

        return try {
            val query = "$trackName $artistName".trim()
            val responseList = service.searchLyrics(query)
            if (responseList.isNotEmpty()) {
                val response = responseList.first()
                val result = response.syncedLyrics ?: response.plainLyrics
                if (result != null) {
                    lyricsCache[cacheKey] = result
                }
                result
            } else {
                // Fallback to searching just the title if no results found
                val titleOnlyList = service.searchLyrics(trackName)
                if (titleOnlyList.isNotEmpty()) {
                    val response = titleOnlyList.first()
                    val result = response.syncedLyrics ?: response.plainLyrics
                    if (result != null) {
                        lyricsCache[cacheKey] = result
                    }
                    result
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.e("LRCLibHelper", "Failed to fetch lyrics for $trackName by $artistName: ${e.message}")
            null
        }
    }
}
