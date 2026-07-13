package com.example.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object CacheManager {
    private const val MAX_CACHE_SIZE_MB = 100L
    private const val BYTES_IN_MB = 1024L * 1024L

    suspend fun trimCacheIfNeeded(context: Context) {
        withContext(Dispatchers.IO) {
            try {
                val cacheDir = context.cacheDir
                val totalSize = getDirSize(cacheDir)
                
                if (totalSize > MAX_CACHE_SIZE_MB * BYTES_IN_MB) {
                    Log.d("CacheManager", "Cache size (${totalSize / BYTES_IN_MB}MB) exceeds limit. Trimming...")
                    // Delete files, prioritizing older ones
                    val files = cacheDir.walkBottomUp().filter { it.isFile }.toList()
                    val sortedFiles = files.sortedBy { it.lastModified() }
                    
                    var currentSize = totalSize
                    val targetSize = (MAX_CACHE_SIZE_MB * BYTES_IN_MB) / 2 // Cut down to 50MB
                    
                    for (file in sortedFiles) {
                        if (currentSize <= targetSize) break
                        val size = file.length()
                        if (file.delete()) {
                            currentSize -= size
                        }
                    }
                    Log.d("CacheManager", "Cache trimmed. New size: ${currentSize / BYTES_IN_MB}MB")
                } else {
                    Log.d("CacheManager", "Cache size (${totalSize / BYTES_IN_MB}MB) is within limits.")
                }
            } catch (e: Exception) {
                Log.e("CacheManager", "Failed to trim cache: ${e.message}")
            }
        }
    }

    private fun getDirSize(dir: File): Long {
        var size = 0L
        if (dir.isDirectory) {
            val files = dir.listFiles()
            if (files != null) {
                for (file in files) {
                    size += if (file.isDirectory) getDirSize(file) else file.length()
                }
            }
        } else {
            size = dir.length()
        }
        return size
    }
}
