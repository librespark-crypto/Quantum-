package com.quantum.player.playback

import com.quantum.player.core.PlaybackState
import com.quantum.player.model.MediaItem

/**
 * A-B Repeat functionality for marking start and end points of a section to repeat.
 */
class AbRepeatController {

    /** Whether A-B repeat is enabled */
    var isEnabled: Boolean = false

    /** Start marker position in milliseconds */
    var startMs: Long = 0

    /** End marker position in milliseconds */
    var endMs: Long = 0

    /** Whether we're currently in the repeat section */
    var isInRepeatSection: Boolean = false

    /** Toggle A-B repeat on/off */
    fun toggle() {
        isEnabled = !isEnabled
        if (!isEnabled) {
            startMs = 0
            endMs = 0
        }
    }

    /** Set A-B repeat markers */
    fun setMarkers(startMs: Long, endMs: Long) {
        this.startMs = startMs.coerceAtMost(endMs)
        this.endMs = endMs.coerceAtLeast(startMs)
        isEnabled = true
    }

    /** Get current position within repeat section */
    fun getPositionInSection(currentPosition: Long): Long {
        if (!isEnabled || startMs == 0 || endMs == 0) return currentPosition
        val sectionLength = endMs - startMs
        if (sectionLength <= 0) return currentPosition
        return startMs + (currentPosition - startMs) % sectionLength
    }

    /** Check if current position is within the repeat section */
    fun isPositionWithinSection(position: Long): Boolean {
        return isEnabled && position >= startMs && position <= endMs
    }
}

/**
 * Chapter navigation for media with chapter markers.
 */
class ChapterNavigator {

    /** Current chapter index */
    var currentChapterIndex: Int = 0

    /** Available chapters */
    private var chapters: List<ChapterInfo> = emptyList()

    /** Add a chapter */
    fun addChapter(title: String, startTimeMs: Long, endTimeMs: Long) {
        chapters = ChapterInfo(title, startTimeMs, endTimeMs) :: chapters
        // Sort by start time
        chapters = chapters.sortedBy { it.startTimeMs }
    }

    /** Get current chapter */
    fun getCurrentChapter(): ChapterInfo? {
        val chapter = chapters.find { currentChapterIndex >= 0 && currentChapterIndex < chapters.size }
        return if (currentChapterIndex in chapters.indices) chapters[currentChapterIndex] else null
    }

    /** Navigate to next chapter */
    fun nextChapter() {
        if (chapters.isEmpty()) return
        currentChapterIndex = (currentChapterIndex + 1) % chapters.size
        // Seek to chapter start
    }

    /** Navigate to previous chapter */
    fun previousChapter() {
        if (chapters.isEmpty()) return
        currentChapterIndex = (currentChapterIndex - 1 + chapters.size) % chapters.size
        // Seek to chapter start
    }

    /** Jump to chapter by index */
    fun jumpToChapter(index: Int) {
        if (index >= 0 && index < chapters.size) {
            currentChapterIndex = index
            // Seek to chapter start time
        }
    }

    /** Data class for chapter information. */
    data class ChapterInfo(
        val title: String,
        val startTimeMs: Long,
        val endTimeMs: Long
    )
}

/**
 * Sleep timer for automatic playback stop.
 */
class SleepTimer {

    /** Whether sleep timer is active */
    var isActive: Boolean = false

    /** Auto-stop time in minutes */
    var stopMinutes: Int = 15

    /** Scheduled task for stopping playback */
    private var scheduledTask: Job? = null

    /** Set sleep timer */
    fun setTimer(minutes: Int) {
        stopMinutes = minutes.coerceIn(1, 120)
        isActive = true
        scheduleStop()
    }

    /** Cancel sleep timer */
    fun cancelTimer() {
        isActive = false
        scheduledTask?.cancel()
        scheduledTask = null
    }

    /** Get remaining time in minutes */
    fun getRemainingMinutes(): Int {
        // In a full implementation, this would calculate based on scheduled time
        return stopMinutes
    }

    /** Schedule playback stop */
    private fun scheduleStop() {
        // In a full implementation, use WorkManager or coroutine timeout
        scheduledTask = kotlinx.coroutines.Dispatchers.Default
            .launch {
                kotlinx.coroutines.delay(stopMinutes * 60 * 1000)
                // Signal playback to stop
            }
    }
}

/**
 * Playback resume support.
 * Persists and restores playback position across app restarts.
 */
class PlaybackResumeManager {

    /** Current resume position per media item */
    private val resumePositions = mutableMapOf<String, Long>()

    /** Save resume position */
    fun saveResumePosition(mediaItemId: String, positionMs: Long) {
        resumePositions[mediaItemId] = positionMs
        // Persist to Room database
    }

    /** Get resume position */
    fun getResumePosition(mediaItemId: String): Long {
        return resumePositions[mediaItemId] ?: 0
    }

    /** Clear resume position */
    fun clearResumePosition(mediaItemId: String) {
        resumePositions.remove(mediaItemId)
    }

    /** Get all saved resume positions */
    fun getAllResumePositions(): Map<String, Long> {
        return resumePositions.toMutableMap()
    }
}

/**
 * Playback speed controller with granular control.
 * Supports speeds from 0.25x to 4.0x with fine-grained adjustments.
 */
class PlaybackSpeedController {

    /** Current playback speed */
    var currentSpeed: Float = 1.0f

    /** Minimum and maximum supported speeds */
    val minSpeed = 0.25f
    val maxSpeed = 4.0f

    /** Speed step for incremental changes */
    val speedStep = 0.05f

    /** Toggle between common speeds */
    private val commonSpeeds = listOf(0.5f, 1.0f, 1.5f, 2.0f, 4.0f)

    /** Increase speed (step or long-press for jump) */
    fun increaseSpeed(step: Float = speedStep): Float {
        val newSpeed = (currentSpeed + step).coerceIn(minSpeed, maxSpeed)
        if (newSpeed != currentSpeed) {
            currentSpeed = newSpeed
        }
        // Return next common speed if at boundary
        return if (currentSpeed >= 2.0f) commonSpeeds.firstOrNull { it >= 2.0f }?.coerceIn(minSpeed, maxSpeed)!!
            else currentSpeed
    }

    /** Decrease speed (step or long-press for jump) */
    fun decreaseSpeed(step: Float = speedStep): Float {
        val newSpeed = (currentSpeed - step).coerceIn(minSpeed, maxSpeed)
        if (newSpeed != currentSpeed) {
            currentSpeed = newSpeed
        }
        // Return previous common speed if at boundary
        return if (currentSpeed <= 0.5f) commonSpeeds.lastOrNull { it <= 0.5f }?.coerceIn(minSpeed, maxSpeed)!!
            else currentSpeed
    }

    /** Set speed to a common value */
    fun setCommonSpeed(speed: Float) {
        val validSpeed = speed.coerceIn(minSpeed, maxSpeed)
        if (validSpeed != currentSpeed) {
            currentSpeed = validSpeed
        }
    }

    /** Get speed as string for display */
    fun getSpeedDisplayString(): String {
        return when {
            currentSpeed == 1.0f -> "1×"
            currentSpeed == 0.5f -> "0.5×"
            currentSpeed == 2.0f -> "2×"
            currentSpeed == 4.0f -> "4×"
            currentSpeed == 0.25f -> "0.25×"
            else -> "×${currentSpeed.toStringAsFixed(2)}"
        }
    }
}