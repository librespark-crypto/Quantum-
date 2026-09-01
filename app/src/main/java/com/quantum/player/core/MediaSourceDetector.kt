package com.quantum.player.core

/**
 * Classifies a media URI into the container/streaming kind that decides which
 * Media3 MediaSource factory is used.
 *
 * Deliberately free of Android and Media3 imports so the classification rules
 * can be covered by plain JVM unit tests.
 */
enum class MediaKind {
    /** HTTP Live Streaming: `.m3u8`, master playlists and adaptive variants. */
    Hls,

    /** MPEG-DASH: `.mpd`, adaptive representations. */
    Dash,

    /** Real Time Streaming Protocol. */
    Rtsp,

    /** Microsoft Smooth Streaming. */
    SmoothStreaming,

    /** A single progressive file (MP4, MKV, WebM, ...). */
    Progressive,

    /** Cannot be decided from the URI alone; the backend has to sniff it. */
    Unknown
}

object MediaSourceDetector {

    /** MIME type Media3 uses to force HLS. */
    const val MIME_HLS = "application/x-mpegURL"

    /** MIME type Media3 uses to force DASH. */
    const val MIME_DASH = "application/dash+xml"

    /** MIME type Media3 uses to force RTSP. */
    const val MIME_RTSP = "application/x-rtsp"

    /** MIME type Media3 uses to force Smooth Streaming. */
    const val MIME_SS = "application/vnd.ms-sstr+xml"

    private val PROGRESSIVE_EXTENSIONS = setOf(
        "mp4", "m4v", "mkv", "webm", "mov", "avi", "ts", "m2ts", "mts",
        "flv", "3gp", "3g2", "ogv", "ogg", "mp3", "m4a", "aac", "flac",
        "wav", "opus"
    )

    /** Extensions the media browser will offer to open. */
    private val BROWSABLE_EXTENSIONS = PROGRESSIVE_EXTENSIONS + setOf("m3u8", "mpd")

    /**
     * Decide the kind from a URI and an optional caller declared MIME type.
     * A declared MIME type always wins over the file extension.
     */
    fun kindOf(uri: String, declaredMimeType: String? = null): MediaKind {
        when (normaliseMime(declaredMimeType)) {
            MIME_HLS, "application/vnd.apple.mpegurl", "audio/mpegurl" -> return MediaKind.Hls
            MIME_DASH -> return MediaKind.Dash
            MIME_RTSP -> return MediaKind.Rtsp
            MIME_SS -> return MediaKind.SmoothStreaming
            null -> Unit
            else -> return MediaKind.Progressive
        }

        val scheme = schemeOf(uri)
        if (scheme == "rtsp") return MediaKind.Rtsp

        val extension = extensionOf(uri)
        return when (extension) {
            "m3u8" -> MediaKind.Hls
            "mpd" -> MediaKind.Dash
            "ism", "isml" -> MediaKind.SmoothStreaming
            in PROGRESSIVE_EXTENSIONS -> MediaKind.Progressive
            else -> MediaKind.Unknown
        }
    }

    /** The Media3 MIME type that forces this kind, or null when sniffing should decide. */
    fun forcedMimeType(kind: MediaKind): String? = when (kind) {
        MediaKind.Hls -> MIME_HLS
        MediaKind.Dash -> MIME_DASH
        MediaKind.Rtsp -> MIME_RTSP
        MediaKind.SmoothStreaming -> MIME_SS
        MediaKind.Progressive, MediaKind.Unknown -> null
    }

    /** True for http/https/rtsp sources, false for file/content/asset sources. */
    fun isNetworkUri(uri: String): Boolean = when (schemeOf(uri)) {
        "http", "https", "rtsp", "rtmp", "ftp", "ftps" -> true
        else -> false
    }

    /** Lower case file extension without the dot; empty when there is none. */
    fun extensionOf(uri: String): String {
        val path = uri.substringBefore('?').substringBefore('#')
        val name = path.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        return if (dot < 0 || dot == name.length - 1) "" else name.substring(dot + 1).lowercase()
    }

    /** Container label for display, e.g. "mp4"; null when unknown. */
    fun containerOf(uri: String): String? = extensionOf(uri).takeIf { it.isNotEmpty() }

    /** Whether the media browser should list this file. */
    fun isSupportedMedia(fileName: String): Boolean =
        extensionOf(fileName) in BROWSABLE_EXTENSIONS

    /** Scheme without the trailing "://", lower cased; empty when absent. */
    fun schemeOf(uri: String): String {
        val idx = uri.indexOf("://")
        if (idx <= 0) return ""
        val candidate = uri.substring(0, idx)
        return if (candidate.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }) {
            candidate.lowercase()
        } else {
            ""
        }
    }

    private fun normaliseMime(mime: String?): String? =
        mime?.trim()?.takeIf { it.isNotEmpty() }?.substringBefore(';')?.trim()?.lowercase()
}
