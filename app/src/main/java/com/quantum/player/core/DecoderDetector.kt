package com.quantum.player.core

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build

/**
 * Detects decoder capabilities using the platform codec list.
 * Reports available hardware and software decoders, supported profiles, levels,
 * 10-bit support and HDR capabilities.
 *
 * Everything here is a real device query (`android.media.MediaCodecList`). The
 * previous implementation answered from hardcoded tables - for example claiming
 * 10-bit and an 8K limit for every HEVC device - and called Media3 APIs that do
 * not exist (`player.isHardwareAccelerationSupported`,
 * `player.deviceInfo.hardwareAcceleratedCodecs`,
 * `MediaCodecUtil.DecodingAvailability`). Codec support depends on the device
 * and the installed decoders, so it must be read, never assumed.
 *
 * Enumerating the codec list is expensive, so it is read once and cached.
 */
object DecoderDetector : DecoderCapabilityChecker {

    /** Android MIME types are not always what people type; map friendly aliases. */
    private val MIME_ALIASES: Map<String, String> = mapOf(
        "video/h264" to "video/avc",
        "video/avc1" to "video/avc",
        "video/h265" to "video/hevc",
        "video/hvc" to "video/hevc",
        "video/hvc1" to "video/hevc",
        "video/vp8" to "video/x-vnd.on2.vp8",
        "video/vp9" to "video/x-vnd.on2.vp9",
        "video/av1" to "video/av01",
        "video/mpeg4" to "video/mp4v-es",
        "video/mpeg2" to "video/mpeg2",
        "video/dolby-vision" to "video/dolby-vision"
    )

    /** Profiles that can carry 10-bit samples, per video MIME type. */
    private val TEN_BIT_PROFILES: Map<String, Set<Int>> = mapOf(
        "video/avc" to setOf(MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10),
        "video/hevc" to setOf(
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10,
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus
        ),
        "video/x-vnd.on2.vp9" to setOf(
            MediaCodecInfo.CodecProfileLevel.VP9Profile2,
            MediaCodecInfo.CodecProfileLevel.VP9Profile3
        ),
        "video/av01" to setOf(
            MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10,
            MediaCodecInfo.CodecProfileLevel.AV1ProfileMain810
        )
    )

    /** Video MIME types the summary screen reports on. */
    val KNOWN_VIDEO_CODECS: List<String> = listOf(
        "video/avc",
        "video/hevc",
        "video/x-vnd.on2.vp8",
        "video/x-vnd.on2.vp9",
        "video/av01",
        "video/mp4v-es",
        "video/mpeg2",
        "video/wvc1",
        "video/dolby-vision"
    )

    @Volatile
    private var cachedCodecs: List<MediaCodecInfo>? = null

    /** Resolve an alias to the Android MIME type the platform actually uses. */
    fun normaliseMimeType(mimeType: String): String {
        val trimmed = mimeType.trim()
        return MIME_ALIASES[trimmed.lowercase()] ?: trimmed
    }

    private fun allCodecs(): List<MediaCodecInfo> {
        cachedCodecs?.let { return it }
        return synchronized(this) {
            cachedCodecs ?: runCatching {
                MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.toList()
            }.getOrElse { emptyList() }.also { cachedCodecs = it }
        }
    }

    private fun codecsFor(mimeType: String): List<MediaCodecInfo> {
        val resolved = normaliseMimeType(mimeType)
        return allCodecs().filter { info ->
            runCatching {
                info.supportedTypes.any { it.equals(resolved, ignoreCase = true) }
            }.getOrDefault(false)
        }
    }

    private fun capabilitiesFor(info: MediaCodecInfo, mimeType: String) =
        runCatching { info.getCapabilitiesForType(normaliseMimeType(mimeType)) }.getOrNull()

    private fun videoCapabilitiesFor(mimeType: String): MediaCodecInfo.VideoCapabilities? =
        codecsFor(mimeType).firstNotNullOfOrNull { info ->
            runCatching { capabilitiesFor(info, mimeType)?.videoCapabilities }.getOrNull()
        }

    private fun looksLikeSoftware(name: String): Boolean {
        val lower = name.lowercase()
        return lower.startsWith("omx.google.") ||
            lower.startsWith("c2.android.") ||
            lower.contains(".sw.")
    }

    private fun isHardware(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { info.isHardwareAccelerated && !info.isSoftwareOnly }.getOrDefault(false)
        } else {
            // Pre-Q the platform does not tell us; use the well known vendor prefixes.
            !looksLikeSoftware(info.name)
        }

    private fun isSoftware(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { info.isSoftwareOnly }.getOrDefault(false)
        } else {
            looksLikeSoftware(info.name)
        }

    private fun profileLevelsFor(info: MediaCodecInfo, mimeType: String): List<MediaCodecInfo.CodecProfileLevel> =
        runCatching { capabilitiesFor(info, mimeType)?.profileLevels?.toList() }.getOrNull()
            ?: emptyList()

    private fun tenBitProfilesFor(info: MediaCodecInfo, mimeType: String): List<Int> {
        val resolved = normaliseMimeType(mimeType)
        val expected = TEN_BIT_PROFILES[resolved].orEmpty()
        return profileLevelsFor(info, mimeType)
            .map { it.profile }
            .filter { it in expected }
            .distinct()
    }

    private fun advertises10BitColorFormat(info: MediaCodecInfo, mimeType: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val formats = runCatching { capabilitiesFor(info, mimeType)?.colorFormats }.getOrNull()
            ?: return false
        return formats.any { it == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420P010 }
    }

    private fun supportsTenBit(info: MediaCodecInfo, mimeType: String): Boolean =
        tenBitProfilesFor(info, mimeType).isNotEmpty() || advertises10BitColorFormat(info, mimeType)

    // -----------------------------------------------------------------
    // DecoderCapabilityChecker
    // -----------------------------------------------------------------

    override fun isVideoCodecSupported(mimeType: String): Boolean =
        codecsFor(mimeType).isNotEmpty()

    override fun isAudioCodecSupported(mimeType: String): Boolean =
        codecsFor(mimeType).isNotEmpty()

    override fun isHardwareDecodingAvailable(mimeType: String): Boolean =
        codecsFor(mimeType).any { isHardware(it) }

    override fun isSoftwareDecodingAvailable(mimeType: String): Boolean =
        codecsFor(mimeType).any { isSoftware(it) }

    override fun isTenBitSupported(mimeType: String): Boolean =
        codecsFor(mimeType).any { supportsTenBit(it, mimeType) }

    override fun getHDRSupport(mimeType: String): HDRSupport {
        val capable = codecsFor(mimeType).filter { supportsTenBit(it, mimeType) }
        if (capable.isEmpty()) return HDRSupport.Unknown
        val hardware = capable.any { isHardware(it) }
        val software = capable.any { isSoftware(it) }
        return when {
            hardware && software -> HDRSupport.Supported
            hardware -> HDRSupport.HardwareOnly
            software -> HDRSupport.SoftwareOnly
            else -> HDRSupport.Unknown
        }
    }

    override fun getSupportedProfiles(mimeType: String): List<String> {
        val resolved = normaliseMimeType(mimeType)
        return codecsFor(mimeType)
            .flatMap { info -> profileLevelsFor(info, mimeType).map { it.profile } }
            .distinct()
            .sorted()
            .map { profileName(resolved, it) }
    }

    override fun getSupportedLevels(mimeType: String): List<String> =
        codecsFor(mimeType)
            .flatMap { info -> profileLevelsFor(info, mimeType).map { it.level } }
            .distinct()
            .sorted()
            .map { "level=$it" }

    override fun isResolutionSupported(mimeType: String, width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        val capabilities = videoCapabilitiesFor(mimeType) ?: return false
        return runCatching { capabilities.isSizeSupported(width, height) }.getOrDefault(false)
    }

    override fun getMaxResolution(mimeType: String): Pair<Int, Int> {
        val capabilities = videoCapabilitiesFor(mimeType) ?: return 0 to 0
        return runCatching {
            val width: Int = capabilities.supportedWidths.upper
            val height: Int = capabilities.supportedHeights.upper
            width to height
        }.getOrDefault(0 to 0)
    }

    override fun detectHardwareDecoders(): List<HardwareDecoderInfo> =
        allCodecs()
            .filter { isHardware(it) }
            .flatMap { info ->
                runCatching { info.supportedTypes.toList() }.getOrDefault(emptyList())
                    .filter { it.startsWith("video/", ignoreCase = true) }
                    .map { mime ->
                        HardwareDecoderInfo(
                            name = info.name,
                            codecMimeType = mime,
                            isHardwareAccelerated = true,
                            supportsTenBit = supportsTenBit(info, mime),
                            hdrSupport = getHDRSupport(mime),
                            maxResolution = runCatching {
                                val caps = capabilitiesFor(info, mime)?.videoCapabilities
                                if (caps != null) {
                                    val width: Int = caps.supportedWidths.upper
                                    val height: Int = caps.supportedHeights.upper
                                    width to height
                                } else {
                                    0 to 0
                                }
                            }.getOrDefault(0 to 0)
                        )
                    }
            }

    override fun verifyCodecSupport(mimeType: String): CapabilityResult {
        val codecs = codecsFor(mimeType)
        val resolved = normaliseMimeType(mimeType)
        return CapabilityResult(
            isSupported = codecs.isNotEmpty(),
            isHardwareAccelerated = codecs.any { isHardware(it) },
            supportedProfiles = getSupportedProfiles(mimeType),
            supportedLevels = getSupportedLevels(mimeType),
            tenBitSupport = codecs.any { supportsTenBit(it, mimeType) },
            hdrSupport = getHDRSupport(mimeType),
            errorMessage = if (codecs.isEmpty()) {
                "No decoder on this device handles $resolved"
            } else {
                null
            }
        )
    }

    // -----------------------------------------------------------------
    // Display helpers
    // -----------------------------------------------------------------

    /**
     * Decoder information for a specific codec. [mimeType] may be null, in
     * which case a device wide summary is returned.
     */
    fun decoderInfoFor(mimeType: String?): DecoderInfo {
        val videoMime = mimeType?.let { normaliseMimeType(it) }
        val (maxWidth, maxHeight) = videoMime?.let { getMaxResolution(it) } ?: (0 to 0)
        return DecoderInfo(
            videoCodec = videoMime ?: "Unknown",
            audioCodec = "Unknown",
            hardwareVideoDecoding = videoMime?.let { isHardwareDecodingAvailable(it) } ?: false,
            hardwareAudioDecoding = false,
            supportedVideoCodecs = KNOWN_VIDEO_CODECS.filter { isVideoCodecSupported(it) },
            supportedAudioCodecs = KNOWN_AUDIO_CODECS.filter { isAudioCodecSupported(it) },
            hdrSupport = videoMime?.let { getHDRSupport(it) } ?: HDRSupport.Unknown,
            resolutionLimit = minOf(maxWidth, maxHeight).takeIf { it > 0 } ?: 0,
            tenBitSupport = videoMime?.let { isTenBitSupported(it) } ?: false
        )
    }

    override fun getDecoderInfo(mimeType: String): DecoderInfo =
        decoderInfoFor(mimeType).copy(audioCodec = normaliseMimeType(mimeType))

    /** Audio MIME types the summary screen reports on. */
    val KNOWN_AUDIO_CODECS: List<String> = listOf(
        "audio/mp4a-latm",
        "audio/mpeg",
        "audio/opus",
        "audio/vorbis",
        "audio/flac",
        "audio/ac3",
        "audio/eac3",
        "audio/alac",
        "audio/vnd.dts",
        "audio/truehd",
        "audio/amr-wb"
    )

    /**
     * Displayable decoder information for Settings -> Playback -> Decoder Information.
     */
    data class DecoderInfoDisplay(
        val codecName: String,
        val hardware: String,
        val tenBit: String,
        val hdr: String,
        val resolution: String,
        val softwareFallback: String
    )

    /** Convert [DecoderInfo] to display format. */
    fun toDisplayInfo(info: DecoderInfo): DecoderInfoDisplay = DecoderInfoDisplay(
        codecName = info.videoCodec,
        hardware = if (info.hardwareVideoDecoding) "Hardware: Supported" else "Hardware: Not Supported",
        tenBit = if (info.tenBitSupport) "10-bit: Supported" else "10-bit: Not Supported",
        hdr = when (info.hdrSupport) {
            HDRSupport.Supported -> "HDR: Supported"
            HDRSupport.HardwareOnly -> "HDR: Hardware Only"
            HDRSupport.SoftwareOnly -> "HDR: Software Only"
            HDRSupport.Unknown -> "HDR: Unknown"
        },
        resolution = if (info.resolutionLimit > 0) {
            "Resolution limit: ${info.resolutionLimit}p"
        } else {
            "Resolution limit: unknown"
        },
        softwareFallback = if (info.videoCodec != "Unknown" &&
            isSoftwareDecodingAvailable(info.videoCodec)
        ) {
            "Software fallback: Available"
        } else {
            "Software fallback: Not available"
        }
    )

    /**
     * Supported codec information summary.
     */
    data class SupportedCodecSummary(
        val mimeType: String,
        val name: String,
        val isHardwareAvailable: Boolean,
        val isSoftwareAvailable: Boolean,
        val tenBit: Boolean,
        val hdr: HDRSupport
    )

    /**
     * Supported codecs summary for display, derived from this device.
     * The old signature took an `ExoPlayer?` only to call a non-existent
     * `isHardwareAccelerationSupported` property on it.
     */
    fun getSupportedCodecs(): List<SupportedCodecSummary> =
        KNOWN_VIDEO_CODECS.map { mimeType ->
            SupportedCodecSummary(
                mimeType = mimeType,
                name = mimeType.substringAfterLast('/').uppercase(),
                isHardwareAvailable = isHardwareDecodingAvailable(mimeType),
                isSoftwareAvailable = isSoftwareDecodingAvailable(mimeType),
                tenBit = isTenBitSupported(mimeType),
                hdr = getHDRSupport(mimeType)
            )
        }

    private fun profileName(mimeType: String, profile: Int): String {
        return when (normaliseMimeType(mimeType)) {
            "video/avc" -> when (profile) {
                MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline -> "AVC Baseline"
                MediaCodecInfo.CodecProfileLevel.AVCProfileMain -> "AVC Main"
                MediaCodecInfo.CodecProfileLevel.AVCProfileExtended -> "AVC Extended"
                MediaCodecInfo.CodecProfileLevel.AVCProfileHigh -> "AVC High"
                MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10 -> "AVC High 10"
                MediaCodecInfo.CodecProfileLevel.AVCProfileHigh422 -> "AVC High 4:2:2"
                MediaCodecInfo.CodecProfileLevel.AVCProfileHigh444 -> "AVC High 4:4:4"
                else -> "AVC profile=$profile"
            }
            "video/hevc" -> when (profile) {
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain -> "HEVC Main"
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 -> "HEVC Main 10"
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMainStill -> "HEVC Main Still"
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 -> "HEVC Main 10 HDR10"
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus -> "HEVC Main 10 HDR10+"
                else -> "HEVC profile=$profile"
            }
            "video/x-vnd.on2.vp9" -> when (profile) {
                MediaCodecInfo.CodecProfileLevel.VP9Profile0 -> "VP9 Profile 0"
                MediaCodecInfo.CodecProfileLevel.VP9Profile1 -> "VP9 Profile 1"
                MediaCodecInfo.CodecProfileLevel.VP9Profile2 -> "VP9 Profile 2"
                MediaCodecInfo.CodecProfileLevel.VP9Profile3 -> "VP9 Profile 3"
                else -> "VP9 profile=$profile"
            }
            "video/x-vnd.on2.vp8" ->
                if (profile == MediaCodecInfo.CodecProfileLevel.VP8ProfileMain) "VP8 Main"
                else "VP8 profile=$profile"
            else -> "$mimeType profile=$profile"
        }
    }
}
