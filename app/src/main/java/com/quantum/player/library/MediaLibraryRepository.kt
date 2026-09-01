package com.quantum.player.library

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.quantum.player.database.QuantumRoomDatabase
import java.io.File
import java.util.Locale

/**
 * MediaStore-backed media library.
 *
 * Responsibilities (mpvRx / MX Player style explorer):
 *  1. Query every video through [MediaStore.Video.Media] (works on API 21+
 *     with the runtime media permission; MANAGE_EXTERNAL_STORAGE is not needed).
 *  2. Join playback history from Room so rows know if they are **new**
 *     (unplayed) and where they should resume.
 *  3. Group videos by parent directory into a recursive [VideoFolder] tree
 *     spanning every storage volume (internal + SD cards / USB OTG).
 *
 * All work is blocking/cursor based and must be called from a background
 * dispatcher (the UI wraps it in [withContext(Dispatchers.IO)]).
 */
class MediaLibraryRepository(
    private val context: Context,
    private val database: QuantumRoomDatabase
) {

    /**
     * Scan MediaStore and build the folder tree.
     *
     * Returns the synthetic [VideoFolder.ROOT]; its children are per-volume
     * roots ("Internal storage", one folder per removable volume) and every
     * folder's [VideoFolder.totalVideoCount] / [VideoFolder.totalDurationMs] /
     * [VideoFolder.newCount] are computed recursively.
     */
    suspend fun loadFolders(): VideoFolder {
        val videos = queryAllVideos()
        if (videos.isEmpty()) return VideoFolder.ROOT

        val historyByUri = HashMap<String, com.quantum.player.database.PlaybackHistoryEntity>()
        runCatching { database.playbackHistoryDao().loadAllOnce() }
            .getOrElse { emptyList() }
            .forEach { row -> if (row.uri.isNotBlank()) historyByUri[row.uri] = row }

        val annotated = videos.map { entry ->
            val history = historyByUri[entry.uri.toString()]
            entry.copy(
                isNew = history == null || history.playCount == 0,
                resumePositionMs = history?.resumePositionMs?.coerceAtLeast(0L) ?: 0L
            )
        }

        return buildTree(annotated)
    }

    private fun queryAllVideos(): List<VideoEntry> {
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = mutableListOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.BUCKET_ID
        ).apply {
            add(MediaStore.Video.Media.WIDTH)
            add(MediaStore.Video.Media.HEIGHT)
        }

        val entries = ArrayList<VideoEntry>()
        runCatching {
            context.contentResolver.query(
                collection,
                projection.toTypedArray(),
                null,
                null,
                "${MediaStore.Video.Media.DATE_MODIFIED} DESC"
            )
        }.getOrNull()?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val dataCol = cursor.getColumnIndex(MediaStore.Video.Media.DATA)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val addedCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val modifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
            val bucketCol = cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_ID)
            val widthCol = cursor.getColumnIndex(MediaStore.Video.Media.WIDTH)
            val heightCol = cursor.getColumnIndex(MediaStore.Video.Media.HEIGHT)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol) ?: continue
                val rawPath = if (dataCol >= 0) cursor.getString(dataCol).orEmpty() else ""
                val volumeName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // content://media/<volume>/video/...
                    Uri.withAppendedPath(collection, id.toString()).pathSegments
                        .getOrNull(1) ?: "external"
                } else {
                    "external"
                }
                entries.add(
                    VideoEntry(
                        id = id,
                        uri = Uri.withAppendedPath(collection, id.toString()),
                        // DATA is hidden on API 29+ but still populated for media
                        // rows; if it is ever blank we synthesize a path from the
                        // volume + name so grouping never blows up.
                        path = rawPath.ifBlank { "/$volumeName/Media/${name}" },
                        title = name.substringBeforeLast('.'),
                        durationMs = cursor.getLong(durationCol),
                        sizeBytes = cursor.getLong(sizeCol),
                        dateAddedSec = cursor.getLong(addedCol),
                        dateModifiedMs = cursor.getLong(modifiedCol) * 1000L,
                        mimeType = cursor.getString(mimeCol).orEmpty(),
                        width = if (widthCol >= 0) cursor.getInt(widthCol) else 0,
                        height = if (heightCol >= 0) cursor.getInt(heightCol) else 0,
                        bucketId = if (bucketCol >= 0) cursor.getString(bucketCol).orEmpty() else ""
                    )
                )
            }
        }
        return entries
    }

    // ------------------------------------------------------------------
    // Folder tree construction
    // ------------------------------------------------------------------

    /** Mutable node used while the tree is assembled, then frozen to [VideoFolder]. */
    private class MutableFolder(val path: String, val name: String) {
        val videos = mutableListOf<VideoEntry>()
        val children = LinkedHashMap<String, MutableFolder>()

        fun freeze(): VideoFolder = VideoFolder(
            path = path,
            name = name,
            videos = videos.sortedBy { it.title.lowercase(Locale.US) },
            folders = children.values
                .map { it.freeze() }
                .sortedBy { it.name.lowercase(Locale.US) }
        )
    }

    private fun buildTree(videos: List<VideoEntry>): VideoFolder {
        val root = MutableFolder("", VideoFolder.ROOT.name)
        // path -> node, for every directory created
        val nodes = HashMap<String, MutableFolder>()

        fun folderFor(folderPath: String): MutableFolder {
            nodes[folderPath]?.let { return it }
            val parentDirPath = folderPath.substringBeforeLast('/', "")
            val node = MutableFolder(folderPath, volumeLabelFor(folderPath)
                ?: folderPath.substringAfterLast('/').ifBlank { folderPath })
            nodes[folderPath] = node
            if (parentPath.isBlank() || isVolumeRoot(folderPath)) {
                root.children[folderPath] = node
            } else {
                folderFor(parentPath).children[folderPath] = node
            }
            return node
        }

        videos.forEach { video ->
            val dirPath = video.parentDirPath
            if (dirPath.isBlank()) return@forEach
            folderFor(dirPath).videos.add(video)
        }

        return root.freeze()
    }

    private fun isVolumeRoot(path: String): Boolean {
        val parent = File(path).parentFile?.absolutePath
        return path.matches(Regex("/storage/[^/]+/?")) ||
            path.matches(Regex("/storage/emulated/\\d+")) ||
            parent == "/storage" ||
            parent == "/mnt"
    }

    private fun volumeLabelFor(path: String): String? {
        if (!isVolumeRoot(path)) return null
        val name = path.trimEnd('/').substringAfterLast('/')
        return if (name == "0" || path.startsWith("/storage/emulated")) {
            "Internal storage"
        } else {
            name.replaceFirstChar { it.uppercase(Locale.US) }
        }
    }
}
