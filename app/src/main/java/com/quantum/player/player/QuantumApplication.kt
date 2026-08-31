package com.quantum.player.player

import android.app.Application
import androidx.room.Room
import com.quantum.player.database.QuantumRoomDatabase
import com.quantum.player.player.QuantumApplication

class QuantumApplication : Application() {

    private val database by lazy {
        QuantumRoomDatabase.getInstance(this)
    }

    override fun onCreate() {
        super.onCreate()
        // Initialize playback manager and other components
        // Database is available via QuantumRoomDatabase.getInstance(this)
    }

    /** Get the application's Room database instance. */
    fun getDatabase(): QuantumRoomDatabase {
        return database
    }
}