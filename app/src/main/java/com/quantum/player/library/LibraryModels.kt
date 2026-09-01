package com.quantum.player.library

import android.net.Uri

/**
 * Folder-grouped media library models (mpvRx / MX Player style).
 *
 * The browser never renders a flat list: every video row belongs to a
 * [VideoFolder], and folders carry *recursive* totals (a folder card counts the
 * videos inside all of its nested sub-directories), which is what the MX Player
 * "Folders" tab does.
 */

/**
 * One playable video on disk, as indexed by [MediaStore.Video.Media].
 *
 * [uri] is always a `content://media/...` URI so the player and Glide/Coil
 * thumbnails can stream it without broad filesystem permissions; [path] is the
 * absolute file path used for grouping, rename/delete and metadata display.
 */
data class VideoEntry(
    val id: Long,
    val uri: Uri,
    val path: String,
    val title: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val dateAddedSec: Long,
    val dateModifiedMs: Long,
    val mimeType: String,
    val width: Int = 0,
    val height: Int = 0,
    val bucketId: String = "",
    /** True when no playback history exists for this video. */
    val isNew: Boolean = false,
    /** Resume position in ms from history; 0 when there is nothing to resume. */
    val resumePositionMs: Long = 0
) {
    /** Parent directory absolute path, or "" for root-level items. */
    val parentDirPath: String
        get() = path.substringBeforeLast('/', "")

    /** File name with extension. */
    val fileName: String get() = path.substringAfterLast('/').ifBlank { title }
}

/**
 * A directory node in the folder hierarchy.
 *
 * [videos] / [folders] are the *direct* children of this directory;
 * [totalVideoCount] / [totalDurationMs] / [newCount] are computed
 * **recursively** over the whole subtree for the folder card badges.
 */
data class VideoFolder(
    val path: String,
    val name: String,
    val videos: List<VideoEntry> = emptyList(),
    val folders: List<VideoFolder> = emptyList()
) {
    /** Recursive count of every video in this folder and its sub-folders. */
    val totalVideoCount: Int get() = videos.size + folders.sumOf { it.totalVideoCount }

    /** Recursive cumulative duration of every video below this folder. */
    val totalDurationMs: Long get() = videos.sumOf { it.durationMs } + folders.sumOf { it.totalDurationMs }

    /** Recursive count of unplayed videos for the red pill badge. */
    val newCount: Int get() = videos.count { it.isNew } + folders.sumOf { it.newCount }

    /** Whether this is the synthetic root node (all storage volumes). */
    val isRoot: Boolean get() = path.isEmpty()

    companion object {
        /** The synthetic root that holds every storage volume's tree. */
        val ROOT = VideoFolder(path = "", name = "Quantum")
    }
}

/**
 * The "continue watching" record persisted by [LastPlayedStore].
 * The browser FAB reads this to launch + resume the last played video directly.
 */
data class LastPlayed(
    val uri: String = "",
    val title: String = "",
    val path: String = "",
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val updatedAtMs: Long = 0L
) {
    val hasMedia: Boolean get() = uri.isNotBlank()

    /** 0f..1f progress fraction for the FAB ring. */
    val progress: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}
