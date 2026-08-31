package com.quantum.player.core

import com.quantum.player.model.MediaItem
import kotlinx.coroutines.flow.Flow

/**
 * Represents a playback session with a media item.
 * Encapsulates the state and control of a single playback instance.
 */
data class PlaybackSessionData(
    val id: String = java.util.UUID.randomUUID().toString(),
    val mediaItem: MediaItem,
    var currentPositionMs: Long = 0,
    var state: PlaybackState = PlaybackState.Idle,
    var isPaused: Boolean = false,
    var playbackSpeed: Float = 1.0f,
    var timestampMs: Long = 0,
    var error: String? = null,
    var subtitleIndex: Int = -1,
    var audioTrackIndex: Int = -1
)