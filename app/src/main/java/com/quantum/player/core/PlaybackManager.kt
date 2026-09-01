package com.quantum.player.core

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.view.SurfaceView
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem as Media3MediaItem
import androidx.media3.common.MediaItem.SubtitleConfiguration
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException as Media3PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.quantum.player.error.PlaybackError
import com.quantum.player.model.MediaItem
import com.quantum.player.model.SubtitleInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Media3/ExoPlayer implementation of the [PlaybackEngine] interface.
 * Preferred for standards-based Android playback (MP4, HLS, DASH, supported codecs).
 *
 * Every method here drives a real [ExoPlayer]; the UI state exposed through
 * [stateFlow], [positionFlow] and [errorFlow] is derived from the player's own
 * listener callbacks, never from local flags.
 *
 * Threading: [ExoPlayer] is application-main-thread affine. The instance is
 * created on the main thread and every player call is routed through
 * [onPlayer]/`Dispatchers.Main.immediate`, so the engine is safe to drive from
 * any coroutine context.
 */
@OptIn(UnstableApi::class)
class PlaybackManager(context: Context) : PlaybackEngine {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var player: ExoPlayer? = null
    private var positionJob: Job? = null
    private var attachedTextureView: TextureView? = null

    private var mediaItem: MediaItem? = null
    private var pendingMime: String? = null
    private var pendingStartPositionMs: Long = 0L

    // ---- Direct on-screen configuration (no Settings screen) ----
    private val volumeBoostProcessor = VolumeBoostAudioProcessor()
    override val audioEffects = AudioEffectsController()
    private var _decoderMode = DecoderMode.HARDWARE

    /** External subtitle files added during the current session, keyed by URI. */
    private val externalSubtitles = mutableListOf<SubtitleConfiguration>()

    private val _stateFlow = MutableStateFlow(PlaybackState.Idle)
    private val _position = MutableStateFlow(0L)
    private val _error = MutableStateFlow<PlaybackError.PlaybackException?>(null)
    private val _videoWidth = MutableStateFlow(0)
    private val _videoHeight = MutableStateFlow(0)
    private val _pixelWidthHeightRatio = MutableStateFlow(1.0f)
    private val _tracksRevision = MutableStateFlow(0)
    private val _cues = MutableStateFlow<List<String>>(emptyList())

    override val stateFlow: Flow<PlaybackState> = _stateFlow.asStateFlow()
    override val positionFlow: Flow<Long> = _position.asStateFlow()
    override val errorFlow: Flow<PlaybackError.PlaybackException?> = _error.asStateFlow()
    override val cuesFlow: Flow<List<String>> = _cues.asStateFlow()

    // -----------------------------------------------------------------
    // Player construction
    // -----------------------------------------------------------------

    private val dataSourceFactory: DataSource.Factory by lazy {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(Util.getUserAgent(appContext, USER_AGENT))
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(HTTP_TIMEOUT_MS)
            .setReadTimeoutMs(HTTP_TIMEOUT_MS)
        DefaultDataSource.Factory(appContext, httpFactory)
    }

    private val mediaSourceFactory: MediaSource.Factory by lazy {
        DefaultMediaSourceFactory(dataSourceFactory)
    }

    /**
     * Create the player on demand. Released players are recreated rather than
     * reused, so [release] is not a terminal state for the engine instance.
     *
     * The renderers factory honours the current [DecoderMode] (the HUD HW/SW
     * badge): software mode forces non-hardware MediaCodec decoders, and the
     * audio sink chain carries [VolumeBoostAudioProcessor] for the up-to-200%
     * software volume boost.
     */
    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        // The volume-boost processor must live in the render chain's audio
        // sink; subclass the factory and override buildAudioSink so the custom
        // sink (carrying the processor) replaces the default. The hook method
        // is stable across Media3 releases (protected, AudioSink return).
        val renderersFactory: RenderersFactory =
            object : DefaultRenderersFactory(appContext) {
                @UnstableApi
                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean
                ): AudioSink? = DefaultAudioSink.Builder()
                    .setAudioProcessors(arrayOf(volumeBoostProcessor))
                    .build()
            }
                .setExtensionRendererMode(
                    if (_decoderMode == DecoderMode.SOFTWARE) {
                        DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                    } else {
                        DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                    }
                )
                .setMediaCodecSelector(
                    if (_decoderMode == DecoderMode.SOFTWARE) SoftwareCodecSelector
                    else MediaCodecSelector.DEFAULT
                )

        val exoPlayer = ExoPlayer.Builder(appContext, renderersFactory)
            .setTrackSelector(DefaultTrackSelector(appContext))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(DEFAULT_SEEK_INCREMENT_MS)
            .setSeekForwardIncrementMs(DEFAULT_SEEK_INCREMENT_MS)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        exoPlayer.addListener(listener)
        attachedTextureView?.let { exoPlayer.setVideoTextureView(it) }
        player = exoPlayer
        return exoPlayer
    }

    /**
     * MediaCodec selector that filters out hardware-accelerated decoders so
     * software decoding is forced. On API < Q hardware/software cannot be
     * distinguished via [MediaCodecInfo] flags, so it falls back to the default
     * selector (extension ffmpeg renderers, when present, still win because the
     * factory is built with EXTENSION_RENDERER_MODE_PREFER).
     */
    private object SoftwareCodecSelector : MediaCodecSelector {
        override fun getDecoderInfos(
            mimeType: String,
            requiresSecureDecoder: Boolean,
            requiresTunnelingDecoder: Boolean
        ): MutableList<MediaCodecInfo> {
            val all = MediaCodecUtil.getDecoderInfos(
                mimeType,
                requiresSecureDecoder,
                requiresTunnelingDecoder
            )
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
                return all
            }
            val softwareOnly = all.filter { info -> !info.hardwareAccelerated }
            return (softwareOnly.ifEmpty { all }).toMutableList()
        }
    }

    /** Flattens a cue group to the plain strings the UI overlay renders. */
    private fun List<Cue>.toCueTexts(): List<String> = mapNotNull { cue ->
        cue.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private val listener = object : Player.Listener {

        override fun onPlaybackStateChanged(playbackState: Int) {
            publishState()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            publishState()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            publishState()
            if (isPlaying) startPositionUpdates()
        }

        override fun onPlayerError(error: Media3PlaybackException) {
            val mapped = Media3ErrorMapper.map(error)
            _error.value = Media3ErrorMapper.refineForSource(
                mapped,
                mediaItem?.uri.orEmpty(),
                pendingMime
            )
            publishState()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            _videoWidth.value = videoSize.width
            _videoHeight.value = videoSize.height
            _pixelWidthHeightRatio.value = videoSize.pixelWidthHeightRatio.takeIf { it > 0f } ?: 1f
        }

        override fun onTracksChanged(tracks: Tracks) {
            _tracksRevision.value += 1
        }

        override fun onCues(cueGroup: CueGroup) {
            // Player.Listener.onCues(CueGroup) is the 1.3.x callback for the
            // currently active cue group; there is no public addTextOutput().
            _cues.value = cueGroup.cues.toCueTexts()
        }

        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_TIMELINE_CHANGED)) {
                _cues.value = emptyList()
            }
        }

        override fun onRenderedFirstFrame() {
            _error.value = null
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            // The equalizer / bass boost must (re)bind whenever the renderers
            // create a fresh audio session - including after an HW/SW rebuild.
            audioEffects.attach(audioSessionId)
        }
    }

    /** Derive the published state from the player itself. */
    private fun publishState() {
        val exoPlayer = player
        if (exoPlayer == null) {
            _stateFlow.value = PlaybackState.Idle
            return
        }
        _stateFlow.value = when {
            _error.value != null && exoPlayer.playbackState == Player.STATE_IDLE -> PlaybackState.Error
            exoPlayer.playbackState == Player.STATE_IDLE -> PlaybackState.Idle
            exoPlayer.playbackState == Player.STATE_BUFFERING -> PlaybackState.Buffering
            exoPlayer.playbackState == Player.STATE_ENDED -> PlaybackState.Ended
            // STATE_READY
            exoPlayer.isPlaying -> PlaybackState.Playing
            else -> PlaybackState.Paused
        }
        _position.value = exoPlayer.currentPosition.coerceAtLeast(0L)
    }

    private fun startPositionUpdates() {
        if (positionJob?.isActive == true) return
        positionJob = scope.launch {
            while (isActive) {
                player?.let {
                    _position.value = it.currentPosition.coerceAtLeast(0L)
                    _cues.value = it.currentCues.cues.toCueTexts()
                }
                delay(POSITION_UPDATE_INTERVAL_MS)
            }
        }
    }

    private suspend fun onPlayer(block: (ExoPlayer) -> Unit) {
        withContext(Dispatchers.Main.immediate) { player?.let(block) }
    }

    // -----------------------------------------------------------------
    // Playback
    // -----------------------------------------------------------------

    override suspend fun play(mediaItem: MediaItem): PlaybackSession {
        this.mediaItem = mediaItem
        _error.value = null
        _tracksRevision.value += 1
        try {
            withContext(Dispatchers.Main.immediate) {
                val exoPlayer = ensurePlayer()
                val kind = MediaSourceDetector.kindOf(mediaItem.uri, mediaItem.format)
                pendingMime = MediaSourceDetector.forcedMimeType(kind)
                val startPosition = pendingStartPositionMs.takeIf { it > 0L }
                    ?: mediaItem.metadata[KEY_RESUME_POSITION]?.let { (it as? Number)?.toLong() }
                    ?: 0L
                exoPlayer.setMediaSource(
                    createMediaSource(mediaItem, kind),
                    startPosition.coerceAtLeast(0L)
                )
                exoPlayer.prepare()
                exoPlayer.playWhenReady = true
            }
            startPositionUpdates()
            return PlaybackSession(
                mediaItem = mediaItem,
                engine = this,
                startPositionMs = pendingStartPositionMs
            )
        } catch (e: Exception) {
            _error.value = PlaybackError.fromException(e)
            _stateFlow.value = PlaybackState.Error
            throw e
        }
    }

    /**
     * Build the MediaSource for [item].
     *
     * HLS and DASH get their dedicated factories so master playlists /
     * adaptive representations are parsed by the streaming parsers rather than
     * guessed at. Everything else goes through [DefaultMediaSourceFactory],
     * which sniffs the container and falls back to progressive playback.
     */
    private fun createMediaSource(item: MediaItem, kind: MediaKind): MediaSource {
        val media3Item = toMedia3Item(item)
        return when (kind) {
            MediaKind.Hls ->
                HlsMediaSource.Factory(dataSourceFactory).createMediaSource(media3Item)

            MediaKind.Dash ->
                DashMediaSource.Factory(dataSourceFactory).createMediaSource(media3Item)

            MediaKind.Progressive ->
                ProgressiveMediaSource.Factory(dataSourceFactory, DefaultExtractorsFactory())
                    .createMediaSource(media3Item)

            MediaKind.Rtsp,
            MediaKind.SmoothStreaming,
            MediaKind.Unknown ->
                mediaSourceFactory.createMediaSource(media3Item)
        }
    }

    private fun toMedia3Item(item: MediaItem): Media3MediaItem {
        val builder = Media3MediaItem.Builder()
            .setUri(item.uri)
            .setMediaId(item.id)
        MediaSourceDetector.forcedMimeType(MediaSourceDetector.kindOf(item.uri, item.format))
            ?.let { builder.setMimeType(it) }
        item.title?.let { builder.setMediaMetadata(
            androidx.media3.common.MediaMetadata.Builder().setTitle(it).build()
        ) }
        val subtitles = buildList {
            item.subtitles?.forEach { add(it.toSubtitleConfiguration()) }
            // .srt/.vtt/.ass files picked from the HUD selector for this item.
            addAll(externalSubtitles)
        }
        if (subtitles.isNotEmpty()) builder.setSubtitleConfigurations(subtitles)
        return builder.build()
    }

    private fun SubtitleInfo.toSubtitleConfiguration(): Media3MediaItem.SubtitleConfiguration =
        Media3MediaItem.SubtitleConfiguration.Builder(Uri.parse(uri))
            .setMimeType(subtitleMimeFor(format))
            .setLanguage(language.takeIf { it.isNotBlank() && it != "unknown" })
            .setSelectionFlags(if (defaultTrack) C.SELECTION_FLAG_DEFAULT else 0)
            .build()

    private fun subtitleMimeFor(format: String): String = when (format.lowercase()) {
        "vtt", "webvtt" -> MimeTypes.TEXT_VTT
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        "ttml", "xml" -> MimeTypes.APPLICATION_TTML
        else -> MimeTypes.APPLICATION_SUBRIP
    }

    override suspend fun pause() = onPlayer { it.playWhenReady = false }

    override suspend fun resume() = onPlayer { it.playWhenReady = true }

    override suspend fun stop() {
        onPlayer {
            it.playWhenReady = false
            it.stop()
        }
        pendingStartPositionMs = 0L
        _position.value = 0L
        _error.value = null
        publishState()
    }

    override suspend fun seekTo(positionMs: Long) = onPlayer {
        it.seekTo(positionMs.coerceAtLeast(0L))
        _position.value = positionMs.coerceAtLeast(0L)
    }

    override suspend fun seekBy(deltaMs: Long) = onPlayer {
        val target = (it.currentPosition + deltaMs).coerceIn(0L, it.duration.coerceAtLeast(0L))
        it.seekTo(target)
        _position.value = target
    }

    override suspend fun setPlaybackSpeed(speed: Float) = onPlayer {
        it.setPlaybackSpeed(speed.coerceIn(MIN_PLAYBACK_SPEED, MAX_PLAYBACK_SPEED))
    }

    override val playbackSpeed: Float
        get() = player?.playbackParameters?.speed ?: 1.0f

    override suspend fun togglePlayPause() = onPlayer { it.playWhenReady = !it.playWhenReady }

    override val currentPosition: Long get() = player?.currentPosition?.coerceAtLeast(0L) ?: 0L

    override val currentTimeMs: Long get() = currentPosition

    override val duration: Long
        get() = player?.duration?.takeIf { it != C.TIME_UNSET && it >= 0 } ?: -1L

    override val bufferedPosition: Long
        get() = player?.bufferedPosition?.takeIf { it != C.TIME_UNSET && it >= 0 } ?: -1L

    override val isPlaying: Boolean get() = player?.isPlaying ?: false

    override val isStopped: Boolean
        get() = player?.playbackState?.let { it == Player.STATE_IDLE } ?: true

    override val isBuffering: Boolean
        get() = player?.playbackState == Player.STATE_BUFFERING

    override val error: String? get() = _error.value?.toString()

    override suspend fun retry(): Boolean {
        val item = mediaItem ?: return false
        if (_error.value == null && _stateFlow.value != PlaybackState.Error) return false
        val resumeAt = currentPosition
        _error.value = null
        pendingStartPositionMs = resumeAt
        play(item)
        return true
    }

    // -----------------------------------------------------------------
    // Tracks
    // -----------------------------------------------------------------

    /** A single selectable track inside the current [Tracks] snapshot. */
    private class TrackRef(val group: Tracks.Group, val indexInGroup: Int)

    /**
     * Flatten all tracks of [trackType] into a stable index space. The returned
     * list is ordered by group then by track, so index *n* always addresses the
     * same track for a given [Tracks] snapshot.
     */
    private fun enumerate(trackType: Int): List<TrackRef> {
        val groups = player?.currentTracks?.groups ?: return emptyList()
        return groups.asSequence()
            .filter { it.type == trackType }
            .flatMap { group -> (0 until group.length).map { TrackRef(group, it) } }
            .toList()
    }

    private fun selectedIndexOf(trackType: Int): Int {
        val refs = enumerate(trackType)
        return refs.indexOfFirst { it.group.isTrackSelected(it.indexInGroup) }
    }

    override val currentAudioTrack: Int get() = selectedIndexOf(C.TRACK_TYPE_AUDIO)

    override val currentSubtitleTrack: Int get() = selectedIndexOf(C.TRACK_TYPE_TEXT)

    override val availableAudioTracks: List<AudioTrackInfo>
        get() {
            // Reading the revision makes the value recompose when tracks change.
            _tracksRevision.value
            return enumerate(C.TRACK_TYPE_AUDIO).mapIndexed { index, ref ->
                val format = ref.group.getTrackFormat(ref.indexInGroup)
                AudioTrackInfo(
                    index = index,
                    name = format.label
                        ?: format.language?.let { "Track ${index + 1} ($it)" }
                        ?: "Track ${index + 1}",
                    codec = format.sampleMimeType ?: "unknown",
                    channels = format.channelCount.takeUnless { it == Format.NO_VALUE } ?: 0,
                    sampleRate = format.sampleRate.takeUnless { it == Format.NO_VALUE } ?: 0,
                    bitrate = format.bitrate.takeUnless { it == Format.NO_VALUE }?.toLong(),
                    isAudioDescription =
                        format.roleFlags and C.ROLE_FLAG_DESCRIBES_VIDEO != 0
                )
            }
        }

    override val availableSubtitleTracks: List<SubtitleTrackInfo>
        get() {
            _tracksRevision.value
            return enumerate(C.TRACK_TYPE_TEXT).mapIndexed { index, ref ->
                val format = ref.group.getTrackFormat(ref.indexInGroup)
                SubtitleTrackInfo(
                    index = index,
                    language = format.language ?: "unknown",
                    name = format.label
                        ?: format.language?.let { "Subtitle ${index + 1} ($it)" }
                        ?: "Subtitle ${index + 1}",
                    format = format.sampleMimeType ?: "unknown"
                )
            }
        }

    override val availableVideoTracks: List<VideoTrackInfo>
        get() {
            _tracksRevision.value
            return enumerate(C.TRACK_TYPE_VIDEO).mapIndexed { index, ref ->
                val format = ref.group.getTrackFormat(ref.indexInGroup)
                VideoTrackInfo(
                    index = index,
                    name = format.label
                        ?: "${format.width}x${format.height}".takeIf { format.width > 0 }
                        ?: "Track ${index + 1}",
                    codec = format.sampleMimeType,
                    profile = null,
                    level = null,
                    width = format.width.takeUnless { w -> w == Format.NO_VALUE } ?: 0,
                    height = format.height.takeUnless { h -> h == Format.NO_VALUE } ?: 0,
                    bitDepth = null
                )
            }
        }

    private suspend fun selectTrack(trackType: Int, index: Int) = onPlayer { exoPlayer ->
        val refs = enumerate(trackType)
        if (index < 0) {
            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(trackType, true)
                .clearOverridesOfType(trackType)
                .build()
            return@onPlayer
        }
        val ref = refs.getOrNull(index) ?: return@onPlayer
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(ref.group.mediaTrackGroup, ref.indexInGroup))
            .setTrackTypeDisabled(trackType, false)
            .build()
    }

    override suspend fun setAudioTrack(index: Int) = selectTrack(C.TRACK_TYPE_AUDIO, index)

    override suspend fun setSubtitleTrack(index: Int) = selectTrack(C.TRACK_TYPE_TEXT, index)

    override suspend fun setVideoTrack(index: Int) = selectTrack(C.TRACK_TYPE_VIDEO, index)

    override suspend fun toggleSubtitle() = onPlayer { exoPlayer ->
        val refs = enumerate(C.TRACK_TYPE_TEXT)
        if (refs.isEmpty()) return@onPlayer
        val currentlyDisabled =
            exoPlayer.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        exoPlayer.trackSelectionParameters = if (currentlyDisabled) {
            val first = refs.first()
            exoPlayer.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(
                    TrackSelectionOverride(first.group.mediaTrackGroup, first.indexInGroup)
                )
                .build()
        } else {
            exoPlayer.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .build()
        }
    }

    // -----------------------------------------------------------------
    // Video geometry / screenshots
    // -----------------------------------------------------------------

    override val videoWidth: Int get() = _videoWidth.value

    override val videoHeight: Int get() = _videoHeight.value

    override val videoAspectRatio: Float
        get() {
            val width = _videoWidth.value
            val height = _videoHeight.value
            if (width <= 0 || height <= 0) return 1f
            return (width * _pixelWidthHeightRatio.value) / height
        }

    override val isVideoValid: Boolean
        get() = _videoWidth.value > 0 && _videoHeight.value > 0

    override suspend fun captureScreenshot(): ByteArray = withContext(Dispatchers.Main.immediate) {
        val textureView = attachedTextureView
            ?: throw IllegalStateException(
                "Screenshot requires a TextureView output; call setVideoTextureView() first " +
                    "(SurfaceView output cannot be read back)."
            )
        val bitmap = textureView.bitmap
            ?: throw IllegalStateException("No video frame has been rendered yet.")
        try {
            ByteArrayOutputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    override fun setVideoSurfaceView(surfaceView: SurfaceView?) {
        attachedTextureView = null
        player?.setVideoSurfaceView(surfaceView)
    }

    override fun setVideoTextureView(textureView: TextureView?) {
        attachedTextureView = textureView
        player?.setVideoTextureView(textureView)
    }

    // -----------------------------------------------------------------
    // Resume / decoder info
    // -----------------------------------------------------------------

    override val resumePosition: Long get() = pendingStartPositionMs

    override suspend fun setResumePosition(position: Long) {
        pendingStartPositionMs = position.coerceAtLeast(0L)
        // Apply immediately when media is already loaded, otherwise it is
        // consumed by the next play() call as the start position.
        if (player?.mediaItemCount?.let { it > 0 } == true) {
            seekTo(position)
        }
    }

    override val decoderInfo: DecoderInfo
        get() {
            val exoPlayer = player
            val videoFormat = exoPlayer?.videoFormat
            val audioFormat = exoPlayer?.audioFormat
            val videoMime = videoFormat?.sampleMimeType
            val info = DecoderDetector.decoderInfoFor(videoMime)
            return info.copy(
                videoCodec = videoMime ?: info.videoCodec,
                audioCodec = audioFormat?.sampleMimeType ?: info.audioCodec
            )
        }

    // -----------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------

    override suspend fun release() {
        positionJob?.cancel()
        positionJob = null
        withContext(Dispatchers.Main.immediate) {
            player?.let { exoPlayer ->
                exoPlayer.removeListener(listener)
                exoPlayer.playWhenReady = false
                exoPlayer.stop()
                exoPlayer.release()
            }
            player = null
            attachedTextureView = null
        }
        pendingStartPositionMs = 0L
        _position.value = 0L
        _videoWidth.value = 0
        _videoHeight.value = 0
        _error.value = null
        _cues.value = emptyList()
        _stateFlow.value = PlaybackState.Idle
    }

    /** Cancel the engine's internal scope. Called when the owning process is going away. */
    fun shutdown() {
        positionJob?.cancel()
        positionJob = null
        audioEffects.release()
        scope.coroutineContext[Job]?.cancel()
    }

    // -----------------------------------------------------------------
    // Direct on-screen configuration (HW/SW badge, volume boost, subs, FX)
    // -----------------------------------------------------------------

    override val decoderMode: DecoderMode get() = _decoderMode

    override suspend fun setDecoderMode(mode: DecoderMode) {
        if (mode == _decoderMode && player != null) return
        _decoderMode = mode
        val currentItem = mediaItem ?: run {
            // No media yet: the next ensurePlayer() picks the mode up.
            return
        }
        // Decoder type is a render-chain property: remember the position, rebuild
        // the player and resume so the switch is instant from the user's view.
        val resumeAt = currentPosition.coerceAtLeast(0L)
        val wasPlaying = player?.playWhenReady ?: true
        withContext(Dispatchers.Main.immediate) {
            player?.let { old ->
                old.removeListener(listener)
                old.playWhenReady = false
                old.stop()
                old.release()
            }
            player = null
            audioEffects.release()
            val rebuilt = ensurePlayer()
            attachedTextureView?.let { rebuilt.setVideoTextureView(it) }
            val kind = MediaSourceDetector.kindOf(currentItem.uri, currentItem.format)
            pendingMime = MediaSourceDetector.forcedMimeType(kind)
            rebuilt.setMediaSource(createMediaSource(currentItem, kind), resumeAt)
            rebuilt.prepare()
            rebuilt.playWhenReady = wasPlaying
            audioEffects.attach(rebuilt.audioSessionId)
        }
        startPositionUpdates()
    }

    @Volatile private var _volumeBoost: Float = 1f
    override val volumeBoost: Float get() = _volumeBoost

    override suspend fun setVolumeBoost(gain: Float) {
        val clamped = gain.coerceIn(0f, MAX_VOLUME_BOOST)
        _volumeBoost = clamped
        withContext(Dispatchers.Main.immediate) {
            volumeBoostProcessor.setGain(clamped)
        }
    }

    override val audioSessionId: Int
        get() = player?.audioSessionId?.takeIf { it != AudioSessionIdUnset }
            ?: AudioEffectsController.AudioSessionUnavailable

    override suspend fun addExternalSubtitle(uri: String, mimeType: String?, language: String?) {
        val format = mimeType ?: when (uri.substringAfterLast('.', "").lowercase()) {
            "vtt" -> MimeTypes.TEXT_VTT
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            "ttml", "xml" -> MimeTypes.APPLICATION_TTML
            else -> MimeTypes.APPLICATION_SUBRIP
        }
        val config = SubtitleConfiguration.Builder(Uri.parse(uri))
            .setMimeType(format)
            .setLanguage(language)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        externalSubtitles.removeAll { it.uri.toString() == uri }
        externalSubtitles.add(config)
        val currentItem = mediaItem ?: return
        // Re-prepare with the rebuilt item; the position is preserved so adding
        // a subtitle mid-film does not restart playback.
        val resumeAt = currentPosition
        withContext(Dispatchers.Main.immediate) {
            val exoPlayer = ensurePlayer()
            val kind = MediaSourceDetector.kindOf(currentItem.uri, currentItem.format)
            pendingMime = MediaSourceDetector.forcedMimeType(kind)
            exoPlayer.setMediaSource(createMediaSource(currentItem, kind), resumeAt.coerceAtLeast(0L))
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        }
        _tracksRevision.value += 1
        startPositionUpdates()
    }

    private companion object {
        const val MAX_VOLUME_BOOST = 2.0f
        const val AudioSessionIdUnset = 0
    }

    private companion object {
        const val USER_AGENT = "QuantumPlayer"
        const val POSITION_UPDATE_INTERVAL_MS = 250L
        const val DEFAULT_SEEK_INCREMENT_MS = 10_000L
        const val HTTP_TIMEOUT_MS = 15_000
        const val MIN_PLAYBACK_SPEED = 0.25f
        const val MAX_PLAYBACK_SPEED = 4.0f
        const val KEY_RESUME_POSITION = "resume_position_ms"
    }
}
