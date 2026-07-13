package com.example.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

object UpdateHelper {
    private val client = OkHttpClient()

    data class UpdateInfo(
        val isUpdateAvailable: Boolean,
        val latestVersion: String,
        val releaseUrl: String
    )

    suspend fun checkForUpdates(currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.github.com/repos/OmkarMishra07/Verse/releases/latest")
                .header("User-Agent", "Verse-Update-Checker")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val responseData = response.body?.string() ?: return@withContext null
                val json = JSONObject(responseData)
                
                val tagName = json.getString("tag_name").replace("verse-v", "").replace("v", "")
                val htmlUrl = json.getString("html_url")
                
                val isUpdateAvailable = isVersionGreater(tagName, currentVersion)
                
                return@withContext UpdateInfo(isUpdateAvailable, tagName, htmlUrl)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        }
    }

    private fun isVersionGreater(latest: String, current: String): Boolean {
        try {
            val latestParts = latest.split(".").map { it.toIntOrNull() ?: 0 }
            val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }
            
            // Compare up to 3 parts (Major, Minor, and Patch)
            val maxLength = minOf(3, maxOf(latestParts.size, currentParts.size))
            for (i in 0 until maxLength) {
                val l = latestParts.getOrElse(i) { 0 }
                val c = currentParts.getOrElse(i) { 0 }
                if (l > c) return true
                if (l < c) return false
            }
            return false
        } catch (e: Exception) {
            return false
        }
    }
}
