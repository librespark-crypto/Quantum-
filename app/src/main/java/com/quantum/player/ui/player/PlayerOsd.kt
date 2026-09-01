package com.quantum.player.ui.player

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * On-screen-display feedback for surface gestures (the dim MX-Player style
 * center cards). Pure presentation: the screen layer owns the values.
 */
@Composable
fun GestureOsd(
    state: OsdState,
    modifier: Modifier = Modifier
) {
    if (state is OsdState.Hidden) return
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black.copy(alpha = 0.72f))
                .padding(horizontal = 22.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            when (state) {
                is OsdState.Brightness -> {
                    Icon(Icons.Filled.Brightness6, contentDescription = null, tint = Color.White)
                    OsdSlider(fraction = state.fraction)
                    Text("Brightness ${(state.fraction * 100).toInt()}%",
                        color = Color.White, style = MaterialTheme.typography.labelMedium)
                }

                is OsdState.Volume -> {
                    Icon(Icons.Filled.VolumeUp, contentDescription = null, tint = Color.White)
                    OsdSlider(fraction = state.fraction / state.maxFraction)
                    val percent = (state.fraction * 100).toInt()
                    Text(
                        if (state.fraction > 1f) "Volume boost $percent%" else "Volume $percent%",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium
                    )
                }

                is OsdState.Scrub -> {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(
                            if (state.deltaMs < 0) Icons.Filled.FastRewind else Icons.Filled.FastForward,
                            contentDescription = null,
                            tint = Color.White
                        )
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(formatTime(state.targetMs),
                                color = Color.White,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold)
                            Text(
                                (if (state.deltaMs >= 0) "+" else "") +
                                    formatTime(kotlin.math.abs(state.deltaMs)),
                                color = Color.White.copy(alpha = 0.8f),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }

                OsdState.Hidden -> Unit
            }
        }
    }
}

@Composable
private fun OsdSlider(fraction: Float) {
    LinearProgressIndicator(
        progress = { fraction.coerceIn(0f, 1f) },
        modifier = Modifier.width(140.dp),
        color = MaterialTheme.colorScheme.primary,
        trackColor = Color.White.copy(alpha = 0.3f)
    )
}

/** Sealed OSD state; [Hidden] shows nothing. */
sealed class OsdState {
    data object Hidden : OsdState()
    data class Brightness(val fraction: Float) : OsdState()
    data class Volume(val fraction: Float, val maxFraction: Float = 2f) : OsdState()
    data class Scrub(val targetMs: Long, val deltaMs: Long) : OsdState()
}

/**
 * Full-screen overlay shown when controls are locked (🔒). Every touch on the
 * surface is ignored; only the floating unlock button is live.
 */
@Composable
fun LockedOverlay(onUnlock: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(16.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(4.dp)
        ) {
            androidx.compose.material3.IconButton(onClick = onUnlock) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = "Unlock controls",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}

/** Small floating unlock pill shown briefly after lock, before the overlay settles. */
@Composable
fun UnlockHint(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.LockOpen, contentDescription = null, tint = Color.White,
                modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Controls unlocked", color = Color.White,
                style = MaterialTheme.typography.labelMedium)
        }
    }
}

internal fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    else String.format(java.util.Locale.US, "%d:%02d", minutes, seconds)
}
