package com.quantum.player.ui.gesture

import androidx.compose.foundation.gesture.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.input.pointer.PointerInputModifier
import androidx.compose.ui.input.pointerPointerInputChange
import androidx.compose.ui.unit.Dp
import com.quantum.player.core.PlaybackEngine
import com.quantum.player.model.MediaItem

/**
 * Gesture handler for video playback controls.
 * Implements the gesture philosophy inspired by mpvRex:
 * - Horizontal swipe: Seek
 * - Left vertical swipe: Brightness
 * - Right vertical swipe: Volume
 * - Double tap: Seek backward/forward
 * - Pinch: Zoom
 * - Two-finger: Advanced controls
 * - Long press: Toggle playback speed
 */
@Composable
fun GestureOverlay(
    modifier: Modifier = Modifier,
    videoWidth: Int = 0,
    videoHeight: Int = 0,
    onHorizontalSeek: (offsetMs: Long) -> Unit,
    onBrightnessChange: (delta: Float) -> Unit,
    onVolumeChange: (delta: Float) -> Unit,
    onDoubleTap: () -> Unit,
    onPinch: (scale: Float) -> Unit,
    onTwoFinger: () -> Unit,
    onLongPress: () -> Unit,
    excludeFor: Set<ExcludeZone> = setOf()
) {
    var doubleTapTime by remember { mutableStateOf(0L) }

    PointerInputModifier(
        detection = remember {
            MultiPointerDetector(
                horizontalSwipe = remember { HorizontalSwipeDetectorConfig(
                    sensitivity = 0.5f,
                    onSwipe = { direction ->
                        when (direction) {
                            HorizontalSwipeDetectorConfig.SwipeDirection.Left -> onHorizontalSeek(
                                calculateSeekOffset(direction)
                            )
                            HorizontalSwipeDetectorConfig.SwipeDirection.Right -> onHorizontalSeek(
                                calculateSeekOffset(direction)
                            )
                        }
                    }
                )
                // Vertical swipes for brightness/volume
                verticalSwipe = remember { VerticalSwipeDetectorConfig(
                    sensitivity = 0.5f,
                    onSwipe = { direction ->
                        when (direction) {
                            VerticalSwipeDetectorConfig.SwipeDirection.Up -> onBrightnessChange(
                                calculateBrightnessDelta(direction)
                            )
                            VerticalSwipeDetectorConfig.SwipeDirection.Down -> onVolumeChange(
                                calculateVolumeDelta(direction)
                            )
                        }
                    }
                )
                // Double tap
                doubleTap = remember { DoubleTapDetectorConfig(
                    onDoubleTap = {
                        // Debounce: ignore if within 500ms of previous double tap
                        val now = System.currentTimeMillis()
                        if (now - doubleTapTime > 500) {
                            doubleTapTime = now
                            onDoubleTap()
                        }
                    }
                )
                // Long press
                longPress = remember { LongPressDetectorConfig(
                    onLongPress = {
                        onLongPress()
                    }
                )
            }
        }
    ) {
        // Content - video player
        Box(modifier = modifier.fillMaxSize()) {
            // Empty - gestures are applied to the video area
        }
    }
}

/**
 * Calculate seek offset based on swipe direction.
 * 10% of video duration per swipe, or configurable amount.
 */
private fun calculateSeekOffset(direction: HorizontalSwipeDetectorConfig.SwipeDirection): Long {
    // Return seek amount based on direction
    return when (direction) {
        HorizontalSwipeDetectorConfig.SwipeDirection.Left -> 10000L // 10 seconds backward
        HorizontalSwipeDetectorConfig.SwipeDirection.Right -> 10000L // 10 seconds forward
        else -> 0L
    }
}

/**
 * Calculate brightness delta based on vertical swipe direction.
 * Up = increase brightness, Down = decrease
 */
private fun calculateBrightnessDelta(direction: VerticalSwipeDetectorConfig.SwipeDirection): Float {
    return when (direction) {
        VerticalSwipeDetectorConfig.SwipeDirection.Up -> 0.1f // Increase
        VerticalSwipeDetectorConfig.SwipeDirection.Down -> -0.1f // Decrease
        else -> 0f
    }
}

/**
 * Calculate volume delta based on vertical swipe direction.
 * Up = increase volume, Down = decrease
 */
private fun calculateVolumeDelta(direction: VerticalSwipeDetectorConfig.SwipeDirection): Float {
    return when (direction) {
        VerticalSwipeDetectorConfig.SwipeDirection.Up -> 0.1f // Increase
        VerticalSwipeDetectorConfig.SwipeDirection.Down -> -0.1f // Decrease
        else -> 0f
    }
}

/**
 * Exclusion zones to prevent gestures from interfering with UI elements.
 * Zones: subtitles, seekbar, system buttons, etc.
 */
enum class ExcludeZone {
    Subtitle,
    Seekbar,
    SystemGesture,
    Button,
    InteractiveOverlay
}

/**
 * Gesture configuration for the player.
 */
data class GestureConfig(
    val horizontalSeekAmountMs: Long = 10000L, // 10 seconds
    val brightnessSensitivity: Float = 0.5f,
    val volumeSensitivity: Float = 0.5f,
    val doubleTapSeekAmountMs: Long = 10000L,
    val longPressSpeed: Float = 2.0f,
    val pinchZoomEnabled: Boolean = true,
    val twoFingerEnabled: Boolean = true
)

/**
 * Provides gesture detection modifiers for the player surface.
 * Can be applied to any Compose component to enable video seeking gestures.
 */
fun GestureDetectorModifier(
    config: GestureConfig = GestureConfig(),
    onSeek: (Long) -> Unit,
    onBrightness: (Float) -> Unit,
    onVolume: (Float) -> Unit,
    onDoubleTap: () -> Unit,
    onLongPress: () -> Unit,
    onPinch: ((Float) -> Unit)? = null,
    onTwoFinger: (() -> Unit)? = null,
    excludeZones: Set<ExcludeZone> = setOf()
): PointerInputModifier {
    val detector = remember {
        MultiPointerDetector(
            horizontalSwipe = remember { HorizontalSwipeDetectorConfig(
                sensitivity = config.brightnessSensitivity,
                onSwipe = { direction ->
                    when (direction) {
                        HorizontalSwipeDetectorConfig.SwipeDirection.Left -> onSeek(
                            -config.horizontalSeekAmountMs
                        )
                        HorizontalSwipeDetectorConfig.SwipeDirection.Right -> onSeek(
                            config.horizontalSeekAmountMs
                        )
                    }
                }
            )
            verticalSwipe = remember { VerticalSwipeDetectorConfig(
                sensitivity = config.volumeSensitivity,
                onSwipe = { direction ->
                    when (direction) {
                        VerticalSwipeDetectorConfig.SwipeDirection.Up -> onBrightness(
                            config.brightnessSensitivity
                        )
                        VerticalSwipeDetectorConfig.SwipeDirection.Down -> onVolume(
                            config.volumeSensitivity
                        )
                    }
                }
            )
            doubleTap = remember { DoubleTapDetectorConfig(
                onDoubleTap = {
                    onDoubleTap()
                }
            )
            longPress = remember { LongPressDetectorConfig(
                onLongPress = {
                    onLongPress()
                }
            )
            // Pinch zoom
            pinch = remember { PinchDetectorConfig(
                onPinch = { scale ->
                    onPinch?.invoke(scale)
                }
            )
            // Two-finger gestures
            twoFinger = remember { TwoFingerDetectorConfig(
                onTwoFinger = {
                    onTwoFinger?.invoke()
                }
            )
        }
    }

    return detector
}