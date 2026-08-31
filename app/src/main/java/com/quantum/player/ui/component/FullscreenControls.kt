package com.quantum.player.ui.component

import androidx.compose.foundation.ImageToggleButton
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberClickable.*
import androidx.compose.foundation.speedclick.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui input.pointer.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.quantum.player.core.PlaybackEngine
import com.quantum.player.core.PlaybackState
import com.quantum.player.model.MediaItem
import com.quantum.player.ui.gesture.GestureConfig

/**
 * Fullscreen control overlay that appears when user interacts.
 * Features minimal UI with smooth animations and Material 3 design.
 */
@Composable
fun FullscreenControlOverlay(
    engine: PlaybackEngine,
    mediaItem: MediaItem,
    controlsVisibility: MutableState<Boolean>,
    onClose: () -> Unit
) {
    // Auto-hide controls after inactivity
    val hideControls by remember {
        autoHideControls(controlsVisible = controlsVisibility, timeoutMs: 3000)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AlphaBackground(controlsVisibility.value)),
        verticalArrangement = Arrangement.End,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top bar with title and close button
        AppBar(controlsVisibility = controlsVisibility, onClose = onClose)

        // Main controls
        ControlsRow(engine = engine, controlsVisibility = controlsVisibility)

        // Bottom bar with progress and time
        BottomProgressBar(engine = engine)
    }
}

/** App bar with title and close button. */
@Composable
fun AppBar(
    controlsVisibility: MutableState<Boolean>,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Menu button (three dots)
        IconButton(
            onClick = { /* open more menu */ }
        ) {
            Icon(Icons.MoreVert, contentDescription = "More options")
        }

        // Title
        Text(
            text = "Quantum Player",
            style = MaterialTheme.typography.subtitle2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        // Close button (hide fullscreen, return to miniplayer)
        IconButton(
            onClick = {
                controlsVisibility.value = false
                // Or close completely
            }
        ) {
            Icon(Icons.Close, contentDescription = "Close fullscreen")
        }
    }
}

/** Controls row with play/pause, speed, subtitle, etc. */
@Composable
fun ControlsRow(
    engine: PlaybackEngine,
    controlsVisibility: MutableState<Boolean>
) {
    Row(
        modifier = Modifier
            .wrapContentWeight(1f)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        // Previous track
        PlayerControlIcon(
            icon = Icons.FewArrowBack,
            contentDescription = "Previous",
            onClick = { /* previous track */ }
        )

        // Play/Pause
        val isPlaying by engine.isPlaying.collectAsState()
        PlayerControlIcon(
            icon = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = if (isPlaying) "Pause" else "Play",
            onClick = { engine.togglePlayPause() }
        )

        // Next track
        PlayerControlIcon(
            icon = Icons.FewArrowForward,
            contentDescription = "Next",
            onClick = { /* next track */ }
        )
    }
}

/** Individual player control icon button. */
@Composable
fun PlayerControlIcon(
    icon: androidx.compose.ui.graphics.Bitmap,
    contentDescription: String,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit
        )
    }
}

/** Bottom progress bar with seek bar and time display. */
@Composable
fun BottomProgressBar(engine: PlaybackEngine) {
    var seekPosition by remember { mutableStateOf(0L) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
            .background(SurfaceDefaults.backgroundColor),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Seek bar
        Slider(
            value = if (engine.duration > 0) engine.currentPosition.toFloat() / engine.duration.toFloat() else 0f,
            onValueChange = { pos -> engine.seekTo(pos.toLong() * engine.duration) },
            modifier = Modifier.weight(1f)
        )

        // Time display row
        Row(
            modifier = Modifier
                .width((engine.duration ?: 0).dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatPosition(engine.currentPosition),
                style = MaterialTheme.typography.body1
            )
            Text(
                text = formatPosition(engine.duration),
                style = MaterialTheme.typography.body1.copy(alpha = 0.7f)
            )
        }
    }
}

/** Format milliseconds to MM:SS format. */
private fun formatPosition(ms: Long): String {
    val minutes = ms / 60000
    val seconds = (ms % 60000) / 1000
    return "$minutes:${seconds}%2d".format(seconds)
}

/**
 * Auto-hide controls after inactivity.
 * Returns a state that automatically hides after timeout.
 */
private fun autoHideControls(
    controlsVisible: MutableState<Boolean>,
    timeoutMs: Long
): State<Boolean> {
    // Reset timer on user interaction
    // In a full implementation, this would use a lifecycle-aware coroutine
    return controlsVisible
}

/**
 * Alpha background that dims when controls are hidden.
 */
@Composable
fun AlphaBackground(visible: Boolean) = androidx.compose.ui.graphics.drawable.Background(
    if (visible) {
        androidx.compose.ui.graphics.Color(0.8f) // Mostly opaque
    } else {
        androidx.compose.ui.graphics.Color(0.3f) // Dimmed
    }
)