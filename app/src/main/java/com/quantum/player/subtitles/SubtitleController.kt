package com.quantum.player.subtitles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quantum.player.core.SubtitleStyle

/**
 * Subtitle rendering for the Compose player.
 *
 * The player publishes decoded cues through `PlaybackEngine.cuesFlow`; this file
 * turns them into styled text using [SubtitleStyle].
 *
 * The previous `ComposeSubtitleController` in this file claimed to implement
 * `com.quantum.player.core.SubtitleController` but overrode members that the
 * interface does not declare (`getAvailableTracks`, `getDelay`, `getStyle`) and
 * imported classes that do not exist (`androidx.compose.foundation.text.SelectableText`,
 * `androidx.compose.foundation.ui.isTextFieldEditor`,
 * `androidx.compose.ui.draw.clip.*`, `androidx.compose.ui.text.annotation.*`).
 * `core.SubtitleController` remains the backend-agnostic contract; it is
 * implemented by a backend, not by a Composable.
 */
@Composable
fun SubtitleOverlay(
    cues: List<String>,
    style: SubtitleStyle = SubtitleStyle(),
    bottomPaddingDp: Float = 96f,
    modifier: Modifier = Modifier
) {
    if (cues.isEmpty()) return
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = 24.dp,
                    end = 24.dp,
                    bottom = bottomPaddingDp.dp
                ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            cues.forEach { cue ->
                Text(
                    text = cue,
                    style = style.toTextStyle(),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .background(style.backgroundColorValue().copy(alpha = style.backgroundOpacity))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

/** Build the [TextStyle] described by [SubtitleStyle]. */
fun SubtitleStyle.toTextStyle(): TextStyle = TextStyle(
    color = parseColor(fontColor, Color.White),
    fontSize = fontSize.sp,
    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
    fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal
)

/** Parse `#RRGGBB` / `#AARRGGBB`, falling back to [fallback] on bad input. */
fun parseColor(value: String, fallback: Color): Color = runCatching {
    Color(android.graphics.Color.parseColor(if (value.startsWith("#")) value else "#$value"))
}.getOrDefault(fallback)

/** Background colour for a subtitle box. */
fun SubtitleStyle.backgroundColorValue(): Color = parseColor(backgroundColor, Color.Black)
