package com.quantum.player.library

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.lastPlayedDataStore by preferencesDataStore(name = "quantum_last_played")

/**
 * Persistence for the "resume last played video" FAB.
 *
 * Stores the last played video's URI, title, path and timestamp with Jetpack
 * DataStore (Preferences). The player writes progress periodically while
 * playing; the library screen reads [lastPlayed] to decide whether the FAB is
 * shown, and calls [launchUri]/[launchPositionMs] when the user taps it to
 * resume playback exactly where they left off.
 */
class LastPlayedStore(private val context: Context) {

    /** Observe the last played record; emitted [LastPlayed.hasMedia] is false when empty. */
    val lastPlayed: Flow<LastPlayed> = context.lastPlayedDataStore.data.map { prefs ->
        LastPlayed(
            uri = prefs[KEY_URI].orEmpty(),
            title = prefs[KEY_TITLE].orEmpty(),
            path = prefs[KEY_PATH].orEmpty(),
            positionMs = prefs[KEY_POSITION] ?: 0L,
            durationMs = prefs[KEY_DURATION] ?: 0L,
            updatedAtMs = prefs[KEY_UPDATED_AT] ?: 0L
        )
    }

    /** Record that playback started for [uri]. Position is kept from the resume point. */
    suspend fun onPlaybackStarted(uri: String, title: String, path: String, positionMs: Long) {
        context.lastPlayedDataStore.edit { prefs ->
            prefs[KEY_URI] = uri
            prefs[KEY_TITLE] = title
            prefs[KEY_PATH] = path
            prefs[KEY_POSITION] = positionMs.coerceAtLeast(0L)
            prefs[KEY_UPDATED_AT] = System.currentTimeMillis()
        }
    }

    /** Persist the current playback position (called on a throttle while playing and on pause). */
    suspend fun saveProgress(positionMs: Long, durationMs: Long) {
        context.lastPlayedDataStore.edit { prefs ->
            if (prefs[KEY_URI].isNullOrBlank()) return@edit
            prefs[KEY_POSITION] = positionMs.coerceAtLeast(0L)
            if (durationMs > 0) prefs[KEY_DURATION] = durationMs
            prefs[KEY_UPDATED_AT] = System.currentTimeMillis()
        }
    }

    /** Forget the record once a video has finished (FAB hides again). */
    suspend fun clear() {
        context.lastPlayedDataStore.edit { it.clear() }
    }

    private companion object {
        val KEY_URI = stringPreferencesKey("last_uri")
        val KEY_TITLE = stringPreferencesKey("last_title")
        val KEY_PATH = stringPreferencesKey("last_path")
        val KEY_POSITION = longPreferencesKey("last_position_ms")
        val KEY_DURATION = longPreferencesKey("last_duration_ms")
        val KEY_UPDATED_AT = longPreferencesKey("last_updated_at")
    }
}
