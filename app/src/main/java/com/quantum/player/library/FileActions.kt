package com.quantum.player.library

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale

/**
 * Strictly *local* file actions for the video list's inline ⋮ menu:
 * File Info, Rename, Share, Delete.
 *
 * MediaStore rows are mutated through the platform content APIs (which own the
 * scoped-storage contract); the raw [File] path is used only for the
 * human-readable metadata in [describe].
 */
object FileActions {

    /** Result of a file action, surfaced as a toast in the UI. */
    data class Result(val ok: Boolean, val message: String)

    /** Static metadata lines for the "File info" bottom sheet. */
    fun describe(entry: VideoEntry): List<Pair<String, String>> = buildList {
        add("File name" to entry.fileName)
        add("Location" to entry.parentDirPath)
        if (entry.width > 0 && entry.height > 0) {
            add("Resolution" to "${entry.width} × ${entry.height}")
        }
        add("Duration" to formatDuration(entry.durationMs))
        add("Size" to formatSize(entry.sizeBytes))
        add("Type" to entry.mimeType.ifBlank { "video/*" })
        add("Modified" to java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM)
            .format(java.util.Date(entry.dateModifiedMs)))
    }

    /**
     * Rename [entry] to [newName] (with or without extension; the original
     * extension is preserved when the user does not type one).
     */
    fun rename(context: Context, entry: VideoEntry, newName: String): Result {
        val target = newName.trim()
        if (target.isEmpty() || target.contains('/') || target.contains('\\')) {
            return Result(false, "Invalid file name")
        }
        val extension = entry.fileName.substringAfterLast('.', "")
        val finalName = if (extension.isNotEmpty() && !target.contains('.')) {
            "$target.$extension"
        } else {
            target
        }
        if (finalName == entry.fileName) return Result(true, "Name unchanged")

        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, finalName)
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                val rows = context.contentResolver.update(entry.uri, values, null, null)
                check(rows > 0) { "MediaStore rejected rename" }
            } else {
                val source = File(entry.path)
                val destination = File(source.parentFile, finalName)
                check(!destination.exists()) { "A file with that name already exists" }
                check(source.renameTo(destination)) { "Rename failed" }
            }
            Result(true, "Renamed to $finalName")
        }.getOrElse { error -> Result(false, error.message ?: "Rename failed") }
    }

    /**
     * Delete [entry] from MediaStore (API 29+ shows the system confirm dialog;
     * on older versions the file is removed directly).
     */
    fun delete(context: Context, entry: VideoEntry): Result = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rows = context.contentResolver.delete(entry.uri, null, null)
            check(rows > 0) { "Delete was not allowed" }
        } else {
            val file = File(entry.path)
            check(file.exists() && file.delete()) { "Delete failed" }
        }
        Result(true, "Deleted ${entry.fileName}")
    }.getOrElse { error -> Result(false, error.message ?: "Delete failed") }

    /** Share [entry] through a FileProvider content URI with a video mime type. */
    fun share(context: Context, entry: VideoEntry): Result = runCatching {
        val shareUri: Uri = if (File(entry.path).exists()) {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                File(entry.path)
            )
        } else {
            entry.uri
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = entry.mimeType.ifBlank { "video/*" }
            putExtra(Intent.EXTRA_STREAM, shareUri)
            putExtra(Intent.EXTRA_SUBJECT, entry.fileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share ${entry.fileName}")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Result(true, "Sharing ${entry.fileName}")
    }.getOrElse { error -> Result(false, error.message ?: "Share failed") }

    // ---- formatting helpers (kept here so the browser has no util grab-bag) ----

    fun formatSize(size: Long): String {
        val kb = size / 1024.0
        return when {
            kb >= 1024.0 * 1024.0 -> String.format(Locale.US, "%.1f GB", kb / (1024.0 * 1024.0))
            kb >= 1024.0 -> String.format(Locale.US, "%.1f MB", kb / 1024.0)
            else -> String.format(Locale.US, "%.0f KB", kb)
        }
    }

    fun formatDuration(durationMs: Long): String {
        if (durationMs <= 0) return "--:--"
        val totalSeconds = durationMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    /** Volume label used for storage paths in File info. */
    fun storageLabel(path: String): String =
        if (path.startsWith(Environment.getExternalStorageDirectory().path)) {
            "Internal storage"
        } else {
            "Removable storage"
        }
}
