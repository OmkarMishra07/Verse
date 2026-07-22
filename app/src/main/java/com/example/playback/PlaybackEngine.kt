package com.example.playback

import android.content.Context
import com.example.data.model.Track
import kotlinx.coroutines.flow.StateFlow

/**
 * Abstraction for audio playback engine.
 * The rest of the application depends only on this interface.
 * Implementations can be swapped without affecting business logic.
 */
interface PlaybackEngine {

    /** Current playback state for the MediaSession / notification. */
    val playerState: StateFlow<PlaybackState>

    /** Initialize the engine with an application context. Must be called once. */
    fun initialize(context: Context)

    /**
     * Load and play a track by resolving its stream URL.
     * The engine handles: stream URL resolution → MediaItem creation → ExoPlayer playback.
     *
     * @param track The track metadata (id = YouTube video ID).
     * @param startPositionMs Position to start from (e.g., resume position).
     */
    suspend fun loadAndPlay(track: Track, startPositionMs: Long = 0L)

    /** Resume playback. */
    fun play()

    /** Pause playback. */
    fun pause()

    /** Seek to a position in milliseconds. */
    fun seekTo(positionMs: Long)

    /** Set playback volume (0..100). */
    fun setVolume(volume: Int)

    /** Release all resources. */
    fun release()

    /** Whether the engine is currently initialized and ready. */
    fun isReady(): Boolean

    /** Whether the engine is currently playing. */
    fun isPlaying(): Boolean

    /** Current playback position in milliseconds. */
    fun currentPositionMs(): Long

    /** Current track duration in milliseconds. */
    fun durationMs(): Long

    /** Current buffered fraction (0..1). */
    fun bufferedFraction(): Float

    /**
     * Prepare (but don't play) a track for fast switching.
     * Used for prefetching upcoming stream URLs and MediaItems.
     */
    suspend fun prepare(track: Track)
}

/**
 * Immutable snapshot of playback state, consumed by the UI and MediaSession.
 */
data class PlaybackState(
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedFraction: Float = 0f
)
