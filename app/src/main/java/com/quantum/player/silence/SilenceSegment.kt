package com.quantum.player.silence

import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Represents a segment of audio that is either speech or silence.
 */
data class SilenceSegment(
    val startMs: Long,
    val endMs: Long,
    val isSilence: Boolean,
    val confidence: Float = 1.0f,
    val rmsEnergy: Float = 0.0f
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

/**
 * Result of audio silence analysis containing speech/silence segments.
 */
data class SilenceAnalysisResult(
    val segments: List<SilenceSegment>,
    val totalDurationMs: Long,
    val analysisQuality: Float = 1.0f,
    val analysisTimeMs: Long = 0
) {
    /** Silence segments only, in playback order. */
    val silences: List<SilenceSegment> get() = segments.filter { it.isSilence }

    /** Total milliseconds of detected silence. */
    val totalSilenceMs: Long get() = silences.sumOf { it.durationMs }
}

/**
 * Configuration for the silence analyzer.
 *
 * [chunkSize] is a size in **bytes**, not samples.
 */
data class SilenceAnalyzerConfig(
    val amplitudeThreshold: Float = 0.02f,
    val minSilenceDurationMs: Long = 500,
    val minSpeechDurationMs: Long = 300,
    val hysteresis: Float = 0.1f,
    val sampleRate: Int = 44100,
    val chunkSize: Int = 4096,
    val sampleFormat: SampleFormat = SampleFormat.PCM_16BIT_LE,
    val channelCount: Int = 2
) {
    /** Bytes per single sample frame (all channels). */
    val frameSize: Int get() = sampleFormat.bytesPerSample * channelCount.coerceAtLeast(1)

    /** Duration of one analysis chunk. */
    val chunkDurationMs: Long
        get() = (chunkSize.toLong() / frameSize.coerceAtLeast(1)) * 1000L / sampleRate.coerceAtLeast(1)

    init {
        require(amplitudeThreshold > 0f) { "amplitudeThreshold must be > 0" }
        require(sampleRate > 0) { "sampleRate must be > 0" }
        require(chunkSize > 0) { "chunkSize must be > 0" }
        require(minSilenceDurationMs >= 0) { "minSilenceDurationMs must be >= 0" }
        require(minSpeechDurationMs >= 0) { "minSpeechDurationMs must be >= 0" }
    }
}

/** PCM layouts the analyzer can read. */
enum class SampleFormat(val bytesPerSample: Int) {
    /** Unsigned 8-bit, the WAV "8-bit PCM" layout. */
    PCM_8BIT(1),

    /** Signed 16-bit little endian, what Media3's audio pipeline produces. */
    PCM_16BIT_LE(2)
}

/**
 * Analyzer that detects silence in audio streams using an amplitude threshold.
 * Performs analysis off the main thread and caches results per media item.
 */
class SilenceAnalyzer(private val config: SilenceAnalyzerConfig = SilenceAnalyzerConfig()) {

    /** Cache for analysis results per media item URI. */
    private val analysisCache = mutableMapOf<String, SilenceAnalysisResult>()

    /** Number of results currently cached. */
    val cacheSize: Int get() = analysisCache.size

    /** Drop all cached results. */
    fun clearCache() {
        synchronized(analysisCache) { analysisCache.clear() }
    }

    /**
     * Analyze audio for silence segments.
     *
     * @param audioData raw PCM samples in [SilenceAnalyzerConfig.sampleFormat]
     * @param uri cache key for the media item
     * @return speech/silence segments covering the analysed range
     */
    suspend fun analyze(audioData: ByteArray, uri: String): SilenceAnalysisResult {
        synchronized(analysisCache) {
            analysisCache[uri]?.let { return it }
        }

        val startedAt = System.nanoTime()
        val result = withContext(Dispatchers.Default) {
            buildResult(audioData, System.nanoTime() - startedAt)
        }
        synchronized(analysisCache) { analysisCache[uri] = result }
        return result
    }

    private suspend fun buildResult(
        audioData: ByteArray,
        elapsedNanos: Long
    ): SilenceAnalysisResult {
        val frameSize = config.frameSize.coerceAtLeast(1)
        val chunkFrames = (config.chunkSize / frameSize).coerceAtLeast(1)
        val chunkBytes = chunkFrames * frameSize

        val chunks = mutableListOf<SilenceSegment>()
        var offset = 0
        var index = 0
        while (offset < audioData.size) {
            currentCoroutineContext().ensureActive()
            val end = minOf(offset + chunkBytes, audioData.size)
            val rms = calculateRms(audioData, offset, end)
            val startMs = framesToMs(index.toLong() * chunkFrames)
            val finishMs = framesToMs(
                index.toLong() * chunkFrames + ((end - offset) / frameSize).toLong()
            )
            chunks.add(
                SilenceSegment(
                    startMs = startMs,
                    endMs = finishMs,
                    isSilence = rms < config.amplitudeThreshold,
                    rmsEnergy = rms
                )
            )
            offset = end
            index++
        }

        val totalDurationMs = framesToMs((audioData.size / frameSize).toLong())
        val merged = applyHysteresis(chunks)
        val coveredMs = merged.sumOf { it.durationMs }
        return SilenceAnalysisResult(
            segments = merged,
            totalDurationMs = totalDurationMs,
            analysisQuality = if (totalDurationMs > 0) {
                (coveredMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            },
            analysisTimeMs = elapsedNanos / 1_000_000L
        )
    }

    private fun framesToMs(frames: Long): Long =
        frames * 1000L / config.sampleRate.toLong()

    /**
     * RMS energy of a byte range, normalised to 0..1.
     *
     * The previous implementation treated every byte as an independent sample and
     * mapped negatives with `(b + 256) / 128`, which produced values above 1.0
     * and ignored the sample width entirely.
     */
    internal fun calculateRms(data: ByteArray, from: Int, to: Int): Float {
        val frameSize = config.frameSize.coerceAtLeast(1)
        var sumSquares = 0.0
        var samples = 0
        var cursor = from
        while (cursor + frameSize <= to) {
            when (config.sampleFormat) {
                SampleFormat.PCM_8BIT -> {
                    // WAV 8-bit PCM is unsigned with 128 as the silence point.
                    val value = ((data[cursor].toInt() and 0xFF) - 128) / 128.0
                    sumSquares += value * value
                    samples++
                }

                SampleFormat.PCM_16BIT_LE -> {
                    // Interleaved channels: average the frame's channels.
                    var frameValue = 0.0
                    for (channel in 0 until config.channelCount.coerceAtLeast(1)) {
                        val byteIndex = cursor + channel * SampleFormat.PCM_16BIT_LE.bytesPerSample
                        if (byteIndex + 1 >= to) break
                        val low = data[byteIndex].toInt() and 0xFF
                        val high = data[byteIndex + 1].toInt()
                        frameValue += ((high shl 8) or low).toShort().toDouble() / 32768.0
                    }
                    frameValue /= config.channelCount.coerceAtLeast(1)
                    sumSquares += frameValue * frameValue
                    samples++
                }
            }
            cursor += frameSize
        }
        return if (samples > 0) sqrt(sumSquares / samples).toFloat() else 0f
    }

    /**
     * Merge adjacent chunks with the same classification, then remove runs that
     * are too short to be real: a silence shorter than [minSilenceDurationMs] is
     * noise, and a speech burst shorter than [minSpeechDurationMs] inside silence
     * is a click. Both are folded into their neighbours.
     */
    internal fun applyHysteresis(chunks: List<SilenceSegment>): List<SilenceSegment> {
        if (chunks.isEmpty()) return emptyList()

        val sorted = chunks.sortedBy { it.startMs }
        var runs = collapse(sorted)

        // Folding one run can make its neighbour short; iterate until stable.
        var guard = 0
        while (guard++ < MAX_HYSTERESIS_PASSES) {
            val before = runs
            runs = collapse(foldShortRuns(runs))
            if (runs == before) break
        }
        return runs
    }

    /** Combine consecutive chunks that share a classification. */
    private fun collapse(sorted: List<SilenceSegment>): List<SilenceSegment> {
        val result = mutableListOf<SilenceSegment>()
        var current: SilenceSegment? = null
        for (chunk in sorted) {
            val active = current
            if (active != null && active.isSilence == chunk.isSilence) {
                current = active.copy(
                    endMs = maxOf(active.endMs, chunk.endMs),
                    rmsEnergy = (active.rmsEnergy + chunk.rmsEnergy) / 2f
                )
            } else {
                active?.let { result.add(it) }
                current = chunk
            }
        }
        current?.let { result.add(it) }
        return result
    }

    /**
     * Replace runs below the configured minimums with the surrounding state:
     * a short silence between two speech runs becomes speech, and a short speech
     * blip between two silences becomes silence.
     */
    private fun foldShortRuns(runs: List<SilenceSegment>): List<SilenceSegment> {
        if (runs.size < 3) return runs
        val out = runs.toMutableList()
        var changed = true
        while (changed) {
            changed = false
            var i = 1
            while (i < out.size - 1) {
                val run = out[i]
                val tooShort = if (run.isSilence) {
                    run.durationMs < config.minSilenceDurationMs
                } else {
                    run.durationMs < config.minSpeechDurationMs
                }
                val previous = out[i - 1]
                val next = out[i + 1]
                if (tooShort && previous.isSilence == next.isSilence) {
                    out[i - 1] = previous.copy(endMs = run.endMs)
                    out.removeAt(i)
                    changed = true
                    // Do not advance: the run now at index i may also be short.
                } else {
                    i++
                }
            }
        }
        return out
    }

    private companion object {
        const val MAX_HYSTERESIS_PASSES = 8
    }
}

/**
 * Controller for skip silence functionality.
 * Automatically seeks across detected silence during playback.
 *
 * Analysis is cancellable: [close] (called when the player is released) stops
 * any in-flight analysis so it cannot outlive the session.
 */
class SkipSilenceController(
    private val config: SilenceAnalyzerConfig = SilenceAnalyzerConfig(),
    private val analyzer: SilenceAnalyzer = SilenceAnalyzer(config),
    private val scope: CoroutineScope? = null
) : AutoCloseable {

    /** Whether skip silence is enabled. */
    @Volatile
    var isEnabled: Boolean = false
        set(value) {
            field = value
            if (!value) analysisJob?.cancel()
        }

    /** Current analysis result, null until analysis completes. */
    @Volatile
    private var currentResult: SilenceAnalysisResult? = null

    /** Silence segment ends we have already jumped to, to stop seek loops. */
    private val skippedPositions = mutableSetOf<Long>()

    private var analysisJob: Job? = null

    /** True while an analysis is running. */
    val isAnalyzing: Boolean get() = analysisJob?.isActive == true

    /**
     * Analyse [uri] and make the result available to the seek helpers.
     * Returns null when analysis is disabled, no audio data is available, or
     * the analysis failed - in the failure case the controller disables itself
     * rather than silently skipping nothing.
     */
    suspend fun processMedia(uri: String, audioData: ByteArray?): SilenceAnalysisResult? {
        if (!isEnabled || audioData == null || audioData.isEmpty()) return null
        return try {
            analyzer.analyze(audioData, uri).also { currentResult = it }
        } catch (e: Exception) {
            // Analysis is impossible for this source (e.g. an encrypted stream).
            isEnabled = false
            currentResult = null
            null
        }
    }

    /** Start analysis on the controller's scope without suspending the caller. */
    fun processMediaAsync(uri: String, audioData: ByteArray?): Job? {
        val owned = scope ?: return null
        if (!isEnabled || audioData == null || audioData.isEmpty()) return null
        analysisJob?.cancel()
        // `this` inside a launch block is the CoroutineScope, not the Job, so the
        // handle has to be taken from the returned Job instead.
        val job = owned.launch { processMedia(uri, audioData) }
        analysisJob = job
        return job
    }

    /**
     * Get the next speech position after [currentPosition], or null when the
     * player is not inside a silence region.
     */
    fun getNextSpeechPosition(currentPosition: Long): Long? {
        val result = currentResult ?: return null
        val active = result.silences.firstOrNull { currentPosition in it.startMs until it.endMs }
            ?: return null
        return active.endMs.takeIf { it > currentPosition }
    }

    /** End of the silence region containing [currentPosition]. */
    fun getSilenceEndPosition(currentPosition: Long): Long? =
        getNextSpeechPosition(currentPosition)

    /**
     * Decide whether playback should jump from [currentPosition].
     *
     * Returns the seek target, or null to keep playing normally. A target that
     * has already been used is not returned again, which is what stops the
     * player from bouncing over the same silent gap forever.
     */
    fun skipTargetFor(currentPosition: Long): Long? {
        if (!isEnabled) return null
        val target = getNextSpeechPosition(currentPosition) ?: return null
        if (isPositionSkipped(target)) return null
        return target
    }

    /** Record that a jump to [position] actually happened. */
    fun onSeekPerformed(position: Long) {
        skippedPositions.add(position)
    }

    /** True when [position] has already been skipped to. */
    fun isPositionSkipped(position: Long): Boolean = skippedPositions.contains(position)

    /** Forget the analysis for the current item (call on media change). */
    fun reset() {
        analysisJob?.cancel()
        analysisJob = null
        currentResult = null
        skippedPositions.clear()
    }

    /** Cancel analysis and release everything held for this session. */
    override fun close() {
        analysisJob?.cancel()
        analysisJob = null
        isEnabled = false
        analyzer.clearCache()
        skippedPositions.clear()
        currentResult = null
    }
}
