package com.quantum.player.silence

import kotlin.math.abs

/**
 * Represents a segment of audio that is either speech or silence.
 */
data class SilenceSegment(
    val startMs: Long,
    val endMs: Long,
    val isSilence: Boolean,
    val confidence: Float = 1.0f,
    val rmsEnergy: Float = 0.0f
)

/**
 * Result of audio silence analysis containing speech/silence segments.
 */
data class SilenceAnalysisResult(
    val segments: List<SilenceSegment>,
    val totalDurationMs: Long,
    val analysisQuality: Float = 1.0f,
    val analysisTimeMs: Long = 0
)

/**
 * Configuration for the silence analyzer.
 */
data class SilenceAnalyzerConfig(
    val amplitudeThreshold: Float = 0.02f,
    val minSilenceDurationMs: Long = 500,
    val minSpeechDurationMs: Long = 300,
    val hysteresis: Float = 0.1f,
    val sampleRate: Int = 44100,
    val chunkSize: Int = 1024
)

/**
 * Analyzer that detects silence in audio streams using amplitude threshold.
 * Performs analysis off the main thread and caches results per media item.
 */
class SilenceAnalyzer(private val config: SilenceAnalyzerConfig = SilenceAnalyzerConfig()) {

    /** Cache for analysis results per media item URI */
    private val analysisCache = mutableMapOf<String, SilenceAnalysisResult>()

    /**
     * Analyze audio stream for silence segments.
     * Returns a SilenceAnalysisResult with speech/silence segments.
     * Performs analysis off the main thread.
     */
    suspend fun analyze(audioData: ByteArray, uri: String): SilenceAnalysisResult {
        // Check cache first
        if (analysisCache.containsKey(uri)) {
            return analysisCache[uri]!!
        }

        return withContext(Dispatchers.Default) {
            val segments = mutableListOf<SilenceSegment>()
            val sampleRate = config.sampleRate
            val chunkSize = config.chunkSize

            // Process audio chunks
            val numChunks = audioData.size / chunkSize
            for (i in 0 until numChunks) {
                val chunk = audioData.sliceArray(
                    i * chunkSize until min((i + 1) * chunkSize, audioData.size)
                )
                val rms = calculateRMS(chunk)
                val isSilence = rms < config.amplitudeThreshold

                val startMs = i * chunkSize.toLong() * 1000 / sampleRate
                val endMs = startMs + chunkSize.toLong() * 1000 / sampleRate

                segments.add(SilenceSegment(startMs, endMs, isSilence, rms = rms))
            }

            // Apply hysteresis/debounce to merge adjacent silence segments
            val filteredSegments = applyHysteresis(segments)

            val result = SilenceAnalysisResult(
                segments = filteredSegments,
                totalDurationMs = audioData.size.toLong() * 1000 / sampleRate,
                analysisQuality = calculateAnalysisQuality(filteredSegments)
            )

            // Cache result
            analysisCache[uri] = result
            result
        }
    }

    /**
     * Calculate RMS (Root Mean Square) energy of audio chunk.
     */
    private fun calculateRMS(chunk: ByteArray): Float {
        var sum: Float = 0f
        var count = 0

        for (i in chunk.indices) {
            val sample = when {
                chunk[i] >= 0 -> chunk[i].toFloat() / 128f
                else -> (chunk[i] + 256).toFloat() / 128f
            }
            sum += sample * sample
            count++
        }

        return if (count > 0) sqrt(sum / count) else 0f
    }

    /**
     * Apply hysteresis to merge adjacent silence segments.
     * Prevents rapid toggling between speech and silence.
     */
    private fun applyHysteresis(segments: List<SilenceSegment>): List<SilenceSegment> {
        if (segments.isEmpty()) return segments

        val result = mutableListOf<SilenceSegment>()
        var currentSilenceStart: Long? = null
        var currentSpeechStart: Long? = null

        // Sort segments by start time
        val sorted = segments.sortedBy { it.startMs }

        for (segment in sorted) {
            if (segment.isSilence) {
                // If we were in speech, end the speech segment
                if (currentSpeechStart != null) {
                    result.add(SilenceSegment(currentSpeechStart!!, segment.startMs, false))
                    currentSpeechStart = null
                }
                // Start or extend silence segment
                if (currentSilenceStart == null) {
                    currentSilenceStart = segment.startMs
                }
                // Otherwise, just extend (the segment will be added at end or merged)
            } else {
                // If we were in silence and it's long enough, keep it
                if (currentSilenceStart != null) {
                    val silenceDuration = segment.startMs - currentSilenceStart!!
                    if (silenceDuration >= config.minSilenceDurationMs) {
                        result.add(SilenceSegment(currentSilenceStart, segment.startMs, true))
                    }
                    currentSilenceStart = null
                }
                // Start speech segment
                if (currentSpeechStart == null) {
                    currentSpeechStart = segment.startMs
                }
            }
        }

        // Handle remaining segments
        if (currentSilenceStart != null) {
            // Get the last segment end
            val lastEnd = sorted.lastOrNull()?.endMs ?: currentSilenceStart
            result.add(SilenceSegment(currentSilenceStart, lastEnd, true))
        }
        if (currentSpeechStart != null) {
            val lastEnd = sorted.lastOrNull()?.endMs ?: currentSpeechStart
            result.add(SilenceSegment(currentSpeechStart, lastEnd, false))
        }

        return result
    }

    /**
     * Calculate analysis quality based on segment distribution.
     */
    private fun calculateAnalysisQuality(segments: List<SilenceSegment>): Float {
        if (segments.isEmpty()) return 1.0f
        val silenceCount = segments.count { it.isSilence }
        val total = segments.size
        return if (total > 0) (silenceCount.toFloat() / total) else 1.0f
    }
}

/**
 * Controller for skip silence functionality.
 * Automatically seeks across detected silence during playback.
 */
class SkipSilenceController(
    private val config: SilenceAnalyzerConfig = SilenceAnalyzerConfig(),
    private val analyzer: SilenceAnalyzer = SilenceAnalyzer()
) : AutoCloseable {

    /** Whether skip silence is enabled */
    var isEnabled: Boolean = false

    /** Current analysis result */
    private var currentResult: SilenceAnalysisResult? = null

    /** Prevent repeated skip loops */
    private val skippedPositions = mutableSetOf<Long>()

    /**
     * Process a media item for silence analysis.
     * Returns the analysis result or null if analysis is not possible.
     */
    suspend fun processMedia(uri: String, audioData: ByteArray?): SilenceAnalysisResult? {
        if (!isEnabled || audioData == null) return null

        return try {
            analyzer.analyze(audioData, uri)
        } catch (e: Exception) {
            // Gracefully disable for streams where analysis is impossible
            isEnabled = false
            null
        }
    }

    /**
     * Get the next speech segment after a given position.
     * Used to seek across silence.
     */
    fun getNextSpeechPosition(currentPosition: Long): Long? {
        if (currentResult == null) return null

        for (segment in currentResult.segments) {
            if (!segment.isSilence && segment.startMs > currentPosition) {
                return segment.startMs
            }
        }
        return null
    }

    /**
     * Get the end of a silence segment starting from a position.
     * Seeks from currentPosition to end of silence.
     */
    fun getSilenceEndPosition(currentPosition: Long): Long? {
        if (currentResult == null) return null

        for (segment in currentResult.segments) {
            if (segment.isSilence && segment.startMs <= currentPosition && currentPosition < segment.endMs) {
                return segment.endMs
            }
        }
        return null
    }

    /**
     * Check if position is within a silence segment.
     */
    fun isPositionInSilence(currentPosition: Long): Boolean {
        if (currentResult == null) return false

        return currentResult.segments.any {
            it.isSilence && it.startMs <= currentPosition && currentPosition < it.endMs
        }
    }

    /**
     * Mark a position as skipped to prevent repeated loops.
     */
    fun markSkipped(position: Long) {
        skippedPositions.add(position)
    }

    /**
     * Check if a position has been recently skipped.
     */
    fun isPositionSkipped(position: Long): Boolean {
        return skippedPositions.contains(position)
    }

    /**
     * Get the optimal skip position across silence.
     * Returns the start of the next speech segment after current position.
     */
    fun getOptimalSkipPosition(currentPosition: Long): Long? {
        if (!isEnabled || currentResult == null) return null

        // Find next speech after current position
        val nextSpeech = getNextSpeechPosition(currentPosition)

        if (nextSpeech != null) {
            // Check if we'd be jumping over silence
            val silenceEnd = getSilenceEndPosition(currentPosition)
            if (silenceEnd != null && nextSpeech > silenceEnd) {
                // Avoid skipping if it would cause loop
                if (!isPositionSkipped(nextSpeech)) {
                    return nextSpeech
                }
            }
        }

        return null
    }

    override fun close() {
        isEnabled = false
        analyzer.analysisCache.clear()
        skippedPositions.clear()
        currentResult = null
    }
}