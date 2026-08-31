package com.quantum.player.database

import android.content.ApplicationContext
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import com.quantum.player.model.MediaItem
import com.quantum.player.model.PlaybackStatistics
import com.quantum.player.model.WatchState
import com.quantum.player.model.VideoSettings

@Database(
    entities = [
        PlaybackHistoryEntity::class,
        FavoriteEntity::class,
        PlaylistEntity::class,
        PlaylistItemEntity::class,
        VideoSettingsEntity::class,
        SilenceAnalysisCacheEntity::class,
        RecentFileEntity::class,
        WatchStateEntity::class,
        ThumbnailCacheEntity::class,
        MetadataCacheEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class QuantumRoomDatabase : RoomDatabase() {

    abstract fun playbackHistoryDao(): PlaybackHistoryDao
    abstract fun favoritesDao(): FavoritesDao
    abstract fun playlistsDao(): PlaylistsDao
    abstract fun videoSettingsDao(): VideoSettingsDao
    abstract fun silenceAnalysisCacheDao(): SilenceAnalysisCacheDao
    abstract fun recentFilesDao(): RecentFilesDao
    abstract fun watchStateDao(): WatchStateDao
    abstract fun thumbnailCacheDao(): ThumbnailCacheDao
    abstract fun metadataCacheDao(): MetadataCacheDao

    /** Create database instance. */
    companion object {
        @Volatile
        private var INSTANCE: QuantumRoomDatabase? = null

        fun getInstance(context: ApplicationContext): QuantumRoomDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    QuantumRoomDatabase::class.java,
                    "quantum-player-db"
                .build()
                INSTANCE = instance
                instance
            }
        }

        /** Delete database for testing. */
        fun deleteDatabase(context: ApplicationContext) {
            Room.deleteDatabase(context.applicationContext)
            INSTANCE = null
        }
    }

    /** Get watched states flow. */
    fun getWatchStatesFlow(): Flow<List<WatchStateEntity>> {
        return flow {
            getWatchStateDao().loadAll().collect { emit(it) }
        }
    }

    /** Get recent files flow. */
    fun getRecentFilesFlow(): Flow<List<RecentFileEntity>> {
        return flow {
            getRecentFilesDao().loadRecent().collect { emit(it) }
        }
    }

    /** Get playback history flow. */
    fun getPlaybackHistoryFlow(): Flow<List<PlaybackHistoryEntity>> {
        return flow {
            getPlaybackHistoryDao().loadAll().collect { emit(it) }
        }
    }
}