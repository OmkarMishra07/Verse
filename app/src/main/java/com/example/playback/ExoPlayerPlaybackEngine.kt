package com.example.playback

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.example.data.model.Track
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * ExoPlayer-based implementation of [PlaybackEngine].
 *
 * Architecture:
 *   videoId → YouTubeStreamResolver → audio stream URL → MediaItem → ExoPlayer
 *
 * This replaces the entire WebView + YouTube IFrame API pipeline.
 * ExoPlayer handles: audio decoding, audio focus, headset/BT controls,
 * caching, buffering, and all standard media playback features.
 *
 * Thread safety: ExoPlayer must be accessed from its internal thread.
 * All public methods are safe to call from any thread.
 */
@OptIn(UnstableApi::class)
object ExoPlayerPlaybackEngine : PlaybackEngine {

    private const val TAG = "ExoPlayerEngine"

    /** Exposed for VerseMusicService to create MediaSession from this player. */
    var exoPlayer: ExoPlayer? = null
        private set
    private var isInitialized = false
    private var currentVideoId: String? = null

    private val _playerState = MutableStateFlow(PlaybackState())
    override val playerState: StateFlow<PlaybackState> = _playerState.asStateFlow()

    // Listener that bridges ExoPlayer events → PlaybackState updates
    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            _playerState.update { it.copy(isPlaying = playing) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    _playerState.update {
                        it.copy(
                            isLoading = false,
                            durationMs = exoPlayer?.duration?.coerceAtLeast(0) ?: 0L,
                            bufferedFraction = exoPlayer?.bufferedPercentage?.toFloat()?.div(100f) ?: 0f
                        )
                    }
                }
                Player.STATE_BUFFERING -> {
                    _playerState.update { it.copy(isLoading = true) }
                }
                Player.STATE_ENDED -> {
                    _playerState.update { it.copy(isPlaying = false, isLoading = false) }
                }
                Player.STATE_IDLE -> {
                    _playerState.update { it.copy(isPlaying = false, isLoading = false) }
                }
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            // Position will be reported via the progress updater
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "ExoPlayer error: ${error.message}", error)
            FirebaseCrashlytics.getInstance().log("ExoPlayer error: ${error.message}")
            FirebaseCrashlytics.getInstance().recordException(error)
            _playerState.update { it.copy(isLoading = false) }

            // If the stream URL is expired/invalid, invalidate the cache and retry
            val videoId = currentVideoId
            if (videoId != null && error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) {
                YouTubeStreamResolver.invalidate(videoId)
                Log.d(TAG, "Invalidated stream cache for $videoId due to HTTP error")
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            _playerState.update {
                it.copy(durationMs = exoPlayer?.duration?.coerceAtLeast(0) ?: 0L)
            }
        }
    }

    override fun initialize(context: Context) {
        if (isInitialized) return

        exoPlayer = ExoPlayer.Builder(context.applicationContext)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK) // Keep WiFi alive during playback
            .build()
            .apply {
                addListener(playerListener)
                playWhenReady = true
            }

        isInitialized = true
        Log.d(TAG, "ExoPlayer initialized")
    }

    override suspend fun loadAndPlay(track: Track, startPositionMs: Long) {
        val player = exoPlayer ?: throw IllegalStateException("ExoPlayer not initialized")

        currentVideoId = track.id
        _playerState.update { it.copy(isLoading = true) }

        try {
            // Resolve the video ID to a direct audio stream URL
            val resolved = YouTubeStreamResolver.resolve(track.id)
                ?: throw IllegalStateException("Failed to resolve stream for ${track.id}")

            val mimeType = YouTubeStreamResolver.getExoPlayerMimeType(resolved.mimeType)

            val mediaItem = MediaItem.Builder()
                .setMediaId(track.id)
                .setUri(Uri.parse(resolved.streamUrl))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(track.title)
                        .setArtist(track.artist)
                        .setAlbumTitle(track.album)
                        .setArtworkUri(
                            if (track.thumbnailUrl.isNotBlank()) Uri.parse(track.thumbnailUrl) else null
                        )
                        .build()
                )
                .setMimeType(mimeType)
                .build()

            player.setMediaItem(mediaItem)
            player.prepare()

            if (startPositionMs > 0) {
                player.seekTo(startPositionMs)
            }

            player.playWhenReady = true
            Log.d(TAG, "Loaded and playing: ${track.title} (${track.id})")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to load track: ${track.title}", e)
            _playerState.update { it.copy(isLoading = false) }
            throw e
        }
    }

    override fun play() {
        exoPlayer?.playWhenReady = true
    }

    override fun pause() {
        exoPlayer?.playWhenReady = false
    }

    override fun seekTo(positionMs: Long) {
        exoPlayer?.seekTo(positionMs)
    }

    override fun setVolume(volume: Int) {
        val vol = volume.coerceIn(0, 100) / 100f
        exoPlayer?.volume = vol
    }

    override fun release() {
        exoPlayer?.removeListener(playerListener)
        exoPlayer?.release()
        exoPlayer = null
        isInitialized = false
        currentVideoId = null
        _playerState.value = PlaybackState()
        Log.d(TAG, "ExoPlayer released")
    }

    override fun isReady(): Boolean = isInitialized

    override fun isPlaying(): Boolean = exoPlayer?.isPlaying == true

    override fun currentPositionMs(): Long = exoPlayer?.currentPosition ?: 0L

    override fun durationMs(): Long {
        val dur = exoPlayer?.duration ?: C.TIME_UNSET
        return if (dur == C.TIME_UNSET || dur < 0) 0L else dur
    }

    override fun bufferedFraction(): Float = (exoPlayer?.bufferedPercentage ?: 0) / 100f

    override suspend fun prepare(track: Track) {
        // Pre-resolve the stream URL so it's cached when loadAndPlay is called.
        // We don't actually create a MediaItem here — just warm the resolver cache.
        try {
            YouTubeStreamResolver.resolve(track.id)
            Log.d(TAG, "Prepared stream URL for: ${track.title}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to prepare stream for ${track.title}: ${e.message}")
        }
    }
}
