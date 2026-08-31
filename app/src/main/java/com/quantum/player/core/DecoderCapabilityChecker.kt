package com.quantum.player.core

import androidx.media3.common.CodecInfo
import androidx.media3.common.MediaCodecUtil
import kotlin.math.abs

/**
 * Checks decoder capabilities and reports supported codecs, profiles, and levels.
 * Detects available hardware and software decoders.
 */
interface DecoderCapabilityChecker {

    /**
     * Check if a video codec is supported.
     * @param mimeType The MIME type of the codec (e.g., "video/hevc", "video/av1")
     * @return True if the codec is supported (hardware or software)
     */
    fun isVideoCodecSupported(mimeType: String): Boolean

    /**
     * Check if an audio codec is supported.
     * @param mimeType The MIME type of the codec (e.g., "audio/ac3", "audio/mp3")
     * @return True if the codec is supported
     */
    fun isAudioCodecSupported(mimeType: String): Boolean

    /**
     * Check if hardware decoding is available for a specific codec.
     * @param mimeType The MIME type of the codec
     * @return True if hardware decoding is available
     */
    fun isHardwareDecodingAvailable(mimeType: String): Boolean

    /**
     * Check if software decoding is available as fallback.
     * @param mimeType The MIME type of the codec
     * @return True if software decoding is available
     */
    fun isSoftwareDecodingAvailable(mimeType: String): Boolean

    /**
     * Get decoder information for a specific codec.
     * @param mimeType The MIME type of the codec
     * @return DecoderInfo with capabilities
     */
    fun getDecoderInfo(mimeType: String): DecoderInfo

    /**
     * Check 10-bit support for a codec.
     * @param mimeType The MIME type of the codec
     * @return True if 10-bit is supported
     */
    fun isTenBitSupported(mimeType: String): Boolean

    /**
     * Check HDR support for a codec.
     * @param mimeType The MIME type of the codec
     * @return HDRSupport level
     */
    fun getHDRSupport(mimeType: String): HDRSupport

    /**
     * Get supported profiles for a codec.
     * @param mimeType The MIME type of the codec
     * @return List of supported profiles
     */
    fun getSupportedProfiles(mimeType: String): List<String>

    /**
     * Get supported levels for a codec.
     * @param mimeType The MIME type of the codec
     * @return List of supported levels
     */
    fun getSupportedLevels(mimeType: String): List<String>

    /**
     * Check if a resolution is supported for a codec.
     * @param mimeType The MIME type of the codec
     * @param width Video width in pixels
     * @param height Video height in pixels
     * @return True if the resolution is supported
     */
    fun isResolutionSupported(mimeType: String, width: Int, height: Int): Boolean

    /**
     * Get maximum supported resolution for a codec.
     * @param mimeType The MIME type of the codec
     * @return Maximum supported resolution (width, height)
     */
    fun getMaxResolution(mimeType: String): Pair<Int, Int>

    /**
     * Detect available hardware decoders from Media3.
     */
    fun detectHardwareDecoders(): List<HardwareDecoderInfo>

    /**
     * Check for codec capability with the current Media3 version.
     * Verify available APIs before using them.
     */
    fun verifyCodecSupport(mimeType: String): CapabilityResult
}

/**
 * Hardware decoder information.
 */
data class HardwareDecoderInfo(
    val name: String,
    val codecMimeType: String,
    val isHardwareAccelerated: Boolean,
    val supportsTenBit: Boolean,
    val hdrSupport: HDRSupport,
    val maxResolution: Pair<Int, Int>
)

/**
 * Capability result with detailed information.
 */
data class CapabilityResult(
    val isSupported: Boolean,
    val isHardwareAccelerated: Boolean,
    val supportedProfiles: List<String>,
    val supportedLevels: List<String>,
    val tenBitSupport: Boolean,
    val hdrSupport: HDRSupport,
    val errorMessage: String?
)

/**
 * Supported codec information.
 */
data class SupportedCodec(
    val mimeType: String,
    val name: String,
    val isHardwareAvailable: Boolean,
    val isSoftwareAvailable: Boolean,
    val tenBit: Boolean,
    val hdr: HDRSupport
)