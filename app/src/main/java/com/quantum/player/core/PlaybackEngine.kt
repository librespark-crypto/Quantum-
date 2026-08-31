package com.quantum.player.core

import com.quantum.player.model.MediaItem
import com.quantum.player.model.PlaybackState
import kotlinx.coroutines.flow.Flow

/**
 * Interface defining the playback engine abstraction.
 * This allows switching between Media3/ExoPlayer, libmpv, or yt-dlp backends
 * without rewriting the UI.
 */
public interface PlaybackEngine {

    /** Start playback of a media item */
    suspend fun play(mediaItem: MediaItem): PlaybackSession

    /** Pause playback */
    suspend fun pause()

    /** Resume playback */
    suspend fun resume()

    /** Stop playback and release resources */
    suspend fun stop()

    /** Get current playback state as a Flow */
    val stateFlow: Flow<PlaybackState>

    /** Get current position in milliseconds */
    val currentPosition: Long

    /** Get duration in milliseconds, or -1 if unknown */
    val duration: Long

    /** Seek to a position in milliseconds */
    suspend fun seekTo(positionMs: Long)

    /** Set playback speed (0.25x to 4.0x) */
    suspend fun setPlaybackSpeed(speed: Float)

    /** Get current playback speed */
    val playbackSpeed: Float

    /** Toggle play/pause */
    suspend fun togglePlayPause()

    /** Get current timestamp */
    val currentTimeMs: Long

    /** Check if currently playing */
    val isPlaying: Boolean

    /** Check if playback is stopped */
    val isStopped: Boolean

    /** Check if playback is buffering */
    val isBuffering: Boolean

    /** Get error information if any */
    val error: String?

    /** Get current audio track index */
    val currentAudioTrack: Int

    /** Set audio track index */
    suspend fun setAudioTrack(index: Int)

    /** Get available audio tracks */
    val availableAudioTracks: List<AudioTrackInfo>

    /** Get current subtitle track index */
    val currentSubtitleTrack: Int

    /** Set subtitle track index */
    suspend fun setSubtitleTrack(index: Int)

    /** Get available subtitle tracks */
    val availableSubtitleTracks: List<SubtitleTrackInfo>

    /** Toggle subtitle on/off */
    suspend fun toggleSubtitle()

    /** Get video width */
    val videoWidth: Int

    /** Get video height */
    val videoHeight: Int

    /** Get video aspect ratio */
    val videoAspectRatio: Float

    /** Check if video is valid */
    val isVideoValid: Boolean

    /** Request screenshot */
    suspend fun captureScreenshot(): ByteArray

    /** Get playback resume position */
    val resumePosition: Long

    /** Set playback resume position */
    suspend fun setResumePosition(position: Long)

    /** Get supported codecs/decoders information */
    val decoderInfo: DecoderInfo

    /** Release engine resources */
    suspend fun release()
}

/**
 * Data class for decoder capability information.
 */
data class DecoderInfo(
    val videoCodec: String = "Unknown",
    val audioCodec: String = "Unknown",
    val hardwareVideoDecoding: Boolean = false,
    val hardwareAudioDecoding: Boolean = false,
    val supportedVideoCodecs: List<String> = emptyList(),
    val supportedAudioCodecs: List<String> = emptyList(),
    val hdrSupport: HDRSupport = HDRSupport.Unknown,
    val resolutionLimit: Int = 0,
    val tenBitSupport: Boolean = false
)

/**
 * HDR support levels.
 */
enum class HDRSupport {
    Unknown,
    Supported,
    HardwareOnly,
    SoftwareOnly
}

/**
 * Audio track information.
 */
data class AudioTrackInfo(
    val index: Int,
    val name: String,
    val codec: String,
    val channels: Int,
    val sampleRate: Int,
    val bitrate: Long?
)

/**
 * Subtitle track information.
 */
data class SubtitleTrackInfo(
    val index: Int,
    val language: String,
    val name: String,
    val format: String
)

/**
 * Playback state representation.
 */
enum class PlaybackState {
    Idle,
    Preparing,
    Playing,
    Paused,
    Buffering,
    Stopped,
    Error,
    Ended
}

/**
 * Media item representation.
 */
data class MediaItem(
    val id: String,
    val uri: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val artworkUri: String?,
    val durationMs: Long,
    val container: String?,
    val videoCodec: String?,
    val audioCodec: String?,
    val subtitles: List<SubtitleInfo>? = null,
    val metadata: Map<String, Any?> = emptyMap()
)

/**
 * Subtitle information.
 */
data class SubtitleInfo(
    val uri: String,
    val language: String,
    val format: String,
    val defaultTrack: Boolean = false
)