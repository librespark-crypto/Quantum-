package com.quantum.player.core

import com.quantum.player.model.MediaItem

/**
 * Represents a playback session with a media item.
 * Encapsulates the state and control of a single playback instance.
 *
 * There used to be two divergent types for this concept (`PlaybackSession`
 * declared at the bottom of PlaybackManager.kt and `PlaybackSessionData` here).
 * They were unified into this single declaration.
 */
data class PlaybackSession(
    val id: String = java.util.UUID.randomUUID().toString(),
    val mediaItem: MediaItem,
    val engine: PlaybackEngine? = null,
    val startPositionMs: Long = 0,
    var currentPositionMs: Long = startPositionMs,
    var state: PlaybackState = PlaybackState.Idle,
    var isPaused: Boolean = false,
    var playbackSpeed: Float = 1.0f,
    var timestampMs: Long = System.currentTimeMillis(),
    var error: String? = null,
    var subtitleIndex: Int = -1,
    var audioTrackIndex: Int = -1
) {
    /** Position that should be persisted so playback can resume later. */
    val resumePositionMs: Long get() = currentPositionMs
}
