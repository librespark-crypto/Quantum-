package com.quantum.player.ui.screen

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.TextureView
import java.io.File
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.quantum.player.core.AspectRatioMode
import com.quantum.player.core.AudioTrackInfo
import com.quantum.player.core.DecoderMode
import com.quantum.player.core.PlaybackEngine
import com.quantum.player.core.PlaybackState
import com.quantum.player.core.SubtitleTrackInfo
import com.quantum.player.error.PlaybackError
import com.quantum.player.model.MediaItem
import com.quantum.player.subtitles.SubtitleOverlay
import com.quantum.player.ui.component.PlaybackErrorPanel
import com.quantum.player.ui.gesture.ExcludeZone
import com.quantum.player.ui.gesture.GestureConfig
import com.quantum.player.ui.gesture.GestureOverlay
import com.quantum.player.ui.player.EqualizerSheet
import com.quantum.player.ui.player.AudioTrackSheet
import com.quantum.player.ui.player.LockedOverlay
import com.quantum.player.ui.player.MediaInfoSheet
import com.quantum.player.ui.player.OsdState
import com.quantum.player.ui.player.OrientationLock
import com.quantum.player.ui.player.GestureOsd
import com.quantum.player.ui.player.PlayerHud
import com.quantum.player.ui.player.SpeedSheet
import com.quantum.player.ui.player.SubtitleSheet
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min

/** Which bottom sheet (if any) is open over playback. */
private enum class Sheet { NONE, AUDIO, SUBTITLE, EQUALIZER, SPEED, INFO }

/**
 * Main player screen: video surface, gesture engine, three-layer HUD and the
 * on-demand bottom sheets.
 *
 * There is no settings screen anywhere in this flow — HW/SW, tracks, decoder,
 * EQ, speed, aspect, boost and orientation are all driven directly from the HUD
 * (see [PlayerHud]) or the sheets opened from it.
 */
@Composable
fun PlayerScreen(
    engine: PlaybackEngine,
    mediaItem: MediaItem,
    playlist: List<MediaItem> = emptyList(),
    startPositionMs: Long = 0L,
    onClose: () -> Unit,
    onEnterPiP: () -> Unit,
    onPlayItem: (MediaItem, Long) -> Unit = { _, _ -> },
    onToggleBackgroundAudioMode: () -> Unit = {},
    onPickSubtitleFile: () -> Unit = {},
    showControlsInitially: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val audioManager = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    val maxStreamVolume = remember {
        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    }

    val state by engine.stateFlow.collectAsState(initial = PlaybackState.Idle)
    val position by engine.positionFlow.collectAsState(initial = 0L)
    val cues by engine.cuesFlow.collectAsState(initial = emptyList())
    val playbackError by engine.errorFlow.collectAsState(initial = null)

    // ---- HUD state ----
    var showControls by remember { mutableStateOf(showControlsInitially) }
    var controlsLocked by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(engine.playbackSpeed) }
    var decoderMode by remember { mutableStateOf(engine.decoderMode) }
    var aspectMode by remember { mutableStateOf(AspectRatioMode.Fit) }
    var backgroundAudio by remember { mutableStateOf(false) }
    var orientation by remember { mutableStateOf(OrientationLock.AUTO) }
    var sheet by remember { mutableStateOf(Sheet.NONE) }
    var toast by remember { mutableStateOf<String?>(null) }
    var longPressBoost by remember { mutableStateOf(false) }

    // ---- tracks ----
    var audioTracks by remember { mutableStateOf<List<AudioTrackInfo>>(emptyList()) }
    var subtitleTracks by remember { mutableStateOf<List<SubtitleTrackInfo>>(emptyList()) }
    var selectedAudio by remember { mutableIntStateOf(-1) }
    var selectedSubtitle by remember { mutableIntStateOf(-1) }
    var muted by remember { mutableStateOf(false) }
    var videoWidthPx by remember { mutableIntStateOf(0) }
    var videoHeightPx by remember { mutableIntStateOf(0) }

    // ---- gesture / zoom state ----
    var osd by remember { mutableStateOf<OsdState>(OsdState.Hidden) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    var scrubTarget by remember { mutableLongStateOf(0L) }

    // ---- Start playback (resume from the FAB/history position) ----
    LaunchedEffect(mediaItem.id) {
        val withResume = if (startPositionMs > 0) {
            mediaItem.copy(metadata = mediaItem.metadata + ("resume_position_ms" to startPositionMs))
        } else {
            mediaItem
        }
        runCatching { engine.play(withResume) }
        speed = engine.playbackSpeed
        decoderMode = engine.decoderMode
    }

    // Refresh track lists once media is parsed.
    LaunchedEffect(state) {
        if (state == PlaybackState.Playing || state == PlaybackState.Buffering ||
            state == PlaybackState.Paused
        ) {
            audioTracks = engine.availableAudioTracks
            subtitleTracks = engine.availableSubtitleTracks
            selectedAudio = engine.currentAudioTrack
            selectedSubtitle = engine.currentSubtitleTrack
            if (videoWidthPx == 0) {
                videoWidthPx = engine.videoWidth
                videoHeightPx = engine.videoHeight
            }
        }
    }

    // Auto-hide controls after 3 seconds of inactivity.
    LaunchedEffect(showControls, state, sheet) {
        if (showControls && !controlsLocked && sheet == Sheet.NONE) {
            delay(3_000)
            showControls = false
        }
    }

    // Toast auto-dismiss.
    LaunchedEffect(toast) {
        if (toast != null) {
            delay(2_000)
            toast = null
        }
    }

    DisposableEffect(engine) {
        // Enter the player in sensor-landscape like MX/mpv; the orientation
        // chip overrides this while playing, and we restore "auto" on exit.
        val previousOrientation = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            engine.setVideoTextureView(null)
            activity?.requestedOrientation =
                previousOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    BackHandler(enabled = controlsLocked || showControls) {
        when {
            controlsLocked -> { controlsLocked = false }
            sheet != Sheet.NONE -> sheet = Sheet.NONE
            showControls -> showControls = false
            else -> onClose()
        }
    }

    val density = LocalDensity.current
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        val containerWidthPx = with(density) { maxWidth.toPx() }
        val containerHeightPx = with(density) { maxHeight.toPx() }
        // ---- Video surface with aspect mode + pinch zoom/pan ----
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).also { it.surfaceTextureListener = null }
            },
            update = { textureView ->
                engine.setVideoTextureView(textureView)
                textureView.applyAspectSize(
                    aspect = aspectMode,
                    videoWidth = videoWidthPx,
                    videoHeight = videoHeightPx,
                    containerWidth = containerWidthPx,
                    containerHeight = containerHeightPx
                )
            },
            modifier = Modifier
                .align(Alignment.Center)
                .wrapContentSize(Alignment.Center)
                .graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                    translationX = panX
                    translationY = panY
                }
        )

        SubtitleOverlay(
            cues = cues,
            bottomPaddingDp = if (showControls) 150f else 56f,
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        // ---- Gesture engine (suppressed while locked or a sheet is up) ----
        if (!controlsLocked && sheet == Sheet.NONE) {
            GestureOverlay(
                config = GestureConfig(
                    currentPositionMs = { engine.currentPosition },
                    currentBrightness = {
                        val b = activity?.window?.attributes?.screenBrightness ?: -1f
                        if (b < 0f) 0.5f else b
                    },
                    currentVolume = {
                        val stream = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                        // Normal system volume occupies the 0..1 half; boost 1..2.
                        stream.toFloat() / maxStreamVolume.coerceAtLeast(1) *
                            (if (engine.volumeBoost > 1f) engine.volumeBoost else 1f)
                    }
                ),
                excludeFor = if (showControls) setOf(ExcludeZone.Seekbar) else emptySet(),
                onScrub = { targetMs, _, finished ->
                    val clamped = targetMs.coerceIn(0L, engine.duration.coerceAtLeast(0L))
                    scrubTarget = clamped
                    osd = OsdState.Scrub(clamped, clamped - engine.currentPosition)
                    if (finished) {
                        scope.launch { engine.seekTo(clamped) }
                        osd = OsdState.Hidden
                    }
                },
                onBrightness = { fraction ->
                    setBrightness(activity, fraction)
                    osd = OsdState.Brightness(fraction)
                },
                onVolume = { fraction ->
                    applyVolume(
                        audioManager = audioManager,
                        maxStream = maxStreamVolume,
                        fraction = fraction,
                        onBoost = { boost ->
                            scope.launch { engine.setVolumeBoost(boost) }
                        }
                    )
                    osd = OsdState.Volume(fraction, maxFraction = 2f)
                },
                onGestureEnd = {
                    // Clear the OSD shortly after the finger lifts.
                    scope.launch { delay(400); osd = OsdState.Hidden }
                },
                onDoubleTapSeekBack = { scope.launch { engine.seekBy(-10_000L) } },
                onDoubleTapSeekForward = { scope.launch { engine.seekBy(10_000L) } },
                onDoubleTapCenter = {
                    if (zoom > 1.01f) {
                        zoom = 1f; panX = 0f; panY = 0f
                    } else {
                        scope.launch { engine.togglePlayPause() }
                    }
                },
                onSingleTap = {
                    if (showControls) showControls = false else { showControls = true }
                    osd = OsdState.Hidden
                },
                onZoomPan = { scale, px, py ->
                    zoom = scale
                    if (scale <= 1.01f) {
                        panX = 0f; panY = 0f
                    } else {
                        // Clamp pan so the frame cannot be dragged off-screen.
                        val maxPanX = (scale - 1f) * 600f
                        val maxPanY = (scale - 1f) * 600f
                        panX = px.coerceIn(-maxPanX, maxPanX)
                        panY = py.coerceIn(-maxPanY, maxPanY)
                    }
                },
                onResetZoom = { zoom = 1f; panX = 0f; panY = 0f },
                onLongPressStart = {
                    longPressBoost = true
                    scope.launch { engine.setPlaybackSpeed(2f) }
                },
                onLongPressEnd = {
                    if (longPressBoost) {
                        longPressBoost = false
                        scope.launch { engine.setPlaybackSpeed(speed) }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // ---- OSD feedback (brightness / volume / scrub) ----
        if (sheet == Sheet.NONE) {
            GestureOsd(state = osd)
        }

        // ---- Three-layer HUD with auto-hide ----
        AnimatedVisibility(
            visible = showControls && !controlsLocked,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            PlayerHud(
                title = mediaItem.displayName,
                state = state,
                positionMs = if (osd is OsdState.Scrub) scrubTarget else position,
                bufferedMs = engine.bufferedPosition.coerceAtLeast(0L),
                durationMs = engine.duration.coerceAtLeast(0L),
                speed = if (longPressBoost) 2f else speed,
                decoderMode = decoderMode,
                aspectMode = aspectMode,
                backgroundAudioEnabled = backgroundAudio,
                orientationLock = orientation,
                audioTracks = audioTracks,
                selectedAudioTrack = selectedAudio,
                subtitleTracks = subtitleTracks,
                selectedSubtitleTrack = selectedSubtitle,
                onBack = onClose,
                onPlayPause = { scope.launch { engine.togglePlayPause() } },
                onSeekTo = { target -> scope.launch { engine.seekTo(target) } },
                onPrevious = { switchItem(playlist, mediaItem, -1, onPlayItem) },
                onNext = { switchItem(playlist, mediaItem, 1, onPlayItem) },
                onAudioClick = { sheet = Sheet.AUDIO },
                onSubtitleClick = { sheet = Sheet.SUBTITLE },
                onInfoClick = { sheet = Sheet.INFO },
                onToggleDecoder = {
                    val newMode = if (decoderMode == DecoderMode.HARDWARE)
                        DecoderMode.SOFTWARE else DecoderMode.HARDWARE
                    decoderMode = newMode
                    scope.launch {
                        engine.setDecoderMode(newMode)
                        toast = "Decoder: ${newMode.badge}"
                    }
                },
                onEqualizerClick = { sheet = Sheet.EQUALIZER },
                onSpeedCycle = {
                    val next = cycleSpeed(speed)
                    speed = next
                    scope.launch { engine.setPlaybackSpeed(next) }
                },
                onSpeedLongPress = { sheet = Sheet.SPEED },
                onScreenshot = {
                    scope.launch {
                        toast = runCatching {
                            val bytes = engine.captureScreenshot()
                            saveScreenshotToGallery(context, bytes, mediaItem.displayName)
                        }.fold({ "Screenshot saved to gallery" }, { "Screenshot failed" })
                    }
                },
                onToggleBackgroundAudio = {
                    backgroundAudio = !backgroundAudio
                    onToggleBackgroundAudioMode()
                    toast = if (backgroundAudio) "Background audio on" else "Background audio off"
                },
                onCycleOrientationLock = {
                    orientation = cycleOrientation(orientation)
                    activity?.requestedOrientation = when (orientation) {
                        OrientationLock.AUTO ->
                            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        OrientationLock.LANDSCAPE ->
                            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        OrientationLock.PORTRAIT ->
                            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                        OrientationLock.SENSOR_LANDSCAPE ->
                            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED.also {
                                orientation = OrientationLock.AUTO
                            }
                    }
                    toast = "Orientation: ${orientation.label}"
                },
                onLockControls = { controlsLocked = true },
                onCycleAspectRatio = {
                    aspectMode = cycleAspect(aspectMode)
                    toast = "Display: ${aspectLabel(aspectMode)}"
                },
                onPip = onEnterPiP
            )
        }

        // ---- Locked state: only the floating unlock icon receives touches ----
        if (controlsLocked) {
            LockedOverlay(onUnlock = { controlsLocked = false })
        }

        // ---- Error panel ----
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

        // ---- Transient toast ----
        toast?.let { message ->
            Text(
                text = message,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 72.dp)
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }

        // ---- Bottom sheets (all configuration lives here, not in Settings) ----
        when (sheet) {
            Sheet.AUDIO -> AudioTrackSheet(
                tracks = audioTracks,
                selectedIndex = selectedAudio,
                muted = muted,
                onSelect = { index ->
                    muted = false
                    selectedAudio = index
                    scope.launch {
                        engine.setVolumeBoost(1f)
                        engine.setAudioTrack(index)
                    }
                },
                onMute = {
                    muted = !muted
                    scope.launch { engine.setVolumeBoost(if (muted) 0f else 1f) }
                },
                onDismiss = { sheet = Sheet.NONE }
            )

            Sheet.SUBTITLE -> SubtitleSheet(
                tracks = subtitleTracks,
                selectedIndex = selectedSubtitle,
                onSelect = { index ->
                    selectedSubtitle = index
                    scope.launch { engine.setSubtitleTrack(index) }
                },
                onPickExternalFile = { onPickSubtitleFile() },
                onDismiss = { sheet = Sheet.NONE }
            )

            Sheet.EQUALIZER -> {
                val fx = engine.audioEffects
                val bands by fx.bands.collectAsState()
                val bass by fx.bassBoostStrength.collectAsState()
                val available by fx.available.collectAsState()
                EqualizerSheet(
                    bands = bands,
                    bassStrength = bass,
                    available = available,
                    onBandChange = { bandIndex, levelMb -> fx.setBandLevel(bandIndex, levelMb) },
                    onBassChange = { fx.setBassBoost(it) },
                    onReset = { fx.reset() },
                    onDismiss = { sheet = Sheet.NONE }
                )
            }

            Sheet.SPEED -> SpeedSheet(
                speed = speed,
                onSelect = { newSpeed ->
                    speed = newSpeed
                    scope.launch { engine.setPlaybackSpeed(newSpeed) }
                },
                onDismiss = { sheet = Sheet.NONE }
            )

            Sheet.INFO -> MediaInfoSheet(
                lines = buildMediaInfo(engine, mediaItem),
                onDismiss = { sheet = Sheet.NONE }
            )

            Sheet.NONE -> Unit
        }
    }
}

// ----------------------------------------------------------------------
// Helpers
// ----------------------------------------------------------------------

/** Apply window brightness (0.01..1) for the left-third vertical drag. */
private fun setBrightness(activity: Activity?, fraction: Float) {
    activity ?: return
    val params = activity.window.attributes
    params.screenBrightness = fraction.coerceIn(0.01f, 1f)
    activity.window.attributes = params
}

/**
 * Apply the right-third vertical drag. The system stream volume covers the
 * 0..100% half; fractions above 1.0 engage the software boost pipeline up to
 * 200%.
 */
private fun applyVolume(
    audioManager: AudioManager,
    maxStream: Int,
    fraction: Float,
    onBoost: (Float) -> Unit
) {
    if (fraction <= 1f) {
        val target = (fraction * maxStream).toInt().coerceIn(0, maxStream)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        onBoost(1f)
    } else {
        // Past 100% system volume: pin the stream at max and boost in software.
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxStream, 0)
        onBoost(fraction.coerceIn(1f, 2f))
    }
}

/** Save a PNG screenshot into the shared Pictures/Quantum collection. */
private fun saveScreenshotToGallery(context: Context, pngBytes: ByteArray, title: String) {
    val bitmap = BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size)
        ?: error("decode failed")
    val filename = "Quantum_${title.take(40)}_${System.currentTimeMillis()}.png"
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/Quantum")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = context.contentResolver.insert(collection, values)
            ?: error("insert failed")
        context.contentResolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        context.contentResolver.update(uri, values, null, null)
    } else {
        @Suppress("DEPRECATION")
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "Quantum"
        ).apply { mkdirs() }
        java.io.File(dir, filename).outputStream().use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        }
        // Tell the media scanner about it.
        val mediaScanner = android.media.MediaScannerConnection(context, null)
        mediaScanner.connect()
        mediaScanner.scanFile(java.io.File(dir, filename).absolutePath, "image/png")
    }
    bitmap.recycle()
}

/** 1x → 1.25x → 1.5x → 2x → back to 1x. */
private fun cycleSpeed(current: Float): Float = when {
    abs(current - 1f) < 0.01f -> 1.25f
    abs(current - 1.25f) < 0.01f -> 1.5f
    abs(current - 1.5f) < 0.01f -> 2f
    else -> 1f
}

/** Fit → Crop/Fill → Stretch → 100% → Fit. */
private fun cycleAspect(current: AspectRatioMode): AspectRatioMode = when (current) {
    AspectRatioMode.Fit, AspectRatioMode.Auto -> AspectRatioMode.Fill
    AspectRatioMode.Fill -> AspectRatioMode.Custom       // Stretch
    AspectRatioMode.Custom -> AspectRatioMode.Original   // 100%
    AspectRatioMode.Original -> AspectRatioMode.Fit
}

private fun aspectLabel(mode: AspectRatioMode): String = when (mode) {
    AspectRatioMode.Fit -> "Fit"
    AspectRatioMode.Fill -> "Crop/Fill"
    AspectRatioMode.Custom -> "Stretch"
    AspectRatioMode.Original -> "100%"
    AspectRatioMode.Auto -> "Auto"
}

private fun cycleOrientation(current: OrientationLock): OrientationLock = when (current) {
    OrientationLock.AUTO -> OrientationLock.LANDSCAPE
    OrientationLock.LANDSCAPE -> OrientationLock.PORTRAIT
    OrientationLock.PORTRAIT -> OrientationLock.SENSOR_LANDSCAPE
    OrientationLock.SENSOR_LANDSCAPE -> OrientationLock.AUTO
}

/** Move to the previous/next item in the folder playlist. */
private fun switchItem(
    playlist: List<MediaItem>,
    current: MediaItem,
    direction: Int,
    onPlayItem: (MediaItem, Long) -> Unit
) {
    if (playlist.isEmpty()) return
    val index = playlist.indexOfFirst { it.id == current.id }
    if (index < 0) return
    val target = (index + direction).let {
        if (it < 0) playlist.lastIndex else if (it >= playlist.size) 0 else it
    }
    onPlayItem(playlist[target], 0L)
}

/** Media info sheet contents (decoder, geometry, tracks) — direct, no settings. */
private fun buildMediaInfo(engine: PlaybackEngine, item: MediaItem): List<Pair<String, String>> {
    val info = engine.decoderInfo
    return buildList {
        add("Title" to item.displayName)
        add("Decoder" to "${info.videoCodec} · ${if (info.hardwareVideoDecoding) "HW" else "SW"}")
        if (engine.videoWidth > 0) {
            add("Resolution" to "${engine.videoWidth} × ${engine.videoHeight}")
        }
        add("Speed" to "${engine.playbackSpeed}x")
        add("Volume boost" to "${(engine.volumeBoost * 100).toInt()}%")
        add("Audio tracks" to engine.availableAudioTracks.size.toString())
        add("Subtitle tracks" to engine.availableSubtitleTracks.size.toString())
    }
}

/**
 * Size the TextureView for the selected display mode. The view is laid out
 * inside a full-screen box, so adjusting its layout params produces:
 *
 *  - Fit       : whole frame visible (letterboxed), limited by the tighter edge
 *  - Fill      : the view fills the container and the frame is center-cropped
 *  - Stretch   : the view fills the container (frames are distorted to match)
 *  - Original  : the view matches the frame aspect, 1:1 pixel scale
 *
 * Pinch zoom + pan are layered on top by the Compose graphicsLayer.
 */
private fun TextureView.applyAspectSize(
    aspect: AspectRatioMode,
    videoWidth: Int,
    videoHeight: Int,
    containerWidth: Float,
    containerHeight: Float
) {
    if (videoWidth <= 0 || videoHeight <= 0 || containerWidth <= 0f || containerHeight <= 0f) return
    val videoRatio = videoWidth.toFloat() / videoHeight.toFloat()
    val containerRatio = containerWidth / containerHeight

    val targetWidth: Int
    val targetHeight: Int
    when (aspect) {
        AspectRatioMode.Fill, AspectRatioMode.Custom -> {
            // Crop/fill and stretch both size the view to the full container;
            // Stretch additionally applies a non-uniform matrix (below).
            targetWidth = containerWidth.toInt()
            targetHeight = containerHeight.toInt()
        }

        AspectRatioMode.Original -> {
            // 100%: video pixel dimensions, capped so it never exceeds the view.
            val cappedW = min(videoWidth.toFloat(), containerWidth)
            val scale = cappedW / videoWidth
            targetWidth = (videoWidth * scale).toInt().coerceAtLeast(1)
            targetHeight = (videoHeight * scale).toInt().coerceAtLeast(1)
        }

        AspectRatioMode.Fit, AspectRatioMode.Auto -> {
            if (videoRatio > containerRatio) {
                targetWidth = containerWidth.toInt()
                targetHeight = (containerWidth / videoRatio).toInt()
            } else {
                targetHeight = containerHeight.toInt()
                targetWidth = (containerHeight * videoRatio).toInt()
            }
        }
    }

    // Stretch distorts the frame to the container's aspect ratio by a
    // non-uniform content matrix centered on the view.
    if (aspect == AspectRatioMode.Custom && targetWidth > 0 && targetHeight > 0) {
        val contentRatio = videoRatio
        val viewRatio = targetWidth.toFloat() / targetHeight
        val stretchMatrix = android.graphics.Matrix()
        if (contentRatio > viewRatio) {
            stretchMatrix.setScale(1f, contentRatio / viewRatio,
                targetWidth / 2f, targetHeight / 2f)
        } else {
            stretchMatrix.setScale(viewRatio / contentRatio, 1f,
                targetWidth / 2f, targetHeight / 2f)
        }
        setTransform(stretchMatrix)
    } else {
        setTransform(null)
    }

    if (layoutParams == null) return
    if (layoutParams.width != targetWidth || layoutParams.height != targetHeight) {
        layoutParams = layoutParams.apply {
            width = targetWidth
            height = targetHeight
        }
    }
}
