package com.example

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy

class VerseApplication : Application(), ImageLoaderFactory {
    companion object {
        lateinit var instance: VerseApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        scheduleExploreRefresh()
    }

    private fun scheduleExploreRefresh() {
        val constraints = androidx.work.Constraints.Builder()
            .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
            .build()

        val exploreRefreshRequest = androidx.work.PeriodicWorkRequestBuilder<com.example.data.remote.ExploreRefreshWorker>(
            24, java.util.concurrent.TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .build()

        androidx.work.WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "ExploreRefreshWork",
            androidx.work.ExistingPeriodicWorkPolicy.KEEP,
            exploreRefreshRequest
        )
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .crossfade(true)
            .crossfade(300)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .strongReferencesEnabled(true)
                    .build()
            }
            .diskCachePolicy(CachePolicy.ENABLED)
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizePercent(0.02)
                    .build()
            }
            // Smart catching technique: use respectCacheHeaders(false) to enforce caching
            .respectCacheHeaders(false)
            .build()
    }
}
