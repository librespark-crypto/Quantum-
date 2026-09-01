package com.quantum.player.library

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Dynamic video keyframe thumbnails with disk caching.
 *
 * Uses [MediaMetadataRetriever.getFrameAtTime] to extract a representative
 * frame (a few percent into the video, so black title frames are avoided),
 * compresses it to a JPEG in the app cache dir, and serves subsequent requests
 * straight from disk. The cache is keyed by MediaStore row id + date-modified
 * so a replaced file never shows a stale frame, and bounded with a simple
 * oldest-first size cap.
 *
 * This is the engine behind the video list thumbnails; Glide/Coil automatic
 * frame decoders are not used because they cannot seek to a representative
 * keyframe or share one bounded cache across the whole grid.
 */
class ThumbnailLoader(context: Context) {

    private val appContext = context.applicationContext
    private val cacheDir = File(appContext.cacheDir, "video_thumbnails").apply { mkdirs() }
    private val maxCacheBytes = 256L * 1024L * 1024L // 256 MB

    /**
     * Return a cached thumbnail file for [entry], extracting it on first use.
     * Returns null when the frame cannot be decoded (corrupt file / DRM).
     */
    suspend fun load(entry: VideoEntry): File? = withContext(Dispatchers.IO) {
        val key = "thumb_${entry.id}_${entry.dateModifiedMs / 1000}.jpg"
        val cached = File(cacheDir, key)
        if (cached.exists() && cached.length() > 0) return@withContext cached

        val retriever = MediaMetadataRetriever()
        val bitmap: Bitmap? = try {
            // Context + content Uri is the only overload that survives
            // scoped-storage permission rules for MediaStore rows.
            retriever.setDataSource(appContext, entry.uri)
            val targetUs = (entry.durationMs * 1000L * 0.02f).toLong().coerceAtLeast(0L)
            // OPTION_CLOSEST_SYNC lands on a real keyframe instead of a mid-GOP
            // seek that some devices render as a black frame.
            retriever.getFrameAtTime(targetUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }

        if (bitmap == null) return@withContext null

        val wrote = runCatching {
            FileOutputStream(cached).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
            }
            true
        }.getOrDefault(false)

        bitmap.recycle()
        if (!wrote) {
            cached.delete()
            null
        } else {
            evictIfNeeded()
            cached
        }
    }

    /** Remove thumbnails for [entry] (used when a file is deleted). */
    fun invalidate(entry: VideoEntry) {
        cacheDir.listFiles { f -> f.name.startsWith("thumb_${entry.id}_") }
            ?.forEach { it.delete() }
    }

    /** Oldest-first eviction when the cache exceeds its size cap. */
    private fun evictIfNeeded() {
        val files = cacheDir.listFiles()?.toMutableList() ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxCacheBytes) return
        files.sortBy { it.lastModified() }
        for (file in files) {
            if (total <= maxCacheBytes) break
            val size = file.length()
            if (file.delete()) total -= size
        }
    }
}
