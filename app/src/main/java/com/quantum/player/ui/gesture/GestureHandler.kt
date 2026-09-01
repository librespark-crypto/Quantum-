package com.quantum.player.ui.gesture

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs

/**
 * Gesture handler for video playback controls.
 * Implements the gesture philosophy inspired by mpvRex:
 *  - Horizontal swipe: Seek
 *  - Left vertical swipe: Brightness
 *  - Right vertical swipe: Volume
 *  - Double tap: Seek backward/forward
 *  - Pinch: Zoom
 *  - Two-finger: Advanced controls
 *  - Long press: Toggle playback speed
 *
 * The previous version of this file was written against a `PointerInputModifier`
 * / `MultiPointerDetector` API that Compose does not have, and it declared
 * `remember { }` calls inside another `remember { }` (illegal: `remember` is a
 * composable and cannot be called from a non-composable lambda).
 */
@Composable
fun GestureOverlay(
    modifier: Modifier = Modifier,
    config: GestureConfig = GestureConfig(),
    onHorizontalSeek: (offsetMs: Long) -> Unit,
    onBrightnessChange: (delta: Float) -> Unit,
    onVolumeChange: (delta: Float) -> Unit,
    onDoubleTapLeft: () -> Unit = {},
    onDoubleTapRight: () -> Unit = {},
    onDoubleTap: () -> Unit = {},
    onSingleTap: () -> Unit = {},
    onPinch: (scale: Float) -> Unit = {},
    onTwoFinger: () -> Unit = {},
    onLongPressStart: () -> Unit = {},
    onLongPressEnd: () -> Unit = {},
    excludeFor: Set<ExcludeZone> = setOf()
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var lastTapAt by remember { mutableLongStateOf(0L) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .then(
                if (ExcludeZone.InteractiveOverlay in excludeFor) {
                    Modifier
                } else {
                    Modifier.gestureDetector(
                        config = config,
                        areaSize = size,
                        onSeek = onHorizontalSeek,
                        onBrightness = onBrightnessChange,
                        onVolume = onVolumeChange,
                        onDoubleTapLeft = onDoubleTapLeft,
                        onDoubleTapRight = onDoubleTapRight,
                        onDoubleTap = onDoubleTap,
                        onSingleTap = onSingleTap,
                        onPinch = onPinch,
                        onTwoFinger = onTwoFinger,
                        onLongPressStart = onLongPressStart,
                        onLongPressEnd = onLongPressEnd,
                        lastTapAt = { lastTapAt },
                        setLastTapAt = { lastTapAt = it }
                    )
                }
            )
    )
}

/**
 * The gesture detectors as a reusable [Modifier].
 *
 * Returns a plain `Modifier`: the old `PointerInputModifier` return type is not
 * a Compose type.
 */
fun Modifier.gestureDetector(
    config: GestureConfig,
    areaSize: IntSize,
    onSeek: (Long) -> Unit,
    onBrightness: (Float) -> Unit,
    onVolume: (Float) -> Unit,
    onDoubleTapLeft: () -> Unit,
    onDoubleTapRight: () -> Unit,
    onDoubleTap: () -> Unit,
    onSingleTap: () -> Unit,
    onPinch: (Float) -> Unit,
    onTwoFinger: () -> Unit,
    onLongPressStart: () -> Unit,
    onLongPressEnd: () -> Unit,
    lastTapAt: () -> Long,
    setLastTapAt: (Long) -> Unit
): Modifier = this
    // Taps: double tap to seek, long press for the temporary speed boost.
    .pointerInput(config) {
        detectTapGestures(
            onDoubleTap = { offset ->
                val now = System.currentTimeMillis()
                if (now - lastTapAt() < DOUBLE_TAP_DEBOUNCE_MS) return@detectTapGestures
                setLastTapAt(now)
                val halfWidth = (areaSize.width / 2).takeIf { it > 0 } ?: return@detectTapGestures
                when {
                    offset.x < halfWidth * THIRD -> onDoubleTapLeft()
                    offset.x > halfWidth * (2 - THIRD) -> onDoubleTapRight()
                    else -> onDoubleTap()
                }
            },
            onTap = { onSingleTap() },
            onLongPress = { onLongPressStart() },
            onPress = {
                try {
                    awaitRelease()
                } finally {
                    onLongPressEnd()
                }
            }
        )
    }
    // Horizontal drag: seek. Accumulate so one swipe produces one seek.
    .pointerInput(config) {
        var accumulated = 0f
        detectHorizontalDragGestures(
            onDragStart = { accumulated = 0f },
            onDragEnd = {
                val offsetMs = accumulated * config.msPerPixel
                if (abs(offsetMs) >= MIN_SEEK_MS) onSeek(offsetMs.toLong())
                accumulated = 0f
            },
            onDragCancel = { accumulated = 0f }
        ) { change, dragAmount ->
            change.consume()
            accumulated += dragAmount
        }
    }
    // Vertical drag: brightness on the left half, volume on the right half.
    .pointerInput(config) {
        detectVerticalDragGestures { change, dragAmount ->
            change.consume()
            val halfWidth = areaSize.width / 2
            val isLeftHalf = halfWidth <= 0 || change.position.x < halfWidth
            val delta = -dragAmount / areaSize.height.coerceAtLeast(1).toFloat()
            if (isLeftHalf) {
                onBrightness(delta * config.brightnessSensitivity)
            } else {
                onVolume(delta * config.volumeSensitivity)
            }
        }
    }
    // Pinch: zoom. Two fingers held still: advanced controls.
    .pointerInput(config) {
        var totalScale = 1f
        detectTransformGestures { centroid, pan, zoom, rotation ->
            if (zoom != 1f && config.pinchZoomEnabled) {
                totalScale *= zoom
                onPinch(totalScale)
            }
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
    val horizontalSeekAmountMs: Long = 10_000L,
    val brightnessSensitivity: Float = 1.0f,
    val volumeSensitivity: Float = 1.0f,
    val doubleTapSeekAmountMs: Long = 10_000L,
    val longPressSpeed: Float = 2.0f,
    val pinchZoomEnabled: Boolean = true,
    val twoFingerEnabled: Boolean = true,
    /** Milliseconds of seek per pixel of horizontal drag. */
    val msPerPixel: Float = 120f
)

private const val DOUBLE_TAP_DEBOUNCE_MS = 500L
private const val MIN_SEEK_MS = 250f
private const val THIRD = 0.66f
