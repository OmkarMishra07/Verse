package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.ui.viewmodel.MusicPlayerViewModel
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine

/**
 * VerseMusicService — foreground MediaSessionService for background music playback.
 *
 * Uses Media3 MediaSessionService which:
 *  - Automatically shows/maintains a persistent playback notification
 *  - Handles lock screen controls, Bluetooth headset buttons, BECOME_NOISY
 *  - Is protected from OS killing (stays alive while notification is visible)
 *  - Provides Android Auto / WearOS compatibility for free
 *
 * Audio is played via YouTube IFrame API (WebView), not ExoPlayer directly.
 * VerseWebViewPlayer bridges our WebView state to Media3's MediaSession.
 */
@OptIn(UnstableApi::class)
class VerseMusicService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var versePlayer: VerseWebViewPlayer? = null

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var locksAcquired = false

    companion object {
        const val ACTION_PLAY  = "com.example.verse.ACTION_PLAY"
        const val ACTION_PAUSE = "com.example.verse.ACTION_PAUSE"
        const val ACTION_NEXT  = "com.example.verse.ACTION_NEXT"
        const val ACTION_PREV  = "com.example.verse.ACTION_PREV"
        const val ACTION_STOP  = "com.example.verse.ACTION_STOP"
    }

    private var isReceiverRegistered = false

    private val noisyReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                MusicPlayerViewModel.instance?.setPlaying(false)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Initialize the WebView inside the service so it lives in service process,
        // completely detached from Activity lifecycle.
        val vm = MusicPlayerViewModel.instance
        WebViewHolder.initInService(this, vm)

        versePlayer = VerseWebViewPlayer()

        // MediaSession — drives the system notification and all external controls
        val sessionIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, versePlayer!!)
            .setSessionActivity(sessionIntent)
            .build()

        // Register noisy receiver to automatically pause when headphones are unplugged
        registerReceiver(noisyReceiver, android.content.IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        isReceiverRegistered = true

        observeViewModelState()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val vm = MusicPlayerViewModel.instance
        when (intent?.action) {
            ACTION_PLAY  -> vm?.setPlaying(true)
            ACTION_PAUSE -> vm?.setPlaying(false)
            ACTION_NEXT  -> vm?.playNext()
            ACTION_PREV  -> vm?.playPrevious()
            ACTION_STOP  -> { vm?.setPlaying(false); stopSelf() }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun observeViewModelState() {
        serviceScope.launch {
            // Wait until ViewModel is available
            var attempts = 0
            while (MusicPlayerViewModel.instance == null && attempts < 30) {
                delay(200); attempts++
            }
            val vm = MusicPlayerViewModel.instance ?: run {
                Log.e("VerseMusicService", "ViewModel unavailable — giving up")
                return@launch
            }

            // Attach the ViewModel's JS bridge to the WebView now that VM is ready
            WebViewHolder.attachViewModel(vm)

            var lastPlaying = false
            combine(vm.currentTrack, vm.isPlaying, vm.currentPositionMs, vm.durationMs) {
                track, playing, pos, dur -> Quad(track, playing, pos, dur)
            }.collect { (track, playing, pos, dur) ->
                // Push state into VerseWebViewPlayer so Media3 notification updates
                versePlayer?.syncState(track, playing, pos, dur)

                // Manage WakeLock and WifiLock
                if (playing != lastPlaying) {
                    lastPlaying = playing
                    if (playing) {
                        acquireLocks()
                    } else {
                        releaseLocks()
                    }
                }
            }
        }
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    // ── Locks ────────────────────────────────────────────────────────────────

    private fun acquireLocks() {
        if (locksAcquired) return
        locksAcquired = true
        try {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Verse:WakeLock")
                .apply { acquire() }
        } catch (e: Exception) { Log.e("VerseMusicService", "WakeLock error: ${e.message}") }
        try {
            wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Verse:WifiLock")
                .apply { acquire() }
        } catch (e: Exception) { Log.e("VerseMusicService", "WifiLock error: ${e.message}") }
    }

    private fun releaseLocks() {
        if (!locksAcquired) return
        locksAcquired = false
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (_: Exception) {}
        wakeLock = null
        wifiLock = null
    }

    override fun onDestroy() {
        releaseLocks()
        if (isReceiverRegistered) {
            try {
                unregisterReceiver(noisyReceiver)
            } catch (_: Exception) {}
            isReceiverRegistered = false
        }
        serviceJob.cancel()
        mediaSession?.release()
        versePlayer?.release()
        WebViewHolder.destroy()
        super.onDestroy()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// VerseWebViewPlayer — bridges WebViewHolder state → Media3 MediaSession
//
// SimpleBasePlayer handles ALL the Player interface boilerplate. We only need to:
//   1. Implement getState() to report current playback state
//   2. Override handle*() methods to respond to commands from system controls
//
// When syncState() is called, invalidateState() triggers Media3 to re-read
// getState() and update the notification/lock screen automatically.
// ─────────────────────────────────────────────────────────────────────────────
@OptIn(UnstableApi::class)
class VerseWebViewPlayer : SimpleBasePlayer(Looper.getMainLooper()) {

    private var track: com.example.data.model.Track? = null
    private var isPlaying = false
    private var positionMs = 0L
    private var durationMs = 0L

    override fun getState(): State {
        val playlist: List<MediaItemData> = track?.let { t ->
            val artUri = try { Uri.parse(t.thumbnailUrl) } catch (_: Exception) { null }
            listOf(
                MediaItemData.Builder(t.id)
                    .setMediaItem(
                        MediaItem.Builder()
                            .setMediaId(t.id)
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(t.title)
                                    .setArtist(t.artist)
                                    .setAlbumTitle(t.album)
                                    .setArtworkUri(artUri)
                                    .build()
                            )
                            .build()
                    )
                    .setDurationUs(if (durationMs > 0L) durationMs * 1_000L else C.TIME_UNSET)
                    .setIsSeekable(true)
                    .setIsDynamic(false)
                    .build()
            )
        } ?: emptyList()

        return State.Builder()
            .setAvailableCommands(
                Player.Commands.Builder().addAll(
                    Player.COMMAND_PLAY_PAUSE,
                    Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_GET_TIMELINE,
                    Player.COMMAND_GET_METADATA,
                ).build()
            )
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(0)
            .setPlaybackState(if (track == null) Player.STATE_IDLE else Player.STATE_READY)
            .setPlayWhenReady(isPlaying, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setContentPositionMs(positionMs)
            .build()
    }

    // ── Command handlers — called when system controls issue commands ──────────

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        MusicPlayerViewModel.instance?.setPlaying(playWhenReady)
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        @Player.Command seekCommand: Int
    ): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM  -> MusicPlayerViewModel.instance?.playNext()
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> MusicPlayerViewModel.instance?.playPrevious()
            else -> MusicPlayerViewModel.instance?.seekTo(positionMs)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        MusicPlayerViewModel.instance?.setPlaying(false)
        return Futures.immediateVoidFuture()
    }

    // ── State sync — called by VerseMusicService on every ViewModel update ────

    fun syncState(
        newTrack: com.example.data.model.Track?,
        newIsPlaying: Boolean,
        newPositionMs: Long,
        newDurationMs: Long
    ) {
        track       = newTrack
        isPlaying   = newIsPlaying
        positionMs  = newPositionMs
        durationMs  = newDurationMs
        invalidateState()   // Tells Media3 to re-read getState() and refresh notification
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Stub kept for backwards compatibility (manifest entry, any lingering references)
// ─────────────────────────────────────────────────────────────────────────────
class MusicPlaybackService : android.app.Service() {
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY
}
