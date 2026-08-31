package com.quantum.player.ytldp

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * Contract for turning a page/site URL into something the playback engine can
 * open. Keeping this as an interface is what lets the UI stay unaware of
 * yt-dlp: it asks for a stream URL and gets one, or a failure.
 */
interface StreamResolver {

    /** Resolve [url] into metadata plus the list of selectable formats. */
    suspend fun resolve(url: String): Result<YtDlpStreamResolver.YtDlpResult>

    /** Pick a format matching [preferences]; the returned format carries the stream URL. */
    suspend fun selectFormat(
        formats: List<YtDlpStreamResolver.YtDlpFormat>,
        videoOnly: Boolean = false,
        audioOnly: Boolean = false,
        preferences: YtDlpStreamResolver.FormatPreferences = YtDlpStreamResolver.FormatPreferences()
    ): Result<YtDlpStreamResolver.YtDlpFormat>

    /** Display metadata for a URL, without selecting a format. */
    suspend fun getMediaInfo(url: String): Result<MediaInfo>

    /**
     * Full pipeline: resolve, pick the best format, and hand back the direct
     * stream URL that the playback engine should open.
     */
    suspend fun resolveStreamUrl(
        url: String,
        preferences: YtDlpStreamResolver.FormatPreferences = YtDlpStreamResolver.FormatPreferences()
    ): Result<ResolvedStream>
}

/** Display metadata for a remote media URL. */
data class MediaInfo(
    val title: String,
    val thumbnailUrl: String,
    val durationSeconds: Long,
    val bestWidth: Int?,
    val bestHeight: Int?,
    val fps: Int?,
    val formatCount: Int
)

/** A resolved stream ready to hand to a `PlaybackEngine`. */
data class ResolvedStream(
    val streamUrl: String,
    val formatId: String,
    val container: String,
    val title: String,
    val hasVideo: Boolean,
    val hasAudio: Boolean
)

/**
 * Wrapper that isolates yt-dlp from the UI.
 * Handles the full pipeline: URL validation -> yt-dlp resolver -> format selection -> PlaybackEngine
 *
 * The previous version of this file declared its own `sealed class Result`,
 * which shadowed `kotlin.Result` and then called `Result.success(...)` on it,
 * referenced types that did not exist (`FormatPreferences`, `MediaInfo`), had an
 * unbalanced parenthesis in the format-selection expression, and ended with an
 * anonymous `object { }` at file scope, which is not legal Kotlin.
 */
class YtDlpResolverWrapper(
    private val resolver: YtDlpStreamResolver = YtDlpStreamResolver(),
    @Suppress("unused") private val scope: CoroutineScope? = CoroutineScope(Dispatchers.IO)
) : StreamResolver {

    override suspend fun resolve(url: String): Result<YtDlpStreamResolver.YtDlpResult> =
        runCatching {
            val result = resolver.resolve(url)
            if (!result.success) throw resolver.toException(url, result)
            result
        }

    override suspend fun selectFormat(
        formats: List<YtDlpStreamResolver.YtDlpFormat>,
        videoOnly: Boolean,
        audioOnly: Boolean,
        preferences: YtDlpStreamResolver.FormatPreferences
    ): Result<YtDlpStreamResolver.YtDlpFormat> = runCatching {
        resolver.selectFormat(formats, videoOnly, audioOnly, preferences)
            ?: throw IllegalStateException("No suitable format found for the selected criteria")
    }

    override suspend fun getMediaInfo(url: String): Result<MediaInfo> = runCatching {
        val result = resolver.resolve(url)
        if (!result.success) throw resolver.toException(url, result)
        val videos = result.formats.filter { it.hasVideo }
        val best = videos.maxByOrNull { it.pixels }
        MediaInfo(
            title = result.title,
            thumbnailUrl = result.thumbnail,
            durationSeconds = result.durationSeconds,
            bestWidth = best?.width,
            bestHeight = best?.height,
            fps = best?.fps ?: videos.mapNotNull { it.fps }.maxOrNull(),
            formatCount = result.formats.size
        )
    }

    override suspend fun resolveStreamUrl(
        url: String,
        preferences: YtDlpStreamResolver.FormatPreferences
    ): Result<ResolvedStream> = runCatching {
        val resolved = resolver.resolve(url)
        if (!resolved.success) throw resolver.toException(url, resolved)
        val format = resolver.selectFormat(resolved.formats, preferences = preferences)
            ?: throw IllegalStateException("yt-dlp reported ${resolved.formats.size} formats but none were playable")
        val streamUrl = format.url
            ?: throw IllegalStateException("Selected format ${format.formatId} has no stream URL")
        ResolvedStream(
            streamUrl = streamUrl,
            formatId = format.formatId,
            container = format.ext,
            title = resolved.title,
            hasVideo = format.hasVideo,
            hasAudio = format.hasAudio
        )
    }
}

/**
 * Factory for creating [YtDlpResolverWrapper] instances.
 *
 * [binaryPath] is where a bundled yt-dlp executable would live; pass null (the
 * default) when none is installed, in which case resolution fails with a clear
 * error instead of pretending to succeed.
 */
object YtDlp {

    /** Default install location inside the app's private files directory. */
    const val DEFAULT_BINARY_DIR = "yt-dlp"
    const val DEFAULT_BINARY_NAME = "yt-dlp"

    fun create(binaryPath: File? = null): YtDlpResolverWrapper = YtDlpResolverWrapper(
        resolver = YtDlpStreamResolver(binaryProvider = { binaryPath })
    )
}
