package com.quantum.player.database

import androidx.room.Database
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import androidx.room.TypeConverters

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
@TypeConverters([Converters::class])
abstract class QuantumDatabase : RoomDatabase() {

    abstract fun playbackHistoryDao(): PlaybackHistoryDao
    abstract fun favoritesDao(): FavoritesDao
    abstract fun playlistsDao(): PlaylistsDao
    abstract fun videoSettingsDao(): VideoSettingsDao
    abstract fun silenceAnalysisCacheDao(): SilenceAnalysisCacheDao
    abstract fun recentFilesDao(): RecentFilesDao
    abstract fun watchStateDao(): WatchStateDao
    abstract fun thumbnailCacheDao(): ThumbnailCacheDao
    abstract fun metadataCacheDao(): MetadataCacheDao

    /**
     * Get recent files as a Flow.
     */
    fun getRecentFilesFlow(): Flow<List<RecentFileEntity>> {
        return flow { getRecentFilesDao().loadAll().collect { it -> emit(it) } }
    }

    /**
     * Get watched states as a Flow.
     */
    fun getWatchStatesFlow(): Flow<List<WatchStateEntity>> {
        return flow { getWatchStateDao().loadAll().collect { it -> emit(it) } }
    }

    /**
     * Converter utility class for type conversions.
     */
    object Converters {
        /** Convert Long to String */
        @TypeConverter
        fun longToString(long: Long): String {
            return long.toString()
        }

        /** Convert String to Long */
        @TypeConverter
        fun stringToLong(string: String): Long {
            return string.toLong()
        }

        /** Convert Float to String */
        @TypeConverter
        fun floatToString(float: Float): String {
            return float.toString()
        }

        /** Convert String to Float */
        @TypeConverter
        fun stringToFloat(string: String): Float {
            return string.toFloat()
        }

        /** Convert AspectRatioMode to String */
        @TypeConverter
        fun aspectRatioModeToString(mode: AspectRatioMode.Mode): String {
            return mode.name
        }

        /** Convert String to AspectRatioMode */
        @TypeConverter
        fun stringToAspectRatioMode(mode: String): AspectRatioMode.Mode {
            return AspectRatioMode.Mode.valueOf(mode)
        }
    }

    /**
     * Enum extension for AspectRatioMode.
     */
    enum class Mode {
        Auto,
        Fit,
        Fill,
        Original,
        Custom
    }
}