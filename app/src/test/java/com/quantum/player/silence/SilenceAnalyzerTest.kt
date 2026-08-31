package com.quantum.player.silence

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for silence detection, segment merging and thresholding.
 *
 * All of this is pure arithmetic on PCM bytes, so it is fully testable on the
 * JVM without a player.
 */
class SilenceAnalyzerTest {

    /** 8 kHz mono 16-bit: 2 bytes per frame, 4096 bytes = 2048 frames = 256 ms. */
    private val config = SilenceAnalyzerConfig(
        amplitudeThreshold = 0.02f,
        minSilenceDurationMs = 500,
        minSpeechDurationMs = 300,
        sampleRate = 8000,
        chunkSize = 4096,
        sampleFormat = SampleFormat.PCM_16BIT_LE,
        channelCount = 1
    )

    private val analyzer = SilenceAnalyzer(config)

    // ------------------------------------------------------------------
    // RMS
    // ------------------------------------------------------------------

    @Test
    fun `rms of digital silence is zero`() {
        val data = ByteArray(4096)
        assertEquals(0f, analyzer.calculateRms(data, 0, data.size), 1e-6f)
    }

    @Test
    fun `rms of a full scale signal is one`() {
        val frames = 2048
        val data = ByteArray(frames * 2)
        for (i in 0 until frames) {
            // Little-endian -32768, the most negative 16-bit sample.
            data[i * 2] = 0x00
            data[i * 2 + 1] = 0x80.toByte()
        }
        assertEquals(1f, analyzer.calculateRms(data, 0, data.size), 1e-6f)
    }

    @Test
    fun `rms is normalised so it never exceeds one`() {
        val data = ByteArray(4096) { 0xFF.toByte() }
        val rms = analyzer.calculateRms(data, 0, data.size)
        assertTrue("rms must stay within 0..1, was $rms", rms in 0f..1f)
    }

    @Test
    fun `rms of an empty range is zero`() {
        assertEquals(0f, analyzer.calculateRms(ByteArray(16), 4, 4), 1e-6f)
    }

    @Test
    fun `eight bit rms treats bytes as signed`() {
        val eightBitConfig = config.copy(sampleFormat = SampleFormat.PCM_8BIT)
        val eightBitAnalyzer = SilenceAnalyzer(eightBitConfig)
        // 0x80 is the 8-bit silence value (unsigned mid-point).
        val silence = ByteArray(256) { 0x80.toByte() }
        assertEquals(0f, eightBitAnalyzer.calculateRms(silence, 0, silence.size), 1e-6f)
        // 0xFF and 0x00 are the extremes.
        val loud = ByteArray(256) { 0xFF.toByte() }
        assertEquals(1f, eightBitAnalyzer.calculateRms(loud, 0, loud.size), 1e-6f)
    }

    // ------------------------------------------------------------------
    // Merging / hysteresis
    // ------------------------------------------------------------------

    @Test
    fun `adjacent chunks with the same classification merge into one segment`() {
        val merged = analyzer.applyHysteresis(
            listOf(
                chunk(0, 200, isSilence = true),
                chunk(200, 400, isSilence = true),
                chunk(400, 600, isSilence = true),
                chunk(600, 900, isSilence = false)
            )
        )
        assertEquals(2, merged.size)
        assertEquals(0L, merged[0].startMs)
        assertEquals(600L, merged[0].endMs)
        assertTrue(merged[0].isSilence)
        assertFalse(merged[1].isSilence)
        assertEquals(600L, merged[1].startMs)
        assertEquals(900L, merged[1].endMs)
    }

    @Test
    fun `a short speech blip between silences is folded into silence`() {
        val merged = analyzer.applyHysteresis(
            listOf(
                chunk(0, 500, isSilence = true),
                chunk(500, 600, isSilence = false), // 100 ms < minSpeechDurationMs
                chunk(600, 1100, isSilence = true)
            )
        )
        assertEquals("the click should disappear", 1, merged.size)
        assertTrue(merged[0].isSilence)
        assertEquals(0L, merged[0].startMs)
        assertEquals(1100L, merged[0].endMs)
    }

    @Test
    fun `a short silence between speech is folded into speech`() {
        val merged = analyzer.applyHysteresis(
            listOf(
                chunk(0, 500, isSilence = false),
                chunk(500, 600, isSilence = true), // 100 ms < minSilenceDurationMs
                chunk(600, 1100, isSilence = false)
            )
        )
        assertEquals(1, merged.size)
        assertFalse(merged[0].isSilence)
        assertEquals(0L, merged[0].startMs)
        assertEquals(1100L, merged[0].endMs)
    }

    @Test
    fun `runs long enough to be real are left alone`() {
        val merged = analyzer.applyHysteresis(
            listOf(
                chunk(0, 500, isSilence = false),
                chunk(500, 1500, isSilence = true),
                chunk(1500, 2000, isSilence = false)
            )
        )
        assertEquals(3, merged.size)
    }

    @Test
    fun `chunks are sorted before merging`() {
        val merged = analyzer.applyHysteresis(
            listOf(
                chunk(600, 900, isSilence = false),
                chunk(0, 300, isSilence = true),
                chunk(300, 600, isSilence = true)
            )
        )
        assertEquals(2, merged.size)
        assertEquals(0L, merged[0].startMs)
        assertEquals(600L, merged[0].endMs)
    }

    @Test
    fun `empty input produces no segments`() {
        assertTrue(analyzer.applyHysteresis(emptyList()).isEmpty())
    }

    @Test
    fun `segment duration is derived not stored`() {
        assertEquals(750L, SilenceSegment(startMs = 250, endMs = 1000, isSilence = true).durationMs)
        // A malformed segment must not report a negative length.
        assertEquals(0L, SilenceSegment(startMs = 900, endMs = 100, isSilence = true).durationMs)
    }

    // ------------------------------------------------------------------
    // Full analysis over synthetic PCM
    // ------------------------------------------------------------------

    @Test
    fun `silence at both ends of a tone is detected`() = runBlocking {
        val audio = buildAudio(
            sampleRate = 8000,
            layout = listOf(600 to false, 800 to true, 600 to false)
        )
        val result = analyzer.analyze(audio, uri = "test://tone")

        assertEquals(2000L, result.totalDurationMs)
        assertEquals("expected silence | tone | silence", 3, result.segments.size)
        assertTrue(result.segments[0].isSilence)
        assertEquals(0L, result.segments[0].startMs)
        assertFalse("the middle must be recognised as sound", result.segments[1].isSilence)
        assertTrue(result.segments[2].isSilence)
        assertEquals(result.totalDurationMs, result.segments[2].endMs)

        assertEquals(2, result.silences.size)
        assertTrue("silence must be a minority of the clip", result.totalSilenceMs < result.totalDurationMs)
        assertTrue(result.totalSilenceMs in 800..1200)
        assertEquals(1f, result.analysisQuality, 1e-6f)
    }

    @Test
    fun `a threshold above every sample classifies everything as silence`() = runBlocking {
        val loud = SilenceAnalyzer(config.copy(amplitudeThreshold = 2f))
        val audio = buildAudio(sampleRate = 8000, layout = listOf(1000 to true, 1000 to true))
        val result = loud.analyze(audio, uri = "test://all-silence")
        assertEquals(1, result.segments.size)
        assertTrue(result.segments[0].isSilence)
        assertEquals(result.totalDurationMs, result.totalSilenceMs)
    }

    @Test
    fun `a threshold below every sample finds no silence`() = runBlocking {
        val strict = SilenceAnalyzer(config.copy(amplitudeThreshold = 0f))
        val audio = buildAudio(sampleRate = 8000, layout = listOf(1000 to true, 1000 to true))
        val result = strict.analyze(audio, uri = "test://no-silence")
        assertEquals(1, result.segments.size)
        assertFalse(result.segments[0].isSilence)
        assertEquals(0L, result.totalSilenceMs)
        assertTrue(result.silences.isEmpty())
    }

    @Test
    fun `analysis results are cached per uri`() = runBlocking {
        val audio = buildAudio(sampleRate = 8000, layout = listOf(1000 to true))
        val first = analyzer.analyze(audio, uri = "test://cached")
        assertEquals(1, analyzer.cacheSize)
        val second = analyzer.analyze(ByteArray(0), uri = "test://cached")
        assertEquals("a cached entry must be returned, not recomputed", first, second)
        analyzer.clearCache()
        assertEquals(0, analyzer.cacheSize)
    }

    @Test
    fun `an empty buffer yields an empty result rather than a crash`() = runBlocking {
        val result = analyzer.analyze(ByteArray(0), uri = "test://empty")
        assertTrue(result.segments.isEmpty())
        assertEquals(0L, result.totalDurationMs)
        assertEquals(0f, result.analysisQuality, 1e-6f)
    }

    // ------------------------------------------------------------------
    // Skip silence controller
    // ------------------------------------------------------------------

    @Test
    fun `controller does nothing before it is enabled`() = runBlocking {
        SkipSilenceController(config).use { controller ->
            assertFalse(controller.isEnabled)
            val result = controller.processMedia(
                "test://disabled",
                buildAudio(8000, listOf(600 to false, 800 to true))
            )
            assertNull(result)
            assertNull(controller.getNextSpeechPosition(0))
        }
    }

    @Test
    fun `controller reports the end of the silence containing the position`() = runBlocking {
        SkipSilenceController(config).use { controller ->
            controller.isEnabled = true
            val result = controller.processMedia(
                "test://skip",
                buildAudio(8000, listOf(600 to false, 800 to true, 600 to false))
            )
            assertNotNull("analysis must produce a result", result)

            val firstSilenceEnd = result!!.silences.first().endMs
            assertEquals(firstSilenceEnd, controller.getNextSpeechPosition(0))
            // A position in the middle of the tone is not inside silence.
            assertNull(controller.getNextSpeechPosition(1000))
        }
    }

    @Test
    fun `a target already skipped is not offered again`() = runBlocking {
        SkipSilenceController(config).use { controller ->
            controller.isEnabled = true
            controller.processMedia(
                "test://loop",
                buildAudio(8000, listOf(600 to false, 800 to true, 600 to false))
            )

            val target = controller.skipTargetFor(0)
            assertNotNull(target)
            assertFalse(controller.isPositionSkipped(target!!))

            controller.onSeekPerformed(target)
            assertTrue(controller.isPositionSkipped(target))
            assertNull(
                "re-offering the same target would bounce playback forever",
                controller.skipTargetFor(0)
            )
        }
    }

    @Test
    fun `reset clears analysis and skip history`() = runBlocking {
        SkipSilenceController(config).use { controller ->
            controller.isEnabled = true
            controller.processMedia(
                "test://reset",
                buildAudio(8000, listOf(600 to false, 800 to true))
            )
            val target = controller.skipTargetFor(0)
            if (target != null) controller.onSeekPerformed(target)
            controller.reset()
            assertNull(controller.getNextSpeechPosition(0))
            if (target != null) assertFalse(controller.isPositionSkipped(target))
        }
    }

    @Test
    fun `null or empty audio produces no result`() = runBlocking {
        SkipSilenceController(config).use { controller ->
            controller.isEnabled = true
            assertNull(controller.processMedia("test://null-audio", null))
            assertNull(controller.processMedia("test://empty-audio", ByteArray(0)))
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun chunk(startMs: Long, endMs: Long, isSilence: Boolean) =
        SilenceSegment(startMs = startMs, endMs = endMs, isSilence = isSilence, rmsEnergy = 0f)

    /**
     * Build 16-bit mono PCM. Each pair is (durationMs, isTone).
     * Tone samples alternate between the two extremes so RMS is 1.0.
     */
    private fun buildAudio(sampleRate: Int, layout: List<Pair<Int, Boolean>>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        layout.forEach { (durationMs, isTone) ->
            val frames = (sampleRate.toLong() * durationMs / 1000L).toInt()
            repeat(frames) { frame ->
                if (isTone) {
                    val negative = frame % 2 == 0
                    out.write(if (negative) 0x00 else 0xFF)
                    out.write(if (negative) 0x80 else 0x7F)
                } else {
                    out.write(0x00)
                    out.write(0x00)
                }
            }
        }
        return out.toByteArray()
    }
}
