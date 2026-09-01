package com.quantum.player.error

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for error mapping.
 *
 * The guarantee under test: nothing is ever swallowed. Every throwable becomes
 * a structured [PlaybackError.PlaybackException] that keeps its cause and says
 * whether the user can retry.
 */
class PlaybackErrorTest {

    @Test
    fun `network exception maps to the network error category`() {
        val mapped = PlaybackError.fromException(
            PlaybackError.NetworkException(detail = "connection reset")
        )
        assertEquals(PlaybackError.NetworkError.code, mapped.code)
        assertEquals(PlaybackError.NetworkError.userMessage, mapped.userMessage)
        assertTrue("detail must survive mapping", mapped.detail?.contains("connection reset") == true)
        assertTrue(PlaybackError.isRetryable(mapped))
    }

    @Test
    fun `unsupported codec exception keeps mime type in detail`() {
        val mapped = PlaybackError.fromException(
            PlaybackError.UnsupportedCodecException(mimeType = "video/x-quantum")
        )
        assertEquals(PlaybackError.UnsupportedCodec.code, mapped.code)
        assertTrue(mapped.detail?.contains("video/x-quantum") == true)
        assertFalse("an unsupported codec will not fix itself on retry", mapped.retryable)
    }

    @Test
    fun `yt-dlp failure maps to the resolution error category`() {
        val mapped = PlaybackError.fromException(
            PlaybackError.YtDlpResolutionException(
                url = "https://example.com/watch",
                detail = "yt-dlp is not installed"
            )
        )
        assertEquals(PlaybackError.YtDlpResolutionError.code, mapped.code)
        assertTrue(mapped.detail?.contains("yt-dlp is not installed") == true)
        assertTrue(mapped.detail?.contains("https://example.com/watch") == true)
    }

    @Test
    fun `unknown throwable is preserved rather than discarded`() {
        val original = IllegalStateException("decoder blew up")
        val mapped = PlaybackError.fromException(original)
        assertEquals(PlaybackError.UnknownError.code, mapped.code)
        assertSame("the original throwable must be kept as the cause", original, mapped.cause)
        assertTrue(mapped.detail?.contains("decoder blew up") == true)
    }

    @Test
    fun `a throwable with no message still produces detail`() {
        val mapped = PlaybackError.fromException(RuntimeException())
        assertEquals(PlaybackError.UnknownError.code, mapped.code)
        assertNotNull(mapped.detail)
        assertTrue(mapped.detail!!.isNotBlank())
    }

    @Test
    fun `mapping is idempotent for existing playback exceptions`() {
        val mapped = PlaybackError.fromException(PlaybackError.SourceNotFound)
        assertSame(PlaybackError.SourceNotFound, mapped)
    }

    @Test
    fun `withDetail preserves the category and only replaces detail`() {
        val enriched = PlaybackError.withDetail(PlaybackError.DrmProtectedStream, "widevine L3")
        assertEquals(PlaybackError.DrmProtectedStream.code, enriched.code)
        assertEquals(PlaybackError.DrmProtectedStream.userMessage, enriched.userMessage)
        assertEquals(PlaybackError.DrmProtectedStream.retryable, enriched.retryable)
        assertEquals("widevine L3", enriched.detail)
    }

    @Test
    fun `withDetail with blank detail keeps the original detail`() {
        val base = PlaybackError.withDetail(PlaybackError.Timeout, "first")
        val again = PlaybackError.withDetail(base, "   ")
        assertEquals("first", again.detail)
    }

    @Test
    fun `error titles are human readable for every catalog entry`() {
        val codes = listOf(
            PlaybackError.UnsupportedCodec,
            PlaybackError.UnsupportedContainer,
            PlaybackError.NetworkError,
            PlaybackError.InvalidUrl,
            PlaybackError.YtDlpResolutionError,
            PlaybackError.DecoderInitializationError,
            PlaybackError.DrmProtectedStream,
            PlaybackError.InvalidHlsPlaylist,
            PlaybackError.InvalidDashManifest,
            PlaybackError.AudioDecoderFailure,
            PlaybackError.VideoDecoderFailure,
            PlaybackError.SubtitleFailure,
            PlaybackError.SourceNotFound,
            PlaybackError.Timeout,
            PlaybackError.UnknownError
        )
        codes.forEach { entry ->
            val title = PlaybackError.getErrorTitle(entry)
            assertTrue("title for ${entry.code} should not be blank", title.isNotBlank())
            assertTrue(
                "user message for ${entry.code} should not be blank",
                entry.userMessage.isNotBlank()
            )
            assertTrue(
                "solution for ${entry.code} should not be blank",
                entry.possibleSolution.isNotBlank()
            )
        }
    }

    @Test
    fun `log formatting includes code and detail`() {
        val log = PlaybackError.formatForLog(
            PlaybackError.withDetail(PlaybackError.InvalidHlsPlaylist, "no variants")
        )
        assertTrue(log.contains(PlaybackError.InvalidHlsPlaylist.code))
        assertTrue(log.contains("no variants"))
    }

    @Test
    fun `retryable flags distinguish transient from permanent failures`() {
        assertTrue("network errors are transient", PlaybackError.NetworkError.retryable)
        assertTrue("timeouts are transient", PlaybackError.Timeout.retryable)
        assertFalse("an unsupported codec is permanent", PlaybackError.UnsupportedCodec.retryable)
        assertFalse("an unsupported container is permanent", PlaybackError.UnsupportedContainer.retryable)
    }
}
