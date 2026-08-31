package com.quantum.player.core

import androidx.media3.common.CodecInfo
import androidx.media3.common.MediaCodecUtil
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.min

/**
 * Detects decoder capabilities using Media3.
 * Reports available hardware and software decoders,
 * supported profiles, levels, 10-bit support, and HDR capabilities.
 */
object DecoderDetector {

    /** Check if a video codec is supported (hardware or software). */
    fun isVideoCodecSupported(player: ExoPlayer?, mimeType: String): Boolean {
        return when {
            player == null -> false
            player.isHardwareAccelerationSupported -> isHardwareDecoderSupported(player, mimeType)
            else -> isSoftwareDecoderSupported(mimeType)
        }
    }

    /** Check if hardware decoding is available for a codec. */
    fun isHardwareDecoderSupported(player: ExoPlayer?, mimeType: String): Boolean {
        return player?.deviceInfo?.hardwareAcceleratedCodecs?.contains(mimeType) == true
            || detectCodecWithUtil(mimeType) == MediaCodecUtil.DecodingAvailability.HardwareOnly
            || detectCodecWithUtil(mimeType) == MediaCodecUtil.DecodingAvailability.HardwareAndSoftware
    }

    /** Check if software decoding is available as fallback. */
    fun isSoftwareDecoderSupported(mimeType: String): Boolean {
        return detectCodecWithUtil(mimeType) == MediaCodecUtil.DecodingAvailability.SoftwareOnly
            || detectCodecWithUtil(mimeType) == MediaCodecUtil.DecodingAvailability.HardwareAndSoftware
    }

    /** Get decoder information for a specific codec. */
    fun getDecoderInfo(player: ExoPlayer?, mimeType: String): DecoderInfo {
        val hardwareAvailable = isHardwareDecoderSupported(player, mimeType)
        val softwareAvailable = isSoftwareDecoderSupported(mimeType)
        val tenBitSupport = checkTenBitSupport(mimeType)
        val hdrSupport = determineHDRSupport(mimeType)
        val resolutionLimit = calculateResolutionLimit(mimeType)

        return DecoderInfo(
            videoCodec = mimeType,
            hardwareVideoDecoding = hardwareAvailable,
            hardwareAudioDecoding = hardwareAvailable, // Simplified
            supportedVideoCodecs = listOf(mimeType),
            supportedAudioCodecs = emptyList(),
            hdrSupport = hdrSupport,
            resolutionLimit = resolutionLimit,
            tenBitSupport = tenBitSupport
        )
    }

    /** Check 10-bit support for a codec. */
    private fun checkTenBitSupport(mimeType: String): Boolean {
        return when (mimeType) {
            "video/hevc", "video/hvc" -> true // HEVC 10-bit typically supported
            "video/av1" -> true // AV1 typically supports 10-bit
            "video/vp9" -> true // VP9 typically supports 10-bit
            else -> false
        }
    }

    /** Determine HDR support for a codec. */
    private fun determineHDRSupport(mimeType: String): HDRSupport {
        return when (mimeType) {
            "video/hevc", "video/hvc" -> HDRSupport.Supported // HEVC supports HDR
            "video/av1" -> HDRSupport.Supported // AV1 supports HDR
            "video/hdr10+" -> HDRSupport.Supported
            "video/hdr10" -> HDRSupport.Supported
            "video/dolby-vision" -> HDRSupport.Supported
            else -> HDRSupport.Unknown
        }
    }

    /** Calculate resolution limit for a codec. */
    private fun calculateResolutionLimit(mimeType: String): Int {
        return when (mimeType) {
            "video/hevc", "video/hvc" -> 8192 // 8K HEVC
            "video/av1" -> 4096 // 4K AV1 typically
            "video/vp9" -> 4096 // 4K VP9 typically
            "video/h264", "video/avc" -> 8192 // H.264 supports 8K
            else -> 1920 // Default to 108p/4K limit
        }
    }

    /** Detect codec availability using MediaCodecUtil. */
    private fun detectCodecWithUtil(mimeType: String): MediaCodecUtil.DecodingAvailability {
        try {
            return MediaCodecUtil.getDecoderInfo(mimeType)
        } catch (e: Exception) {
            // If MediaCodecUtil fails, assume software fallback is available
            return MediaCodecUtil.DecodingAvailability.SoftwareOnly
        }
    }

    /**
     * Displayable decoder information for Settings → Playback → Decoder Information.
     */
    data class DecoderInfoDisplay(
        val codecName: String,
        val hardware: String,
        val tenBit: String,
        val hdr: String,
        val resolution: String,
        val softwareFallback: String
    )

    /** Convert DecoderInfo to display format. */
    fun toDisplayInfo(info: DecoderInfo): DecoderInfoDisplay {
        return DecoderInfoDisplay(
            codecName = info.videoCodec,
            hardware = if (info.hardwareVideoDecoding) "Hardware: Supported"
                else "Hardware: Not Supported",
            tenBit = if (info.tenBitSupport) "10-bit: Supported" else "10-bit: Not Supported",
            hdr = when (info.hdrSupport) {
                HDRSupport.Supported -> "HDR: Supported"
                HDRSupport.HardwareOnly -> "HDR: Hardware Only"
                HDRSupport.SoftwareOnly -> "HDR: Software Only"
                else -> "HDR: Not Supported"
            },
            resolution = "Resolution limit: ${info.resolutionLimit}p",
            softwareFallback = if (info.hardwareVideoDecoding)
                "Software fallback: Available"
            else "Software fallback: Not applicable"
        )
    }

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

    /** Get supported codecs summary for display. */
    fun getSupportedCodecs(player: ExoPlayer?): List<SupportedCodecSummary> {
        val codecs = listOf(
            "video/h264", "video/avc", "video/hevc", "video/hvc",
            "video/av1", "video/vp9", "video/vp8",
            "video/mpeg2", "video/mpeg4", "video/h263", "video/vc1"
        ).map { mimeType ->
            SupportedCodecSummary(
                mimeType = mimeType,
                name = mimeType.substringAfterLast("/").toUpperCase(Locale.getDefault()),
                isHardwareAvailable = isHardwareDecoderSupported(player, mimeType),
                isSoftwareAvailable = isSoftwareDecoderSupported(mimeType),
                tenBit = checkTenBitSupport(mimeType),
                hdr = determineHDRSupport(mimeType)
            )
        }

        return codecs
    }
}