package com.quantum.player.core

import android.content.Context
import android.media.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.MediaSource
import androidx.media3.exoplayer.HlsMediaSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.ConcatenatingMediaSource
import androidx.media3.exoplayer.source.MediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.Selector
import androidx.media3.exoplayer.upstream.DataSource
import androidx.media3.exoplayer.upstream.DefaultDataSourceFactory
import androidx.media3.exoplayer.upstream.DefaultHttpDataSourceFactory
import androidx.media3.exoplayer.upstream.HttpDataSource
import androidx.media3.common.util.UnstableApi
import com.quantum.player.model.MediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.stateFlow
import java.util.Locale

/**
 * Media3/ExoPlayer implementation of the PlaybackEngine interface.
 * Preferred for standards-based Android playback (MP4, HLS, DASH, supported codecs).
 */
class PlaybackManager(context: Context) : PlaybackEngine {

    private var player: ExoPlayer? = null
    private val _stateFlow = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    private val _position = MutableStateFlow<Long>(0)
    private var mediaItem: MediaItem? = null

    val stateFlow: Flow<PlaybackState> = _stateFlow.asFlow()
    val currentPosition: Long = _position.value
    val duration: Long = player?.duration ?: -1
    val playbackSpeed: Float = player?.playbackSpeed ?: 1.0f
    val currentAudioTrack: Int = player?.currentAudioTrackIndex ?: -1
    val currentSubtitleTrack: Int = player?.currentSubtitleTrackIndex ?: -1
    val videoWidth: Int = player?.videoWidth ?: 0
    val videoHeight: Int = player?.videoHeight ?: 0
    val videoAspectRatio: Float = if (player?.videoWidth != 0 && player?.videoHeight != 0) {
        player?.videoWidth.toFloat() / player?.videoHeight.toFloat() ?: 1f
    } else 1f
    val isVideoValid: Boolean = player != null && player?.videoWidth != null && player?.videoHeight != 0
    val isPlaying: Boolean = player?.isPlaying ?: false
    val isStopped: Boolean = player?.isStopped ?: false
    val isBuffering: Boolean = player?.isPlayingUtilizingBandwidth ?: false
    val error: String? = player?.error?.message
    val currentTimeMs: Long = player?.currentPosition ?: 0
    val resumePosition: Long = player?.currentPosition ?: 0
    val availableAudioTracks: List<AudioTrackInfo> = emptyList()
    val availableSubtitleTracks: List<SubtitleTrackInfo> = emptyList()

    init {
        player = ExoPlayer.Builder(context).build()
        player?.addListener(object : ExoPlayer.Listener {
            override fun onStateChanged(
                player: ExoPlayer,
                playbackState: ExoPlayer.PlaybackState,
                reason: Any?
            ) {
                when (playbackState) {
                    ExoPlayer.PlaybackState.IDLE -> _stateFlow.value = PlaybackState.Idle
                    ExoPlayer.PlaybackState.READY -> _stateFlow.value = PlaybackState.Playing
                    ExoPlayer.PlaybackState.BUFFERING -> _stateFlow.value = PlaybackState.Buffering
                    ExoPlayer.PlaybackState.PAUSED -> _stateFlow.value = PlaybackState.Paused
                }
            }

            override fun onPlaybackError(player: ExoPlayer, error: Exception) {
                _stateFlow.value = PlaybackState.Error
            }

            override fun onIsPlayingChanged(player: ExoPlayer, isPlaying: Boolean) {
                _stateFlow.value = if (isPlaying) PlaybackState.Playing else PlaybackState.Paused
            }

            override fun onVideoSizeChanged(
                player: ExoPlayer,
                width: Int,
                height: Int,
                unrotateWidth: Int,
                unrotateHeight: Int
            ) {
                videoWidth = width
                videoHeight = height
                videoAspectRatio = if (width != 0 && height != 0) width.toFloat() / height.toFloat() else 1f
                _stateFlow.value = _stateFlow.value
            }

            override fun onTimelineChanged(
                player: ExoPlayer,
                timeline: Any?,
                reason: Int
            ) {
                // Handle timeline changes
            }
        })

        player?.setTrackSelector(DefaultTrackSelector())
    }

    /**
     * Play a media item.
     */
    override suspend fun play(mediaItem: MediaItem): PlaybackSession {
        this.mediaItem = mediaItem
        try {
            val media3Item = MediaItem.fromUri(mediaItem.uri)
            val mediaSource = createMediaSource(media3Item)
            player?.setMediaSource(mediaSource)
            player?.prepare()
            return PlaybackSession(this, mediaItem)
        } catch (e: Exception) {
            _stateFlow.value = PlaybackState.Error
            throw RuntimeException("Failed to play media: ${e.message}", e)
        }
    }

    /**
     * Create a MediaSource based on the media item URI.
     */
    private fun createMediaSource(mediaItem: Media.MediaItem): MediaSource {
        val dataSourceFactory = DefaultHttpDataSourceFactory(
            "QuantumPlayer/${context.packageName}"
        ).apply { setUserAgentExemptionPattern(mediaItem.uri) }

        return when {
            mediaItem.uri.startsWith("http://") || mediaItem.uri.startsWith("https://") ->
                ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)

            mediaItem.uri.endsWith(".m3u8", ignoreCase = true) ->
                HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)

            mediaItem.uri.endsWith(".mpd", ignoreCase = true) ->
                DashMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)

            else -> ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
        }
    }

    /**
     * Pause playback.
     */
    override suspend fun pause() {
        player?.pause()
    }

    /**
     * Resume playback.
     */
    override suspend fun resume() {
        player?.play()
    }

    /**
     * Stop playback and release resources.
     */
    override suspend fun stop() {
        player?.stop()
        player?.release()
        player = null
        _stateFlow.value = PlaybackState.Idle
        _position.value = 0
    }

    /**
     * Seek to a position in milliseconds.
     */
    override suspend fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs)
    }

    /**
     * Set playback speed (0.25x to 4.0x).
     */
    override suspend fun setPlaybackSpeed(speed: Float) {
        val clampedSpeed = speed.coerceIn(0.25f, 4.0f)
        player?.setPlaybackSpeed(clampedSpeed)
    }

    /**
     * Toggle play/pause.
     */
    override suspend fun togglePlayPause() {
        if (isPlaying) {
            pause()
        } else {
            resume()
        }
    }

    /**
     * Set audio track index.
     */
    override suspend fun setAudioTrack(index: Int) {
        player?.setCurrentAudioIndex(index)
    }

    /**
     * Toggle subtitle on/off.
     */
    override suspend fun toggleSubtitle() {
        // Toggle subtitle visibility
        val current = player?.currentSubtitleTrackIndex ?: -1
        if (current >= 0) {
            player?.setSubtitleEnabled(!player?.isSubtitleEnabled ?: false)
        }
    }

    /**
     * Capture a screenshot of the current video frame.
     */
    override suspend fun captureScreenshot(): ByteArray {
        // Return current frame as bitmap bytes
        return player?.takeVideoSnapshot()?.let { snapshot ->
            snapshot.getBitmap().compress(
                android.graphics.Bitmap.CompressFormat.PNG,
                100,
                /* output */ null
            )?.toByteArray() ?: emptyByteArray()
        } else emptyByteArray()
    }

    /**
     * Set resume position.
     */
    override suspend fun setResumePosition(position: Long) {
        // Store position in Room database
    }

    /**
     * Set subtitle track index.
     */
    override suspend fun setSubtitleTrack(index: Int) {
        player?.setSubtitle(index)
    }

    /**
     * Set playback speed with coroutine support.
     */
    fun setSpeed(speed: Float) = player?.setPlaybackSpeed(speed) ?: 1.0f

    /**
     * Internal position tracking update.
     */
    fun updatePosition(positionMs: Long) {
        _position.value = positionMs
    }

    /**
     * Release player resources.
     */
    override suspend fun release() {
        player?.stop()
        player?.release()
        player = null
        _stateFlow.value = PlaybackState.Idle
        _position.value = 0
    }
}

/**
 * Playback session data class.
 */
data class PlaybackSession(
    val engine: PlaybackEngine,
    val mediaItem: MediaItem,
    val startPositionMs: Long = 0
) {
    var currentPositionMs: Long = startPositionMs
    var isPaused: Boolean = false
}