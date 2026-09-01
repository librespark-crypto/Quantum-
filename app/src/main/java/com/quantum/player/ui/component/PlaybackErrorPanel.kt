package com.quantum.player.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Error panel shown when playback fails. Shows what happened, what the user can
 * do about it, and a retry affordance - a decode failure must never just crash.
 * Reached directly over playback; there is no settings screen behind it.
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
            .background(Color(0xB3000000)),
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
            Text(text = userMessage, color = Color(0xFFB0B0B0),
                style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            Text(text = solution, color = Color(0xFF8A93A6),
                style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Dismiss",
                    color = Color(0xFFB0B0B0),
                    modifier = Modifier.clickable { onDismiss() }.padding(8.dp)
                )
                if (retryable) {
                    Text(
                        text = "Retry",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onRetry() }.padding(8.dp)
                    )
                }
            }
        }
    }
}
