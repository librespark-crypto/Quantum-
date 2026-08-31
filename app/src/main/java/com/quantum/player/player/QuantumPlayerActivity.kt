package com.quantum.player.player

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.quantum.player.browser.MediaBrowser
import com.quantum.player.core.PlaybackEngine
import com.quantum.player.pip.QuantumPiPHandler
import com.quantum.player.model.MediaItem
import com.quantum.player.service.QuantumBackgroundService
import com.quantum.player.ui.screen.PlayerScreen
import com.quantum.player.ui.theme.QuantumTheme

/**
 * Main activity: media browser plus fullscreen playback.
 *
 * This activity is declared in the manifest as the launcher but did not exist,
 * so the app had nothing to start.
 *
 * Playback lives in the application-scoped [PlaybackEngine]; this activity only
 * attaches a video output while it is in the foreground, and detaches it in
 * [onStop] so the surface is never leaked when the UI goes away.
 */
class QuantumPlayerActivity : ComponentActivity() {

    private val engine: PlaybackEngine
        get() = (application as QuantumApplication).playbackEngine

    private var pipHandler: QuantumPiPHandler? = null

    private var currentItem by mutableStateOf<MediaItem?>(null)

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pipHandler = QuantumPiPHandler(this, engine)
        pipHandler?.restorePiPState(savedInstanceState)?.let { currentItem = it }
        handleIncomingIntent(intent)
        requestNotificationPermissionIfNeeded()

        setContent {
            // The player surface is always dark; the browser follows the system theme.
            QuantumTheme(forceDark = currentItem != null) {
                val item = currentItem
                if (item == null) {
                    MediaBrowser(
                        onSelect = { selected -> currentItem = selected },
                        onOpenUrl = { url -> currentItem = mediaItemFromUri(url) }
                    )
                } else {
                    PlayerScreen(
                        engine = engine,
                        mediaItem = item,
                        onClose = { currentItem = null },
                        onEnterPiP = { pipHandler?.enterPiP(item) },
                        showControlsInitially = pipHandler?.isInPiPMode != true
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        @Suppress("DEPRECATION")
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                val uri = intent.data?.toString() ?: intent.getStringExtra(
                    QuantumBackgroundService.EXTRA_URI
                )
                if (!uri.isNullOrBlank()) {
                    currentItem = mediaItemFromUri(
                        uri,
                        intent.getStringExtra(QuantumBackgroundService.EXTRA_TITLE)
                    )
                }
            }

            QuantumBackgroundService.ACTION_START -> {
                val uri = intent.getStringExtra(QuantumBackgroundService.EXTRA_URI)
                if (!uri.isNullOrBlank()) {
                    currentItem = mediaItemFromUri(
                        uri,
                        intent.getStringExtra(QuantumBackgroundService.EXTRA_TITLE)
                    )
                }
            }
        }
    }

    private fun mediaItemFromUri(uri: String, title: String? = null): MediaItem = MediaItem(
        id = uri,
        uri = uri,
        title = title ?: displayNameFor(uri)
    )

    private fun displayNameFor(uri: String): String =
        runCatching { Uri.parse(uri).lastPathSegment?.substringAfterLast('/') }.getOrNull()
            ?: uri.substringAfterLast('/')

    // ---- Picture in picture ----

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        currentItem?.let { pipHandler?.enterPiP(it) }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        @Suppress("DEPRECATION")
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pipHandler?.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pipHandler?.savePiPState(outState)
        outState.putString(STATE_CURRENT_URI, currentItem?.uri.orEmpty())
        outState.putString(STATE_CURRENT_TITLE, currentItem?.title.orEmpty())
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onStop() {
        // Detach the surface unless we are handing off to picture-in-picture.
        if (pipHandler?.isInPiPMode != true) {
            engine.setVideoTextureView(null)
        }
        super.onStop()
    }

    override fun onDestroy() {
        // The engine is application scoped: it survives this activity so the
        // background service can keep playing. Only the surface is released.
        engine.setVideoTextureView(null)
        pipHandler = null
        super.onDestroy()
    }

    private companion object {
        const val STATE_CURRENT_URI = "state_current_uri"
        const val STATE_CURRENT_TITLE = "state_current_title"
    }
}
