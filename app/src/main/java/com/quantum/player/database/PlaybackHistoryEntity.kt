package com.quantum.player.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.quantum.player.core.AspectRatioMode
import com.quantum.player.model.WatchState

/**
 * Entity for storing playback history.
 *
 * Column names are declared explicitly with [ColumnInfo]: the DAO queries were
 * written against snake_case columns, but Room derives column names from the
 * Kotlin property names, so without these annotations every query referenced a
 * column that does not exist.
 *
 * The `total_play_time_ms` column was added because `getAveragePlayTime()`
 * already queries it. It is additive - no existing column changed.
 */
@Entity(tableName = "playback_history")
data class PlaybackHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "media_item_id")
    val mediaItemId: String = "",

    @ColumnInfo(name = "title")
    val title: String = "",

    @ColumnInfo(name = "uri")
    val uri: String = "",

    @ColumnInfo(name = "resume_position_ms")
    val resumePositionMs: Long = 0,

    @ColumnInfo(name = "total_play_time_ms")
    val totalPlayTimeMs: Long = 0,

    @ColumnInfo(name = "last_played")
    val lastPlayed: Long = 0,

    @ColumnInfo(name = "play_count")
    val playCount: Int = 0,

    @ColumnInfo(name = "watch_state")
    val watchState: String = WatchState.Unwatched.name,

    @ColumnInfo(name = "rating")
    val rating: Float = 0f
) {
    /** Convert watch state string to enum (unknown values fall back to Unwatched). */
    val watchStateEnum: WatchState
        get() = WatchState.fromName(watchState)

    /** Convert watch state enum to a stored row. */
    fun withWatchState(state: WatchState): PlaybackHistoryEntity =
        copy(watchState = state.name)
}

/**
 * Entity for storing favorites.
 */
@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "media_item_id")
    val mediaItemId: String = "",

    @ColumnInfo(name = "title")
    val title: String = "",

    @ColumnInfo(name = "uri")
    val uri: String = "",

    @ColumnInfo(name = "added_at")
    val addedAt: Long = 0
)

/**
 * Entity for storing playlists.
 */
@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "name")
    val name: String = "",

    @ColumnInfo(name = "description")
    val description: String = ""
)

/**
 * Entity for playlist items (many-to-many between playlists and media items).
 *
 * `@PrimaryKey(columnName = ...)` was invalid: `@PrimaryKey` only accepts
 * `autoGenerate`. Column naming belongs on `@ColumnInfo`.
 */
@Entity(tableName = "playlist_items")
data class PlaylistItemEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "item_id")
    val id: Long = 0,

    @ColumnInfo(name = "playlist_id")
    val playlistId: Long = 0,

    @ColumnInfo(name = "media_item_id")
    val mediaItemId: String = "",

    @ColumnInfo(name = "order_index")
    val orderIndex: Int = 0
)

/**
 * Entity for storing per-video settings.
 *
 * `aspectRatio` is stored as the enum itself; [Converters] maps it to TEXT so
 * the column type is unchanged.
 */
@Entity(tableName = "video_settings")
data class VideoSettingsEntity(
    @PrimaryKey
    @ColumnInfo(name = "media_item_id")
    val mediaItemId: String = "",

    @ColumnInfo(name = "preferred_speed")
    val preferredSpeed: Float = 1.0f,

    @ColumnInfo(name = "resume_position_ms")
    val resumePositionMs: Long = 0,

    @ColumnInfo(name = "selected_audio_track")
    val selectedAudioTrack: Int = -1,

    @ColumnInfo(name = "selected_subtitle_track")
    val selectedSubtitleTrack: Int = -1,

    @ColumnInfo(name = "subtitle_delay_ms")
    val subtitleDelayMs: Long = 0,

    @ColumnInfo(name = "subtitle_size")
    val subtitleSize: Float = 20f,

    @ColumnInfo(name = "subtitle_position_x")
    val subtitlePositionX: Float = 0f,

    @ColumnInfo(name = "subtitle_position_y")
    val subtitlePositionY: Float = 0f,

    @ColumnInfo(name = "aspect_ratio")
    val aspectRatio: AspectRatioMode = AspectRatioMode.Auto,

    @ColumnInfo(name = "skip_silence")
    val skipSilence: Boolean = false,

    @ColumnInfo(name = "hdr_mode")
    val hdrMode: String = "default",

    @ColumnInfo(name = "last_modified")
    val lastModified: Long = 0L
)

/**
 * Entity for storing silence analysis cache.
 */
@Entity(tableName = "silence_analysis_cache")
data class SilenceAnalysisCacheEntity(
    @PrimaryKey
    @ColumnInfo(name = "media_item_id")
    val mediaItemId: String = "",

    @ColumnInfo(name = "analysis_data")
    val analysisData: String = "",

    @ColumnInfo(name = "last_analyzed")
    val lastAnalyzed: Long = 0L,

    @ColumnInfo(name = "access_count")
    val accessCount: Int = 0
)

/**
 * Entity for storing recently played files.
 */
@Entity(tableName = "recent_files")
data class RecentFileEntity(
    @PrimaryKey
    @ColumnInfo(name = "file_path")
    val filePath: String = "",

    @ColumnInfo(name = "last_accessed")
    val lastAccessed: Long = 0,

    @ColumnInfo(name = "access_count")
    val accessCount: Int = 0,

    @ColumnInfo(name = "favorite")
    val favorite: Boolean = false
)

/**
 * Entity for storing watched/unwatched state per media item.
 */
@Entity(tableName = "watch_state")
data class WatchStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "media_item_id")
    val mediaItemId: String = "",

    @ColumnInfo(name = "watch_state")
    val watchState: WatchState = WatchState.Unwatched,

    @ColumnInfo(name = "last_updated")
    val lastUpdated: Long = 0L
)

/**
 * Entity for storing thumbnail cache.
 */
@Entity(tableName = "thumbnail_cache")
data class ThumbnailCacheEntity(
    @PrimaryKey
    @ColumnInfo(name = "media_item_id")
    val mediaItemId: String = "",

    @ColumnInfo(name = "thumbnail_data")
    val thumbnailData: ByteArray = ByteArray(0),

    @ColumnInfo(name = "thumbnail_width")
    val thumbnailWidth: Int = 0,

    @ColumnInfo(name = "thumbnail_height")
    val thumbnailHeight: Int = 0,

    @ColumnInfo(name = "last_generated")
    val lastGenerated: Long = 0L
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ThumbnailCacheEntity) return false
        return mediaItemId == other.mediaItemId &&
            thumbnailWidth == other.thumbnailWidth &&
            thumbnailHeight == other.thumbnailHeight &&
            lastGenerated == other.lastGenerated &&
            thumbnailData.contentEquals(other.thumbnailData)
    }

    override fun hashCode(): Int {
        var result = mediaItemId.hashCode()
        result = 31 * result + thumbnailData.contentHashCode()
        result = 31 * result + thumbnailWidth
        result = 31 * result + thumbnailHeight
        result = 31 * result + lastGenerated.hashCode()
        return result
    }
}

/**
 * Entity for storing metadata cache.
 */
@Entity(tableName = "metadata_cache")
data class MetadataCacheEntity(
    @PrimaryKey
    @ColumnInfo(name = "media_item_id")
    val mediaItemId: String = "",

    @ColumnInfo(name = "metadata_json")
    val metadataJson: String = "",

    @ColumnInfo(name = "last_updated")
    val lastUpdated: Long = 0L
)
