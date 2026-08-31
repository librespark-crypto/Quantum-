package com.quantum.player.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.quantum.player.R
import com.quantum.player.core.AspectRatioMode
import com.quantum.player.core.AudioTrackInfo
import com.quantum.player.core.PlaybackState
import com.quantum.player.core.SubtitleTrackInfo
import com.quantum.player.service.NotificationController
import java.util.Locale

/**
 * Fullscreen control overlay that appears when user interacts.
 * Features minimal UI with smooth animations and Material 3 design.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullscreenControlOverlay(
    title: String,
    state: PlaybackState,
    positionMs: Long,
    durationMs: Long,
    speed: Float,
    audioTracks: List<AudioTrackInfo>,
    selectedAudioTrack: Int,
    subtitleTracks: List<SubtitleTrackInfo>,
    selectedSubtitleTrack: Int,
    aspectRatio: AspectRatioMode,
    controlsLocked: Boolean,
    isBuffering: Boolean,
    onClose: () -> Unit,
    onPlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSeekBy: (Long) -> Unit,
    onSpeedSelected: (Float) -> Unit,
    onAudioTrackSelected: (Int) -> Unit,
    onSubtitleTrackSelected: (Int) -> Unit,
    onAspectRatioSelected: (AspectRatioMode) -> Unit,
    onToggleLock: () -> Unit,
    onScreenshot: () -> Unit,
    onEnterPiP: () -> Unit,
    modifier: Modifier = Modifier
) {
    var scrubbing by remember { mutableStateOf(false) }
    var scrubPosition by remember { mutableStateOf(0L) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SCRIM)
            .padding(vertical = 12.dp, horizontal = 16.dp)
    ) {
        // ---- Top bar ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.close),
                    tint = Color.White
                )
            }
            Text(
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (!controlsLocked) {
                IconButton(onClick = onScreenshot) {
                    Icon(
                        Icons.Filled.PhotoCamera,
                        contentDescription = stringResource(R.string.screenshot),
                        tint = Color.White
                    )
                }
                IconButton(onClick = onEnterPiP) {
                    Icon(
                        Icons.Filled.PictureInPictureAlt,
                        contentDescription = stringResource(R.string.pip_enter),
                        tint = Color.White
                    )
                }
            }
            IconButton(onClick = onToggleLock) {
                Icon(
                    if (controlsLocked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                    contentDescription = stringResource(R.string.lock_controls),
                    tint = Color.White
                )
            }
        }

        // ---- Centre ----
        if (isBuffering || state == PlaybackState.Preparing) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(48.dp)
                    .align(Alignment.Center),
                color = MaterialTheme.colorScheme.primary
            )
        }

        if (!controlsLocked) {
            Row(
                modifier = Modifier.align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(28.dp)
            ) {
                IconButton(onClick = { onSeekBy(-SEEK_STEP_MS) }) {
                    Icon(
                        Icons.Filled.Replay10,
                        contentDescription = stringResource(R.string.backward_10),
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                }
                IconButton(onClick = onPlayPause) {
                    Icon(
                        if (state == PlaybackState.Playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (state == PlaybackState.Playing) {
                            stringResource(R.string.pause)
                        } else {
                            stringResource(R.string.play)
                        },
                        tint = Color.White,
                        modifier = Modifier.size(56.dp)
                    )
                }
                IconButton(onClick = { onSeekBy(SEEK_STEP_MS) }) {
                    Icon(
                        Icons.Filled.Forward10,
                        contentDescription = stringResource(R.string.forward_10),
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }
        }

        // ---- Bottom bar ----
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
        ) {
            if (!controlsLocked) {
                val shownPosition = if (scrubbing) scrubPosition else positionMs
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = NotificationController.formatPosition(shownPosition),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall
                    )
                    Slider(
                        value = sliderValue(shownPosition, durationMs),
                        onValueChange = { fraction ->
                            scrubbing = true
                            scrubPosition = (fraction * durationMs.coerceAtLeast(0L)).toLong()
                        },
                        onValueChangeFinished = {
                            onSeekTo(scrubPosition)
                            scrubbing = false
                        },
                        valueRange = 0f..1f,
                        enabled = durationMs > 0,
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = Color.White.copy(alpha = 0.25f)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp)
                    )
                    Text(
                        text = if (durationMs > 0) {
                            NotificationController.formatPosition(durationMs)
                        } else {
                            "--:--"
                        },
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    SpeedMenu(speed = speed, onSpeedSelected = onSpeedSelected)
                    SubtitleMenu(
                        tracks = subtitleTracks,
                        selected = selectedSubtitleTrack,
                        onSelect = onSubtitleTrackSelected
                    )
                    AudioTrackMenu(
                        tracks = audioTracks,
                        selected = selectedAudioTrack,
                        onSelect = onAudioTrackSelected
                    )
                    AspectRatioMenu(current = aspectRatio, onSelect = onAspectRatioSelected)
                }
            }
        }
    }
}

@Composable
private fun SpeedMenu(speed: Float, onSpeedSelected: (Float) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.Speed, contentDescription = stringResource(R.string.speed), tint = Color.White)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            PLAYBACK_SPEEDS.forEach { option ->
                DropdownMenuItem(
                    text = { Text(formatSpeed(option)) },
                    trailingIcon = {
                        if (option == speed) Icon(Icons.Filled.Check, contentDescription = null)
                    },
                    onClick = {
                        onSpeedSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun SubtitleMenu(
    tracks: List<SubtitleTrackInfo>,
    selected: Int,
    onSelect: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                Icons.Filled.Subtitles,
                contentDescription = stringResource(R.string.subtitle),
                tint = Color.White
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.off)) },
                trailingIcon = {
                    if (selected < 0) Icon(Icons.Filled.Check, contentDescription = null)
                },
                onClick = {
                    onSelect(-1)
                    expanded = false
                }
            )
            tracks.forEach { track ->
                DropdownMenuItem(
                    text = { Text(track.name) },
                    trailingIcon = {
                        if (track.index == selected) Icon(Icons.Filled.Check, contentDescription = null)
                    },
                    onClick = {
                        onSelect(track.index)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun AudioTrackMenu(
    tracks: List<AudioTrackInfo>,
    selected: Int,
    onSelect: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                Icons.Filled.Audiotrack,
                contentDescription = stringResource(R.string.audio_track),
                tint = Color.White
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (tracks.isEmpty()) {
                DropdownMenuItem(text = { Text(stringResource(R.string.no_tracks)) }, onClick = { expanded = false })
            }
            tracks.forEach { track ->
                DropdownMenuItem(
                    text = { Text(track.name) },
                    trailingIcon = {
                        if (track.index == selected) Icon(Icons.Filled.Check, contentDescription = null)
                    },
                    onClick = {
                        onSelect(track.index)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun AspectRatioMenu(current: AspectRatioMode, onSelect: (AspectRatioMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                Icons.Filled.AspectRatio,
                contentDescription = stringResource(R.string.aspect_ratio),
                tint = Color.White
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DISPLAYABLE_ASPECT_RATIOS.forEach { mode ->
                DropdownMenuItem(
                    text = {
                        Text(
                            when (mode) {
                                AspectRatioMode.Auto -> stringResource(R.string.aspect_auto)
                                AspectRatioMode.Fit -> stringResource(R.string.aspect_fit)
                                AspectRatioMode.Fill -> stringResource(R.string.aspect_fill)
                                AspectRatioMode.Original -> stringResource(R.string.aspect_original)
                                AspectRatioMode.Custom -> mode.name
                            }
                        )
                    },
                    trailingIcon = {
                        if (mode == current) Icon(Icons.Filled.Check, contentDescription = null)
                    },
                    onClick = {
                        onSelect(mode)
                        expanded = false
                    }
                )
            }
        }
    }
}

/**
 * Error panel shown when playback fails. Shows what happened, what the user can
 * do about it, and a retry affordance - a decode failure must never just crash.
 */
@Composable
fun PlaybackErrorPanel(
    title: String,
    userMessage: String,
    solution: String,
    retryable: Boolean,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SCRIM),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF1A1F2E))
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(8.dp))
            Text(text = userMessage, color = Color(0xFFB0B0B0), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            Text(text = solution, color = Color(0xFF8A93A6), style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.dismiss),
                    color = Color(0xFFB0B0B0),
                    modifier = Modifier.clickable { onDismiss() }.padding(8.dp)
                )
                if (retryable) {
                    Text(
                        text = stringResource(R.string.retry),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onRetry() }.padding(8.dp)
                    )
                }
            }
        }
    }
}

private val SCRIM = Color(0xB3000000)
private const val SEEK_STEP_MS = 10_000L
private val PLAYBACK_SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f, 4.0f)
private val DISPLAYABLE_ASPECT_RATIOS =
    listOf(AspectRatioMode.Auto, AspectRatioMode.Fit, AspectRatioMode.Fill, AspectRatioMode.Original)

private fun sliderValue(positionMs: Long, durationMs: Long): Float =
    if (durationMs > 0) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f

internal fun formatSpeed(speed: Float): String =
    String.format(Locale.US, if (speed % 1f == 0f) "%.0fx" else "%.2gx", speed)
