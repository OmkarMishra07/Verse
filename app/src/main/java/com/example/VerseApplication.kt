package com.example

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import com.google.firebase.Firebase
import com.google.firebase.initialize
import okhttp3.OkHttpClient

class VerseApplication : Application(), ImageLoaderFactory {
    companion object {
        lateinit var instance: VerseApplication
            private set
    }

    /** Shared OkHttpClient for Coil image loading with thumbnail fallback. */
    val coilClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .addInterceptor(com.example.data.network.ThumbnailFallbackInterceptor())
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        Firebase.initialize(this)
        // TODO: Initialize App Check when reCAPTCHA site key is configured in Firebase Console.
        // See: https://firebase.google.com/docs/app-check/android/recaptcha

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
                    .maxSizePercent(0.30)
                    .strongReferencesEnabled(true)
                    .build()
            }
            .diskCachePolicy(CachePolicy.ENABLED)
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizePercent(0.05)
                    .build()
            }
            .bitmapFactoryMaxParallelism(2)
            .okHttpClient(coilClient)
            .respectCacheHeaders(false)
            .build()
    }
}
