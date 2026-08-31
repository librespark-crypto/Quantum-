package com.quantum.player.error

/**
 * Structured playback errors with user-friendly messages and solutions.
 * Never crash because a codec or stream is unsupported.
 *
 * Every entry carries:
 *  - [PlaybackException.code]             stable machine readable id
 *  - [PlaybackException.message]          developer facing message
 *  - [PlaybackException.userMessage]      text safe to show to a user
 *  - [PlaybackException.possibleSolution] actionable hint
 *  - [PlaybackException.retryable]        whether a retry button makes sense
 *  - [PlaybackException.detail]           optional backend supplied detail
 *
 * This class is deliberately free of Android/Media3 imports so the mapping can
 * be exercised by plain JVM unit tests.
 */
object PlaybackError {

    /** Base error class. */
    open class PlaybackException(
        val code: String,
        override val message: String,
        val userMessage: String,
        val possibleSolution: String,
        val retryable: Boolean = false,
        val detail: String? = null,
        cause: Throwable? = null
    ) : RuntimeException(message, cause) {

        override fun toString(): String =
            "PlaybackException[$code] $message" + (detail?.let { " ($it)" } ?: "")
    }

    /** Unsupported codec error. */
    object UnsupportedCodec : PlaybackException(
        code = "unsupported_codec",
        message = "Codec not supported",
        userMessage = "This video uses a codec that is not supported by your device.",
        possibleSolution = "Try a different video file or convert the video to a supported format.",
        retryable = false
    )

    /** Unsupported container error. */
    object UnsupportedContainer : PlaybackException(
        code = "unsupported_container",
        message = "Container format not supported",
        userMessage = "The file container format is not supported.",
        possibleSolution = "Try a different source, or a file in a common container such as MP4 or MKV.",
        retryable = false
    )

    /** Network error. */
    object NetworkError : PlaybackException(
        code = "network_error",
        message = "Network error occurred",
        userMessage = "Could not connect to the network source.",
        possibleSolution = "Check your internet connection and try again.",
        retryable = true
    )

    /** Invalid URL error. */
    object InvalidUrl : PlaybackException(
        code = "invalid_url",
        message = "Invalid URL provided",
        userMessage = "The provided URL is not valid or could not be parsed.",
        possibleSolution = "Check the URL for typos and try again, or use a direct media file.",
        retryable = true
    )

    /** yt-dlp resolution error. */
    object YtDlpResolutionError : PlaybackException(
        code = "yt_dlp_resolution_error",
        message = "Failed to resolve URL with yt-dlp",
        userMessage = "Could not extract video information from the URL.",
        possibleSolution = "The URL might be restricted, age-limited, or unavailable. " +
            "Try a different URL, or play a direct media link instead.",
        retryable = true
    )

    /** Decoder initialization error. */
    object DecoderInitializationError : PlaybackException(
        code = "decoder_initialization_error",
        message = "Failed to initialize decoder",
        userMessage = "Could not initialize the video decoder. Hardware acceleration may not be available.",
        possibleSolution = "Try enabling software decoding in settings, or try a different device.",
        retryable = true
    )

    /** DRM/protected stream error. */
    object DrmProtectedStream : PlaybackException(
        code = "drm_protected",
        message = "DRM/protected stream detected",
        userMessage = "This stream is protected by DRM and cannot be played.",
        possibleSolution = "This content requires a licensed DRM module, which this player does not ship.",
        retryable = false
    )

    /** Invalid HLS playlist error. */
    object InvalidHlsPlaylist : PlaybackException(
        code = "invalid_hls_playlist",
        message = "Invalid HLS playlist",
        userMessage = "The playlist could not be parsed. It may be corrupted or unsupported.",
        possibleSolution = "Try a different video source or check the playlist URL.",
        retryable = true
    )

    /** Invalid DASH manifest error. */
    object InvalidDashManifest : PlaybackException(
        code = "invalid_dash_manifest",
        message = "Invalid DASH manifest",
        userMessage = "The DASH manifest could not be parsed.",
        possibleSolution = "Try a different video source or check the manifest URL.",
        retryable = true
    )

    /** Audio decoder failure. */
    object AudioDecoderFailure : PlaybackException(
        code = "audio_decoder_failure",
        message = "Audio decoder failure",
        userMessage = "Could not initialize the audio decoder.",
        possibleSolution = "Try switching audio tracks or enabling software audio decoding.",
        retryable = true
    )

    /** Video decoder failure. */
    object VideoDecoderFailure : PlaybackException(
        code = "video_decoder_failure",
        message = "Video decoder failure",
        userMessage = "Could not initialize the video decoder.",
        possibleSolution = "Try enabling software video decoding in settings, or try a different source.",
        retryable = true
    )

    /** Subtitle track could not be loaded. */
    object SubtitleFailure : PlaybackException(
        code = "subtitle_failure",
        message = "Subtitle track could not be loaded",
        userMessage = "The subtitle track could not be read.",
        possibleSolution = "Check the subtitle file or select a different track.",
        retryable = true
    )

    /** The media source was unreachable or the file does not exist. */
    object SourceNotFound : PlaybackException(
        code = "source_not_found",
        message = "Media source not found",
        userMessage = "The file or stream could not be found.",
        possibleSolution = "Check that the file still exists or that the link is still valid.",
        retryable = true
    )

    /** Playback was interrupted by a timeout. */
    object Timeout : PlaybackException(
        code = "timeout",
        message = "Playback timed out",
        userMessage = "The stream stopped responding.",
        possibleSolution = "Check your connection speed and try again.",
        retryable = true
    )

    /** Anything that does not map onto a known category. */
    object UnknownError : PlaybackException(
        code = "unknown_error",
        message = "Unknown playback error",
        userMessage = "An unexpected error occurred.",
        possibleSolution = "Please try again.",
        retryable = true
    )

    // ---------------------------------------------------------------------
    // Throwable types thrown by the resolver/backend layers.
    // ---------------------------------------------------------------------

    private fun detailSuffix(detail: String?): String =
        if (detail.isNullOrBlank()) "" else ": $detail"

    class UnsupportedCodecException(
        val mimeType: String,
        val detail: String? = null
    ) : RuntimeException("Unsupported codec: $mimeType" + detailSuffix(detail))

    class UnsupportedContainerException(
        val container: String,
        val detail: String? = null
    ) : RuntimeException("Unsupported container: $container" + detailSuffix(detail))

    class NetworkException(
        val detail: String? = null,
        cause: Throwable? = null
    ) : RuntimeException("Network error" + detailSuffix(detail), cause)

    class InvalidUrlException(
        val url: String,
        val detail: String? = null
    ) : RuntimeException("Invalid URL: $url" + detailSuffix(detail))

    class YtDlpResolutionException(
        val url: String,
        val detail: String? = null
    ) : RuntimeException("yt-dlp resolution failed for: $url" + detailSuffix(detail))

    class DecoderInitializationException(
        val mimeType: String,
        val detail: String? = null
    ) : RuntimeException("Decoder initialization failed for: $mimeType" + detailSuffix(detail))

    class DRMException(
        val detail: String? = null
    ) : RuntimeException("DRM/protected stream detected" + detailSuffix(detail))

    class InvalidHlsPlaylistException(
        val url: String,
        val detail: String? = null
    ) : RuntimeException("Invalid HLS playlist: $url" + detailSuffix(detail))

    class InvalidDashManifestException(
        val url: String,
        val detail: String? = null
    ) : RuntimeException("Invalid DASH manifest: $url" + detailSuffix(detail))

    class AudioDecoderException(
        val detail: String? = null
    ) : RuntimeException("Audio decoder failure" + detailSuffix(detail))

    class VideoDecoderException(
        val detail: String? = null
    ) : RuntimeException("Video decoder failure" + detailSuffix(detail))

    class SubtitleException(
        val detail: String? = null
    ) : RuntimeException("Subtitle track could not be loaded" + detailSuffix(detail))

    class SourceNotFoundException(
        val uri: String,
        val detail: String? = null
    ) : RuntimeException("Media source not found: $uri" + detailSuffix(detail))

    // ---------------------------------------------------------------------
    // Mapping
    // ---------------------------------------------------------------------

    /** Attach backend supplied detail to a catalog entry without losing the category. */
    fun withDetail(
        base: PlaybackException,
        detail: String?,
        cause: Throwable? = null
    ): PlaybackException = PlaybackException(
        code = base.code,
        message = base.message,
        userMessage = base.userMessage,
        possibleSolution = base.possibleSolution,
        retryable = base.retryable,
        detail = detail?.takeIf { it.isNotBlank() } ?: base.detail,
        cause = cause ?: base.cause
    )

    /**
     * Convert any throwable into a structured [PlaybackException].
     *
     * Nothing is ever swallowed: unknown throwables become [UnknownError] with
     * the original message preserved as detail and as the cause.
     */
    fun fromException(e: Throwable): PlaybackException = when (e) {
        is PlaybackException -> e
        is UnsupportedCodecException -> withDetail(UnsupportedCodec, "${e.mimeType} ${e.detail.orEmpty()}".trim(), e)
        is UnsupportedContainerException -> withDetail(UnsupportedContainer, "${e.container} ${e.detail.orEmpty()}".trim(), e)
        is NetworkException -> withDetail(NetworkError, e.detail, e)
        is InvalidUrlException -> withDetail(InvalidUrl, "${e.url} ${e.detail.orEmpty()}".trim(), e)
        is YtDlpResolutionException -> withDetail(YtDlpResolutionError, "${e.url} ${e.detail.orEmpty()}".trim(), e)
        is DecoderInitializationException -> withDetail(DecoderInitializationError, e.mimeType, e)
        is DRMException -> withDetail(DrmProtectedStream, e.detail, e)
        is InvalidHlsPlaylistException -> withDetail(InvalidHlsPlaylist, e.url, e)
        is InvalidDashManifestException -> withDetail(InvalidDashManifest, e.url, e)
        is AudioDecoderException -> withDetail(AudioDecoderFailure, e.detail, e)
        is VideoDecoderException -> withDetail(VideoDecoderFailure, e.detail, e)
        is SubtitleException -> withDetail(SubtitleFailure, e.detail, e)
        is SourceNotFoundException -> withDetail(SourceNotFound, e.uri, e)
        else -> withDetail(UnknownError, e.message ?: e.javaClass.simpleName, e)
    }

    /** Get user-friendly error title. */
    fun getErrorTitle(exception: PlaybackException): String = when (exception.code) {
        UnsupportedCodec.code -> "Unsupported Codec"
        UnsupportedContainer.code -> "Unsupported Container"
        NetworkError.code -> "Network Error"
        InvalidUrl.code -> "Invalid URL"
        YtDlpResolutionError.code -> "Resolution Failed"
        DecoderInitializationError.code -> "Decoder Error"
        DrmProtectedStream.code -> "DRM Protected"
        InvalidHlsPlaylist.code -> "Invalid Playlist"
        InvalidDashManifest.code -> "Invalid Manifest"
        AudioDecoderFailure.code -> "Audio Decoder Failure"
        VideoDecoderFailure.code -> "Video Decoder Failure"
        SubtitleFailure.code -> "Subtitle Error"
        SourceNotFound.code -> "Not Found"
        Timeout.code -> "Timed Out"
        else -> "Playback Error"
    }

    /** Get retryability status. */
    fun isRetryable(exception: PlaybackException): Boolean = exception.retryable

    /**
     * Render the error for a log line or a dialog body: what happened, the
     * underlying detail, and what the user can do about it.
     */
    fun formatForLog(exception: PlaybackException): String = buildString {
        append("ERROR [").append(exception.code).append("]: ").appendLine(exception.userMessage)
        exception.detail?.let { appendLine("DETAIL: $it") }
        append("SOLUTION: ").append(exception.possibleSolution)
    }
}
