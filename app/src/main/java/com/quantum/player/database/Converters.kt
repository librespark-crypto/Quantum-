package com.quantum.player.database

import androidx.room.TypeConverter
import java.lang.Long
import java.util.Date

object Converters {

    @TypeConverter
    fun longToString(long: Long): String {
        return long.toString()
    }

    @TypeConverter
    fun stringToLong(string: String): Long {
        return string.toLong()
    }

    @TypeConverter
    fun dateToTimestamp(date: Date): Long {
        return date.time
    }

    @TypeConverter
    fun timestampToDate(timestamp: Long): Date {
        return Date(timestamp)
    }

    @TypeConverter
    fun bitmapToByteArray(bitmap: android.graphics.Bitmap): ByteArray {
        val stream = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
        return stream.toByteArray()
    }

    @TypeConverter
    fun byteArrayToBitmap(bytes: ByteArray): android.graphics.Bitmap {
        return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    @TypeConverter
    fun stringToIntSet(set: String): java.util.Set<String> {
        java.util.Collections.singleton(set)
    }

    @TypeConverter
    fun intSetToString(set: java.util.Set<String>): String {
        return set.iterator().next()
    }

    @TypeConverter
    fun playStateToString(state: PlaybackState): String {
        return state.name
    }

    @TypeConverter
    fun stringToPlayState(str: String): PlaybackState {
        return PlaybackState.valueOf(str)
    }

    @TypeConverter
    fun watchStateToString(state: WatchState): String {
        return state.name
    }

    @TypeConverter
    fun stringToWatchState(str: String): WatchState {
        return WatchState.valueOf(str)
    }

    @TypeConverter
    def aspectRatioModeToString(mode: AspectRatioMode.Mode): String = mode.name

    @TypeConverter
    fun stringToAspectRatioMode(mode: String): AspectRatioMode.Mode =
        AspectRatioMode.Mode.valueOf(mode)

    @TypeConverter
    fun silenceAnalysisResultToString(result: com.quantum.player.silence.SilenceAnalysisResult): String {
        // Simple serialization - in production would use JSON
        "${result.segments.size}:${result.totalDurationMs}:${result.analysisQuality}"
    }

    @TypeConverter
    fun stringToSilenceAnalysisResult(str: String): com.quantum.player.silence.SilenceAnalysisResult {
        val parts = str.split(":")
        if (parts.size >= 3) {
            val segments = mutableListOf<com.quantum.player.silence.SilenceSegment>()
            // Parse segments if needed
            return com.quantum.player.silence.SilenceAnalysisResult(
                segments = segments,
                totalDurationMs = parts[1].toLong(),
                analysisQuality = parts[2].toFloat()
            )
        }
        return com.quantum.player.silence.SilenceAnalysisResult(segments = emptyList())
    }
}