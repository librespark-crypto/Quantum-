package com.quantum.player.playback

import com.quantum.player.database.VideoSettingsDao
import com.quantum.player.database.VideoSettingsEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

/**
 * A-B Repeat functionality for marking start and end points of a section to repeat.
 */
class AbRepeatController {

    /** Whether A-B repeat is enabled. */
    var isEnabled: Boolean = false
        private set

    /** Start marker position in milliseconds. */
    var startMs: Long = 0
        private set

    /** End marker position in milliseconds. */
    var endMs: Long = 0
        private set

    /** Toggle A-B repeat on/off. */
    fun toggle() {
        isEnabled = !isEnabled
        if (!isEnabled) {
            startMs = 0
            endMs = 0
        }
    }

    /**
     * Set A-B repeat markers. The pair is normalised so `startMs <= endMs`
     * regardless of the order the caller supplies.
     */
    fun setMarkers(first: Long, second: Long) {
        startMs = minOf(first, second).coerceAtLeast(0L)
        endMs = maxOf(first, second)
        isEnabled = endMs > startMs
    }

    /** Length of the loop, or 0 when no valid loop is set. */
    val sectionLengthMs: Long
        get() = if (isEnabled) (endMs - startMs).coerceAtLeast(0L) else 0L

    /**
     * Wrap a position that has run past the B point back into the section.
     * Returns the position unchanged when A-B repeat is not usable.
     */
    fun wrapPosition(currentPosition: Long): Long {
        val length = sectionLengthMs
        if (length <= 0 || currentPosition < startMs) return currentPosition
        return startMs + (currentPosition - startMs) % length
    }

    /**
     * The position to seek to when the loop end is reached, or null when the
     * caller should keep playing normally.
     */
    fun seekTargetAt(currentPosition: Long): Long? =
        if (isEnabled && sectionLengthMs > 0 && currentPosition >= endMs) startMs else null

    /** Check if current position is within the repeat section. */
    fun isPositionWithinSection(position: Long): Boolean =
        isEnabled && position >= startMs && position <= endMs
}

/**
 * Chapter navigation for media with chapter markers.
 */
class ChapterNavigator {

    /** Current chapter index. */
    var currentChapterIndex: Int = 0
        private set

    /** Available chapters, ordered by start time. */
    var chapters: List<ChapterInfo> = emptyList()
        private set

    /** Add a chapter. */
    fun addChapter(title: String, startTimeMs: Long, endTimeMs: Long) {
        // `ChapterInfo(...) :: chapters` is not Kotlin; build the list properly.
        chapters = (chapters + ChapterInfo(title, startTimeMs, endTimeMs))
            .sortedBy { it.startTimeMs }
        currentChapterIndex = currentChapterIndex.coerceIn(0, (chapters.size - 1).coerceAtLeast(0))
    }

    /** Replace the whole chapter list (e.g. parsed from the container). */
    fun setChapters(newChapters: List<ChapterInfo>) {
        chapters = newChapters.sortedBy { it.startTimeMs }
        currentChapterIndex = 0
    }

    /** Get current chapter. */
    fun getCurrentChapter(): ChapterInfo? = chapters.getOrNull(currentChapterIndex)

    /** Chapter containing [positionMs], or null when outside all chapters. */
    fun chapterAt(positionMs: Long): ChapterInfo? =
        chapters.firstOrNull { positionMs in it.startTimeMs until it.endTimeMs }

    /** Navigate to next chapter, returning where playback should seek. */
    fun nextChapter(): Long? {
        if (chapters.isEmpty()) return null
        currentChapterIndex = (currentChapterIndex + 1) % chapters.size
        return chapters[currentChapterIndex].startTimeMs
    }

    /** Navigate to previous chapter, returning where playback should seek. */
    fun previousChapter(): Long? {
        if (chapters.isEmpty()) return null
        currentChapterIndex = (currentChapterIndex - 1 + chapters.size) % chapters.size
        return chapters[currentChapterIndex].startTimeMs
    }

    /** Jump to chapter by index, returning where playback should seek. */
    fun jumpToChapter(index: Int): Long? {
        val chapter = chapters.getOrNull(index) ?: return null
        currentChapterIndex = index
        return chapter.startTimeMs
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
class SleepTimer(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + Job())
) : AutoCloseable {

    /** Whether sleep timer is active. */
    @Volatile
    var isActive: Boolean = false
        private set

    /** Auto-stop time in minutes. */
    var stopMinutes: Int = 15
        private set

    private var scheduledTask: Job? = null
    private var deadlineMs: Long = 0L

    /**
     * Set the sleep timer.
     * @param onElapsed invoked on the timer's dispatcher when time is up
     */
    fun setTimer(minutes: Int, onElapsed: () -> Unit) {
        stopMinutes = minutes.coerceIn(1, 120)
        deadlineMs = System.currentTimeMillis() + stopMinutes * MILLIS_PER_MINUTE
        isActive = true
        scheduledTask?.cancel()
        scheduledTask = scope.launch {
            delay(stopMinutes * MILLIS_PER_MINUTE)
            isActive = false
            onElapsed()
        }
    }

    /** Cancel sleep timer. */
    fun cancelTimer() {
        isActive = false
        deadlineMs = 0L
        scheduledTask?.cancel()
        scheduledTask = null
    }

    /** Stop the timer. Safe to call more than once. */
    override fun close() {
        cancelTimer()
    }

    /** Get remaining time in minutes, rounded up. 0 when inactive. */
    fun getRemainingMinutes(): Int {
        if (!isActive || deadlineMs == 0L) return 0
        val remaining = deadlineMs - System.currentTimeMillis()
        if (remaining <= 0) return 0
        return ((remaining + MILLIS_PER_MINUTE - 1) / MILLIS_PER_MINUTE).toInt()
    }

    private companion object {
        const val MILLIS_PER_MINUTE = 60_000L
    }
}

/**
 * Playback resume support.
 * Persists and restores playback position across app restarts.
 *
 * The in-memory map is a fast path for the current process; when a
 * [VideoSettingsDao] is supplied the position is also written to Room, so it
 * survives an app restart. The old implementation only kept the map and had a
 * `// Persist to Room database` comment where the write should have been.
 */
class PlaybackResumeManager(
    private val settingsDao: VideoSettingsDao? = null
) {

    /** Current resume position per media item. */
    private val resumePositions = mutableMapOf<String, Long>()

    /** Save resume position. */
    suspend fun saveResumePosition(mediaItemId: String, positionMs: Long) {
        val safePosition = positionMs.coerceAtLeast(0L)
        synchronized(resumePositions) { resumePositions[mediaItemId] = safePosition }
        val dao = settingsDao ?: return
        val existing = dao.loadByMediaItemId(mediaItemId)
        if (existing == null) {
            dao.save(
                VideoSettingsEntity(
                    mediaItemId = mediaItemId,
                    resumePositionMs = safePosition,
                    lastModified = System.currentTimeMillis()
                )
            )
        } else {
            dao.updateResumePosition(mediaItemId, safePosition)
        }
    }

    /**
     * Get resume position. Falls back to the database when the value is not in
     * the in-memory map.
     */
    suspend fun getResumePosition(mediaItemId: String): Long {
        synchronized(resumePositions) {
            resumePositions[mediaItemId]?.let { return it }
        }
        val persisted = settingsDao?.loadByMediaItemId(mediaItemId)?.resumePositionMs ?: 0L
        synchronized(resumePositions) { resumePositions[mediaItemId] = persisted }
        return persisted
    }

    /** Clear resume position. */
    suspend fun clearResumePosition(mediaItemId: String) {
        synchronized(resumePositions) { resumePositions.remove(mediaItemId) }
        settingsDao?.loadByMediaItemId(mediaItemId)?.let {
            settingsDao.updateResumePosition(mediaItemId, 0L)
        }
    }

    /** Get all cached resume positions held in this process. */
    fun getAllResumePositions(): Map<String, Long> =
        synchronized(resumePositions) { resumePositions.toMap() }

    /**
     * Whether a stored position is worth resuming from: skip the first few
     * seconds, and skip a position at the very end of the item.
     */
    fun isResumable(resumePositionMs: Long, durationMs: Long): Boolean {
        if (resumePositionMs < MIN_RESUME_MS) return false
        if (durationMs > 0 && resumePositionMs > durationMs - END_MARGIN_MS) return false
        return true
    }

    private companion object {
        const val MIN_RESUME_MS = 5_000L
        const val END_MARGIN_MS = 30_000L
    }
}

/**
 * Playback speed controller with granular control.
 * Supports speeds from 0.25x to 4.0x with fine-grained adjustments.
 */
class PlaybackSpeedController {

    /** Current playback speed. */
    var currentSpeed: Float = 1.0f
        private set

    /** Minimum and maximum supported speeds. */
    val minSpeed = 0.25f
    val maxSpeed = 4.0f

    /** Speed step for incremental changes. */
    val speedStep = 0.05f

    /** Common speeds offered by the speed menu. */
    val commonSpeeds = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f, 4.0f)

    /** Increase speed by [step], clamped to the supported range. */
    fun increaseSpeed(step: Float = speedStep): Float =
        setSpeed(currentSpeed + abs(step))

    /** Decrease speed by [step], clamped to the supported range. */
    fun decreaseSpeed(step: Float = speedStep): Float =
        setSpeed(currentSpeed - abs(step))

    /** Set speed to a common value. */
    fun setCommonSpeed(speed: Float): Float = setSpeed(speed)

    /** Set an arbitrary speed, clamped to the supported range. */
    fun setSpeed(speed: Float): Float {
        // The previous version updated the field but then returned a fixed
        // 2.0/0.5 value at the range boundaries, so the UI showed a speed the
        // player was never given.
        currentSpeed = speed.coerceIn(minSpeed, maxSpeed)
        return currentSpeed
    }

    /** Reset to normal speed. */
    fun reset(): Float = setSpeed(1.0f)

    /** Get speed as string for display. */
    fun getSpeedDisplayString(): String = speedToString(currentSpeed)

    /** Format a speed the same way everywhere in the app. */
    fun speedToString(speed: Float): String =
        String.format(Locale.US, if (speed % 1f == 0f) "%.0fx" else "%.2gx", speed)
}
