package com.quantum.player.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * DAOs for the Quantum database.
 *
 * Fixes applied here:
 *  - `delete`/`deleteAll` had no Room annotation at all, so KSP could not
 *    generate an implementation.
 *  - `isFavorite` ran `SELECT *` into a `Boolean`; it now uses `EXISTS`.
 *  - `... AS exists` used a SQLite keyword as a column alias.
 *  - `getAveragePlayTime` returned `Long?` for an `AVG()` aggregate, which
 *    SQLite evaluates as a float; it is now `Double?`.
 *  - `SilenceAnalysisCacheDao.updateAccessCount` bound a `:count` parameter the
 *    query never used.
 *  - The collection reads that the database exposes as `Flow` now actually
 *    return `Flow` instead of a one-shot `List` that callers tried to `collect`.
 */
@Dao
interface PlaybackHistoryDao {

    /** Insert playback history item. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(history: PlaybackHistoryEntity)

    /** Insert multiple history items. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(vararg histories: PlaybackHistoryEntity)

    /** Delete a history item. */
    @Delete
    suspend fun delete(history: PlaybackHistoryEntity)

    /** Delete all history items. */
    @Query("DELETE FROM playback_history")
    suspend fun deleteAll()

    /** Get all history items ordered by last played. */
    @Query("SELECT * FROM playback_history ORDER BY last_played DESC")
    fun loadAll(): Flow<List<PlaybackHistoryEntity>>

    /** Get history item by URI. */
    @Query("SELECT * FROM playback_history WHERE uri = :uri LIMIT 1")
    suspend fun loadByUri(uri: String): PlaybackHistoryEntity?

    /** Get history item by media item ID. */
    @Query("SELECT * FROM playback_history WHERE media_item_id = :id LIMIT 1")
    suspend fun loadByMediaItemId(id: String): PlaybackHistoryEntity?

    /** Update play count, last played time and the resume position. */
    @Query(
        "UPDATE playback_history SET play_count = play_count + 1, " +
            "last_played = :timestamp, resume_position_ms = :resumePositionMs " +
            "WHERE id = :id"
    )
    suspend fun updateCount(id: Long, timestamp: Long, resumePositionMs: Long)

    /** Persist a resume position without touching the play count. */
    @Query("UPDATE playback_history SET resume_position_ms = :positionMs WHERE id = :id")
    suspend fun updateResumePosition(id: Long, positionMs: Long)

    /** Accumulate watched time. */
    @Query("UPDATE playback_history SET total_play_time_ms = total_play_time_ms + :deltaMs WHERE id = :id")
    suspend fun addPlayTime(id: Long, deltaMs: Long)

    /** Get total play count. */
    @Query("SELECT SUM(play_count) FROM playback_history")
    suspend fun getTotalPlayCount(): Long?

    /** Get average play time in milliseconds. */
    @Query("SELECT AVG(total_play_time_ms) FROM playback_history")
    suspend fun getAveragePlayTime(): Double?
}

@Dao
interface FavoritesDao {

    /** Check if item is in favorites. */
    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE media_item_id = :id)")
    suspend fun isFavorite(id: String): Boolean

    /** Add item to favorites. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addFavorite(favorite: FavoriteEntity)

    /** Remove item from favorites. */
    @Query("DELETE FROM favorites WHERE media_item_id = :id")
    suspend fun removeFavorite(id: String)

    /** Get all favorites. */
    @Query("SELECT * FROM favorites ORDER BY added_at DESC")
    fun loadAll(): Flow<List<FavoriteEntity>>

    /** Get favorite count. */
    @Query("SELECT COUNT(*) FROM favorites")
    suspend fun getCount(): Int
}

@Dao
interface PlaylistsDao {

    /** Create a new playlist. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun createPlaylist(playlist: PlaylistEntity)

    /** Delete a playlist and its items. */
    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deletePlaylist(id: Long)

    /** Remove every item belonging to a playlist. */
    @Query("DELETE FROM playlist_items WHERE playlist_id = :playlistId")
    suspend fun deletePlaylistItems(playlistId: Long)

    /** Get a playlist by ID. */
    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun loadById(id: Long): PlaylistEntity?

    /** Get all playlists. */
    @Query("SELECT * FROM playlists")
    fun loadAll(): Flow<List<PlaylistEntity>>

    /** Add item to playlist. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addItemToPlaylist(item: PlaylistItemEntity)

    /** Remove item from playlist. */
    @Query("DELETE FROM playlist_items WHERE playlist_id = :playlistId AND media_item_id = :mediaId")
    suspend fun removeItemFromPlaylist(playlistId: Long, mediaId: String)

    /** Get items in a playlist. */
    @Query("SELECT * FROM playlist_items WHERE playlist_id = :playlistId ORDER BY order_index")
    fun loadItems(playlistId: Long): Flow<List<PlaylistItemEntity>>

    /** Get all items across all playlists. */
    @Query("SELECT * FROM playlist_items")
    fun loadAllItems(): Flow<List<PlaylistItemEntity>>

    /** Check if item exists in playlist. */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM playlist_items " +
            "WHERE playlist_id = :playlistId AND media_item_id = :mediaId)"
    )
    suspend fun existsInPlaylist(playlistId: Long, mediaId: String): Boolean
}

@Dao
interface VideoSettingsDao {

    /** Get video settings for a media item. */
    @Query("SELECT * FROM video_settings WHERE media_item_id = :id")
    suspend fun loadByMediaItemId(id: String): VideoSettingsEntity?

    /** Insert or update video settings. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(settings: VideoSettingsEntity)

    /** Update resume position. */
    @Query("UPDATE video_settings SET resume_position_ms = :position, last_modified = :timestamp WHERE media_item_id = :id")
    suspend fun updateResumePosition(id: String, position: Long, timestamp: Long = System.currentTimeMillis())

    /** Update preferred speed. */
    @Query("UPDATE video_settings SET preferred_speed = :speed, last_modified = :timestamp WHERE media_item_id = :id")
    suspend fun updatePreferredSpeed(id: String, speed: Float, timestamp: Long = System.currentTimeMillis())

    /** Update subtitle track. */
    @Query("UPDATE video_settings SET selected_subtitle_track = :track WHERE media_item_id = :id")
    suspend fun updateSubtitleTrack(id: String, track: Int)

    /** Update audio track. */
    @Query("UPDATE video_settings SET selected_audio_track = :track WHERE media_item_id = :id")
    suspend fun updateAudioTrack(id: String, track: Int)

    /** Update subtitle delay. */
    @Query("UPDATE video_settings SET subtitle_delay_ms = :delay WHERE media_item_id = :id")
    suspend fun updateSubtitleDelay(id: String, delay: Long)

    /** Update aspect ratio. */
    @Query("UPDATE video_settings SET aspect_ratio = :mode WHERE media_item_id = :id")
    suspend fun updateAspectRatio(id: String, mode: String)

    /** Update skip silence setting. */
    @Query("UPDATE video_settings SET skip_silence = :skip WHERE media_item_id = :id")
    suspend fun updateSkipSilence(id: String, skip: Boolean)

    /** Update HDR mode. */
    @Query("UPDATE video_settings SET hdr_mode = :mode WHERE media_item_id = :id")
    suspend fun updateHDRMode(id: String, mode: String)

    /** Get all video settings. */
    @Query("SELECT * FROM video_settings")
    fun loadAll(): Flow<List<VideoSettingsEntity>>

    /** Get total count. */
    @Query("SELECT COUNT(*) FROM video_settings")
    suspend fun getCount(): Int
}

@Dao
interface SilenceAnalysisCacheDao {

    /** Get cached analysis result. */
    @Query("SELECT * FROM silence_analysis_cache WHERE media_item_id = :id")
    suspend fun loadByMediaItemId(id: String): SilenceAnalysisCacheEntity?

    /** Save analysis result. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveCache(cache: SilenceAnalysisCacheEntity)

    /** Record a cache hit. */
    @Query("UPDATE silence_analysis_cache SET access_count = access_count + 1 WHERE media_item_id = :id")
    suspend fun incrementAccessCount(id: String)

    /** Evict the least recently analysed entries beyond [keepCount]. */
    @Query(
        "DELETE FROM silence_analysis_cache WHERE media_item_id NOT IN " +
            "(SELECT media_item_id FROM silence_analysis_cache ORDER BY last_analyzed DESC LIMIT :keepCount)"
    )
    suspend fun evictBeyond(keepCount: Int)

    /** Get all cached analyses. */
    @Query("SELECT * FROM silence_analysis_cache ORDER BY last_analyzed DESC")
    fun loadAll(): Flow<List<SilenceAnalysisCacheEntity>>
}

@Dao
interface RecentFilesDao {

    /** Add or update recent file. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addRecentFile(file: RecentFileEntity)

    /** Get recent files ordered by last accessed. */
    @Query("SELECT * FROM recent_files ORDER BY last_accessed DESC LIMIT 50")
    fun loadRecent(): Flow<List<RecentFileEntity>>

    /** Update access count. */
    @Query("UPDATE recent_files SET last_accessed = :timestamp, access_count = access_count + 1 WHERE file_path = :filePath")
    suspend fun updateAccess(filePath: String, timestamp: Long)

    /** Remove recent file. */
    @Query("DELETE FROM recent_files WHERE file_path = :filePath")
    suspend fun removeRecentFile(filePath: String)

    /** Check if file is in recent list. */
    @Query("SELECT EXISTS(SELECT 1 FROM recent_files WHERE file_path = :filePath)")
    suspend fun exists(filePath: String): Boolean
}

@Dao
interface WatchStateDao {

    /** Get watch state for a media item. */
    @Query("SELECT * FROM watch_state WHERE media_item_id = :id")
    suspend fun loadByMediaItemId(id: String): WatchStateEntity?

    /** Update watch state. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun updateWatchState(watchState: WatchStateEntity)

    /** Set as watched. */
    @Query("UPDATE watch_state SET watch_state = 'Watched', last_updated = :timestamp WHERE media_item_id = :id")
    suspend fun setAsWatched(id: String, timestamp: Long)

    /** Set as unwatched. */
    @Query("UPDATE watch_state SET watch_state = 'Unwatched', last_updated = :timestamp WHERE media_item_id = :id")
    suspend fun setAsUnwatched(id: String, timestamp: Long)

    /** Get all watch states. */
    @Query("SELECT * FROM watch_state")
    fun loadAll(): Flow<List<WatchStateEntity>>

    /** Get watched count. */
    @Query("SELECT COUNT(*) FROM watch_state WHERE watch_state = 'Watched'")
    suspend fun getWatchedCount(): Int

    /** Get unwatched count. */
    @Query("SELECT COUNT(*) FROM watch_state WHERE watch_state = 'Unwatched'")
    suspend fun getUnwatchedCount(): Int
}

@Dao
interface ThumbnailCacheDao {

    /** Get thumbnail for a media item. */
    @Query("SELECT * FROM thumbnail_cache WHERE media_item_id = :id")
    suspend fun loadByMediaItemId(id: String): ThumbnailCacheEntity?

    /** Save thumbnail. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveThumbnail(thumbnail: ThumbnailCacheEntity)

    /** Remove thumbnail. */
    @Query("DELETE FROM thumbnail_cache WHERE media_item_id = :id")
    suspend fun removeByMediaItemId(id: String)
}

@Dao
interface MetadataCacheDao {

    /** Get metadata for a media item. */
    @Query("SELECT * FROM metadata_cache WHERE media_item_id = :id")
    suspend fun loadByMediaItemId(id: String): MetadataCacheEntity?

    /** Save metadata. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveMetadata(metadata: MetadataCacheEntity)

    /** Update last updated timestamp. */
    @Query("UPDATE metadata_cache SET last_updated = :timestamp WHERE media_item_id = :id")
    suspend fun updateTimestamp(id: String, timestamp: Long)
}
