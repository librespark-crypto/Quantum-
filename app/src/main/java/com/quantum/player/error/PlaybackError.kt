package com.quantum.player.error

import com.quantum.player.core.PlaybackState

/**
 * Structured playback errors with user-friendly messages and solutions.
 * Never crash because a codec or stream is unsupported.
 */
object PlaybackError {

    /** Base error class. */
    sealed class PlaybackException(
        val code: String,
        val message: String,
        val userMessage: String,
        val possibleSolution: String,
        val retryable: Boolean = false
    )

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
        possibleSolution = "Try renaming the file extension or using a different source.",
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
        possibleSolution = "The URL might be restricted, age-limited, or unavailable. Try a different URL or use the built-in browser.",
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
        possibleSolution = "This content requires licensed DRM modules. Some platforms may provide these.",
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
        possibleSolution = "Try enabling software video decoding in settings, or try a different output format.",
        retryable = true
    )

    /** Convert exception to PlaybackException. */
    fun fromException(e: Exception): PlaybackException = when (e) {
        is UnsupportedCodecException -> UnsupportedCodec
        is UnsupportedContainerException -> UnsupportedContainer
        is NetworkException -> NetworkError
        is InvalidUrlException -> InvalidUrl
        is YtDlpResolutionException -> YtDlpResolutionError
        is DecoderInitializationException -> DecoderInitializationError
        is DRMException -> DrmProtectedStream
        is InvalidHlsPlaylistException -> InvalidHlsPlaylist
        is InvalidDashManifestException -> InvalidDashManifest
        is AudioDecoderException -> AudioDecoderFailure
        is VideoDecoderException -> VideoDecoderFailure
        else -> PlaybackException(
            code = "unknown_error",
            message = e.message ?: "Unknown error",
            userMessage = "An unexpected error occurred. Please try again.",
            retryable = true
        )
    }

    /** Data classes for specific exception types. */
    data class UnsupportedCodecException(
        val mimeType: String,
        val detail: String? = null
            ) : RuntimeException(detail) {
        init {
            message = "Unsupported codec: $mimeType"
        }
    }

    data class UnsupportedContainerException(
        val container: String,
        val detail: String? = null
            ) : RuntimeException(detail) {
        init {
            message = "Unsupported container: $container"
        }
    }

    data class NetworkException(
        val cause: String? = null
            ) : RuntimeException(cause) {
        init {
            message = "Network error"
        }
    }

    data class InvalidUrlException(
        val url: String,
        val detail: String? = null
            ) : RuntimeException(detail) {
        init {
            message = "Invalid URL: $url"
        }
    }

    data class YtDlpResolutionException(
        val url: String,
        val detail: String? = null
            ) : RuntimeException(detail) {
        init {
            message = "yt-dlp resolution failed for: $url"
        }
    }

    data class DecoderInitializationException(
        val mimeType: String,
        val detail: String? = null
            ) : RuntimeException(detail) {
        init {
            message = "Decoder initialization failed for: $mimeType"
        }
    }

    data class DRMException(
        val message: String? = null
            ) : RuntimeException(message) {
        init {
            message = "DRM/protected stream detected"
        }
    }

    data class InvalidHlsPlaylistException(
        val url: String,
        val detail: String? = null
            ) : RuntimeException(detail) {
        init {
            message = "Invalid HLS playlist: $url"
        }
    }

    data class InvalidDashManifestException(
        val url: String,
        val detail: String? = null
            ) : RuntimeException(detail) {
        init {
            message = "Invalid DASH manifest: $url"
        }
    }

    data class AudioDecoderException(
        val detail: String? = null
            ) : RuntimeException(detail) {
        init {
            message = "Audio decoder failure"
        }
    }

    data class VideoDecoderException(
        val detail: String? = null
            ) : RuntimeException(detail) {
        init {
            message = "Video decoder failure"
        }
    }

    /**
     * Display error information to the user.
     * Shows what happened + possible solution + retry option.
     */
    fun showError(
        exception: PlaybackException,
        onRetry: () -> Unit,
        onDismiss: () -> Unit
    ) {
        // In a real UI, this would show an alert dialog with:
        // - What happened (userMessage)
        // - Possible solution
        // - Retry button (calls onRetry)
        // - Dismiss button (calls onDismiss)

        // For now, print to console
        println("ERROR [${exception.code}]: ${exception.userMessage}")
        println("SOLUTION: ${exception.possibleSolution}")
        println("---")
    }

    /** Get user-friendly error title. */
    fun getErrorTitle(exception: PlaybackException): String {
        return when (exception) {
            UnsupportedCodec -> "Unsupported Codec"
            UnsupportedContainer -> "Unsupported Container"
            NetworkError -> "Network Error"
            InvalidUrl -> "Invalid URL"
            YtDlpResolutionError -> "Resolution Failed"
            DecoderInitializationError -> "Decoder Error"
            DrmProtectedStream -> "DRM Protected"
            InvalidHlsPlaylist -> "Invalid Playlist"
            InvalidDashManifest -> "Invalid Manifest"
            AudioDecoderFailure -> "Audio Decoder Failure"
            VideoDecoderFailure -> "Video Decoder Failure"
            else -> "Playback Error"
        }
    }

    /** Get retryability status. */
    fun isRetryable(exception: PlaybackException): Boolean {
        return exception.retryable
    }
}