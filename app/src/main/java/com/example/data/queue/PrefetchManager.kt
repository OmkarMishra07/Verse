package com.example.data.queue

import android.content.Context
import android.util.Log
import coil.ImageLoader
import coil.request.ImageRequest
import coil.size.Size
import com.example.data.model.Track
import com.example.data.network.YouTubeSearchHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Prefetches metadata, thumbnails, and resolves streaming URLs for upcoming tracks.
 *
 * Goal: By the time the current song ends, the next 3-5 songs should have their
 * streaming data and thumbnails ready, eliminating any gap between tracks and
 * making the queue UI feel instant.
 *
 * Thread-safe: uses a [Mutex] to prevent duplicate prefetch jobs.
 */
class PrefetchManager(
    private val scope: CoroutineScope,
    private val prefetchCount: Int = 5
) {
    companion object {
        private const val TAG = "PrefetchManager"
        private const val PREFETCH_DELAY_MS = 2000L  // Start prefetching 2s after song starts
    }

    private data class PrefetchedTrack(
        val track: Track,
        val resolved: Track?,       // Resolved video ID (for iTunes tracks)
        val prefetchedAt: Long = System.currentTimeMillis(),
        val thumbnailPrefetched: Boolean = false,
        val isValid: Boolean = true
    )

    private val prefetchedTracks = mutableMapOf<String, PrefetchedTrack>()
    private val prefetchMutex = Mutex()
    private var currentJob: Job? = null

    private val _prefetchState = MutableStateFlow<PrefetchStatus>(PrefetchStatus.Idle)
    val prefetchState = _prefetchState.asStateFlow()

    sealed class PrefetchStatus {
        data object Idle : PrefetchStatus()
        data class Prefetching(val trackTitle: String, val progress: Int, val total: Int) : PrefetchStatus()
        data class Ready(val count: Int) : PrefetchStatus()
    }

    /**
     * Start prefetching for upcoming tracks.
     * Called when a new song starts playing.
     *
     * @param upcoming The next N tracks in the queue.
     * @param resolveTrack Function to resolve a track (e.g., iTunes → YouTube lookup).
     * @param context Android context for Coil image loading.
     */
    fun startPrefetching(
        upcoming: List<Track>,
        resolveTrack: suspend (Track) -> Track = { it },
        context: Context? = null
    ) {
        currentJob?.cancel()
        currentJob = scope.launch {
            delay(PREFETCH_DELAY_MS)
            prefetchMutex.withLock {
                try {
                    _prefetchState.value = PrefetchStatus.Prefetching(
                        trackTitle = upcoming.firstOrNull()?.title ?: "",
                        progress = 0,
                        total = upcoming.size.coerceAtMost(prefetchCount)
                    )

                    val toPrefetch = upcoming.take(prefetchCount)
                    var done = 0

                    for (track in toPrefetch) {
                        if (!isActive) break

                        try {
                            // 1. Resolve track (iTunes → YouTube lookup)
                            val resolved = resolveTrack(track)

                            // 2. Prefetch thumbnail into Coil cache
                            var thumbPrefetched = false
                            if (context != null) {
                                try {
                                    val thumbUrl = resolved.thumbnailUrl.ifBlank { track.thumbnailUrl }
                                    if (thumbUrl.isNotBlank()) {
                                        val imageRequest = ImageRequest.Builder(context)
                                            .data(thumbUrl)
                                            .size(Size.ORIGINAL)
                                            .crossfade(false)
                                            .build()
                                        val imageLoader = coil.ImageLoader(context)
                                        imageLoader.execute(imageRequest)
                                        thumbPrefetched = true
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "Thumbnail prefetch failed for '${track.title}': ${e.message}")
                                }
                            }

                            prefetchedTracks[track.id] = PrefetchedTrack(
                                track = track,
                                resolved = resolved,
                                thumbnailPrefetched = thumbPrefetched
                            )
                            done++
                            _prefetchState.value = PrefetchStatus.Prefetching(
                                trackTitle = track.title,
                                progress = done,
                                total = toPrefetch.size
                            )
                        } catch (e: Exception) {
                            Log.w(TAG, "Prefetch failed for '${track.title}': ${e.message}")
                            prefetchedTracks[track.id] = PrefetchedTrack(
                                track = track,
                                resolved = null,
                                isValid = false
                            )
                        }
                    }

                    _prefetchState.value = PrefetchStatus.Ready(done)
                    Log.d(TAG, "Prefetched $done/${toPrefetch.size} tracks")
                } finally {
                    // Keep state as Ready until next prefetch cycle
                }
            }
        }
    }

    /**
     * Get a prefetched (and resolved) track, or null if not available.
     * Returns the resolved version if available, otherwise the original.
     */
    fun getPrefetched(trackId: String): Track? {
        val entry = prefetchedTracks[trackId] ?: return null
        if (!entry.isValid) return null
        return entry.resolved ?: entry.track
    }

    /**
     * Check if a track has been prefetched.
     */
    fun isPrefetched(trackId: String): Boolean {
        val entry = prefetchedTracks[trackId] ?: return false
        return entry.isValid
    }

    /**
     * Remove a track from the prefetch cache (e.g., when removed from queue).
     */
    fun remove(trackId: String) {
        prefetchedTracks.remove(trackId)
    }

    /**
     * Clear all prefetched data.
     */
    fun clear() {
        currentJob?.cancel()
        prefetchedTracks.clear()
        _prefetchState.value = PrefetchStatus.Idle
    }

    /**
     * Cancel any in-progress prefetching.
     */
    fun cancel() {
        currentJob?.cancel()
        _prefetchState.value = PrefetchStatus.Idle
    }

    /**
     * Number of tracks currently prefetched and ready.
     */
    fun readyCount(): Int = prefetchedTracks.values.count { it.isValid }
}
