package com.quantum.player.pip

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.quantum.player.core.PlaybackEngine
import com.quantum.player.model.MediaItem
import com.quantum.player.player.QuantumApplication
import com.quantum.player.ui.screen.PlayerScreen
import com.quantum.player.ui.theme.QuantumTheme

/**
 * Picture-in-picture playback activity.
 *
 * Declared in the manifest but missing from the source. It plays a single URI
 * handed to it and drops straight into PiP; controls are hidden by default
 * because the PiP window is small.
 *
 * It shares the application-scoped [PlaybackEngine] with the main activity, so
 * there is never a second player.
 */
class QuantumPiPActivity : ComponentActivity() {

    private val engine: PlaybackEngine
        get() = (application as QuantumApplication).playbackEngine

    private var pipHandler: QuantumPiPHandler? = null
    private var mediaItem by mutableStateOf<MediaItem?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pipHandler = QuantumPiPHandler(this, engine)
        mediaItem = pipHandler?.restorePiPState(savedInstanceState) ?: readIntent(intent)

        setContent {
            QuantumTheme(forceDark = true) {
                mediaItem?.let { item ->
                    PlayerScreen(
                        engine = engine,
                        mediaItem = item,
                        onClose = { finish() },
                        onEnterPiP = { pipHandler?.enterPiP(item) },
                        showControlsInitially = false
                    )
                }
            }
        }
    }

    private fun readIntent(intent: Intent?): MediaItem? {
        val uri = intent?.getStringExtra(EXTRA_URI) ?: intent?.data?.toString()
        if (uri.isNullOrBlank()) return null
        return MediaItem(
            id = uri,
            uri = uri,
            title = intent?.getStringExtra(EXTRA_TITLE) ?: uri.substringAfterLast('/')
        )
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        mediaItem?.let { pipHandler?.enterPiP(it) }
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
    }

    override fun onStop() {
        if (pipHandler?.isInPiPMode != true) {
            engine.setVideoTextureView(null)
        }
        super.onStop()
    }

    override fun onDestroy() {
        engine.setVideoTextureView(null)
        pipHandler = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_TITLE = "title"

        /** Launch PiP playback of [uri]. */
        fun start(context: Context, uri: String, title: String? = null) {
            context.startActivity(
                Intent(context, QuantumPiPActivity::class.java)
                    .putExtra(EXTRA_URI, uri)
                    .putExtra(EXTRA_TITLE, title)
            )
        }
    }
}
