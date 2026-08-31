package com.quantum.player.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.quantum.player.core.PlaybackState
import com.quantum.player.model.MediaItem
import com.quantum.player.player.QuantumApplication

/**
 * Notification controller for background playback.
 * Provides playback controls in the notification shade and lock screen.
 */
class NotificationController(private val context: Context, private val player: androidx.media3.exoplayer.ExoPlayer?) {

    private var notificationId = 1
    private var notificationChannelId = "quantum_player_background"

    /** Build and show the playback notification. */
    fun showPlaybackNotification(mediaItem: MediaItem) {
        // Create intent to return to app
        val contentIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, ::class.java).apply {
                action = "com.quantum.player.RESUME"
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Play/pause intent
        val playPauseIntent = PendingIntent.getService(
            context, 1,
            Intent(context, ::class.java).apply {
                action = "com.quantum.player.PAUSE"
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Skip previous intent
        val skipPrevIntent = PendingIntent.getService(
            context, 2,
            Intent(context, ::class.java).apply {
                action = "com.quantum.player.SKIP_PREV"
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Skip next intent
        val skipNextIntent = PendingIntent.getService(
            context, 3,
            Intent(context, ::class.java).apply {
                action = "com.quantum.player.SKIP_NEXT"
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Stop intent
        val stopIntent = PendingIntent.getService(
            context, 4,
            Intent(context, ::class.java).apply {
                action = "com.quantum.player.STOP"
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Seek forward intent
        val seekForwardIntent = PendingIntent.getService(
            context, 5,
            Intent(context, ::class.java).apply {
                action = "com.quantum.player.SEEK_FORWARD"
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Seek backward intent
        val seekBackwardIntent = PendingIntent.getService(
            context, 6,
            Intent(context, ::class.java).apply {
                action = "com.quantum.player.SEEK_BACKWARD"
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Set playback speed intent
        val speedIntent = PendingIntent.getService(
            context, 7,
            Intent(context, ::class.java).apply {
                action = "com.quantum.player.SPEED_CHANGE"
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, notificationChannelId)
            .setContentTitle(mediaItem.title ?: "Quantum Player")
            .setContentText(formatPosition(player?.currentPosition ?: 0))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setLargeIcon(mediaItem.artworkUri?.let {
                android.provider.MediaStore.Images.Media.getBitmap(
                    context.contentResolver,
                    it
                )
            } ?: androidx.core.graphics.BitmapUtils.getAppIcon(context))
            .setSubText("Playing")
            .setShowWhen(false)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentIntent)
            .addAction(android.R.drawable.ic_media_rew, "Back 10s", seekBackwardIntent)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Pause",
                playPauseIntent
            )
            .addAction(android.R.drawable.ic_media_forward, "Forward 10s", seekForwardIntent)
            .addAction(android.R.drawable.ic_menu_manage, "Speed", speedIntent)
            .addAction(
                android.R.drawable.ic_menu_close_cancel,
                "Stop",
                stopIntent
            )

        val notification = builder.build()

        // Get or create notification channel
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                notificationChannelId,
                "Quantum Player Background",
                android.app.NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background playback notifications"
                enableLights(true)
                lightColor = android.graphics.Color.WHITE
                enableVibration(true)
            }
            manager.createNotificationChannel(channel)
        }

        manager.notify(notificationId, notification)
    }

    /** Update notification with current playback position. */
    fun updatePosition(position: Long, duration: Long) {
        val builder = NotificationCompat.Builder(context, notificationChannelId)
            .setContentText(formatPosition(position))

        notificationId++
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(notificationId, builder.build())
    }

    /** Hide the playback notification. */
    fun hide() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.cancel(notificationId)
    }

    /** Format position to MM:SS. */
    private fun formatPosition(position: Long): String {
        val minutes = position / 60000
        val seconds = (position % 60000) / 1000
        return "$minutes:${seconds}%2d".format(seconds)
    }

    /** Handle notification action clicks. */
    fun handleAction(action: String) {
        when (action) {
            "com.quantum.player.PAUSE" -> player?.pause()
            "com.quantum.player.SKIP_PREV" -> player?.seekTo(
                (player?.currentPosition ?: 0) - 10000
            )
            "com.quantum.player.SKIP_NEXT" -> player?.seekTo(
                (player?.currentPosition ?: 0) + 10000
            )
            "com.quantum.player.STOP" -> player?.stop()
            "com.quantum.player.SEEK_FORWARD" -> player?.seekTo(
                (player?.currentPosition ?: 0) + 30000
            )
            "com.quantum.player.SEEK_BACKWARD" -> player?.seekTo(
                (player?.currentPosition ?: 0) - 30000
            )
            "com.quantum.player.RESUME" -> player?.play()
        }
    }
}