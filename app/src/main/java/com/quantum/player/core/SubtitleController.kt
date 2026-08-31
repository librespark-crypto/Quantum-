package com.quantum.player.core

import kotlinx.coroutines.flow.Flow

/**
 * Controller for managing subtitle tracks and display.
 * Handles subtitle loading, track selection, styling, and timing.
 */
interface SubtitleController {

    /** Get current subtitle track index */
    val currentTrackIndex: Int

    /** Get available subtitle tracks */
    val availableTracks: Flow<List<SubtitleTrackInfo>>

    /** Get subtitle display text */
    val subtitleText: Flow<String?>

    /** Get subtitle timing delay */
    val subtitleDelay: Long

    /** Set subtitle delay */
    suspend fun setDelay(delayMs: Long)

    /** Toggle subtitle on/off */
    suspend fun toggleSubtitle()

    /** Select subtitle track by index */
    suspend fun selectTrack(index: Int)

    /** Get subtitle styling options */
    val subtitleStyle: SubtitleStyle

    /** Set subtitle styling */
    suspend fun setStyle(style: SubtitleStyle)

    /** Refresh subtitle display */
    fun refresh()
}

/**
 * Subtitle styling options.
 */
data class SubtitleStyle(
    val fontSize: Float = 20f,
    val fontColor: String = "#FFFFFF",
    val backgroundColor: String = "#000000",
    val backgroundOpacity: Float = 0.5f,
    val fontFamily: String = "Roboto",
    val bold: Boolean = false,
    val italic: Boolean = false
)