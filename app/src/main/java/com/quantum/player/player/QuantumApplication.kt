package com.quantum.player.player

import android.app.Application
import com.quantum.player.core.PlaybackEngine
import com.quantum.player.core.PlaybackManager
import com.quantum.player.database.QuantumRoomDatabase

/**
 * Application entry point.
 *
 * Holds the single application-scoped [PlaybackEngine]. Both the player activity
 * and the background service resolve playback through this instance, which is
 * what prevents two players from being created for the same item (duplicate
 * audio, duplicate surfaces, leaked decoders).
 *
 * The old version imported itself (`import com.quantum.player.player.QuantumApplication`),
 * which is not legal Kotlin.
 */
class QuantumApplication : Application() {

    private var engineInstance: PlaybackManager? = null

    /** The shared Room database. */
    val database: QuantumRoomDatabase by lazy { QuantumRoomDatabase.getInstance(this) }

    /** The one and only playback engine for this process. */
    val playbackEngine: PlaybackEngine
        get() = engineInstance ?: PlaybackManager(this).also { engineInstance = it }

    override fun onCreate() {
        super.onCreate()
        // Database and engine are created lazily so app start stays cheap.
    }

    /** Get the application's Room database instance. */
    fun getDatabase(): QuantumRoomDatabase = database

    override fun onTerminate() {
        // Only release a player that was actually created.
        engineInstance?.let { engine ->
            kotlinx.coroutines.runBlocking { engine.release() }
            engine.shutdown()
        }
        engineInstance = null
        super.onTerminate()
    }
}
