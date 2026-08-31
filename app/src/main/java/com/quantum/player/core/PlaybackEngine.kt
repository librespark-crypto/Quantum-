package com.quantum.player.core

import android.view.SurfaceView
import android.view.TextureView
import com.quantum.player.model.MediaItem
import kotlinx.coroutines.flow.Flow

/**
 * Interface defining the playback engine abstraction.
 *
 * This allows switching between Media3/ExoPlayer, libmpv, or yt-dlp backends
 * without rewriting the UI. Nothing above this interface may reference a
 * concrete backend type (`androidx.media3.*`, `libmpv`): the layers are
 *
 *     UI -> ViewModel -> PlaybackEngine -> concrete backend
 *
 * Video output is attached through plain `android.view` types rather than a
 * player view, which keeps the abstraction honest for non-Media3 backends.
 */
interface PlaybackEngine {

    /** Start playback of a media item. */
    suspend fun play(mediaItem: MediaItem): PlaybackSession

    /** Pause playback. */
    suspend fun pause()

    /** Resume playback. */
    suspend fun resume()

    /** Stop playback and release resources. */
    suspend fun stop()

    /** Current playback state. */
    val stateFlow: Flow<PlaybackState>

    /** Current playback position in milliseconds, observed. */
    val positionFlow: Flow<Long>

    /**
     * Structured error for the current/last failure, or null when playback is
     * healthy. Carries a user facing message plus a suggested solution so the
     * UI never has to guess what went wrong.
     */
    val errorFlow: Flow<com.quantum.player.error.PlaybackError.PlaybackException?>

    /** Get current position in milliseconds. */
    val currentPosition: Long

    /** Get duration in milliseconds, or -1 if unknown. */
    val duration: Long

    /** Buffered position in milliseconds, or -1 if unknown. */
    val bufferedPosition: Long

    /** Seek to a position in milliseconds. */
    suspend fun seekTo(positionMs: Long)

    /** Relative seek; negative values seek backwards. */
    suspend fun seekBy(deltaMs: Long)

    /** Set playback speed (0.25x to 4.0x). */
    suspend fun setPlaybackSpeed(speed: Float)

    /** Get current playback speed. */
    val playbackSpeed: Float

    /** Toggle play/pause. */
    suspend fun togglePlayPause()

    /** Get current timestamp. */
    val currentTimeMs: Long

    /** Check if currently playing. */
    val isPlaying: Boolean

    /** Check if playback is stopped. */
    val isStopped: Boolean

    /** Check if playback is buffering. */
    val isBuffering: Boolean

    /** Get error information if any. */
    val error: String?

    /** Retry the media item that last failed. Returns false if there is nothing to retry. */
    suspend fun retry(): Boolean

    /** Get current audio track index. */
    val currentAudioTrack: Int

    /** Set audio track index. */
    suspend fun setAudioTrack(index: Int)

    /** Get available audio tracks. */
    val availableAudioTracks: List<AudioTrackInfo>

    /** Get current subtitle track index. */
    val currentSubtitleTrack: Int

    /** Set subtitle track index. */
    suspend fun setSubtitleTrack(index: Int)

    /** Get available subtitle tracks. */
    val availableSubtitleTracks: List<SubtitleTrackInfo>

    /** Get available video tracks. */
    val availableVideoTracks: List<VideoTrackInfo>

    /** Set video track index. */
    suspend fun setVideoTrack(index: Int)

    /** Toggle subtitle on/off. */
    suspend fun toggleSubtitle()

    /** Get video width. */
    val videoWidth: Int

    /** Get video height. */
    val videoHeight: Int

    /** Get video aspect ratio. */
    val videoAspectRatio: Float

    /** Check if video is valid. */
    val isVideoValid: Boolean

    /** Request a screenshot of the current frame as PNG bytes. */
    suspend fun captureScreenshot(): ByteArray

    /** Get playback resume position. */
    val resumePosition: Long

    /** Set the position playback should start from. */
    suspend fun setResumePosition(position: Long)

    /** Get supported codecs/decoders information. */
    val decoderInfo: DecoderInfo

    /**
     * Attach the surface that video frames are rendered to. Pass null to detach.
     * Exactly one of the two may be attached at a time.
     */
    fun setVideoSurfaceView(surfaceView: SurfaceView?)

    /** Attach a [TextureView] output; required for [captureScreenshot]. */
    fun setVideoTextureView(textureView: TextureView?)

    /**
     * The text of the subtitle cues that are active right now, in rendering
     * order. Published as plain strings so the UI renders them with the app's
     * own subtitle styling, and so a non-Media3 backend can publish the same
     * shape.
     *
     * Media3 1.3.x exposes no per-cue timestamps to the app (`Cue` carries no
     * timing and there is no public `addTextOutput`), so cue windows are not
     * available here - only the currently visible text is.
     */
    val cuesFlow: Flow<List<String>>

    /** Release engine resources. */
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
    val bitrate: Long?,
    /** True for an audio-description (accessibility) rendition of the programme. */
    val isAudioDescription: Boolean = false
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
