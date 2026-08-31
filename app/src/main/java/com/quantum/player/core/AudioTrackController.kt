package com.quantum.player.core

import kotlinx.coroutines.flow.Flow

/**
 * Controller for managing audio tracks.
 * Handles audio track selection, language preference, and audio properties.
 */
interface AudioTrackController {

    /** Get current audio track index */
    val currentTrackIndex: Int

    /** Get available audio tracks */
    val availableTracks: Flow<List<AudioTrackInfo>>

    /** Select audio track by index */
    suspend fun selectTrack(index: Int)

    /** Get audio properties */
    val sampleRate: Int

    /** Get audio channels */
    val channelCount: Int

    /** Get audio codec */
    val codec: String?

    /** Toggle audio description/metadata */
    suspend fun toggleAudioDescription()

    /** Refresh audio track list */
    fun refresh()
}