package com.quantum.player.core

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlaybackException
import com.quantum.player.error.PlaybackError

/**
 * Maps Media3 / ExoPlayer failures onto the app's structured [PlaybackError]
 * catalogue.
 *
 * This lives in `core` (not in `error`) on purpose: [PlaybackError] must stay
 * free of Android/Media3 types so it can be unit tested on the JVM. The
 * Media3-specific translation is therefore kept here, next to the only code
 * that has a Media3 exception in hand.
 *
 * Nothing is swallowed - unmapped codes fall through to
 * [PlaybackError.UnknownError] with the original code name retained as detail.
 */
@OptIn(UnstableApi::class)
object Media3ErrorMapper {

    /** Translate any Media3 playback failure. */
    fun map(error: PlaybackException): PlaybackError.PlaybackException {
        val detail = error.errorCodeName
        return when (error) {
            is ExoPlaybackException -> mapExo(error, detail)
            else -> PlaybackError.withDetail(catalogueFor(error.errorCode), detail, error)
        }
    }

    private fun mapExo(
        error: ExoPlaybackException,
        detail: String
    ): PlaybackError.PlaybackException = when (error.type) {
        ExoPlaybackException.TYPE_SOURCE ->
            PlaybackError.withDetail(catalogueFor(error.errorCode), sourceDetail(error), error)

        ExoPlaybackException.TYPE_RENDERER ->
            PlaybackError.withDetail(rendererError(error), rendererDetail(error), error)

        ExoPlaybackException.TYPE_REMOTE ->
            PlaybackError.withDetail(PlaybackError.UnknownError, "remote: $detail", error)

        else -> PlaybackError.withDetail(PlaybackError.UnknownError, detail, error)
    }

    private fun sourceDetail(error: ExoPlaybackException): String =
        error.sourceException.message ?: error.errorCodeName

    private fun rendererDetail(error: ExoPlaybackException): String {
        val cause = error.rendererException.message ?: error.errorCodeName
        return "rendererIndex=${error.rendererIndex} $cause"
    }

    /**
     * A renderer failure is either an audio or a video decoder failure.
     * `ExoPlaybackException.rendererName` is the renderer's own name (for
     * example "MediaCodecVideoRenderer"), which is what distinguishes them.
     */
    private fun rendererError(error: ExoPlaybackException): PlaybackError.PlaybackException {
        val name = error.rendererName.orEmpty()
        return when {
            name.contains("audio", ignoreCase = true) -> PlaybackError.AudioDecoderFailure
            name.contains("video", ignoreCase = true) -> PlaybackError.VideoDecoderFailure
            else -> catalogueFor(error.errorCode)
        }
    }

    /** Translate a raw Media3 error code into the closest catalogue entry. */
    private fun catalogueFor(errorCode: Int): PlaybackError.PlaybackException = when (errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> PlaybackError.NetworkError

        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE -> PlaybackError.SourceNotFound

        PlaybackException.ERROR_CODE_TIMEOUT -> PlaybackError.Timeout

        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> PlaybackError.UnsupportedContainer

        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> PlaybackError.UnsupportedContainer

        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED -> PlaybackError.DecoderInitializationError

        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED -> PlaybackError.VideoDecoderFailure

        PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED,
        PlaybackException.ERROR_CODE_DRM_DEVICE_REVOKED,
        PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED,
        PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED,
        PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DRM_UNSPECIFIED -> PlaybackError.DrmProtectedStream

        else -> PlaybackError.UnknownError
    }

    /**
     * Refine a DASH/HLS parsing failure into the specific playlist/manifest
     * error so the user message actually matches the source type.
     */
    fun refineForSource(
        error: PlaybackError.PlaybackException,
        uri: String,
        mimeType: String?
    ): PlaybackError.PlaybackException {
        if (error.code != PlaybackError.UnsupportedContainer.code) return error
        val isHls = mimeType == androidx.media3.common.MimeTypes.APPLICATION_M3U8 ||
            uri.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
        val isDash = mimeType == androidx.media3.common.MimeTypes.APPLICATION_MPD ||
            uri.substringBefore('?').endsWith(".mpd", ignoreCase = true)
        return when {
            isHls -> PlaybackError.withDetail(PlaybackError.InvalidHlsPlaylist, error.detail, error)
            isDash -> PlaybackError.withDetail(PlaybackError.InvalidDashManifest, error.detail, error)
            else -> error
        }
    }
}
