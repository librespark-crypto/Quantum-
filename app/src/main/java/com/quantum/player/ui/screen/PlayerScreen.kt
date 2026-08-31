package com.quantum.player.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.MeasureHelper
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.quantum.player.core.PlaybackEngine
import com.quantum.player.core.PlaybackState
import com.quantum.player.model.MediaItem
import com.quantum.player.ui.component.*

/**
 * Main player screen with video playback and controls.
 * Features gesture-based controls and Material 3 design.
 */
@Composable
fun PlayerScreen(
    engine: PlaybackEngine,
    mediaItem: MediaItem,
    onClose: () -> Unit
) {
    var showControls by remember { mutableStateOf(true) }
    var playbackSpeed by remember { mutableStateOf(1.0f) }

    // State flows from playback engine
    val state by engine.stateFlow.collectAsState(initial = PlaybackState.Idle)
    val position by engine.currentPosition.collectAsState(initial = 0L)
    val duration by engine.duration.collectAsState(initial = -1L)
    val isPlaying by engine.isPlaying.collectAsState(initial = false)

    // Video player area
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Video preview placeholder
        if (state == PlaybackState.Idle || state == PlaybackState.Preparing) {
            PlaceholderVideoPlayer(
                modifier = Modifier.fillMaxSize(),
                contentColor = Color.Gray
            )
        }

        // Controls row at bottom
        if (showControls) {
            PlayerControlsBar(
                state = state,
                position = position,
                duration = duration,
                isPlaying = isPlaying,
                onPlayPause = { engine.togglePlayPause() },
                onSeek = { seekTo(it) },
                onSpeedChange = { playbackSpeed = it },
                onSkipSilence = { /* TODO: implement */ },
                playbackSpeed = playbackSpeed,
                onSpeedSelected = { speed ->
                    playbackSpeed = speed
                    engine.setPlaybackSpeed(speed)
                }
            )
        }
    }

    /** Seek to position in milliseconds */
    fun seekTo(positionMs: Long) {
        engine.seekTo(positionMs)
    }
}

/**
 * Placeholder video player shown during loading.
 */
@Composable
fun PlaceholderVideoPlayer(
    modifier: Modifier,
    contentColor: Color
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(contentColor),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Video Player",
            color = contentColor.copy(alpha = 0.6f),
            style = MaterialTheme.typography.h6
        )
    }
}

/**
 * Player controls bar with play/pause, seekbar, and time display.
 */
@Composable
fun PlayerControlsBar(
    state: PlaybackState,
    position: Long,
    duration: Long,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onSkipSilence: () -> Unit,
    playbackSpeed: Float,
    onSpeedSelected: (Float) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Play/Pause button
        IconButton(
            onClick = onPlayPause,
            enabled = state != PlaybackState.Idle
        ) {
            Icon(
                if (isPlaying) {
                    Icons.Filled.Pause
                } else {
                    Icons.Filled.PlayArrow
                },
                contentDescription = if (isPlaying) "Pause" else "Play"
            )
        }

        // Seek bar
        Slider(
            value = if (duration > 0) position.toFloat() / duration.toFloat() else 0f,
            onValueChange = { seekTo(it * duration) },
            style = SliderStyle(
                thumb = { Icon(Icons.False, contentDescription = null) },
                track = { ThumbGraph() }
            ),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
        )

        // Time display
        Column(
            modifier = Modifier
                .width(80.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.End
        ) {
            Text(
                text = positionString(position),
                style = MaterialTheme.typography.body1
            )
            Text(
                text = if (duration > 0) positionString(duration) else "--:--",
                style = MaterialTheme.typography.body1.copy(alpha = 0.7f)
            )
        }

        // Speed button - opens a menu
        Button(
            onClick = {
                // Open speed selection menu
                val menu = MaterialTheme.menu { }
                onSpeedSelected(1.0f)
                onSpeedSelected(0.75f)
                onSpeedSelected(1.5f)
                onSpeedSelected(2.0f)
            }
        ) {
            Text(
                text = "×${playbackSpeed.toStringAsFixed(1)}",
                style = MaterialTheme.typography.body1
            )
            Icon(Icons.False.ExpandMore, contentDescription = "Speed options")
        }
    }
}

/**
 * Thumb graph for slider.
 */
@Composable
fun ThumbGraph() {
    Canvas(modifier = Modifier.size(8.dp, 8.dp)) {
        val paint = android.graphics.Paint()
        paint.color = android.graphics.Color.White
        drawRect(
            left = 0f,
            top = 0f,
            right = 8f,
            bottom = 8f,
            paint = paint
        )
    }
}

/**
 * Position formatter converting milliseconds to MM:SS format.
 */
private fun positionString(ms: Long): String {
    val minutes = ms / 60000
    val seconds = (ms % 60000) / 1000
    return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
}