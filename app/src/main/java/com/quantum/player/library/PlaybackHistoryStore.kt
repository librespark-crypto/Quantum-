package com.quantum.player.library

import android.net.Uri
import com.quantum.player.database.PlaybackHistoryEntity
import com.quantum.player.database.QuantumRoomDatabase
import com.quantum.player.model.WatchState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Writes playback history into Room so the library can:
 *  - tag unplayed videos with NEW badges (play_count == 0 / no row)
 *  - show resume positions (resume_position_ms)
 *
 * Reads happen in [MediaLibraryRepository]; this class owns the writes,
 * throttled by the caller (progress is persisted every few seconds while
 * playing and once on pause/close).
 */
class PlaybackHistoryStore(private val database: QuantumRoomDatabase) {

    /** Record a fresh playback start (increments play count). */
    suspend fun recordStarted(uri: String, title: String) = withContext(Dispatchers.IO) {
        val dao = database.playbackHistoryDao()
        val existing = dao.loadByUri(uri)
        if (existing == null) {
            dao.insert(
                PlaybackHistoryEntity(
                    mediaItemId = uri,
                    title = title,
                    uri = uri,
                    playCount = 1,
                    lastPlayed = System.currentTimeMillis(),
                    watchState = WatchState.InProgress.name
                )
            )
        } else {
            dao.insert(
                existing.copy(
                    playCount = existing.playCount + 1,
                    lastPlayed = System.currentTimeMillis(),
                    watchState = WatchState.InProgress.name
                )
            )
        }
    }

    /** Persist the current resume position (throttled by the caller). */
    suspend fun recordProgress(uri: String, title: String, positionMs: Long, durationMs: Long) =
        withContext(Dispatchers.IO) {
            val dao = database.playbackHistoryDao()
            val existing = dao.loadByUri(uri)
            // Mark as watched when reaching the final ~95%.
            val finished = durationMs > 0 && positionMs >= durationMs * 0.95
            if (existing == null) {
                dao.insert(
                    PlaybackHistoryEntity(
                        mediaItemId = uri,
                        title = title,
                        uri = uri,
                        playCount = 1,
                        resumePositionMs = if (finished) 0 else positionMs,
                        lastPlayed = System.currentTimeMillis(),
                        totalPlayTimeMs = 0,
                        watchState = if (finished) WatchState.Watched.name else WatchState.InProgress.name
                    )
                )
            } else {
                dao.insert(
                    existing.copy(
                        resumePositionMs = if (finished) 0 else positionMs,
                        lastPlayed = System.currentTimeMillis(),
                        watchState = if (finished) WatchState.Watched.name else existing.watchState
                    )
                )
            }
        }

    /** True when [uri] belongs to a local content:// media row (vs a stream). */
    fun isLocalUri(uri: String): Boolean =
        runCatching { Uri.parse(uri).scheme == "content" || Uri.parse(uri).scheme == "file" }
            .getOrDefault(false)
}
