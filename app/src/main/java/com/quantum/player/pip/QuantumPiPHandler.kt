package com.quantum.player.pip

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import com.quantum.player.core.PlaybackEngine
import com.quantum.player.core.PlaybackState
import com.quantum.player.model.MediaItem

/**
 * Picture-in-Picture handler for Quantum player.
 * Manages PiP mode, aspect ratio and state restoration.
 *
 * The previous version referenced APIs that do not exist
 * (`activity.window.setCallbackProxy`, `ComponentActivityCallback`,
 * `Activity.exitPictureInPictureMode`, `setPictureInPictureRotation`,
 * `PictureInPictureParams.Builder.setMediaDescription`,
 * `androidx.lifecycle.OnGoingNotification`), took `activity`/`engine` as plain
 * constructor parameters and then used them as properties, and defined an
 * extension `setAspectRatio` that called itself recursively.
 *
 * PiP requires API 26; every entry point here is a no-op below that.
 */
class QuantumPiPHandler(
    private val activity: Activity,
    private val engine: PlaybackEngine
) {

    private var currentMediaItem: MediaItem? = null

    /** True while the activity is in picture-in-picture mode. */
    var isInPiPMode: Boolean = false
        private set

    /** Last video dimensions, used to keep the PiP window correctly proportioned. */
    private var lastWidth: Int = 0
    private var lastHeight: Int = 0

    /** Whether this device supports PiP at all. */
    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    /**
     * Enter PiP mode with the current media item.
     * Returns false when PiP is unavailable or playback is not active.
     */
    fun enterPiP(mediaItem: MediaItem?): Boolean {
        if (!isSupported) return false
        // Entering PiP with nothing playing just shows an empty window.
        if (!engine.isPlaying && !engine.isBuffering) return false
        currentMediaItem = mediaItem
        return enterPiPInternal()
    }

    /** Enter PiP for whatever is currently playing. */
    fun enterPiPForCurrentPlayback(): Boolean {
        if (!isSupported) return false
        return enterPiPInternal()
    }

    private fun enterPiPInternal(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return runCatching {
            activity.enterPictureInPictureMode(buildParams())
        }.getOrDefault(false)
    }

    private fun buildParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(aspectRatioFor(lastWidth, lastHeight))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Let the system drop into PiP automatically when the user leaves.
            builder.setAutoEnterEnabled(true)
            builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    /** Keep the PiP window proportioned when the video size becomes known. */
    fun updateAspectRatio(videoWidth: Int, videoHeight: Int) {
        if (videoWidth <= 0 || videoHeight <= 0) return
        if (videoWidth == lastWidth && videoHeight == lastHeight) return
        lastWidth = videoWidth
        lastHeight = videoHeight
        if (!isSupported || !isInPiPMode) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching { activity.setPictureInPictureParams(buildParams()) }
    }

    /** Called from `Activity.onPictureInPictureModeChanged`. */
    fun onPictureInPictureModeChanged(inPiP: Boolean, newConfig: Configuration?) {
        isInPiPMode = inPiP
        if (!inPiP) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching { activity.setPictureInPictureParams(buildParams()) }
    }

    /**
     * Handle back press in PiP mode.
     * @return true when the press was consumed
     */
    fun handleBackPress(onExitPiP: () -> Unit = {}, onBackToApp: () -> Unit = {}): Boolean {
        if (!isInPiPMode) return false
        onExitPiP()
        return true
    }

    /** Save PiP state for restoration across configuration changes. */
    fun savePiPState(outBundle: Bundle) {
        outBundle.putBoolean(KEY_PIP_ACTIVE, isInPiPMode)
        outBundle.putString(KEY_CURRENT_URI, currentMediaItem?.uri.orEmpty())
        outBundle.putInt(KEY_WIDTH, lastWidth)
        outBundle.putInt(KEY_HEIGHT, lastHeight)
    }

    /** Restore PiP state. */
    fun restorePiPState(savedBundle: Bundle?): MediaItem? {
        if (savedBundle == null) return null
        lastWidth = savedBundle.getInt(KEY_WIDTH, 0)
        lastHeight = savedBundle.getInt(KEY_HEIGHT, 0)
        isInPiPMode = savedBundle.getBoolean(KEY_PIP_ACTIVE, false)
        val uri = savedBundle.getString(KEY_CURRENT_URI).orEmpty()
        if (uri.isBlank()) return null
        val item = MediaItem(uri = uri, title = uri.substringAfterLast('/'))
        currentMediaItem = item
        return item
    }

    /** Whether playback should keep running while in PiP. */
    fun shouldKeepPlaying(state: PlaybackState): Boolean =
        state == PlaybackState.Playing || state == PlaybackState.Buffering

    companion object {
        private const val KEY_PIP_ACTIVE = "pip_active"
        private const val KEY_CURRENT_URI = "current_uri"
        private const val KEY_WIDTH = "pip_width"
        private const val KEY_HEIGHT = "pip_height"

        /** Default PiP window proportion when the video size is unknown. */
        private const val DEFAULT_NUMERATOR = 16
        private const val DEFAULT_DENOMINATOR = 9

        /**
         * Build a valid PiP aspect ratio. The platform rejects ratios outside
         * roughly 1:2.39..2.39:1, so extreme video sizes are clamped.
         */
        fun aspectRatioFor(width: Int, height: Int): Rational {
            if (width <= 0 || height <= 0) return Rational(DEFAULT_NUMERATOR, DEFAULT_DENOMINATOR)
            val clampedWidth = width.coerceIn(1, 4096)
            val clampedHeight = height.coerceIn(1, 4096)
            val ratio = clampedWidth.toFloat() / clampedHeight.toFloat()
            val safe = ratio.coerceIn(0.42f, 2.39f)
            return Rational((safe * 1000).toInt(), 1000)
        }
    }
}
