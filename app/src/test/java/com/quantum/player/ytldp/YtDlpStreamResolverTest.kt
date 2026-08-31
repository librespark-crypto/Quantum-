package com.quantum.player.ytldp

import com.quantum.player.error.PlaybackError
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.fail

/**
 * Unit tests for the yt-dlp resolver.
 *
 * The yt-dlp binary is not bundled with the app, so these tests inject a fake
 * process runner. That exercises the parts that are actually implemented -
 * URL validation, JSON parsing, format selection and stream URL extraction -
 * without pretending the binary exists.
 */
class YtDlpStreamResolverTest {

    private val resolver = YtDlpStreamResolver()

    /** Realistic `yt-dlp --dump-single-json` output for a three format video. */
    private val sampleJson = """
        {
          "title": "Test Video",
          "duration": 120,
          "thumbnail": "https://example.com/thumb.jpg",
          "formats": [
            {
              "format_id": "18", "ext": "mp4", "width": 640, "height": 360, "fps": 30,
              "vcodec": "avc1.42001E", "acodec": "mp4a.40.2", "tbr": 600,
              "protocol": "https", "url": "https://example.com/18.mp4"
            },
            {
              "format_id": "137", "ext": "mp4", "width": 1920, "height": 1080, "fps": 30,
              "vcodec": "avc1.640028", "acodec": "none", "tbr": 4000,
              "protocol": "https", "url": "https://example.com/137.mp4"
            },
            {
              "format_id": "140", "ext": "m4a", "vcodec": "none", "acodec": "mp4a.40.2",
              "tbr": 128, "protocol": "https", "url": "https://example.com/140.m4a"
            }
          ]
        }
    """.trimIndent()

    private fun stubResolver(exitCode: Int, output: String) = YtDlpStreamResolver(
        binaryProvider = { null },
        runner = { YtDlpStreamResolver.ProcessResult(exitCode, output) }
    )

    // ------------------------------------------------------------------
    // URL validation
    // ------------------------------------------------------------------

    @Test
    fun `http and https urls are accepted`() {
        assertTrue(resolver.validateUrl("https://example.com/watch?v=abc"))
        assertTrue(resolver.validateUrl("http://example.com/watch"))
    }

    @Test
    fun `streaming schemes are accepted`() {
        assertTrue(resolver.validateUrl("rtsp://camera.local/stream"))
        assertTrue(resolver.validateUrl("rtmp://live.example.com/app"))
    }

    @Test
    fun `validation is case insensitive`() {
        assertTrue(resolver.validateUrl("HTTPS://EXAMPLE.COM/watch"))
    }

    @Test
    fun `non urls and blanks are rejected`() {
        assertFalse(resolver.validateUrl(""))
        assertFalse(resolver.validateUrl("   "))
        // The old check matched on ".com", which let file paths through.
        assertFalse(resolver.validateUrl("/sdcard/Movies/example.com.mp4"))
        assertFalse(resolver.validateUrl("file:///sdcard/a.mp4"))
        assertFalse(resolver.validateUrl("just some words"))
    }

    @Test
    fun `an invalid url fails without invoking the binary`() = runBlocking {
        var invoked = false
        val guarded = YtDlpStreamResolver(
            binaryProvider = { null },
            runner = {
                invoked = true
                YtDlpStreamResolver.ProcessResult(0, sampleJson)
            }
        )
        val result = guarded.resolve("not a url")
        assertFalse(result.success)
        assertEquals("Invalid URL format", result.error)
        assertFalse("the binary must not run for an invalid url", invoked)
    }

    // ------------------------------------------------------------------
    // JSON parsing
    // ------------------------------------------------------------------

    @Test
    fun `parses metadata and all three formats`() {
        val result = resolver.parse(sampleJson)
        assertTrue(result.success)
        assertEquals("Test Video", result.title)
        assertEquals(120L, result.durationSeconds)
        assertEquals("https://example.com/thumb.jpg", result.thumbnail)
        assertEquals(3, result.formats.size)
        assertNull(result.error)
    }

    @Test
    fun `video and audio only formats are classified correctly`() {
        val formats = resolver.extractFormats(org.json.JSONObject(sampleJson).optJSONArray("formats"))
        val progressive = formats.first { it.formatId == "18" }
        val videoOnly = formats.first { it.formatId == "137" }
        val audioOnly = formats.first { it.formatId == "140" }

        assertTrue(progressive.hasVideo)
        assertTrue(progressive.hasAudio)
        assertTrue(progressive.isProgressive)

        assertTrue(videoOnly.hasVideo)
        assertFalse(videoOnly.hasAudio)
        assertFalse(videoOnly.isProgressive)

        assertFalse(audioOnly.hasVideo)
        assertTrue(audioOnly.hasAudio)
        assertFalse(audioOnly.isProgressive)
    }

    @Test
    fun `missing resolution fields parse as null not zero`() {
        val formats = resolver.extractFormats(org.json.JSONObject(sampleJson).optJSONArray("formats"))
        val audioOnly = formats.first { it.formatId == "140" }
        assertNull(audioOnly.width)
        assertNull(audioOnly.height)
        assertEquals(0L, audioOnly.pixels)
    }

    @Test
    fun `formats without an id or a usable body are skipped`() {
        val json = org.json.JSONArray(
            """
            [
              {"ext":"mp4","url":"https://example.com/noid.mp4"},
              {"format_id":"","url":"https://example.com/blank.mp4"},
              {"format_id":"22","ext":"mp4","width":1280,"height":720,
               "vcodec":"avc1","acodec":"mp4a","url":"https://example.com/22.mp4"}
            ]
            """.trimIndent()
        )
        val formats = resolver.extractFormats(json)
        assertEquals(1, formats.size)
        assertEquals("22", formats[0].formatId)
    }

    @Test
    fun `a null formats array yields an empty list`() {
        assertTrue(resolver.extractFormats(null).isEmpty())
    }

    @Test
    fun `invalid json produces a failure not an exception`() {
        val result = resolver.parse("this is not json {{{")
        assertFalse(result.success)
        assertNotNull(result.error)
        assertTrue(result.formats.isEmpty())
    }

    @Test
    fun `a json document with no formats is reported as a failure`() {
        val result = resolver.parse("""{"title":"Empty","formats":[]}""")
        assertFalse(result.success)
        assertTrue(result.formats.isEmpty())
        assertNotNull(result.error)
    }

    // ------------------------------------------------------------------
    // Format selection
    // ------------------------------------------------------------------

    private val parsedFormats = resolver.parse(sampleJson).formats

    @Test
    fun `a progressive format is preferred by default`() {
        val selected = resolver.selectFormat(parsedFormats)
        assertEquals("18", selected?.formatId)
        assertTrue(selected!!.isProgressive)
    }

    @Test
    fun `video only selection returns the highest resolution video`() {
        val selected = resolver.selectFormat(parsedFormats, videoOnly = true)
        assertEquals("137", selected?.formatId)
    }

    @Test
    fun `audio only selection returns the audio track`() {
        val selected = resolver.selectFormat(parsedFormats, audioOnly = true)
        assertEquals("140", selected?.formatId)
    }

    @Test
    fun `max height excludes formats that are too large`() {
        val selected = resolver.selectFormat(
            parsedFormats,
            videoOnly = true,
            preferences = YtDlpStreamResolver.FormatPreferences(maxHeight = 480, preferProgressive = false)
        )
        assertEquals("1080p must be excluded", "18", selected?.formatId)
    }

    @Test
    fun `preferProgressive false falls back to the best video only stream`() {
        val selected = resolver.selectFormat(
            parsedFormats,
            preferences = YtDlpStreamResolver.FormatPreferences(preferProgressive = false)
        )
        assertEquals("137", selected?.formatId)
    }

    @Test
    fun `selection returns null when nothing matches`() {
        assertTrue(resolver.selectFormat(emptyList()) == null)
        // Formats with no url cannot be played.
        val unplayable = listOf(
            YtDlpStreamResolver.YtDlpFormat(
                formatId = "x", ext = "mp4", width = 640, height = 360, fps = 30,
                vcodec = "avc1", acodec = "mp4a", tbr = 600, filesize = null,
                formatNote = null, protocol = "https", url = null
            )
        )
        assertNull(resolver.selectFormat(unplayable))
    }

    @Test
    fun `stream url is looked up by format id`() {
        assertEquals(
            "https://example.com/137.mp4",
            resolver.streamUrlFor(parsedFormats, "137")
        )
        assertNull(resolver.streamUrlFor(parsedFormats, "does-not-exist"))
    }

    // ------------------------------------------------------------------
    // Resolution pipeline
    // ------------------------------------------------------------------

    @Test
    fun `a successful run resolves through the injected runner`() = runBlocking {
        val result = stubResolver(exitCode = 0, output = sampleJson)
            .resolve("https://example.com/watch?v=abc")
        assertTrue(result.success)
        assertEquals(3, result.formats.size)
        assertEquals("Test Video", result.title)
    }

    @Test
    fun `a non zero exit code surfaces the tool output`() = runBlocking {
        val result = stubResolver(
            exitCode = 1,
            output = "ERROR: [youtube] abc: Video unavailable"
        ).resolve("https://example.com/watch?v=abc")
        assertFalse(result.success)
        assertTrue(result.error!!.contains("exit code 1"))
        assertTrue(result.error!!.contains("Video unavailable"))
    }

    @Test
    fun `a missing binary fails with a truthful message`() = runBlocking {
        // No binaryProvider and no runner: exactly the situation on a real device.
        val result = YtDlpStreamResolver().resolve("https://example.com/watch?v=abc")
        assertFalse(result.success)
        assertTrue(result.error!!.contains("not installed"))
        assertTrue("no formats may be invented", result.formats.isEmpty())
    }

    @Test
    fun `a failure maps onto the structured playback error`() {
        val failure = YtDlpStreamResolver.YtDlpResult(success = false, error = "boom")
        val exception = resolver.toException("https://example.com/watch", failure)
        assertEquals("https://example.com/watch", exception.url)
        assertEquals("boom", exception.detail)
        assertEquals(
            PlaybackError.YtDlpResolutionError.code,
            PlaybackError.fromException(exception).code
        )
    }

    // ------------------------------------------------------------------
    // Wrapper
    // ------------------------------------------------------------------

    @Test
    fun `the wrapper resolves a playable stream url end to end`() = runBlocking {
        val wrapper = YtDlpResolverWrapper(resolver = stubResolver(0, sampleJson))
        val result = wrapper.resolveStreamUrl("https://example.com/watch?v=abc")
        assertTrue(result.isSuccess)
        val stream = result.getOrThrow()
        assertEquals("https://example.com/18.mp4", stream.streamUrl)
        assertEquals("18", stream.formatId)
        assertEquals("mp4", stream.container)
        assertEquals("Test Video", stream.title)
        assertTrue(stream.hasVideo)
        assertTrue(stream.hasAudio)
    }

    @Test
    fun `the wrapper reports failure instead of returning a null url`() = runBlocking {
        val wrapper = YtDlpResolverWrapper(resolver = stubResolver(1, "ERROR: nope"))
        val result = wrapper.resolveStreamUrl("https://example.com/watch?v=abc")
        assertTrue(result.isFailure)
        result.exceptionOrNull()?.let { assertTrue(it is PlaybackError.YtDlpResolutionException) }
            ?: fail("expected an exception")
    }

    @Test
    fun `the wrapper exposes media info`() = runBlocking {
        val wrapper = YtDlpResolverWrapper(resolver = stubResolver(0, sampleJson))
        val info = wrapper.getMediaInfo("https://example.com/watch?v=abc").getOrThrow()
        assertEquals("Test Video", info.title)
        assertEquals(120L, info.durationSeconds)
        assertEquals(3, info.formatCount)
        assertEquals(1920, info.bestWidth)
        assertEquals(1080, info.bestHeight)
        assertEquals(30, info.fps)
    }

    @Test
    fun `the wrapper rejects an invalid url`() = runBlocking {
        val wrapper = YtDlpResolverWrapper(resolver = stubResolver(0, sampleJson))
        val result = wrapper.resolve("/sdcard/not/a/url")
        assertTrue(result.isFailure)
    }

    @Test
    fun `factory builds a wrapper without a bundled binary`() {
        assertNotNull(YtDlp.create(binaryPath = null))
        assertEquals("yt-dlp", YtDlp.DEFAULT_BINARY_NAME)
    }
}
