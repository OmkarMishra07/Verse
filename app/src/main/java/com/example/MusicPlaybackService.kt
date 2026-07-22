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
import android.os.PowerManager
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.DefaultMediaNotificationProvider
import com.example.playback.ExoPlayerPlaybackEngine
import com.example.ui.viewmodel.MusicPlayerViewModel
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.*

/**
 * VerseMusicService — foreground MediaSessionService for background music playback.
 *
 * Uses Media3 MediaSessionService which:
 *  - Automatically shows/maintains a persistent playback notification
 *  - Handles lock screen controls, Bluetooth headset buttons, BECOME_NOISY
 *  - Is protected from OS killing (stays alive while notification is visible)
 *  - Provides Android Auto / WearOS compatibility for free
 *
 * Audio is played via Media3 ExoPlayer with direct stream URLs resolved from YouTube.
 * No WebView dependency — all playback is native.
 */
@OptIn(UnstableApi::class)
class VerseMusicService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

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
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                MusicPlayerViewModel.instance?.setPlaying(false, fromUser = true)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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

        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (e: Exception) {
            Log.e("VerseMusicService", "Failed to set thread priority: ${e.message}")
        }

        FirebaseCrashlytics.getInstance().setCustomKey("service_lifecycle_state", "creating")

        // Step 1: Create notification channel
        createNotificationChannel()

        // Step 2: Configure Media3 notification provider
        val provider = DefaultMediaNotificationProvider.Builder(this)
            .setNotificationId(notificationId)
            .setChannelId(channelId)
            .setChannelName(R.string.app_name)
            .build()
        provider.setSmallIcon(R.drawable.ic_notification)
        setMediaNotificationProvider(provider)

        // Step 3: Start foreground with placeholder
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

        // Step 4: Initialize ExoPlayer and playback engine
        ExoPlayerPlaybackEngine.initialize(this)

        // Step 5: Build MediaSession — ExoPlayer is the real player, Media3 drives notification
        val sessionIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val exoPlayer = ExoPlayerPlaybackEngine.exoPlayer
        if (exoPlayer != null) {
            mediaSession = MediaSession.Builder(this, exoPlayer)
                .setSessionActivity(sessionIntent)
                .build()
            addSession(mediaSession!!)
        }

        // Step 6: Noisy receiver for headphone unplug
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(noisyReceiver, android.content.IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(noisyReceiver, android.content.IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        }
        isReceiverRegistered = true

        // Step 7: Listen for ExoPlayer state changes to drive background autoplay
        observePlaybackState()

        FirebaseCrashlytics.getInstance().setCustomKey("service_lifecycle_state", "running")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val vm = MusicPlayerViewModel.instance
        when (intent?.action) {
            ACTION_PLAY  -> vm?.setPlaying(true, fromUser = true)
            ACTION_PAUSE -> vm?.setPlaying(false, fromUser = true)
            ACTION_NEXT  -> vm?.playNext()
            ACTION_PREV  -> vm?.playPrevious()
            ACTION_STOP  -> { vm?.setPlaying(false, fromUser = true); stopSelf() }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        FirebaseCrashlytics.getInstance().log("VerseMusicService: onTaskRemoved — stopping service")
        FirebaseCrashlytics.getInstance().setCustomKey("service_lifecycle_state", "task_removed")
        val vm = MusicPlayerViewModel.instance
        vm?.setPlaying(false)
        serviceScope.launch {
            delay(300)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    /**
     * Observe ExoPlayer state for:
     * 1. Background autoplay: when track ends → play next
     * 2. Wake/Wifi lock management
     */
    private fun observePlaybackState() {
        serviceScope.launch {
            // Wait until ViewModel is available
            while (MusicPlayerViewModel.instance == null) {
                delay(200)
            }
            val vm = MusicPlayerViewModel.instance!!

            // Track lock state
            var lastPlayingForLock = false

            // Observe ExoPlayer state for background autoplay and lock management
            ExoPlayerPlaybackEngine.playerState.collect { state ->
                // Manage WakeLock and WifiLock
                if (state.isPlaying != lastPlayingForLock) {
                    lastPlayingForLock = state.isPlaying
                    if (state.isPlaying) acquireLocks() else releaseLocks()
                }
            }
        }

        // Listen for ExoPlayer track ended → auto-advance
        serviceScope.launch {
            while (MusicPlayerViewModel.instance == null) {
                delay(200)
            }
            val vm = MusicPlayerViewModel.instance!!

            val exoPlayer = ExoPlayerPlaybackEngine.exoPlayer ?: return@launch
            exoPlayer.addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == androidx.media3.common.Player.STATE_ENDED) {
                        val pos = exoPlayer.currentPosition
                        val dur = exoPlayer.duration
                        // Guard against false-positive ENDED events near start (seek-to-start)
                        val isNearStart = pos < 3000L
                        if (!isNearStart) {
                            Log.d("VerseMusicService", "Track ended (pos=${pos}ms, dur=${dur}ms) → playNext")
                            vm.playNext(isAutoPlay = true)
                        } else {
                            Log.w("VerseMusicService", "Ignored false-positive ENDED near start (pos=${pos}ms)")
                        }
                    }
                }
            })
        }
    }

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
        ExoPlayerPlaybackEngine.release()
        super.onDestroy()
    }
}
