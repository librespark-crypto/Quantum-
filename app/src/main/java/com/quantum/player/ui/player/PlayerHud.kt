package com.quantum.player.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.SkipNext
import androidx.compose.material.icons.automirrored.filled.SkipPrevious
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.InfoOutlined
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ScreenLockLandscape
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectTapGestures
import com.quantum.player.core.AspectRatioMode
import com.quantum.player.core.AudioTrackInfo
import com.quantum.player.core.DecoderMode
import com.quantum.player.core.PlaybackState
import com.quantum.player.core.SubtitleTrackInfo

/**
 * The full player HUD overlay, structured into three distinct layers
 * (mpvRx / MX Player model):
 *
 *  1. [TopAppBarLayer]   — back, title (ellipsize=end), ♪ audio selector,
 *                          subtitle selector, HW/SW decoder badge.
 *  2. [QuickChipBar]     — horizontal scrollable pill row: EQ/tuner, playback
 *                          speed (tap cycles, long-press slider), screenshot,
 *                          background audio, orientation lock.
 *  3. [BottomControls]   — screen lock (bottom-left), seekbar with buffer,
 *                          prev/play/next, aspect ratio cycle + PiP
 *                          (bottom-right).
 *
 * Everything is direct on-screen control: there is NO settings screen behind
 * any of these buttons.
 */
@Composable
fun PlayerHud(
    title: String,
    state: PlaybackState,
    positionMs: Long,
    bufferedMs: Long,
    durationMs: Long,
    speed: Float,
    decoderMode: DecoderMode,
    aspectMode: AspectRatioMode,
    backgroundAudioEnabled: Boolean,
    orientationLock: OrientationLock,
    audioTracks: List<AudioTrackInfo>,
    selectedAudioTrack: Int,
    subtitleTracks: List<SubtitleTrackInfo>,
    selectedSubtitleTrack: Int,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onAudioClick: () -> Unit,
    onSubtitleClick: () -> Unit,
    onToggleDecoder: () -> Unit,
    onInfoClick: () -> Unit = {},
    onEqualizerClick: () -> Unit,
    onSpeedCycle: () -> Unit,
    onSpeedLongPress: () -> Unit,
    onScreenshot: () -> Unit,
    onToggleBackgroundAudio: () -> Unit,
    onCycleOrientationLock: () -> Unit,
    onLockControls: () -> Unit,
    onCycleAspectRatio: () -> Unit,
    onPip: () -> Unit,
    modifier: Modifier = Modifier
) {
    var scrubbing by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableFloatStateOf(0f) }

    Box(modifier = modifier
        .fillMaxSize()
        .background(SCRIM)) {

        // ---- Layer 1: top app bar ----
        TopAppBarLayer(
            title = title,
            decoderMode = decoderMode,
            onBack = onBack,
            onAudioClick = onAudioClick,
            onSubtitleClick = onSubtitleClick,
            onToggleDecoder = onToggleDecoder,
            modifier = Modifier.align(Alignment.TopCenter)
        )

        // ---- Layer 2: floating quick-action chip bar ----
        QuickChipBar(
            speed = speed,
            backgroundAudioEnabled = backgroundAudioEnabled,
            orientationLock = orientationLock,
            onEqualizerClick = onEqualizerClick,
            onSpeedCycle = onSpeedCycle,
            onSpeedLongPress = onSpeedLongPress,
            onScreenshot = onScreenshot,
            onToggleBackgroundAudio = onToggleBackgroundAudio,
            onCycleOrientationLock = onCycleOrientationLock,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 64.dp)
        )

        // ---- Buffering spinner ----
        if (state == PlaybackState.Buffering || state == PlaybackState.Preparing) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(52.dp)
                    .align(Alignment.Center),
                color = MaterialTheme.colorScheme.primary
            )
        }

        // ---- Layer 3: bottom playback controls ----
        BottomControls(
            state = state,
            positionMs = positionMs,
            bufferedMs = bufferedMs,
            durationMs = durationMs,
            scrubbing = scrubbing,
            scrubFraction = scrubFraction,
            aspectMode = aspectMode,
            onScrubStart = { scrubbing = true },
            onScrubChange = { scrubFraction = it },
            onScrubEnd = { fraction ->
                scrubbing = false
                onSeekTo((fraction * durationMs.coerceAtLeast(0L)).toLong())
            },
            onPlayPause = onPlayPause,
            onPrevious = onPrevious,
            onNext = onNext,
            onLockControls = onLockControls,
            onCycleAspectRatio = onCycleAspectRatio,
            onPip = onPip,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

// ----------------------------------------------------------------------
// Layer 1: top app bar
// ----------------------------------------------------------------------

@Composable
private fun TopAppBarLayer(
    title: String,
    decoderMode: DecoderMode,
    onBack: () -> Unit,
    onAudioClick: () -> Unit,
    onSubtitleClick: () -> Unit,
    onToggleDecoder: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HudIconButton(icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back", onClick = onBack)
        Text(
            text = title,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        // ♪ audio track selector
        HudIconButton(icon = Icons.Filled.Audiotrack,
            contentDescription = "Audio track", onClick = onAudioClick)
        // Subtitle / CC selector
        HudIconButton(icon = Icons.Filled.Subtitles,
            contentDescription = "Subtitles", onClick = onSubtitleClick)
        // Media / decoder info (direct sheet, not a settings screen)
        HudIconButton(icon = Icons.Filled.InfoOutlined,
            contentDescription = "Media info", onClick = onInfoClick)
        // HW/SW decoder badge: instant toggle on tap
        DecoderBadge(mode = decoderMode, onClick = onToggleDecoder)
        Spacer(Modifier.width(4.dp))
    }
}

@Composable
private fun DecoderBadge(mode: DecoderMode, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (mode == DecoderMode.HARDWARE) Color(0xFF2E7D32) else Color(0xFFEF6C00)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            mode.badge,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

// ----------------------------------------------------------------------
// Layer 2: quick-action chip bar
// ----------------------------------------------------------------------

@Composable
private fun QuickChipBar(
    speed: Float,
    backgroundAudioEnabled: Boolean,
    orientationLock: OrientationLock,
    onEqualizerClick: () -> Unit,
    onSpeedCycle: () -> Unit,
    onSpeedLongPress: () -> Unit,
    onScreenshot: () -> Unit,
    onToggleBackgroundAudio: () -> Unit,
    onCycleOrientationLock: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 🎛️ equalizer / tuner
        QuickChip(icon = Icons.Filled.Tune, label = "Tuner", onClick = onEqualizerClick)

        // Playback speed: tap cycles 1x → 1.25x → 1.5x → 2x, long-press slider.
        Box(
            modifier = Modifier.combinedClickChip(
                onClick = onSpeedCycle,
                onLongClick = onSpeedLongPress
            )
        ) {
            ChipContent(icon = Icons.Filled.Speed, label = formatSpeed(speed), active = false)
        }

        // 📷 screenshot
        QuickChip(icon = Icons.Filled.PhotoCamera, label = "Capture",
            onClick = onScreenshot)

        // 🎧 background audio
        QuickChip(icon = Icons.Filled.Headphones, label = "BG audio",
            active = backgroundAudioEnabled, onClick = onToggleBackgroundAudio)

        // Orientation lock
        QuickChip(
            icon = if (orientationLock == OrientationLock.AUTO) {
                Icons.Filled.ScreenRotation
            } else {
                Icons.Filled.ScreenLockLandscape
            },
            label = orientationLock.label,
            active = orientationLock != OrientationLock.AUTO,
            onClick = onCycleOrientationLock
        )
    }
}

/** Screen orientation override cycled by the chip. */
enum class OrientationLock(val label: String) {
    AUTO("Auto"),
    LANDSCAPE("Landscape"),
    PORTRAIT("Portrait"),
    SENSOR_LANDSCAPE("Sensor L")
}

@Composable
private fun QuickChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean = false,
    onClick: () -> Unit
) {
    Box(modifier = Modifier
        .clip(RoundedCornerShape(20.dp))
        .background(
            if (active) MaterialTheme.colorScheme.primary
            else Color.White.copy(alpha = 0.16f)
        )
        .clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        ChipContent(icon = icon, label = label, active = active)
    }
}

@Composable
private fun ChipContent(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (active) MaterialTheme.colorScheme.onPrimary else Color.White,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(5.dp))
        Text(
            label,
            color = if (active) MaterialTheme.colorScheme.onPrimary else Color.White,
            style = MaterialTheme.typography.labelMedium
        )
    }
}

// ----------------------------------------------------------------------
// Layer 3: bottom playback controls
// ----------------------------------------------------------------------

@Composable
private fun BottomControls(
    state: PlaybackState,
    positionMs: Long,
    bufferedMs: Long,
    durationMs: Long,
    scrubbing: Boolean,
    scrubFraction: Float,
    aspectMode: AspectRatioMode,
    onScrubStart: () -> Unit,
    onScrubChange: (Float) -> Unit,
    onScrubEnd: (Float) -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onLockControls: () -> Unit,
    onCycleAspectRatio: () -> Unit,
    onPip: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 10.dp)) {

        // ---- Progress section: elapsed | seekbar + buffer | duration ----
        val shownFraction = if (scrubbing) scrubFraction
        else if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        val bufferFraction = if (durationMs > 0)
            (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                formatTime(if (scrubbing) (shownFraction * durationMs).toLong() else positionMs),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall
            )
            Box(modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)) {
                // Buffer track behind the seekbar.
                LinearProgressIndicator(
                    progress = { bufferFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.Center)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = Color.White.copy(alpha = 0.4f),
                    trackColor = Color.White.copy(alpha = 0.18f)
                )
                Slider(
                    value = shownFraction,
                    onValueChange = {
                        if (!scrubbing) onScrubStart()
                        onScrubChange(it)
                    },
                    onValueChangeFinished = { onScrubEnd(shownFraction) },
                    valueRange = 0f..1f,
                    enabled = durationMs > 0,
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = Color.Transparent
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Text(
                if (durationMs > 0) formatTime(durationMs) else "--:--",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall
            )
        }

        Spacer(Modifier.height(2.dp))

        // ---- Transport row ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Bottom-left: screen lock
            HudIconButton(
                icon = Icons.Filled.ScreenLockLandscape,
                contentDescription = "Lock controls",
                onClick = onLockControls
            )

            // Center: previous / play-pause / next
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                HudIconButton(
                    icon = Icons.AutoMirrored.Filled.SkipPrevious,
                    contentDescription = "Previous video",
                    onClick = onPrevious,
                    size = 34
                )
                Spacer(Modifier.width(20.dp))
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable(onClick = onPlayPause)
                        .padding(10.dp)
                ) {
                    Icon(
                        if (state == PlaybackState.Playing) Icons.Filled.Pause
                        else Icons.Filled.PlayArrow,
                        contentDescription = if (state == PlaybackState.Playing) "Pause" else "Play",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(36.dp)
                    )
                }
                Spacer(Modifier.width(20.dp))
                HudIconButton(
                    icon = Icons.AutoMirrored.Filled.SkipNext,
                    contentDescription = "Next video",
                    onClick = onNext,
                    size = 34
                )
            }

            // Bottom-right: aspect ratio cycle + PiP
            HudIconButton(
                icon = Icons.Filled.AspectRatio,
                contentDescription = "Aspect ratio: ${aspectMode.label()}",
                onClick = onCycleAspectRatio
            )
            HudIconButton(
                icon = Icons.Filled.PictureInPictureAlt,
                contentDescription = "Picture in picture",
                onClick = onPip
            )
        }
    }
}

@Composable
private fun HudIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    size: Int = 26
) {
    androidx.compose.material3.IconButton(onClick = onClick) {
        Icon(icon, contentDescription = contentDescription, tint = Color.White,
            modifier = Modifier.size(size.dp))
    }
}

private fun AspectRatioMode.label(): String = when (this) {
    AspectRatioMode.Fit -> "Fit"
    AspectRatioMode.Fill -> "Crop/Fill"
    AspectRatioMode.Original -> "100%"
    AspectRatioMode.Auto -> "Auto"
    AspectRatioMode.Custom -> "Stretch"
}

internal fun formatSpeed(speed: Float): String =
    String.format(java.util.Locale.US, if (speed % 1f == 0f) "%.0fX" else "%.2gX", speed)

/** Long-click capable modifier for the speed chip (tap = cycle, long-press = slider). */
private fun Modifier.combinedClickChip(
    onClick: () -> Unit,
    onLongClick: () -> Unit
): Modifier = this
    .clip(RoundedCornerShape(20.dp))
    .background(Color.White.copy(alpha = 0.16f))
    .pointerInput(onClick, onLongClick) {
        detectTapGestures(
            onTap = { onClick() },
            onLongPress = { onLongClick() }
        )
    }
    .padding(horizontal = 12.dp, vertical = 8.dp)

private val SCRIM = Color(0x99000000)
