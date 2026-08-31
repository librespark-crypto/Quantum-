package com.quantum.player.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.quantum.player.R
import com.quantum.player.model.MediaItem
import com.quantum.player.player.QuantumPlayerActivity
import java.util.Locale

/**
 * Notification controller for background playback.
 * Provides playback controls in the notification shade and lock screen.
 *
 * Fixes: every `Intent(context, ::class.java)` was a syntax error, the artwork
 * lookup passed a `String` where a `Uri` was required and called a
 * `androidx.core.graphics.BitmapUtils` class that does not exist, and
 * [updatePosition] incremented the notification id so each update posted a brand
 * new notification instead of replacing the previous one.
 */
class NotificationController(private val context: Context) {

    /** Build the ongoing playback notification. */
    fun buildPlaybackNotification(
        mediaItem: MediaItem,
        isPlaying: Boolean,
        positionMs: Long,
        durationMs: Long
    ): Notification {
        createChannelIfNeeded()

        val contentIntent = PendingIntent.getActivity(
            context,
            REQUEST_CONTENT,
            Intent(context, QuantumPlayerActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            immutableFlags(PendingIntent.FLAG_UPDATE_CURRENT)
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(mediaItem.displayName)
            .setContentText(
                if (durationMs > 0) {
                    "${formatPosition(positionMs)} / ${formatPosition(durationMs)}"
                } else {
                    formatPosition(positionMs)
                }
            )
            .setSmallIcon(R.drawable.ic_stat_playback)
            .setSubText(if (isPlaying) context.getString(R.string.play) else context.getString(R.string.pause))
            .setShowWhen(false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setContentIntent(contentIntent)
            .addAction(
                android.R.drawable.ic_media_rew,
                context.getString(R.string.backward_10),
                serviceAction(QuantumBackgroundService.ACTION_SEEK_BACK, REQUEST_SEEK_BACK)
            )
            .addAction(
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isPlaying) context.getString(R.string.pause) else context.getString(R.string.play),
                serviceAction(QuantumBackgroundService.ACTION_TOGGLE_PLAY, REQUEST_TOGGLE)
            )
            .addAction(
                android.R.drawable.ic_media_ff,
                context.getString(R.string.forward_10),
                serviceAction(QuantumBackgroundService.ACTION_SEEK_FORWARD, REQUEST_SEEK_FORWARD)
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.stop),
                serviceAction(QuantumBackgroundService.ACTION_STOP, REQUEST_STOP)
            )
            .build()
    }

    /** Post or refresh the notification. */
    fun show(notification: Notification) {
        // On API 33+ posting requires POST_NOTIFICATIONS; silently skipping is
        // correct here because playback itself must keep working.
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    /** Dismiss the playback notification. */
    fun hide() {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun serviceAction(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            context,
            requestCode,
            Intent(context, QuantumBackgroundService::class.java).setAction(action),
            immutableFlags(PendingIntent.FLAG_UPDATE_CURRENT)
        )

    private fun immutableFlags(base: Int): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            base or PendingIntent.FLAG_IMMUTABLE
        } else {
            base
        }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = ContextCompat.getSystemService(context, NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_playback),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notification_channel_playback_desc)
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val NOTIFICATION_ID = 0x51_41

        private const val CHANNEL_ID = "quantum_player_background"

        private const val REQUEST_CONTENT = 0
        private const val REQUEST_TOGGLE = 1
        private const val REQUEST_SEEK_BACK = 2
        private const val REQUEST_SEEK_FORWARD = 3
        private const val REQUEST_STOP = 4

        /** Format a position as m:ss or h:mm:ss. */
        fun formatPosition(positionMs: Long): String {
            val safe = positionMs.coerceAtLeast(0L)
            val totalSeconds = safe / 1000
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            return if (hours > 0) {
                String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format(Locale.US, "%d:%02d", minutes, seconds)
            }
        }
    }
}
