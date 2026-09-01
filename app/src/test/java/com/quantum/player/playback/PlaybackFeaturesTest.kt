package com.quantum.player.playback

import com.quantum.player.core.AspectRatioMode
import com.quantum.player.database.VideoSettingsDao
import com.quantum.player.database.VideoSettingsEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the playback feature helpers: A-B repeat, chapters, the sleep
 * timer, resume positions and speed control.
 *
 * None of these touch the player, so they are exercised directly.
 */
class PlaybackFeaturesTest {

    // ------------------------------------------------------------------
    // A-B repeat
    // ------------------------------------------------------------------

    @Test
    fun `setMarkers normalises reversed input`() {
        val ab = AbRepeatController()
        ab.setMarkers(first = 60_000, second = 10_000)
        assertEquals(10_000L, ab.startMs)
        assertEquals(60_000L, ab.endMs)
        assertTrue("a valid range enables the loop", ab.isEnabled)
        assertEquals(50_000L, ab.sectionLengthMs)
    }

    @Test
    fun `equal markers do not enable a zero length loop`() {
        val ab = AbRepeatController()
        ab.setMarkers(first = 5_000, second = 5_000)
        assertFalse(ab.isEnabled)
        assertEquals(0L, ab.sectionLengthMs)
    }

    @Test
    fun `negative markers are clamped to zero`() {
        val ab = AbRepeatController()
        ab.setMarkers(first = -1_000, second = 2_000)
        assertEquals(0L, ab.startMs)
        assertEquals(2_000L, ab.endMs)
    }

    @Test
    fun `toggle disables and clears the markers`() {
        val ab = AbRepeatController()
        ab.setMarkers(1_000, 4_000)
        assertTrue(ab.isEnabled)
        ab.toggle()
        assertFalse(ab.isEnabled)
        assertEquals(0L, ab.startMs)
        assertEquals(0L, ab.endMs)
    }

    @Test
    fun `position past the B point wraps back into the section`() {
        val ab = AbRepeatController()
        ab.setMarkers(10_000, 20_000)
        assertEquals(10_000L, ab.wrapPosition(20_000))
        assertEquals(12_000L, ab.wrapPosition(22_000))
        // Before A the position is left alone.
        assertEquals(5_000L, ab.wrapPosition(5_000))
        // Inside the section nothing moves.
        assertEquals(15_000L, ab.wrapPosition(15_000))
    }

    @Test
    fun `seek target is only offered once the B point is reached`() {
        val ab = AbRepeatController()
        ab.setMarkers(10_000, 20_000)
        assertNull(ab.seekTargetAt(15_000))
        assertEquals(10_000L, ab.seekTargetAt(20_000))
        assertEquals(10_000L, ab.seekTargetAt(25_000))
    }

    @Test
    fun `no seek target while repeat is disabled`() {
        val ab = AbRepeatController()
        assertNull(ab.seekTargetAt(100_000))
    }

    @Test
    fun `membership test respects the enabled flag`() {
        val ab = AbRepeatController()
        ab.setMarkers(10_000, 20_000)
        assertTrue(ab.isPositionWithinSection(15_000))
        assertTrue(ab.isPositionWithinSection(10_000))
        assertTrue(ab.isPositionWithinSection(20_000))
        assertFalse(ab.isPositionWithinSection(25_000))
        ab.toggle()
        assertFalse("disabled repeat contains nothing", ab.isPositionWithinSection(15_000))
    }

    // ------------------------------------------------------------------
    // Chapters
    // ------------------------------------------------------------------

    private fun navigator() = ChapterNavigator().apply {
        setChapters(
            listOf(
                ChapterNavigator.ChapterInfo("Intro", 0, 30_000),
                ChapterNavigator.ChapterInfo("Main", 30_000, 90_000),
                ChapterNavigator.ChapterInfo("Credits", 90_000, 100_000)
            )
        )
    }

    @Test
    fun `chapter lookup by position`() {
        val nav = navigator()
        assertEquals("Intro", nav.chapterAt(1_000)?.title)
        assertEquals("Main", nav.chapterAt(60_000)?.title)
        assertEquals("Credits", nav.chapterAt(99_000)?.title)
        assertNull("position past the end is outside every chapter", nav.chapterAt(200_000))
    }

    @Test
    fun `next and previous wrap around the chapter list`() {
        val nav = navigator()
        assertEquals(0, nav.currentChapterIndex)
        assertEquals(30_000L, nav.nextChapter())
        assertEquals(90_000L, nav.nextChapter())
        assertEquals("wraps to the first chapter", 0L, nav.nextChapter())
        assertEquals("wraps to the last chapter", 90_000L, nav.previousChapter())
    }

    @Test
    fun `jumping to an unknown chapter returns null and keeps the index`() {
        val nav = navigator()
        nav.jumpToChapter(1)
        assertNull(nav.jumpToChapter(99))
        assertEquals(1, nav.currentChapterIndex)
        assertEquals(30_000L, nav.jumpToChapter(0))
        assertEquals(0, nav.currentChapterIndex)
    }

    @Test
    fun `empty chapter list never navigates`() {
        val nav = ChapterNavigator()
        assertNull(nav.nextChapter())
        assertNull(nav.previousChapter())
        assertNull(nav.jumpToChapter(0))
        assertNull(nav.getCurrentChapter())
        assertNull(nav.chapterAt(1_000))
    }

    @Test
    fun `setChapters replaces rather than appends`() {
        val nav = navigator()
        assertEquals(3, nav.chapters.size)
        nav.setChapters(listOf(ChapterNavigator.ChapterInfo("Only", 0, 10_000)))
        assertEquals(1, nav.chapters.size)
        assertEquals("Only", nav.getCurrentChapter()?.title)
    }

    // ------------------------------------------------------------------
    // Speed
    // ------------------------------------------------------------------

    @Test
    fun `setSpeed returns the value it actually applied`() {
        val speed = PlaybackSpeedController()
        assertEquals(1.5f, speed.setSpeed(1.5f), 1e-4f)
        assertEquals(1.5f, speed.currentSpeed, 1e-4f)
    }

    @Test
    fun `speed is clamped to the supported range`() {
        val speed = PlaybackSpeedController()
        // The previous implementation returned a hard-coded 2.0 / 0.5 here, so
        // the UI displayed a speed the player was never given.
        assertEquals(speed.maxSpeed, speed.setSpeed(99f), 1e-4f)
        assertEquals(speed.minSpeed, speed.setSpeed(0.001f), 1e-4f)
        assertEquals(speed.minSpeed, speed.setSpeed(-5f), 1e-4f)
    }

    @Test
    fun `step changes move by the step and stay in range`() {
        val speed = PlaybackSpeedController()
        assertEquals(1.0f + speed.speedStep, speed.increaseSpeed(), 1e-4f)
        assertEquals(1.0f, speed.decreaseSpeed(), 1e-4f)
        repeat(200) { speed.increaseSpeed() }
        assertEquals(speed.maxSpeed, speed.currentSpeed, 1e-4f)
        repeat(400) { speed.decreaseSpeed() }
        assertEquals(speed.minSpeed, speed.currentSpeed, 1e-4f)
    }

    @Test
    fun `reset returns to normal speed`() {
        val speed = PlaybackSpeedController()
        speed.setSpeed(3f)
        assertEquals(1f, speed.reset(), 1e-4f)
        assertEquals(1f, speed.currentSpeed, 1e-4f)
    }

    @Test
    fun `speed formatting has no decimal point for whole numbers`() {
        val speed = PlaybackSpeedController()
        assertEquals("1x", speed.speedToString(1f))
        assertEquals("2x", speed.speedToString(2f))
        assertEquals("1.5x", speed.speedToString(1.5f))
        speed.setSpeed(1f)
        assertEquals("1x", speed.getSpeedDisplayString())
    }

    @Test
    fun `common speeds all fall inside the supported range`() {
        val speed = PlaybackSpeedController()
        speed.commonSpeeds.forEach { common ->
            assertTrue("$common must be >= min", common >= speed.minSpeed)
            assertTrue("$common must be <= max", common <= speed.maxSpeed)
            assertEquals(common, speed.setCommonSpeed(common), 1e-4f)
        }
    }

    // ------------------------------------------------------------------
    // Resume positions
    // ------------------------------------------------------------------

    @Test
    fun `resume positions round trip through the manager`() = runBlocking {
        val manager = PlaybackResumeManager()
        assertEquals(0L, manager.getResumePosition("item-1"))
        manager.saveResumePosition("item-1", 42_000)
        assertEquals(42_000L, manager.getResumePosition("item-1"))
        assertEquals(mapOf("item-1" to 42_000L), manager.getAllResumePositions())
        manager.clearResumePosition("item-1")
        assertEquals(0L, manager.getResumePosition("item-1"))
    }

    @Test
    fun `negative resume positions are rejected`() = runBlocking {
        val manager = PlaybackResumeManager()
        manager.saveResumePosition("item-2", -1_000)
        assertEquals(0L, manager.getResumePosition("item-2"))
    }

    @Test
    fun `resume positions are persisted to the dao`() = runBlocking {
        val dao = FakeVideoSettingsDao()
        val manager = PlaybackResumeManager(dao)

        manager.saveResumePosition("persisted", 30_000)
        assertEquals(30_000L, dao.loadByMediaItemId("persisted")?.resumePositionMs)

        // A second save must update, not duplicate.
        manager.saveResumePosition("persisted", 55_000)
        assertEquals(55_000L, dao.loadByMediaItemId("persisted")?.resumePositionMs)
        assertEquals(1, dao.getCount())

        manager.clearResumePosition("persisted")
        assertEquals(0L, dao.loadByMediaItemId("persisted")?.resumePositionMs)
    }

    @Test
    fun `a position only in the database is picked up`() = runBlocking {
        val dao = FakeVideoSettingsDao()
        dao.save(VideoSettingsEntity(mediaItemId = "cold", resumePositionMs = 77_000))
        val manager = PlaybackResumeManager(dao)
        assertEquals(77_000L, manager.getResumePosition("cold"))
    }

    @Test
    fun `resumability skips the head and the tail of an item`() {
        val manager = PlaybackResumeManager()
        assertFalse("the first seconds are not worth resuming", manager.isResumable(2_000, 600_000))
        assertTrue(manager.isResumable(60_000, 600_000))
        assertFalse("a position at the very end is not worth resuming", manager.isResumable(595_000, 600_000))
        // Unknown duration: only the head rule applies.
        assertTrue(manager.isResumable(60_000, 0))
    }

    // ------------------------------------------------------------------
    // Sleep timer
    // ------------------------------------------------------------------

    @Test
    fun `sleep timer is inactive until set`() {
        // A dedicated scope, never the runBlocking one: a pending timer job
        // would otherwise keep runBlocking from returning.
        val scope = CoroutineScope(Dispatchers.Default + Job())
        try {
            val timer = SleepTimer(scope)
            assertFalse(timer.isActive)
            assertEquals(0, timer.getRemainingMinutes())
            timer.close()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `sleep timer reports a remaining time and can be cancelled`() {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        try {
            val timer = SleepTimer(scope)
            timer.setTimer(15) { }
            assertTrue(timer.isActive)
            assertEquals(15, timer.stopMinutes)
            val remaining = timer.getRemainingMinutes()
            assertEquals(15, remaining)
            timer.cancelTimer()
            assertFalse(timer.isActive)
            assertEquals(0, timer.getRemainingMinutes())
            timer.close()
        } finally {
            scope.cancel()
        }
    }

    // ------------------------------------------------------------------
    // Test double
    // ------------------------------------------------------------------

    /** In-memory stand-in for the Room DAO so persistence can be asserted. */
    private class FakeVideoSettingsDao : VideoSettingsDao {
        private val rows = mutableMapOf<String, VideoSettingsEntity>()

        override suspend fun loadByMediaItemId(id: String): VideoSettingsEntity? = rows[id]

        override suspend fun save(settings: VideoSettingsEntity) {
            rows[settings.mediaItemId] = settings
        }

        override suspend fun updateResumePosition(id: String, position: Long, timestamp: Long) {
            rows[id]?.let { rows[id] = it.copy(resumePositionMs = position, lastModified = timestamp) }
        }

        override suspend fun updatePreferredSpeed(id: String, speed: Float, timestamp: Long) {
            rows[id]?.let { rows[id] = it.copy(preferredSpeed = speed, lastModified = timestamp) }
        }

        override suspend fun updateSubtitleTrack(id: String, track: Int) {
            rows[id]?.let { rows[id] = it.copy(selectedSubtitleTrack = track) }
        }

        override suspend fun updateAudioTrack(id: String, track: Int) {
            rows[id]?.let { rows[id] = it.copy(selectedAudioTrack = track) }
        }

        override suspend fun updateSubtitleDelay(id: String, delay: Long) {
            rows[id]?.let { rows[id] = it.copy(subtitleDelayMs = delay) }
        }

        override suspend fun updateAspectRatio(id: String, mode: String) {
            // The column stores the AspectRatioMode enum; the DAO takes its name.
            val parsed = runCatching { AspectRatioMode.valueOf(mode) }.getOrNull() ?: return
            rows[id]?.let { rows[id] = it.copy(aspectRatio = parsed) }
        }

        override suspend fun updateSkipSilence(id: String, skip: Boolean) {
            rows[id]?.let { rows[id] = it.copy(skipSilence = skip) }
        }

        override suspend fun updateHDRMode(id: String, mode: String) {
            rows[id]?.let { rows[id] = it.copy(hdrMode = mode) }
        }

        override fun loadAll(): Flow<List<VideoSettingsEntity>> = flowOf(rows.values.toList())

        override suspend fun getCount(): Int = rows.size
    }
}
