package com.quantum.player.core

import kotlinx.coroutines.flow.Flow

/**
 * Controller for managing video tracks and properties.
 * Handles video track selection, aspect ratio, rotation, and quality.
 */
interface VideoTrackController {

    /** Get current video track index */
    val currentTrackIndex: Int

    /** Get available video tracks */
    val availableTracks: Flow<List<VideoTrackInfo>>

    /** Select video track by index */
    suspend fun selectTrack(index: Int)

    /** Get video width */
    val videoWidth: Int

    /** Get video height */
    val videoHeight: Int

    /** Get video aspect ratio */
    val aspectRatio: Float

    /** Get video codec */
    val codec: String?

    /** Get video profile */
    val profile: String?

    /** Get video level */
    val level: String?

    /** Get bit depth */
    val bitDepth: Int?

    /** Set aspect ratio mode */
    suspend fun setAspectRatio(mode: AspectRatioMode)

    /** Set video rotation */
    suspend fun rotate(degrees: Int)

    /** Flip video horizontally */
    suspend fun flipHorizontal()

    /** Flip video vertically */
    suspend fun flipVertical()

    /** Refresh video track list */
    fun refresh()
}

/**
 * Aspect ratio modes.
 */
enum class AspectRatioMode {
    Auto,
    Fit,
    Fill,
    Original,
    Custom
}

/* Video track info data class */
data class VideoTrackInfo(
    val index: Int,
    val name: String,
    val codec: String?,
    val profile: String?,
    val level: String?,
    val width: Int,
    val height: Int,
    val bitDepth: Int?
)