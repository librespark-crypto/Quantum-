package com.quantum.player.ytldp

import com.quantum.player.error.PlaybackError
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * yt-dlp stream resolver for extracting video/audio formats from URLs.
 * Isolated from the UI - provides resolved formats for the playback engine.
 *
 * Pipeline: URL -> Validation -> yt-dlp Resolver -> Available Formats -> Quality Selection -> PlaybackEngine
 *
 * ## Status of this integration
 *
 * The **resolver logic is real**: URL validation, yt-dlp JSON parsing, format
 * selection and stream URL extraction are fully implemented and unit tested
 * (see `YtDlpStreamResolverTest`).
 *
 * The **yt-dlp binary itself is not bundled** with this app. There is no
 * `yt-dlp` on Android by default, and modern Android will not execute a binary
 * downloaded to app-writable storage (W^X since API 29). Shipping this
 * therefore requires packaging a native yt-dlp build as a JNI library or an
 * APK asset installed into the app's private, executable directory - none of
 * which exists in this repository.
 *
 * When no binary is present [resolve] fails with
 * [PlaybackError.YtDlpResolutionException] rather than returning an empty or
 * fabricated result, so callers get a truthful error instead of a stream that
 * silently does not play.
 */
class YtDlpStreamResolver(
    /** Where to look for the yt-dlp executable. Null means "not installed". */
    private val binaryProvider: (() -> File?)? = null,
    /** Runs the binary and returns its output. Injectable for tests. */
    private val runner: (suspend (List<String>) -> ProcessResult)? = null
) {

    /** Raw process result. */
    data class ProcessResult(val exitCode: Int, val output: String)

    /** A single format offered by yt-dlp. */
    data class YtDlpFormat(
        val formatId: String,
        val ext: String,
        val width: Int?,
        val height: Int?,
        val fps: Int?,
        val vcodec: String?,
        val acodec: String?,
        val tbr: Long?,
        val filesize: Long?,
        val formatNote: String?,
        val protocol: String?,
        val url: String?
    ) {
        /** True when this format carries a video stream. */
        val hasVideo: Boolean get() = !vcodec.isNullOrBlank() && vcodec != "none"

        /** True when this format carries an audio stream. */
        val hasAudio: Boolean get() = !acodec.isNullOrBlank() && acodec != "none"

        /** True when a single format can be played on its own. */
        val isProgressive: Boolean get() = hasVideo && hasAudio

        /** Pixels, used for quality ordering; 0 when the resolution is unknown. */
        val pixels: Long get() = (width?.toLong() ?: 0L) * (height?.toLong() ?: 0L)
    }

    /** Result of resolving a URL. */
    data class YtDlpResult(
        val success: Boolean,
        val formats: List<YtDlpFormat> = emptyList(),
        val title: String = "",
        val thumbnail: String = "",
        val durationSeconds: Long = 0,
        val error: String? = null
    )

    /** Quality preferences used by [selectFormat]. */
    data class FormatPreferences(
        val maxHeight: Int = MAX_HEIGHT,
        val maxWidth: Int = MAX_WIDTH,
        val preferredFps: Int? = null,
        val preferProgressive: Boolean = true
    )

    /**
     * Resolve a URL using yt-dlp to get available formats.
     * @param url The URL to resolve
     * @return List of available formats with quality information
     */
    suspend fun resolve(url: String): YtDlpResult {
        if (!validateUrl(url)) {
            return YtDlpResult(success = false, error = "Invalid URL format")
        }
        val executable = locateBinary()
        if (runner == null && executable == null) {
            return YtDlpResult(
                success = false,
                error = "yt-dlp is not installed on this device. " +
                    "Direct media URLs (including .m3u8 and .mpd) play without it."
            )
        }
        val command = listOfNotNull(
            executable?.absolutePath ?: "yt-dlp",
            "--no-playlist",
            "--dump-single-json",
            "--no-warnings",
            url
        )
        return withContext(Dispatchers.IO) {
            val result = withTimeoutOrNull(TIMEOUT_MS) { invoke(command) }
                ?: return@withContext YtDlpResult(
                    success = false,
                    error = "yt-dlp timed out after ${TIMEOUT_MS / 1000}s"
                )
            if (result.exitCode != 0) {
                return@withContext YtDlpResult(
                    success = false,
                    error = "yt-dlp failed with exit code ${result.exitCode}: " +
                        result.output.take(ERROR_OUTPUT_LIMIT)
                )
            }
            parse(result.output)
        }
    }

    private suspend fun invoke(command: List<String>): ProcessResult {
        runner?.let { return it(command) }
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val finished = process.waitFor(60, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return ProcessResult(exitCode = -1, output = "yt-dlp did not finish in time")
        }
        return ProcessResult(exitCode = process.exitValue(), output = output)
    }

    private fun locateBinary(): File? = binaryProvider?.invoke()?.takeIf { it.isFile && it.canExecute() }

    /**
     * Validate URL format before passing to yt-dlp.
     */
    fun validateUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val lower = url.trim().lowercase()
        // Only real URL schemes are accepted; matching on ".com" alone let
        // arbitrary file paths through.
        return lower.startsWith("http://") ||
            lower.startsWith("https://") ||
            lower.startsWith("rtsp://") ||
            lower.startsWith("rtmp://")
    }

    /**
     * Parse yt-dlp's `--dump-single-json` output.
     */
    fun parse(jsonOutput: String): YtDlpResult {
        val root = runCatching { JSONObject(jsonOutput) }.getOrNull()
            ?: return YtDlpResult(success = false, error = "yt-dlp returned invalid JSON")

        val formats = extractFormats(root.optJSONArray("formats"))
        return YtDlpResult(
            success = formats.isNotEmpty(),
            formats = formats,
            title = root.optString("title", "Unknown Title"),
            thumbnail = root.optString("thumbnail", ""),
            durationSeconds = root.optLong("duration", 0L),
            error = if (formats.isEmpty()) "yt-dlp reported no usable formats" else null
        )
    }

    /**
     * Extract format information from the yt-dlp `formats` array.
     */
    fun extractFormats(formats: JSONArray?): List<YtDlpFormat> {
        if (formats == null) return emptyList()
        val result = mutableListOf<YtDlpFormat>()
        for (i in 0 until formats.length()) {
            val entry = formats.optJSONObject(i) ?: continue
            val formatId = entry.optString("format_id").takeIf { it.isNotBlank() } ?: continue
            val url = entry.optString("url").takeIf { it.isNotBlank() }
            result.add(
                YtDlpFormat(
                    formatId = formatId,
                    ext = entry.optString("ext"),
                    width = entry.optIntOrNull("width"),
                    height = entry.optIntOrNull("height"),
                    fps = entry.optDouble("fps", Double.NaN)
                        .takeIf { !it.isNaN() }?.toInt(),
                    vcodec = entry.optString("vcodec").takeIf { it.isNotBlank() && it != "none" },
                    acodec = entry.optString("acodec").takeIf { it.isNotBlank() && it != "none" },
                    tbr = entry.optLong("tbr").takeIf { it > 0 },
                    filesize = entry.optLong("filesize").takeIf { it > 0 }
                        ?: entry.optLong("filesize_approx").takeIf { it > 0 },
                    formatNote = entry.optString("format_note").takeIf { it.isNotBlank() },
                    protocol = entry.optString("protocol").takeIf { it.isNotBlank() },
                    url = url
                )
            )
        }
        return result
    }

    /**
     * Select the best format for playback.
     * @param formats Available formats
     * @param videoOnly Whether to select video-only format
     * @param audioOnly Whether to select audio-only format
     * @return the chosen format, or null when nothing matches
     */
    fun selectFormat(
        formats: List<YtDlpFormat>,
        videoOnly: Boolean = false,
        audioOnly: Boolean = false,
        preferences: FormatPreferences = FormatPreferences()
    ): YtDlpFormat? {
        val playable = formats.filter { it.url != null }
        if (playable.isEmpty()) return null

        if (audioOnly) {
            return playable.filter { it.hasAudio && !it.hasVideo }
                .maxByOrNull { it.tbr ?: 0L }
                ?: playable.filter { it.hasAudio }.maxByOrNull { it.tbr ?: 0L }
        }

        if (videoOnly) {
            return playable.filter { it.hasVideo && !it.hasAudio }
                .withinLimits(preferences)
                .bestFor(preferences)
        }

        // Prefer a single progressive file so the player needs no muxing.
        val progressive = playable.filter { it.isProgressive }.withinLimits(preferences)
        if (progressive.isNotEmpty() && (preferences.preferProgressive || playable.none { it.hasVideo })) {
            return progressive.bestFor(preferences)
        }
        return playable.filter { it.hasVideo }.withinLimits(preferences).bestFor(preferences)
    }

    private fun List<YtDlpFormat>.withinLimits(preferences: FormatPreferences): List<YtDlpFormat> {
        val filtered = filter { format ->
            val height = format.height ?: 0
            val width = format.width ?: 0
            height <= preferences.maxHeight && width <= preferences.maxWidth
        }
        return filtered.ifEmpty { this }
    }

    private fun List<YtDlpFormat>.bestFor(preferences: FormatPreferences): YtDlpFormat? {
        if (isEmpty()) return null
        preferences.preferredFps?.let { wanted ->
            val exact = filter { it.fps == wanted }
            if (exact.isNotEmpty()) return exact.maxByOrNull { it.pixels }
        }
        return maxWithOrNull(
            compareBy({ it.pixels }, { it.fps ?: 0 }, { it.tbr ?: 0L })
        )
    }

    /** Extract the direct stream URL for a selected format. */
    fun streamUrlFor(formats: List<YtDlpFormat>, formatId: String): String? =
        formats.firstOrNull { it.formatId == formatId }?.url

    /** Map a failure onto the structured error type. */
    fun toException(url: String, result: YtDlpResult): PlaybackError.YtDlpResolutionException =
        PlaybackError.YtDlpResolutionException(url = url, detail = result.error)

    private fun JSONObject.optIntOrNull(name: String): Int? =
        if (isNull(name) || !has(name)) null else optInt(name).takeIf { it > 0 }

    companion object {
        const val DEFAULT_QUALITY = "best"
        const val AUDIO_QUALITY = "bestaudio"
        const val VIDEO_QUALITY = "bestvideo"

        /** yt-dlp command timeout. */
        const val TIMEOUT_MS = 60_000L

        /** Maximum width for video selection. */
        const val MAX_WIDTH = 1920

        /** Maximum height for video selection. */
        const val MAX_HEIGHT = 1080

        private const val ERROR_OUTPUT_LIMIT = 500
    }
}
