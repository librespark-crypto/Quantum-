package com.quantum.player.ui.screen

import android.content.Context
import android.media.AudioManager
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.quantum.player.R
import com.quantum.player.core.AspectRatioMode
import com.quantum.player.core.AudioTrackInfo
import com.quantum.player.core.PlaybackEngine
import com.quantum.player.core.PlaybackState
import com.quantum.player.core.SubtitleTrackInfo
import com.quantum.player.error.PlaybackError
import com.quantum.player.model.MediaItem
import com.quantum.player.subtitles.SubtitleOverlay
import com.quantum.player.ui.component.FullscreenControlOverlay
import com.quantum.player.ui.component.PlaybackErrorPanel
import com.quantum.player.ui.component.formatSpeed
import com.quantum.player.ui.gesture.ExcludeZone
import com.quantum.player.ui.gesture.GestureConfig
import com.quantum.player.ui.gesture.GestureOverlay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Main player screen with video playback and controls.
 * Features gesture-based controls and Material 3 design.
 *
 * Video is rendered into a [TextureView] (rather than a SurfaceView) because
 * [PlaybackEngine.captureScreenshot] reads frames back with
 * `TextureView.getBitmap()`, which a SurfaceView cannot provide.
 */
@Composable
fun PlayerScreen(
    engine: PlaybackEngine,
    mediaItem: MediaItem,
    onClose: () -> Unit,
    onEnterPiP: () -> Unit,
    showControlsInitially: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val view = LocalView.current

    val state by engine.stateFlow.collectAsState(initial = PlaybackState.Idle)
    val position by engine.positionFlow.collectAsState(initial = 0L)
    val cues by engine.cuesFlow.collectAsState(initial = emptyList())
    val playbackError by engine.errorFlow.collectAsState(initial = null)

    var showControls by remember { mutableStateOf(showControlsInitially) }
    var controlsLocked by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(engine.playbackSpeed) }
    var aspectRatio by remember { mutableStateOf(AspectRatioMode.Auto) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var longPressSpeedActive by remember { mutableStateOf(false) }
    var audioTracks by remember { mutableStateOf<List<AudioTrackInfo>>(emptyList()) }
    var subtitleTracks by remember { mutableStateOf<List<SubtitleTrackInfo>>(emptyList()) }
    var selectedAudioTrack by remember { mutableIntStateOf(-1) }
    var selectedSubtitleTrack by remember { mutableIntStateOf(-1) }
    var videoWidthPx by remember { mutableIntStateOf(0) }
    var videoHeightPx by remember { mutableIntStateOf(0) }
    var toast by remember { mutableStateOf<String?>(null) }

    // Start playback for the requested item.
    LaunchedEffect(mediaItem.id) {
        runCatching { engine.play(mediaItem) }
    }

    // Track lists only become meaningful once the source is parsed.
    LaunchedEffect(state) {
        if (state == PlaybackState.Playing || state == PlaybackState.Buffering ||
            state == PlaybackState.Paused
        ) {
            audioTracks = engine.availableAudioTracks
            subtitleTracks = engine.availableSubtitleTracks
            selectedAudioTrack = engine.currentAudioTrack
            selectedSubtitleTrack = engine.currentSubtitleTrack
        }
    }

    // Keep the reported video geometry in sync with what the decoder produced.
    LaunchedEffect(state, position) {
        if (engine.videoWidth != videoWidthPx || engine.videoHeight != videoHeightPx) {
            videoWidthPx = engine.videoWidth
            videoHeightPx = engine.videoHeight
        }
    }

    // Detach the output surface when this screen leaves the composition so the
    // (application scoped) player never keeps a dead surface alive.
    DisposableEffect(engine) {
        onDispose { engine.setVideoTextureView(null) }
    }

    // Back closes the controls first, then the player.
    BackHandler(enabled = showControls && !controlsLocked) { showControls = false }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        val containerWidth = constraints.maxWidth.toFloat()
        val containerHeight = constraints.maxHeight.toFloat()

        // ---- Video output ----
        AndroidView(
            factory = { textureContext ->
                TextureView(textureContext).also { engine.setVideoTextureView(it) }
            },
            update = { textureView ->
                engine.setVideoTextureView(textureView)
                textureView.scaleX = zoom
                textureView.scaleY = zoom
            },
            modifier = Modifier
                .fillMaxSize()
                .aspectFit(aspectRatio, videoWidthPx, videoHeightPx, containerWidth, containerHeight)
        )

        // ---- Subtitles ----
        SubtitleOverlay(
            cues = cues,
            bottomPaddingDp = if (showControls) 112f else 56f,
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        // ---- Gestures ----
        if (!controlsLocked) {
            GestureOverlay(
                config = GestureConfig(),
                excludeFor = if (showControls) setOf(ExcludeZone.Seekbar) else emptySet(),
                onHorizontalSeek = { offsetMs -> scope.launch { engine.seekBy(offsetMs) } },
                onBrightnessChange = { delta -> adjustBrightness(view.context, delta) },
                onVolumeChange = { delta -> adjustVolume(view.context, delta) },
                onDoubleTapLeft = { scope.launch { engine.seekBy(-10_000L) } },
                onDoubleTapRight = { scope.launch { engine.seekBy(10_000L) } },
                onDoubleTap = { showControls = !showControls },
                onSingleTap = { showControls = !showControls },
                onPinch = { scale -> zoom = scale.coerceIn(0.5f, 4f) },
                onLongPressStart = {
                    longPressSpeedActive = true
                    scope.launch { engine.setPlaybackSpeed(2.0f) }
                },
                onLongPressEnd = {
                    if (longPressSpeedActive) {
                        longPressSpeedActive = false
                        scope.launch { engine.setPlaybackSpeed(speed) }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // ---- Controls ----
        if (showControls) {
            FullscreenControlOverlay(
                title = mediaItem.displayName,
                state = state,
                positionMs = position,
                durationMs = engine.duration,
                speed = if (longPressSpeedActive) 2.0f else speed,
                audioTracks = audioTracks,
                selectedAudioTrack = selectedAudioTrack,
                subtitleTracks = subtitleTracks,
                selectedSubtitleTrack = selectedSubtitleTrack,
                aspectRatio = aspectRatio,
                controlsLocked = controlsLocked,
                isBuffering = engine.isBuffering,
                onClose = onClose,
                onPlayPause = { scope.launch { engine.togglePlayPause() } },
                onSeekTo = { target -> scope.launch { engine.seekTo(target) } },
                onSeekBy = { delta -> scope.launch { engine.seekBy(delta) } },
                onSpeedSelected = { newSpeed ->
                    speed = newSpeed
                    scope.launch { engine.setPlaybackSpeed(newSpeed) }
                },
                onAudioTrackSelected = { index ->
                    selectedAudioTrack = index
                    scope.launch { engine.setAudioTrack(index) }
                },
                onSubtitleTrackSelected = { index ->
                    selectedSubtitleTrack = index
                    scope.launch { engine.setSubtitleTrack(index) }
                },
                onAspectRatioSelected = { aspectRatio = it },
                onToggleLock = { controlsLocked = !controlsLocked },
                onScreenshot = {
                    scope.launch {
                        toast = runCatching { engine.captureScreenshot() }
                            .map { context.getString(R.string.screenshot_saved) }
                            .getOrElse { context.getString(R.string.screenshot_failed) }
                    }
                },
                onEnterPiP = onEnterPiP
            )
        }

        // ---- Errors ----
        playbackError?.let { error ->
            PlaybackErrorPanel(
                title = PlaybackError.getErrorTitle(error),
                userMessage = error.userMessage,
                solution = buildString {
                    append(error.possibleSolution)
                    error.detail?.let { append("\n\n").append(it) }
                },
                retryable = error.retryable,
                onRetry = { scope.launch { engine.retry() } },
                onDismiss = { scope.launch { engine.stop() } }
            )
        }

        // ---- Transient message ----
        toast?.let { message ->
            Text(
                text = message,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 64.dp)
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
            LaunchedEffect(message) {
                kotlinx.coroutines.delay(2_000)
                toast = null
            }
        }

        // Long-press speed indicator.
        if (longPressSpeedActive) {
            Text(
                text = formatSpeed(2.0f),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(24.dp)
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}

/**
 * Size the video surface according to the selected aspect ratio mode.
 */
private fun Modifier.aspectFit(
    mode: AspectRatioMode,
    videoWidth: Int,
    videoHeight: Int,
    containerWidth: Float,
    containerHeight: Float
): Modifier {
    if (videoWidth <= 0 || videoHeight <= 0 || containerWidth <= 0f || containerHeight <= 0f) {
        return this.fillMaxSize()
    }
    val videoRatio = videoWidth.toFloat() / videoHeight.toFloat()
    val containerRatio = containerWidth / containerHeight
    return when (mode) {
        AspectRatioMode.Fill -> this.fillMaxSize()
        AspectRatioMode.Fit, AspectRatioMode.Auto -> {
            if (videoRatio > containerRatio) {
                this.fillMaxWidth().height(containerWidth / videoRatio)
            } else {
                this.height(containerHeight).fillMaxWidth(
                    (containerHeight * videoRatio) / containerWidth
                )
            }
        }

        AspectRatioMode.Original -> this.fillMaxSize()
        AspectRatioMode.Custom -> this.fillMaxSize()
    }
}

/** Adjust the window brightness by [delta] (-1..1 relative). */
private fun adjustBrightness(context: Context, delta: Float) {
    val activity = context as? android.app.Activity ?: return
    if (abs(delta) < 0.001f) return
    val attributes = activity.window.attributes
    val current = if (attributes.screenBrightness < 0f) 0.5f else attributes.screenBrightness
    attributes.screenBrightness = (current + delta).coerceIn(0.01f, 1f)
    activity.window.attributes = attributes
}

/** Adjust the media stream volume by [delta] (-1..1 relative). */
private fun adjustVolume(context: Context, delta: Float) {
    if (abs(delta) < 0.001f) return
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    if (max <= 0) return
    val step = (delta * max).toInt().let { if (it == 0) (if (delta > 0) 1 else -1) else it }
    audioManager.adjustStreamVolume(
        AudioManager.STREAM_MUSIC,
        if (step > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
        0
    )
}
