package com.quantum.player.ytldp

import com.quantum.player.core.MediaItem
import com.quantum.player.core.PlaybackEngine
import com.quantum.player.model.MediaItem as ModelMediaItem
import kotlinx.coroutines.*

/**
 * Wrapper that isolates yt-dlp from the UI.
 * Handles the full pipeline: URL validation -> yt-dlp resolver -> format selection -> PlaybackEngine
 */
class YtDlpResolverWrapper(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) : StreamResolver {

    private val resolver = YtDlpStreamResolver()

    override suspend fun resolve(url: String): Result<YtDlpResult> {
        return try {
            val result = resolver.resolve(url)
            if (result.success) Result.success(result)
            else Result.failure(RuntimeException(result.error ?: "Unknown error"))
        } catch (e: Exception) {
            Result.failure(
                RuntimeException("yt-dlp resolution error: ${e.message}")
            )
        }
    }

    override suspend fun selectFormat(
        formats: List<YtDlpStreamResolver.YtDlpFormat>,
        videoOnly: Boolean,
        audioOnly: Boolean,
        preferences: FormatPreferences
    ): Result<String?> {
        return try {
            val formatId = resolver.selectFormat(
                formats, videoOnly, audioOnly,
                preferences.preferredResolution,
                preferences.preferredFps
            )
            if (formatId != null) Result.success(formatId)
            else Result.failure(
                RuntimeException("No suitable format found for selected criteria")
            )
        } catch (e: Exception) {
            Result.failure(
                RuntimeException("Format selection failed: ${e.message}")
            )
        }
    }

    override suspend fun getMediaInfo(url: String): Result<MediaInfo> {
        return try {
            val result = resolver.resolve(url)
            if (result.success) {
                Result.success(
                    MediaInfo(
                        title = result.title,
                        thumbnailUrl = result.thumbnail,
                        duration = result.formats
                            .filter { it.hasVideo }
                            .sortedByDescending { it.fps ?? 0 }
                            .firstOrNull()?.filesize?.let { /* would need duration from metadata */ } ?: 0,
                        resolution = result.formats
                            .filter { it.hasVideo }
                            .sortedByDescending { (it.width ?: 0).coerceAtMost(1920) * (it.height ?: 0).coerceAtMost(1080)
                            .firstOrNull()?.let { Pair(it.width!!, it.height!!) },
                        fps = result.formats
                            .filter { it.hasVideo }
                            .sortedByDescending { it.fps ?? 0 }
                            .firstOrNull()?.fps ?? 0,
                        format = result.formats.firstOrNull()?.formatNote
                    )
                )
            } else Result.failure(
                RuntimeException(result.error ?: "Unknown error")
            )
        } catch (e: Exception) {
            Result.failure(
                RuntimeException("Failed to get media info: ${e.message}")
            )
        }
    }
}

/**
 * Result type for resolver operations.
 */
sealed class Result<out T> {
    data class Success<out T>(val data: T) : Result<T>()
    data class Failure(val exception: Exception) : Result<T>()
}

/**
 * Factory for creating YtDlpResolverWrapper instances.
 */
object {
    fun create(): YtDlpResolverWrapper = YtDlpResolverWrapper()
}