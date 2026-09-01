package com.quantum.player.ui.gesture

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Touch gesture engine for the player surface (mpvRx / MX Player model).
 *
 *  - Left third, vertical drag   : screen brightness (0%..100%) with OSD
 *  - Right third, vertical drag  : media volume, incl. software boost to 200%
 *  - Horizontal drag             : timeline scrub with target time + delta OSD
 *  - Double tap left third       : seek back [doubleTapSeekMs]
 *  - Double tap right third      : seek forward [doubleTapSeekMs]
 *  - Double tap center           : play / pause
 *  - Single tap                  : toggle controls
 *  - Pinch                       : 1.0x..3.0x zoom with pan support
 *  - Long press                  : temporary 2x speed (hold)
 *
 * The detector reports *progress* callbacks (0..1 fraction for brightness/volume,
 * absolute target ms + delta for seeks) so the screen layer can render the OSD
 * and apply the change in one place.
 */
@Composable
fun GestureOverlay(
    modifier: Modifier = Modifier,
    config: GestureConfig = GestureConfig(),
    onScrub: (targetMs: Long, deltaMs: Long, finished: Boolean) -> Unit,
    onBrightness: (fraction: Float) -> Unit,
    onVolume: (fraction: Float) -> Unit,
    onDoubleTapSeekBack: () -> Unit = {},
    onDoubleTapSeekForward: () -> Unit = {},
    onDoubleTapCenter: () -> Unit = {},
    onSingleTap: () -> Unit = {},
    onZoomPan: (scale: Float, panXPx: Float, panYPx: Float) -> Unit = { _, _, _ -> },
    onResetZoom: () -> Unit = {},
    onLongPressStart: () -> Unit = {},
    onLongPressEnd: () -> Unit = {},
    onGestureEnd: () -> Unit = {},
    excludeFor: Set<ExcludeZone> = setOf()
) {
    var size by remember { mutableStateOf(IntSize.Zero) }

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
                        onScrub = onScrub,
                        onBrightness = onBrightness,
                        onVolume = onVolume,
                        onDoubleTapSeekBack = onDoubleTapSeekBack,
                        onDoubleTapSeekForward = onDoubleTapSeekForward,
                        onDoubleTapCenter = onDoubleTapCenter,
                        onSingleTap = onSingleTap,
                        onZoomPan = onZoomPan,
                        onResetZoom = onResetZoom,
                        onLongPressStart = onLongPressStart,
                        onLongPressEnd = onLongPressEnd,
                        onGestureEnd = onGestureEnd
                    )
                }
            )
    )
}

/** The gesture detectors as a reusable [Modifier]. */
fun Modifier.gestureDetector(
    config: GestureConfig,
    areaSize: IntSize,
    onScrub: (targetMs: Long, deltaMs: Long, finished: Boolean) -> Unit,
    onBrightness: (Float) -> Unit,
    onVolume: (Float) -> Unit,
    onDoubleTapSeekBack: () -> Unit,
    onDoubleTapSeekForward: () -> Unit,
    onDoubleTapCenter: () -> Unit,
    onSingleTap: () -> Unit,
    onZoomPan: (scale: Float, panXPx: Float, panYPx: Float) -> Unit,
    onResetZoom: () -> Unit,
    onLongPressStart: () -> Unit,
    onLongPressEnd: () -> Unit,
    onGestureEnd: () -> Unit
): Modifier = this
    // Taps: single toggles controls; double-tap zone depends on screen third;
    // long press holds a temporary speed boost.
    .pointerInput(config) {
        detectTapGestures(
            onDoubleTap = { offset ->
                val width = areaSize.width.takeIf { it > 0 } ?: return@detectTapGestures
                val third = width / 3f
                when {
                    offset.x < third -> onDoubleTapSeekBack()
                    offset.x > width - third -> onDoubleTapSeekForward()
                    else -> onDoubleTapCenter()
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
    // Drags: horizontal = scrub; vertical = brightness (left third) / volume
    // (right two thirds). Tracked continuously so each mode can render a live
    // OSD, not just an end-of-gesture delta.
    .pointerInput(config, areaSize) {
        detectPlayerDrags(
            config = config,
            areaSize = areaSize,
            onScrub = onScrub,
            onBrightness = onBrightness,
            onVolume = onVolume,
            onEnd = onGestureEnd
        )
    }
    // Pinch to zoom (1x..3x) with pan; tap-zoom-reset handled by double-tap on
    // already-zoomed video in the screen layer.
    .pointerInput(config) {
        var scale = 1f
        var panX = 0f
        var panY = 0f
        detectTransformGestures { _, pan, zoom, _ ->
            if (zoom != 1f) {
                scale = (scale * zoom).coerceIn(1f, config.maxZoom)
                if (scale <= 1.01f) {
                    scale = 1f
                    panX = 0f
                    panY = 0f
                }
            }
            if (scale > 1f) {
                panX += pan.x
                panY += pan.y
            } else {
                panX = 0f
                panY = 0f
            }
            onZoomPan(scale, panX, panY)
        }
    }

/** Which drag behaviour a gesture resolved to. */
private enum class DragMode { NONE, SEEK, BRIGHTNESS, VOLUME }

/**
 * Custom drag detector: unlike the stock drag gestures it decides the axis AND
 * the brightness/volume zone from the down position, and keeps firing while the
 * finger is down so the OSD updates live. The mode is locked on the first
 * movement past slop so a gesture cannot flip between seek and brightness.
 */
private suspend fun PointerInputScope.detectPlayerDrags(
    config: GestureConfig,
    areaSize: IntSize,
    onScrub: (targetMs: Long, deltaMs: Long, finished: Boolean) -> Unit,
    onBrightness: (fraction: Float) -> Unit,
    onVolume: (fraction: Float) -> Unit,
    onEnd: () -> Unit
) {
    val height = areaSize.height.coerceAtLeast(1)
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val downX = down.position.x
        var mode = DragMode.NONE
        var lastX = downX
        var lastY = down.position.y
        var startPositionMs = 0L
        var startFraction = 0.5f
        var accumulatedDx = 0f
        var dragStarted = false

        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull() ?: break
            if (!change.pressed) {
                if (mode == DragMode.SEEK) {
                    val deltaMs = (accumulatedDx * config.msPerPixel).roundToLong()
                    onScrub(startPositionMs + deltaMs, deltaMs, true)
                }
                if (dragStarted) onEnd()
                break
            }

            val dx = change.position.x - lastX
            val dy = change.position.y - lastY

            if (mode == DragMode.NONE) {
                val totalX = abs(change.position.x - downX)
                val totalY = abs(change.position.y - down.position.y)
                if (totalX > config.touchSlop || totalY > config.touchSlop) {
                    val isLeftThird = downX < areaSize.width.coerceAtLeast(1) / 3f
                    mode = when {
                        totalX > totalY -> DragMode.SEEK
                        isLeftThird -> DragMode.BRIGHTNESS
                        else -> DragMode.VOLUME
                    }
                    startPositionMs = config.currentPositionMs()
                    startFraction = if (mode == DragMode.BRIGHTNESS) {
                        config.currentBrightness()
                    } else {
                        config.currentVolume()
                    }
                    dragStarted = true
                }
            }

            if (mode != DragMode.NONE) {
                change.consume()
                when (mode) {
                    DragMode.SEEK -> {
                        accumulatedDx += dx
                        val deltaMs = (accumulatedDx * config.msPerPixel).roundToLong()
                        onScrub(startPositionMs + deltaMs, deltaMs, false)
                    }

                    DragMode.BRIGHTNESS -> {
                        startFraction = (startFraction + (-dy / height) * config.brightnessSensitivity)
                            .coerceIn(0.01f, 1f)
                        onBrightness(startFraction)
                    }

                    DragMode.VOLUME -> {
                        startFraction = (startFraction + (-dy / height) * config.volumeSensitivity)
                            .coerceIn(0f, config.maxVolumeFraction)
                        onVolume(startFraction)
                    }

                    DragMode.NONE -> Unit
                }
            }

            lastX = change.position.x
            lastY = change.position.y
        }
    }
}

/** Exclusion zones to prevent gestures from interfering with UI elements. */
enum class ExcludeZone {
    Subtitle,
    Seekbar,
    SystemGesture,
    Button,
    InteractiveOverlay
}

/**
 * Gesture configuration. The live-value lambdas ([currentPositionMs],
 * [currentBrightness], [currentVolume]) let the detector seed a drag from the
 * real player/window state instead of guessing 50%.
 */
data class GestureConfig(
    val doubleTapSeekMs: Long = 10_000L,
    val brightnessSensitivity: Float = 1.0f,
    val volumeSensitivity: Float = 1.0f,
    val longPressSpeed: Float = 2.0f,
    val pinchZoomEnabled: Boolean = true,
    val twoFingerEnabled: Boolean = true,
    /** Milliseconds of seek per pixel of horizontal drag. */
    val msPerPixel: Float = 120f,
    /** Maximum zoom factor for pinch gestures. */
    val maxZoom: Float = 3.0f,
    /** Volume fraction ceiling: 2.0 == 200% (software boost). */
    val maxVolumeFraction: Float = 2.0f,
    /** Touch slop in px before a drag is recognized. */
    val touchSlop: Float = 24f,
    val currentPositionMs: () -> Long = { 0L },
    val currentBrightness: () -> Float = { 0.5f },
    val currentVolume: () -> Float = { 0.5f }
)
