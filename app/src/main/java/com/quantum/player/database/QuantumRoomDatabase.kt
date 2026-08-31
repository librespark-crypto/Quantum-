package com.quantum.player.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import kotlinx.coroutines.flow.Flow

/**
 * The Quantum Room database.
 *
 * There used to be two `@Database` classes (`QuantumDatabase` and
 * `QuantumRoomDatabase`) declaring the same ten entities at the same version,
 * with a third copy of the type converters nested inside the first one. Only
 * this class is used (see `QuantumApplication`), so the duplicate was removed
 * rather than kept as dead, divergent schema.
 */
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
@TypeConverters(Converters::class)
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

    /** Get watched states as a reactive stream. */
    fun getWatchStatesFlow(): Flow<List<WatchStateEntity>> = watchStateDao().loadAll()

    /** Get recent files as a reactive stream. */
    fun getRecentFilesFlow(): Flow<List<RecentFileEntity>> = recentFilesDao().loadRecent()

    /** Get playback history as a reactive stream. */
    fun getPlaybackHistoryFlow(): Flow<List<PlaybackHistoryEntity>> =
        playbackHistoryDao().loadAll()

    companion object {
        const val DATABASE_NAME: String = "quantum-player-db"

        @Volatile
        private var INSTANCE: QuantumRoomDatabase? = null

        /** Get the shared database instance. */
        fun getInstance(context: Context): QuantumRoomDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    QuantumRoomDatabase::class.java,
                    DATABASE_NAME
                ).build().also { INSTANCE = it }
            }

        /** Delete the database file. Intended for tests and "clear app data" flows. */
        fun deleteDatabase(context: Context) {
            // `Room.deleteDatabase` does not exist; deleting is a Context operation.
            context.applicationContext.deleteDatabase(DATABASE_NAME)
            INSTANCE = null
        }
    }
}
