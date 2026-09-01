package com.quantum.player.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.quantum.player.core.AudioEffectsController
import com.quantum.player.core.AudioTrackInfo
import com.quantum.player.core.SubtitleTrackInfo

/**
 * All on-screen configuration surfaces for the player. There is NO settings
 * activity: every option the app offers is reachable from one of these sheets,
 * opened directly from the HUD chips / app bar buttons.
 */

// ----------------------------------------------------------------------
// Audio track selector (♪)
// ----------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioTrackSheet(
    tracks: List<AudioTrackInfo>,
    selectedIndex: Int,
    muted: Boolean,
    onSelect: (Int) -> Unit,
    onMute: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SHEET_COLOR) {
        SheetHeader("Audio track")
        SheetRow(
            label = "Mute",
            selected = muted,
            onClick = onMute
        )
        if (tracks.isEmpty()) {
            SheetEmpty("No audio tracks found")
        } else {
            LazyColumn(modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()) {
                items(tracks, key = { it.index }) { track ->
                    SheetRow(
                        label = buildString {
                            append(track.name)
                            if (track.channels > 0) append(" · ${track.channels}ch")
                            if (track.sampleRate > 0) append(" · ${track.sampleRate / 1000}kHz")
                        },
                        selected = !muted && track.index == selectedIndex,
                        onClick = { onSelect(track.index) }
                    )
                }
            }
        }
    }
}

// ----------------------------------------------------------------------
// Subtitle / CC selector
// ----------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubtitleSheet(
    tracks: List<SubtitleTrackInfo>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onPickExternalFile: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SHEET_COLOR) {
        SheetHeader("Subtitles")
        SheetRow(label = "Off", selected = selectedIndex < 0, onClick = { onSelect(-1) })
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(tracks, key = { "sub_${it.index}" }) { track ->
                SheetRow(
                    label = buildString {
                        append(track.name)
                        if (track.language.isNotBlank() && track.language != "unknown") {
                            append(" · ${track.language}")
                        }
                    },
                    selected = track.index == selectedIndex,
                    onClick = { onSelect(track.index) }
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = onPickExternalFile) {
                Icon(Icons.Filled.FolderOpen, contentDescription = null,
                    modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Open .srt / .vtt file")
            }
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

// ----------------------------------------------------------------------
// Equalizer / tuner (🎛️)
// ----------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerSheet(
    bands: List<AudioEffectsController.EqBand>,
    bassStrength: Int,
    available: Boolean,
    onBandChange: (Int, Int) -> Unit,
    onBassChange: (Int) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SHEET_COLOR) {
        SheetHeader("Audio tuner")
        if (!available) {
            SheetEmpty("Equalizer unavailable on this device")
            return@ModalBottomSheet
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
        ) {
            // Volume boost is applied in the audio pipeline; expose it here too.
            Text("Equalizer", style = MaterialTheme.typography.titleSmall,
                color = Color.White, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            bands.forEach { band ->
                EqBandRow(
                    band = band,
                    onChange = { levelMb -> onBandChange(band.index, levelMb) }
                )
            }
            Spacer(Modifier.height(12.dp))
            Text("Bass boost", style = MaterialTheme.typography.titleSmall,
                color = Color.White, fontWeight = FontWeight.SemiBold)
            Slider(
                value = bassStrength.toFloat(),
                onValueChange = { onBassChange(it.toInt()) },
                valueRange = 0f..1000f,
                colors = sliderColors()
            )
            Row(modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onReset) { Text("Reset") }
                TextButton(onClick = onDismiss) { Text("Done") }
            }
        }
    }
}

@Composable
private fun EqBandRow(
    band: AudioEffectsController.EqBand,
    onChange: (Int) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()) {
        Text(
            text = if (band.centerFreqHz >= 1000) "${band.centerFreqHz / 1000}k"
            else "${band.centerFreqHz}",
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(44.dp)
        )
        Slider(
            value = band.fraction,
            onValueChange = { fraction ->
                val range = band.maxLevelMb - band.minLevelMb
                onChange(band.minLevelMb + (fraction * range).toInt())
            },
            valueRange = 0f..1f,
            colors = sliderColors(),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
        )
        Text(
            text = "${band.levelMb / 100}dB",
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.width(48.dp)
        )
    }
}

// ----------------------------------------------------------------------
// Playback speed slider (long-press on the 1X chip)
// ----------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedSheet(
    speed: Float,
    onSelect: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SHEET_COLOR) {
        SheetHeader("Playback speed")
        var value by remember { mutableFloatStateOf(speed) }
        Column(modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .navigationBarsPadding()) {
            Text(formatSpeed(value), color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp))
            Slider(
                value = value,
                onValueChange = { value = it },
                onValueChangeFinished = { onSelect(value) },
                valueRange = 0.25f..4f,
                colors = sliderColors()
            )
            Row(modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.5f, 1f, 1.25f, 1.5f, 2f).forEach { preset ->
                    TextButton(onClick = {
                        value = preset
                        onSelect(preset)
                    }) { Text(formatSpeed(preset)) }
                }
            }
        }
    }
}

// ----------------------------------------------------------------------
// Media info sheet (decoder / resolution / tracks - direct, not settings)
// ----------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaInfoSheet(
    lines: List<Pair<String, String>>,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SHEET_COLOR) {
        SheetHeader("Media info")
        Column(modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .navigationBarsPadding()) {
            lines.forEach { (label, value) ->
                Row(modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)) {
                    Text(label, color = Color.White.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.width(140.dp))
                    Text(value, color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ----------------------------------------------------------------------
// Shared sheet bits
// ----------------------------------------------------------------------

@Composable
private fun SheetHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = Color.White,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp)
    )
}

@Composable
private fun SheetRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.White,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f))
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun SheetEmpty(message: String) {
    Box(modifier = Modifier
        .fillMaxWidth()
        .padding(24.dp), contentAlignment = Alignment.Center) {
        Text(message, color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun sliderColors() = SliderDefaults.colors(
    thumbColor = MaterialTheme.colorScheme.primary,
    activeTrackColor = MaterialTheme.colorScheme.primary,
    inactiveTrackColor = Color.White.copy(alpha = 0.25f)
)

private val SHEET_COLOR = Color(0xFF15181F)
