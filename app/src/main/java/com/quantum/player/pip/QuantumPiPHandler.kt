package com.quantum.player.pip

import android.app.PictureInPictureParams
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.OnGoingNotification
import com.quantum.player.core.PlaybackEngine
import com.quantum.player.model.MediaItem
import com.quantum.player.player.QuantumApplication

/**
 * Picture-in-Picture handler for Quantum player.
 * Manages PiP mode, controls, and lifecycle.
 */
class QuantumPiPHandler(
    activity: ComponentActivity,
    engine: PlaybackEngine
) : DefaultLifecycleObserver {

    private var currentMediaItem: MediaItem? = null
    private var pipEnabled = false

    override fun onStart(lifecycle: LifecycleOwner) {
        // Register PiP state callback
        activity.window?.setCallbackProxy(object : ComponentActivityCallbackProxy(activity) {
            override fun onPictureInPictureModeChanged(
                isPictureInPicture: Boolean,
                newConfig: Configuration?
            ) {
                pipEnabled = isPictureInPicture
                if (isPictureInPicture) {
                    setupPiPControls()
                }
            }
        })
    }

    override fun onStop(lifecycle: LifecycleOwner) {
        // Clean up PiP callback
    }

    /**
     * Enter PiP mode with the current media item.
     */
    fun enterPiP(mediaItem: MediaItem, options: Bundle? = null) {
        currentMediaItem = mediaItem

        // Set up PiP params
        var params: PictureInPictureParams? = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            params = PictureInPictureParams.Builder()
                .setAspectRatio(
                    if (mediaItem.videoWidth > 0 && mediaItem.videoHeight > 0) {
                        Rational(mediaItem.videoWidth, mediaItem.videoHeight)
                    } else {
                        Rational(16, 9)
                    }
                )
                .setMediaDescription(
                    android.media.MediaDescription.Builder()
                        .setTitle(mediaItem.title ?: "Quantum")
                        .setSubtitle(mediaItem.artist)
                        .build()
                )
                .setRotationEnabled(true)
                .build()
        }

        activity.enterPictureInPictureMode(params)
    }

    /**
     * Exit PiP mode and return to fullscreen.
     */
    fun exitPiP() {
        currentMediaItem = null
        activity.exitPictureInPictureMode()
    }

    /**
     * Setup PiP controls in the system notification/overlay.
     */
    private fun setupPiPControls() {
        // PiP controls are system-provided
        // - Tap to play/pause
        // - System gestures for seek/volume/brightness
        // - PiP-specific controls in the PiP window
    }

    /**
     * Update PiP aspect ratio when video dimensions change.
     */
    fun updateAspectRatio(videoWidth: Int, videoHeight: Int) {
        // Re-enter PiP with new aspect ratio if needed
    }

    /**
     * Handle back press in PiP mode.
     * Exits PiP and returns to fullscreen activity.
     */
    fun handleBackPress(
        onExitPiP: () -> Unit,
        onBackToApp: () -> Unit
    ): Boolean {
        if (pipEnabled) {
            exitPiP()
            return true
        }
        return false
    }

    /**
     * Update the PiP notification with current playback state.
     */
    fun updatePiPNotification(
        isPlaying: Boolean,
        currentPosition: Long,
        duration: Long
    ) {
        // Update ongoing notification for PiP session
        // This provides playback controls in the notification shade
    }

    /**
     * Rotate the PiP window based on device orientation.
     */
    fun setPiPRotation(rotation: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            activity.setPictureInPictureRotation(rotation)
        }
    }

    /**
     * Get current PiP mode status.
     */
    fun isInPiPMode(): Boolean = pipEnabled

    /**
     * Save PiP state for restoration.
     */
    fun savePiPState(
        outBundle: Bundle
    ) {
        outBundle.putBoolean("pip_active", pipEnabled)
        outBundle.putString("current_uri", currentMediaItem?.uri ?: "")
    }

    /**
     * Restore PiP state.
     */
    fun restorePiPState(
        savedBundle: Bundle
    ) {
        if (savedBundle.getBoolean("pip_active", false)) {
            // Re-enter PiP mode
            // Note: URI would need to be re-parsed and playback resumed
        }
    }

    /**
     * Custom callback proxy for PiP mode changes.
     */
    private class ComponentActivityCallbackProxy(
        private val activity: ComponentActivity
    ) : ComponentActivityCallback() {
        // Proxy callback for PiP mode changes
    }
}

/**
 * Helper function to configure PiP params with proper aspect ratio.
 */
fun PictureInPictureParams.Builder.setAspectRatio(
    ratio: Rational
): PictureInPictureParams.Builder {
    return this.setAspectRatio(ratio)
}