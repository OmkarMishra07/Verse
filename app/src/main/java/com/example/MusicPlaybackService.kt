package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import coil.ImageLoader
import coil.request.ImageRequest
import com.example.ui.viewmodel.MusicPlayerViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine

class MusicPlaybackService : Service() {

    private var mediaSession: MediaSession? = null
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private val notificationId = 1002
    private val channelId = "music_playback_channel"

    companion object {
        const val ACTION_UPDATE = "com.example.action.UPDATE"
        const val ACTION_PLAY = "com.example.action.PLAY"
        const val ACTION_PAUSE = "com.example.action.PAUSE"
        const val ACTION_NEXT = "com.example.action.NEXT"
        const val ACTION_PREV = "com.example.action.PREV"
        const val ACTION_STOP = "com.example.action.STOP"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        setupMediaSession()
        observeViewModelState()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Music Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows music playback controls in the notification drawer"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun setupMediaSession() {
        mediaSession = MediaSession(this, "IpodPlayerMediaSession").apply {
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    MusicPlayerViewModel.instance?.setPlaying(true)
                }

                override fun onPause() {
                    MusicPlayerViewModel.instance?.setPlaying(false)
                }

                override fun onSkipToNext() {
                    MusicPlayerViewModel.instance?.playNext()
                }

                override fun onSkipToPrevious() {
                    MusicPlayerViewModel.instance?.playPrevious()
                }

                override fun onSeekTo(pos: Long) {
                    MusicPlayerViewModel.instance?.seekTo(pos)
                }
            })
            isActive = true
        }
    }

    private fun observeViewModelState() {
        serviceScope.launch {
            val vm = MusicPlayerViewModel.instance ?: return@launch
            combine(
                vm.currentTrack,
                vm.isPlaying,
                vm.currentPositionMs,
                vm.durationMs
            ) { track, isPlaying, position, duration ->
                StateUpdate(track, isPlaying, position, duration)
            }.collect { state ->
                updatePlaybackState(state)
                updateNotificationAndMetadata(state)
            }
        }
    }

    private data class StateUpdate(
        val track: com.example.data.model.Track?,
        val isPlaying: Boolean,
        val position: Long,
        val duration: Long
    )

    private var lastSetPosition = -1L
    private var lastSetPlaying = false

    private fun updatePlaybackState(state: StateUpdate) {
        val session = mediaSession ?: return
        val currentPos = state.position
        val isPlaying = state.isPlaying
        
        // Update if play state changed, or position drifted by more than 2 seconds, or if position is 0 (new track)
        val shouldUpdate = isPlaying != lastSetPlaying || 
                           currentPos == 0L || 
                           Math.abs(currentPos - lastSetPosition) > 2000L

        if (shouldUpdate) {
            lastSetPosition = currentPos
            lastSetPlaying = isPlaying

            val playbackStateBuilder = PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_SKIP_TO_NEXT or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackState.ACTION_SEEK_TO
                )

            val pbState = if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
            playbackStateBuilder.setState(pbState, currentPos, if (isPlaying) 1.0f else 0.0f)
            session.setPlaybackState(playbackStateBuilder.build())
        }
    }

    private var lastThumbnailUrl: String? = null
    private var lastBitmap: Bitmap? = null

    private fun updateNotificationAndMetadata(state: StateUpdate) {
        val track = state.track ?: return
        val session = mediaSession ?: return

        // Update MediaSession Metadata
        val metadataBuilder = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, track.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, track.artist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, track.album)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, state.duration)

        if (track.thumbnailUrl == lastThumbnailUrl && lastBitmap != null) {
            metadataBuilder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, lastBitmap)
            session.setMetadata(metadataBuilder.build())
            showNotification(state, lastBitmap)
        } else {
            val url = track.thumbnailUrl
            lastThumbnailUrl = url
            val imageLoader = ImageLoader(this)
            val request = ImageRequest.Builder(this)
                .data(url)
                .target { drawable ->
                    val bitmap = (drawable as? BitmapDrawable)?.bitmap
                    if (bitmap != null) {
                        lastBitmap = bitmap
                        metadataBuilder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, bitmap)
                        session.setMetadata(metadataBuilder.build())
                        showNotification(state, bitmap)
                    } else {
                        session.setMetadata(metadataBuilder.build())
                        showNotification(state, null)
                    }
                }
                .build()
            imageLoader.enqueue(request)
        }
    }

    private fun showNotification(state: StateUpdate, albumArt: Bitmap?) {
        val track = state.track ?: return
        val session = mediaSession ?: return

        // PendingIntent to launch MainActivity when clicking the notification
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Play/Pause Intent
        val playPauseIntent = PendingIntent.getService(
            this, 1,
            Intent(this, MusicPlaybackService::class.java).apply { action = if (state.isPlaying) ACTION_PAUSE else ACTION_PLAY },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Next Intent
        val nextIntent = PendingIntent.getService(
            this, 2,
            Intent(this, MusicPlaybackService::class.java).apply { action = ACTION_NEXT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Prev Intent
        val prevIntent = PendingIntent.getService(
            this, 3,
            Intent(this, MusicPlaybackService::class.java).apply { action = ACTION_PREV },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Stop Intent (swiping away or dismissing)
        val stopIntent = PendingIntent.getService(
            this, 4,
            Intent(this, MusicPlaybackService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificationBuilder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        notificationBuilder
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(track.title)
            .setContentText(track.artist)
            .setContentIntent(contentIntent)
            .setDeleteIntent(stopIntent)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(state.isPlaying)

        if (albumArt != null) {
            notificationBuilder.setLargeIcon(albumArt)
        }

        val prevAction = Notification.Action.Builder(
            android.R.drawable.ic_media_previous,
            "Previous",
            prevIntent
        ).build()
        val playPauseAction = Notification.Action.Builder(
            if (state.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            if (state.isPlaying) "Pause" else "Play",
            playPauseIntent
        ).build()
        val nextAction = Notification.Action.Builder(
            android.R.drawable.ic_media_next,
            "Next",
            nextIntent
        ).build()

        notificationBuilder.addAction(prevAction)
        notificationBuilder.addAction(playPauseAction)
        notificationBuilder.addAction(nextAction)

        val notification = notificationBuilder.build()
        if (state.isPlaying) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    notificationId,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(notificationId, notification)
            }
        } else {
            stopForeground(STOP_FOREGROUND_DETACH)
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(notificationId, notification)
        }
    }

    private fun buildPlaceholderNotification(): Notification {
        val notificationBuilder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return notificationBuilder
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("iPod Player")
            .setContentText("Loading playback...")
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val placeholder = buildPlaceholderNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                notificationId,
                placeholder,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(notificationId, placeholder)
        }

        when (intent?.action) {
            ACTION_PLAY -> MusicPlayerViewModel.instance?.setPlaying(true)
            ACTION_PAUSE -> MusicPlayerViewModel.instance?.setPlaying(false)
            ACTION_NEXT -> MusicPlayerViewModel.instance?.playNext()
            ACTION_PREV -> MusicPlayerViewModel.instance?.playPrevious()
            ACTION_STOP -> {
                MusicPlayerViewModel.instance?.setPlaying(false)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceJob.cancel()
        mediaSession?.release()
        super.onDestroy()
    }
}
