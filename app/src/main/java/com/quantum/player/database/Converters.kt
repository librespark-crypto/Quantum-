package com.quantum.player.database

import androidx.room.TypeConverter
import com.quantum.player.core.AspectRatioMode
import com.quantum.player.model.WatchState
import com.quantum.player.silence.SilenceAnalysisResult
import com.quantum.player.silence.SilenceSegment

/**
 * Room type converters for Quantum.
 *
 * The previous version of this file did not compile (`def` keyword, a
 * non-existent `AspectRatioMode.Mode`, an `import java.lang.Long` that shadowed
 * Kotlin's `Long`) and declared a `Long <-> String` converter, which is actively
 * dangerous: Room would have used it for every `Long` column. Only converters
 * for types Room cannot store natively are kept here.
 */
object Converters {

    private const val CACHE_FORMAT_VERSION = 1
    private const val FIELD_SEPARATOR = ","

    @TypeConverter
    fun watchStateToString(state: WatchState): String = state.name

    @TypeConverter
    fun stringToWatchState(value: String): WatchState = WatchState.fromName(value)

    @TypeConverter
    fun aspectRatioModeToString(mode: AspectRatioMode): String = mode.name

    @TypeConverter
    fun stringToAspectRatioMode(value: String): AspectRatioMode =
        AspectRatioMode.entries.firstOrNull { it.name == value } ?: AspectRatioMode.Auto

    /**
     * Serialise a silence analysis result into a stable, round-trippable text
     * form for `silence_analysis_cache.analysis_data`.
     *
     * Layout: `v1|totalDurationMs|analysisQuality|analysisTimeMs|seg|seg|...`
     * where each `seg` is `startMs,endMs,isSilence,confidence,rmsEnergy`.
     *
     * The previous implementation stored only `"count:duration:quality"` and
     * then rebuilt the result with an empty segment list, so the cache silently
     * discarded the analysis it was supposed to preserve.
     */
    @TypeConverter
    fun silenceAnalysisResultToString(result: SilenceAnalysisResult): String = buildString {
        append(CACHE_FORMAT_VERSION).append('|')
            .append(result.totalDurationMs).append('|')
            .append(result.analysisQuality).append('|')
            .append(result.analysisTimeMs)
        result.segments.forEach { segment ->
            append('|').append(segment.startMs)
                .append(FIELD_SEPARATOR).append(segment.endMs)
                .append(FIELD_SEPARATOR).append(if (segment.isSilence) 1 else 0)
                .append(FIELD_SEPARATOR).append(segment.confidence)
                .append(FIELD_SEPARATOR).append(segment.rmsEnergy)
        }
    }

    @TypeConverter
    fun stringToSilenceAnalysisResult(value: String): SilenceAnalysisResult {
        if (value.isBlank()) return SilenceAnalysisResult(segments = emptyList(), totalDurationMs = 0)
        val parts = value.split('|')
        if (parts.size < 4 || parts[0].toIntOrNull() != CACHE_FORMAT_VERSION) {
            return SilenceAnalysisResult(segments = emptyList(), totalDurationMs = 0)
        }
        val totalDurationMs = parts[1].toLongOrNull() ?: 0L
        val analysisQuality = parts[2].toFloatOrNull() ?: 1f
        val analysisTimeMs = parts[3].toLongOrNull() ?: 0L
        val segments = parts.drop(4).mapNotNull { entry ->
            val fields = entry.split(FIELD_SEPARATOR)
            if (fields.size != 5) return@mapNotNull null
            val start = fields[0].toLongOrNull() ?: return@mapNotNull null
            val end = fields[1].toLongOrNull() ?: return@mapNotNull null
            val silent = fields[2].toIntOrNull() ?: return@mapNotNull null
            SilenceSegment(
                startMs = start,
                endMs = end,
                isSilence = silent != 0,
                confidence = fields[3].toFloatOrNull() ?: 1f,
                rmsEnergy = fields[4].toFloatOrNull() ?: 0f
            )
        }
        return SilenceAnalysisResult(
            segments = segments,
            totalDurationMs = totalDurationMs,
            analysisQuality = analysisQuality,
            analysisTimeMs = analysisTimeMs
        )
    }

}
