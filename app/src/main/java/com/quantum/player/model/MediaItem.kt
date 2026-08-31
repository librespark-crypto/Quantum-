package com.quantum.player.model

import java.util.UUID

/**
 * Media item representing a video or audio file/stream.
 */
data class MediaItem(
    val id: String = UUID.randomUUID().toString(),
    val uri: String = "",
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val artworkUri: String? = null,
    val durationMs: Long = 0,
    val container: String? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val subtitles: List<SubtitleInfo>? = null,
    val metadata: Map<String, Any> = emptyMap(),
    val format: String? = null,
    val sizeBytes: Long = 0
)

/**
 * Subtitle information for external subtitle files.
 */
data class SubtitleInfo(
    val uri: String = "",
    val language: String = "unknown",
    val format: String = "srt",
    val defaultTrack: Boolean = false,
    val embedded: Boolean = false
)

/**
 * Playback statistics for tracking.
 */
data class PlaybackStatistics(
    val mediaItemId: String,
    val playCount: Int = 0,
    val totalPlayTimeMs: Long = 0,
    val averageRating: Float = 0f,
    val lastPlayed: Long = 0,
    val resumePositionMs: Long = 0
)

/**
 * Watch state for tracking watched/unwatched status.
 */
enum class WatchState {
    Watched,
    Unwatched,
    InProgress,
    Paused
}

/**
 * Per-video settings stored persistently.
 */
data class VideoSettings(
    val mediaItemId: String,
    val preferredSpeed: Float = 1.0f,
    val resumePositionMs: Long = 0,
    val selectedAudioTrack: Int = -1,
    val selectedSubtitleTrack: Int = -1,
    val subtitleDelayMs: Long = 0,
    val subtitleSize: Float = 20f,
    val subtitlePosition: Pair<Float, Float> = Pair(0f, 0f),
    val aspectRatio: AspectRatioMode = AspectRatioMode.Auto,
    val skipSilence: Boolean = false,
    val hdrMode: String? = null,
    val metadata: Map<String, Any> = emptyMap()
)