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
import androidx.media3.session.DefaultMediaNotificationProvider
import com.example.ui.viewmodel.MusicPlayerViewModel
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

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

    private val notificationId = 1001
    private val channelId = "verse_playback_channel"

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

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Create our channel — use the SAME channelId that we pass to
            // DefaultMediaNotificationProvider so Media3 and our placeholder
            // both live in the same channel with no conflict.
            val channel = NotificationChannel(
                channelId,
                "Verse Music",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Verse music playback controls"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildPlaceholderNotification(): Notification {
        // Minimal placeholder — only shown for a fraction of a second until
        // Media3's DefaultMediaNotificationProvider takes over.
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Verse")
            .setContentText("Loading...")
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        FirebaseCrashlytics.getInstance().setCustomKey("service_lifecycle_state", "creating")

        // Step 1: Create channel BEFORE everything else
        createNotificationChannel()

        // Step 2: Tell Media3 to use OUR channel and notification ID.
        // This MUST happen before the MediaSession is created.
        val provider = DefaultMediaNotificationProvider.Builder(this)
            .setNotificationId(notificationId)
            .setChannelId(channelId)
            .setChannelName(R.string.app_name)  // "Verse"
            .build()
        // Give Media3 our branded icon for the status bar
        provider.setSmallIcon(R.drawable.ic_notification)
        setMediaNotificationProvider(provider)

        // Step 3: Satisfy Android's 5-second foreground rule with a placeholder.
        // Media3 will replace this notification automatically once syncState()
        // fires and the player transitions from STATE_IDLE → STATE_READY.
        val placeholder = buildPlaceholderNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    notificationId,
                    placeholder,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(notificationId, placeholder)
            }
        } catch (e: Exception) {
            Log.e("VerseMusicService", "startForeground failed: ${e.message}")
        }

        // Step 4: Initialize WebView and player
        val vm = MusicPlayerViewModel.instance
        WebViewHolder.initInService(this, vm)
        versePlayer = VerseWebViewPlayer()

        // Step 5: Build MediaSession — Media3 drives the notification from here
        val sessionIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, versePlayer!!)
            .setSessionActivity(sessionIntent)
            .build()

        // Step 6: Noisy receiver for headphone unplug
        registerReceiver(noisyReceiver, android.content.IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        isReceiverRegistered = true

        // Step 7: Start observing ViewModel — each update calls invalidateState()
        // which triggers Media3 to refresh the notification with real track info
        observeViewModelState()
        FirebaseCrashlytics.getInstance().setCustomKey("service_lifecycle_state", "running")
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

    /**
     * Called when the user swipes away the app from Recents.
     * Stop playback cleanly and let the service die so no zombie notification remains.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        FirebaseCrashlytics.getInstance().log("VerseMusicService: onTaskRemoved — stopping service")
        FirebaseCrashlytics.getInstance().setCustomKey("service_lifecycle_state", "task_removed")
        val vm = MusicPlayerViewModel.instance
        vm?.setPlaying(false)
        // Give the pause command a moment to propagate, then stop
        serviceScope.launch {
            delay(300)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
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

            // ── Notification-critical state: track, playing, loading ──────────
            // Only update Media3 when these actually change (NOT on every position
            // tick which fires every 500 ms). This prevents constant notification
            // rebuilds that cause flicker and drain battery.
            var lastPlayingForLock = false
            combine(
                vm.currentTrack,
                vm.isPlaying,
                vm.isLoading
            ) { track, playing, loading -> Triple(track, playing, loading) }
                .distinctUntilChanged()
                .collect { (track, playing, loading) ->
                    // Push state into VerseWebViewPlayer so Media3 notification updates
                    val pos = vm.currentPositionMs.value
                    val dur = vm.durationMs.value
                    versePlayer?.syncState(track, playing, pos, dur, loading)

                    // Manage WakeLock and WifiLock
                    if (playing != lastPlayingForLock) {
                        lastPlayingForLock = playing
                        if (playing) acquireLocks() else releaseLocks()
                    }
                }
        }

        // ── Seek detector (updates notification only on manual seek) ─────────
        // To prevent notification flicker, we don't update Media3 on every 500ms tick.
        // Instead, we watch for jumps > 1500ms which indicate a manual seek.
        serviceScope.launch {
            var attempts = 0
            while (MusicPlayerViewModel.instance == null && attempts < 30) {
                delay(200); attempts++
            }
            val vm = MusicPlayerViewModel.instance ?: return@launch

            var lastPos = vm.currentPositionMs.value
            vm.currentPositionMs.collect { pos ->
                if (kotlin.math.abs(pos - lastPos) > 1500L) {
                    val track = vm.currentTrack.value
                    if (track != null) {
                        versePlayer?.syncState(
                            track, 
                            vm.isPlaying.value, 
                            pos, 
                            vm.durationMs.value, 
                            vm.isLoading.value
                        )
                    }
                }
                lastPos = pos
            }
        }

        // ── Background autoplay driver ────────────────────────────────────────
        // The Composable LaunchedEffect(track?.id, playTrigger) that calls
        // WebViewHolder.loadVideo() only runs while the UI is visible.
        // When the song ends and playNext() fires in the background, we need
        // the service to drive WebViewHolder.loadVideo() so the next track
        // actually starts playing.
        serviceScope.launch {
            // Wait until ViewModel is available (same guard as above)
            var attempts = 0
            while (MusicPlayerViewModel.instance == null && attempts < 30) {
                delay(200); attempts++
            }
            val vm = MusicPlayerViewModel.instance ?: return@launch

            var lastPlayTrigger = vm.playTrigger.value
            var lastTrackId: String? = vm.currentTrack.value?.id

            combine(vm.currentTrack, vm.playTrigger) { track, trigger ->
                track to trigger
            }.collect { (track, trigger) ->
                val trackId = track?.id ?: return@collect
                // Fire loadVideo only when a new play is requested (trigger changed
                // OR the track itself changed), mirroring the Composable's LaunchedEffect.
                if (trigger != lastPlayTrigger || trackId != lastTrackId) {
                    lastPlayTrigger = trigger
                    lastTrackId = trackId
                    val startSecs = (vm.currentPositionMs.value / 1000).toInt()
                    Log.d("VerseMusicService", "BG autoplay: loading video $trackId @ ${startSecs}s")
                    WebViewHolder.loadVideo(trackId, startSecs, autoplay = true)
                }
            }
        }
    }

    private data class QuintState<A, B, C, D, E>(val first: A, val second: B, val third: C, val fourth: D, val fifth: E)

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
        FirebaseCrashlytics.getInstance().setCustomKey("service_lifecycle_state", "destroyed")
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
    private var isLoading = false
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
            .setCurrentMediaItemIndex(if (playlist.isEmpty()) C.INDEX_UNSET else 0)
            .setPlaybackState(
                when {
                    track == null -> Player.STATE_IDLE
                    isLoading     -> Player.STATE_BUFFERING
                    else          -> Player.STATE_READY
                }
            )
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
        newDurationMs: Long,
        newIsLoading: Boolean = false
    ) {
        track       = newTrack
        isPlaying   = newIsPlaying
        isLoading   = newIsLoading
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
