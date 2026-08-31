package com.quantum.player.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.MediaSessionCompat
import android.os.Build
import android.service.media.MediaBrowserService
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.quantum.player.core.PlaybackEngine
import com.quantum.player.model.MediaItem
import com.quantum.player.player.QuantumApplication

/**
 * Background playback service for Quantum player.
 * Provides MediaSession-based playback controls for:
 * - Notification controls
 * - Headset/Bluetooth controls
 * - Lock-screen controls
 * - Audio-only background playback
 */
class QuantumBackgroundService : MediaBrowserService(), PlaybackEngine {

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSessionCompat? = null

    override fun onCreate() {
        super.onCreate()

        // Create MediaSession for playback controls
        mediaSession = MediaSessionCompat(this, "QuantumService").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_ENABLE_KEY_CONTROL
            )
            setCallback(MediaSessionCallback())
            setActive(true)
        }

        // Initialize ExoPlayer
        player = ExoPlayer.Builder(this).build()
        player?.addListener(object : ExoPlayer.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateMediaSessionState()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                updateMediaSessionState()
            }

            override fun onCurrentPositionChanged(position: Long) {
                updatePosition(position)
            }

            override fun onDurationChanged(newDuration: Long) {
                updateMediaSessionState()
            }

            override fun onError(error: Exception?) {
                // Handle error
            }
        })

        // Set up audio manager for handling focus
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        // Handle playback intents
        val action = intent.action
        when (action) {
            "com.quantum.player.PLAY" -> {
                val uri = intent.getStringExtra("uri")
                if (uri != null) {
                    playMediaItem(uri)
                }
            }
            "com.quantum.player.PAUSE" -> {
                player?.pause()
            }
            "com.quantum.player.SEEK" -> {
                val position = intent.getLongExtra("position", 0L)
                player?.seekTo(position)
            }
            "com.quantum.player.SPEED" -> {
                val speed = intent.getFloatExtra("speed", 1.0f)
                player?.setPlaybackSpeed(speed)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        player?.stop()
        player?.release()
        player = null
        mediaSession?.setActive(false)
        mediaSession = null
        super.onDestroy()
    }

    override fun onGetRoot(
        callerPackage: String?,
        requestedMaxDepth: Int,
        outReason: String?
    ): BrowserRoot? {
        return BrowserRoot("", null)
    }

    override fun onLoadChildren(
        parent: String,
        result: Result<List<MediaBrowserCompat.MediaItem>>
    ) {
        result.sendResult(emptyList())
    }

    /**
     * Play a media item from the given URI.
     */
    private fun playMediaItem(uri: String) {
        try {
            val mediaItem = MediaItem(uri = uri)
            player?.setMediaItem(mediaItem)
            player?.prepare()
            player?.play()
            updateMediaSessionState()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Update MediaSession state with current playback information.
     */
    private fun updateMediaSessionState() {
        player?.currentPosition?.let { position ->
            player?.duration?.let { duration ->
                val state = when {
                    player?.isPlaying == true -> MediaSessionCompat.State.Builder()
                        .setState(MediaSessionCompat.STATE_PLAYING, position, 1.0f)
                        .build()
                    else -> MediaSessionCompat.State.Builder()
                        .setState(MediaSessionCompat.STATE_PAUSED, position, 1.0f)
                        .build()
                }
                mediaSession?.setState(state)

                // Update notification
                updateNotification(position, duration)
            }
        }
    }

    /**
     * Update the notification with current playback position.
     */
    private fun updateNotification(position: Long, duration: Long) {
        val contentTitle = "Quantum Player"
        val contentText = formatPosition(position)

        // Play intent
        val playbackIntent = Intent(this, ::class.java).apply {
            action = "com.quantum.player.PLAY"
        }
        val pendingIntent = PendingIntent.getService(
            this, 0, playbackIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Skip previous intent
        val skipPrevIntent = Intent(this, ::class.java).apply {
            action = "com.quantum.player.SKIP_PREV"
        }
        val skipPendingPrev = PendingIntent.getService(
            this, 1, skipPrevIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Skip next intent
        val skipNextIntent = Intent(this, ::class.java).apply {
            action = "com.quantum.player.SKIP_NEXT"
        }
        val skipPendingNext = PendingIntent.getService(
            this, 2, skipNextIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Pause intent
        val pauseIntent = Intent(this, ::class.java).apply {
            action = "com.quantum.player.PAUSE"
        }
        val pausePendingIntent = PendingIntent.getService(
            this, 3, pauseIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Stop intent
        val stopIntent = Intent(this, ::class.java).apply {
            action = "com.quantum.player.STOP"
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 4, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, "quantum_player")
            .setStyle(
                androidx.media2.compat.NotificationCompat.BigTextStyle()
                    .bigText(formatPosition(duration))
            )
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setLargeIcon(androidx.core.graphics.BitmapUtils.createBitmap(64, 64) { canvas ->
                canvas.drawColor(android.graphics.Color.WHITE)
            })
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_media_previous, "Previous", skipPendingPrev)
            .addAction(android.R.drawable.ic_media_pause, "Pause", pausePendingIntent)
            .addAction(android.R.drawable.ic_media_next, "Next", skipPendingNext)

        val notification = builder.build()
        startForeground(1, notification)
    }

    /** Format position to MM:SS. */
    private fun formatPosition(position: Long): String {
        val minutes = position / 60000
        val seconds = (position % 60000) / 1000
        return "$minutes:${seconds}%2d".format(seconds)
    }

    /** MediaSession callback for handling playback commands. */
    private class MediaSessionCallback : MediaSessionCompat.Callback() {
        override fun onPlay() {
            player?.play()
        }

        override fun onPause() {
            player?.pause()
        }

        override fun onSeekTo(position: Long) {
            player?.seekTo(position)
        }

        override fun onStop() {
            player?.stop()
            player?.release()
            player = null
            mediaSession?.setActive(false)
            stopSelf()
        }

        override fun onFastForward() {
            player?.seekTo(
                (player?.currentPosition ?: 0) + 10000
            )
        }

        override fun onRewind() {
            player?.seekTo(
                (player?.currentPosition ?: 0) - 10000
            )
        }
    }
}