package com.quantum.player.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.lang.Long

/**
 * Entity for storing playback history.
 */
@Entity(tableName = "playback_history")
data class PlaybackHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val mediaItemId: String = "",
    val title: String = "",
    val uri: String = "",
    val resumePositionMs: Long = 0,
    val lastPlayed: Long = 0,
    val playCount: Int = 0,
    val watchState: String = WatchState.Unwatched.name,
    val rating: Float = 0f
) {

    /** Convert watch state string to enum */
    val watchStateEnum: WatchState
        get() = WatchState.valueOf(watchState)

    /** Convert watch state enum to string */
    fun setWatchState(state: WatchState) {
        this.watchState = state.name
    }
}

/**
 * Entity for storing favorites.
 */
@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val mediaItemId: String = "",
    val title: String = "",
    val uri: String = "",
    val addedAt: Long = 0
)

/**
 * Entity for storing playlists.
 */
@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val name: String = "",
    val description: String = ""
)

/**
 * Entity for playlist items (many-to-many between playlists and media items).
 */
@Entity(tableName = "playlist_items")
data class PlaylistItemEntity(
    @PrimaryKey(
        columnName = "item_id",
        autoGenerate = false
    )
    val id: Long = 0,

    val playlistId: Long = 0,
    val mediaItemId: String = "",
    val orderIndex: Int = 0
)

/**
 * Entity for storing per-video settings.
 */
@Entity(tableName = "video_settings")
data class VideoSettingsEntity(
    @PrimaryKey
    val mediaItemId: String = "",

    val preferredSpeed: Float = 1.0f,
    val resumePositionMs: Long = 0,
    val selectedAudioTrack: Int = -1,
    val selectedSubtitleTrack: Int = -1,
    val subtitleDelayMs: Long = 0,
    val subtitleSize: Float = 20f,
    val subtitlePositionX: Float = 0f,
    val subtitlePositionY: Float = 0f,
    val aspectRatio: String = AspectRatioMode.Auto.name,
    val skipSilence: Boolean = false,
    val hdrMode: String = "default",
    val lastModified: Long = 0L
)

/**
 * Entity for storing silence analysis cache.
 */
@Entity(tableName = "silence_analysis_cache")
data class SilenceAnalysisCacheEntity(
    @PrimaryKey
    val mediaItemId: String = "",

    val analysisData: String = "",  // JSON serialized analysis result
    val lastAnalyzed: Long = 0L,
    val accessCount: Int = 0
)

/**
 * Entity for storing recently played files.
 */
@Entity(tableName = "recent_files")
data class RecentFileEntity(
    @PrimaryKey
    val filePath: String = "",

    val lastAccessed: Long = 0,
    val accessCount: Int = 0,
    val favorite: Boolean = false
)

/**
 * Entity for storing watched/unwatched state per media item.
 */
@Entity(tableName = "watch_state")
data class WatchStateEntity(
    @PrimaryKey
    val mediaItemId: String = "",

    val watchState: String = WatchState.Unwatched.name,
    val lastUpdated: Long = 0L
)

/**
 * Entity for storing thumbnail cache.
 */
@Entity(tableName = "thumbnail_cache")
data class ThumbnailCacheEntity(
    @PrimaryKey
    val mediaItemId: String = "",

    val thumbnailData: ByteArray = emptyByteArray(),
    val thumbnailWidth: Int = 0,
    val thumbnailHeight: Int = 0,
    val lastGenerated: Long = 0L
)

/**
 * Entity for storing metadata cache.
 */
@Entity(tableName = "metadata_cache")
data class MetadataCacheEntity(
    @PrimaryKey
    val mediaItemId: String = "",

    val metadataJson: String = "",
    val lastUpdated: Long = 0L
)