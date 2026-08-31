package com.quantum.player.service

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.quantum.player.core.PlaybackEngine
import com.quantum.player.core.PlaybackState
import com.quantum.player.model.MediaItem
import com.quantum.player.player.QuantumApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Background playback service for Quantum player.
 *
 * Provides:
 *  - a foreground service so playback survives the UI being backgrounded
 *  - notification controls (play/pause, seek, stop)
 *
 * The service does **not** own a player. It drives the single application-scoped
 * [PlaybackEngine] held by [QuantumApplication], which is what guarantees there
 * is only ever one player and therefore no double playback or leaked surfaces.
 *
 * The previous version extended `MediaBrowserService` (a media *browsing*
 * service, wrong for a video player), also declared itself as a `PlaybackEngine`
 * without implementing a single member of it, imported `MediaSessionCompat` from
 * a package it does not live in, and overrode `ExoPlayer.Listener` callbacks
 * that do not exist.
 *
 * Limitation: no `MediaSession` is created, so bluetooth/lock-screen transport
 * keys are not handled. Wiring one up requires handing a Media3 `Player` to
 * `androidx.media3.session`, which would break the backend-agnostic
 * [PlaybackEngine] boundary; it is deliberately left out rather than faked.
 */
class QuantumBackgroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val notifications by lazy { NotificationController(this) }

    private val engine: PlaybackEngine
        get() = (application as QuantumApplication).playbackEngine

    private var currentItem: MediaItem? = null
    private var observerJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val uri = intent.getStringExtra(EXTRA_URI)
                if (uri.isNullOrBlank()) {
                    stopSelfSafely()
                    return START_NOT_STICKY
                }
                val title = intent.getStringExtra(EXTRA_TITLE)
                startPlayback(
                    MediaItem(
                        uri = uri,
                        title = title ?: uri.substringAfterLast('/')
                    )
                )
            }

            ACTION_TOGGLE_PLAY -> scope.launch { engine.togglePlayPause() }
            ACTION_PAUSE -> scope.launch { engine.pause() }
            ACTION_RESUME -> scope.launch { engine.resume() }
            ACTION_STOP -> {
                scope.launch { engine.stop() }
                stopSelfSafely()
                return START_NOT_STICKY
            }

            ACTION_SEEK_FORWARD -> scope.launch { engine.seekBy(SEEK_STEP_MS) }
            ACTION_SEEK_BACK -> scope.launch { engine.seekBy(-SEEK_STEP_MS) }
            ACTION_SEEK_TO -> {
                val position = intent.getLongExtra(EXTRA_POSITION, 0L)
                scope.launch { engine.seekTo(position) }
            }

            ACTION_SET_SPEED -> {
                val speed = intent.getFloatExtra(EXTRA_SPEED, 1.0f)
                scope.launch { engine.setPlaybackSpeed(speed) }
            }

            else -> {
                // No action: keep an already running session alive.
                if (currentItem == null) stopSelfSafely()
            }
        }
        return START_STICKY
    }

    private fun startPlayback(item: MediaItem) {
        currentItem = item
        promoteToForeground(notifications.buildPlaybackNotification(item, false, 0L, -1L))
        observeEngine()
        scope.launch {
            runCatching { engine.play(item) }
                .onFailure { error ->
                    // Never let a playback failure take the service down.
                    android.util.Log.e(TAG, "Playback failed: ${error.message}", error)
                }
        }
    }

    private fun observeEngine() {
        if (observerJob?.isActive == true) return
        observerJob = scope.launch {
            engine.stateFlow.collectLatest { state ->
                val item = currentItem ?: return@collectLatest
                when (state) {
                    PlaybackState.Error, PlaybackState.Stopped, PlaybackState.Idle -> {
                        stopForegroundCompat()
                        notifications.hide()
                    }

                    else -> notifications.show(
                        notifications.buildPlaybackNotification(
                            mediaItem = item,
                            isPlaying = state == PlaybackState.Playing,
                            positionMs = engine.currentPosition,
                            durationMs = engine.duration
                        )
                    )
                }
            }
        }
    }

    private fun promoteToForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NotificationController.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NotificationController.NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun stopSelfSafely() {
        stopForegroundCompat()
        notifications.hide()
        stopSelf()
    }

    override fun onDestroy() {
        observerJob?.cancel()
        observerJob = null
        scope.cancel()
        stopForegroundCompat()
        notifications.hide()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "QuantumBgService"
        private const val SEEK_STEP_MS = 10_000L

        const val ACTION_START = "com.quantum.player.PLAY"
        const val ACTION_PAUSE = "com.quantum.player.PAUSE"
        const val ACTION_RESUME = "com.quantum.player.RESUME"
        const val ACTION_TOGGLE_PLAY = "com.quantum.player.TOGGLE_PLAY"
        const val ACTION_STOP = "com.quantum.player.STOP"
        const val ACTION_SEEK = "com.quantum.player.SEEK"
        const val ACTION_SEEK_TO = "com.quantum.player.SEEK_TO"
        const val ACTION_SEEK_FORWARD = "com.quantum.player.SEEK_FORWARD"
        const val ACTION_SEEK_BACK = "com.quantum.player.SEEK_BACK"
        const val ACTION_SET_SPEED = "com.quantum.player.SPEED"

        const val EXTRA_URI = "uri"
        const val EXTRA_TITLE = "title"
        const val EXTRA_POSITION = "position"
        const val EXTRA_SPEED = "speed"

        /** Start background playback of [uri]. */
        fun start(context: Context, uri: String, title: String?) {
            val intent = Intent(context, QuantumBackgroundService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_URI, uri)
                .putExtra(EXTRA_TITLE, title)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Send a transport command to a running service. */
        fun send(context: Context, action: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, QuantumBackgroundService::class.java).setAction(action)
            )
        }

        /** True when the device is in battery saver / doze and playback may be limited. */
        fun isPowerSaveMode(context: Context): Boolean {
            val powerManager = ContextCompat.getSystemService(context, PowerManager::class.java)
            return powerManager?.isPowerSaveMode ?: false
        }
    }
}
