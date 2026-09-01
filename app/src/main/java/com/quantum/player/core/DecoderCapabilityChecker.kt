package com.quantum.player.core

/**
 * Checks decoder capabilities and reports supported codecs, profiles, and levels.
 * Detects available hardware and software decoders.
 *
 * Implemented by [DecoderDetector], which queries the device's actual
 * `android.media.MediaCodecList`. The previous version imported
 * `androidx.media3.common.CodecInfo` and `androidx.media3.common.MediaCodecUtil`
 * (neither exists) and answered capability questions from hardcoded tables.
 *
 * NOTE: enumerating codecs is comparatively expensive. Call from a background
 * dispatcher; results are cached by the implementation.
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
     */
    fun isHardwareDecodingAvailable(mimeType: String): Boolean

    /**
     * Check if software decoding is available as fallback.
     */
    fun isSoftwareDecodingAvailable(mimeType: String): Boolean

    /**
     * Get decoder information for a specific codec.
     */
    fun getDecoderInfo(mimeType: String): DecoderInfo

    /**
     * Check 10-bit support for a codec, based on the profiles and colour
     * formats the device actually advertises.
     */
    fun isTenBitSupported(mimeType: String): Boolean

    /**
     * Check HDR support for a codec.
     */
    fun getHDRSupport(mimeType: String): HDRSupport

    /**
     * Get supported profiles for a codec, as reported by the device.
     */
    fun getSupportedProfiles(mimeType: String): List<String>

    /**
     * Get supported levels for a codec, as reported by the device.
     */
    fun getSupportedLevels(mimeType: String): List<String>

    /**
     * Check if a resolution is supported for a codec.
     */
    fun isResolutionSupported(mimeType: String, width: Int, height: Int): Boolean

    /**
     * Get maximum supported resolution for a codec.
     * @return Maximum supported resolution (width, height); (0, 0) when unknown.
     */
    fun getMaxResolution(mimeType: String): Pair<Int, Int>

    /**
     * Detect available hardware decoders from the platform codec list.
     */
    fun detectHardwareDecoders(): List<HardwareDecoderInfo>

    /**
     * Full capability check for a codec with the current platform APIs.
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
