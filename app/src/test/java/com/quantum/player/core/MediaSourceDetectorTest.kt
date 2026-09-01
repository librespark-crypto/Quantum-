package com.quantum.player.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for media source / URL detection.
 *
 * These run on the JVM with no Android dependency, which is the point: source
 * detection has to be right before the player is ever constructed.
 */
class MediaSourceDetectorTest {

    @Test
    fun `m3u8 extension is detected as HLS`() {
        assertEquals(MediaKind.Hls, MediaSourceDetector.kindOf("https://cdn.example.com/a/b/index.m3u8"))
    }

    @Test
    fun `mpd extension is detected as DASH`() {
        assertEquals(MediaKind.Dash, MediaSourceDetector.kindOf("https://cdn.example.com/manifest.mpd"))
    }

    @Test
    fun `mp4 mkv and webm are progressive`() {
        assertEquals(MediaKind.Progressive, MediaSourceDetector.kindOf("/sdcard/Movies/a.mp4"))
        assertEquals(MediaKind.Progressive, MediaSourceDetector.kindOf("/sdcard/Movies/b.mkv"))
        assertEquals(MediaKind.Progressive, MediaSourceDetector.kindOf("/sdcard/Movies/c.webm"))
    }

    @Test
    fun `rtsp scheme is detected before the extension is considered`() {
        assertEquals(
            MediaKind.Rtsp,
            MediaSourceDetector.kindOf("rtsp://camera.local:554/stream1")
        )
    }

    @Test
    fun `declared mime type wins over the file extension`() {
        // A .mp4 URL that the server declares as an HLS playlist is HLS.
        assertEquals(
            MediaKind.Hls,
            MediaSourceDetector.kindOf(
                uri = "https://cdn.example.com/stream.mp4",
                declaredMimeType = MediaSourceDetector.MIME_HLS
            )
        )
        assertEquals(
            MediaKind.Dash,
            MediaSourceDetector.kindOf(
                uri = "https://cdn.example.com/stream.mp4",
                declaredMimeType = MediaSourceDetector.MIME_DASH
            )
        )
    }

    @Test
    fun `declared mime type is matched case insensitively`() {
        assertEquals(
            MediaKind.Hls,
            MediaSourceDetector.kindOf("https://x/y.mp4", "APPLICATION/X-MPEGURL")
        )
    }

    @Test
    fun `unknown extension yields unknown rather than a guess`() {
        assertEquals(MediaKind.Unknown, MediaSourceDetector.kindOf("https://cdn.example.com/page.html"))
        assertEquals(MediaKind.Unknown, MediaSourceDetector.kindOf("https://cdn.example.com/noext"))
    }

    @Test
    fun `forced mime type maps back onto the media3 constants`() {
        assertEquals(MediaSourceDetector.MIME_HLS, MediaSourceDetector.forcedMimeType(MediaKind.Hls))
        assertEquals(MediaSourceDetector.MIME_DASH, MediaSourceDetector.forcedMimeType(MediaKind.Dash))
        assertEquals(MediaSourceDetector.MIME_RTSP, MediaSourceDetector.forcedMimeType(MediaKind.Rtsp))
        // Progressive lets the extractor sniff, so there is nothing to force.
        assertNull(MediaSourceDetector.forcedMimeType(MediaKind.Progressive))
        assertNull(MediaSourceDetector.forcedMimeType(MediaKind.Unknown))
    }

    @Test
    fun `network uri detection covers http https and rtsp only`() {
        assertTrue(MediaSourceDetector.isNetworkUri("https://example.com/a.mp4"))
        assertTrue(MediaSourceDetector.isNetworkUri("http://example.com/a.mp4"))
        assertTrue(MediaSourceDetector.isNetworkUri("rtsp://example.com/a"))
        assertFalse(MediaSourceDetector.isNetworkUri("file:///sdcard/a.mp4"))
        assertFalse(MediaSourceDetector.isNetworkUri("content://media/external/video/1"))
        assertFalse(MediaSourceDetector.isNetworkUri("/sdcard/a.mp4"))
    }

    @Test
    fun `extension ignores query string and fragment`() {
        assertEquals("m3u8", MediaSourceDetector.extensionOf("https://x/y/index.m3u8?token=abc"))
        assertEquals("mp4", MediaSourceDetector.extensionOf("https://x/y/a.mp4#t=10"))
        assertEquals("", MediaSourceDetector.extensionOf("https://x/y/noext"))
        assertEquals("", MediaSourceDetector.extensionOf("https://x/y/trailingdot."))
    }

    @Test
    fun `extension is lower cased`() {
        assertEquals("mkv", MediaSourceDetector.extensionOf("/sdcard/Movie.MKV"))
    }

    @Test
    fun `container label is null when there is no extension`() {
        assertEquals("mp4", MediaSourceDetector.containerOf("/sdcard/a.mp4"))
        assertNull(MediaSourceDetector.containerOf("https://x/y/noext"))
    }

    @Test
    fun `browser lists media but not arbitrary files`() {
        assertTrue(MediaSourceDetector.isSupportedMedia("movie.mp4"))
        assertTrue(MediaSourceDetector.isSupportedMedia("playlist.m3u8"))
        assertTrue(MediaSourceDetector.isSupportedMedia("manifest.mpd"))
        assertFalse(MediaSourceDetector.isSupportedMedia("notes.txt"))
        assertFalse(MediaSourceDetector.isSupportedMedia("archive.zip"))
        assertFalse(MediaSourceDetector.isSupportedMedia("noextension"))
    }

    @Test
    fun `scheme parsing rejects strings that are not urls`() {
        assertEquals("https", MediaSourceDetector.schemeOf("https://example.com"))
        assertEquals("content", MediaSourceDetector.schemeOf("content://media/external/video/1"))
        assertEquals("", MediaSourceDetector.schemeOf("/sdcard/a.mp4"))
        // A Windows style path must not be read as scheme "c".
        assertEquals("", MediaSourceDetector.schemeOf("c://weird"))
    }
}
