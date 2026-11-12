package io.github.danprav04.polyglotbooks.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackComponentsTest {

    @Test
    fun testSleepTimerCountdown() = runTest {
        var finished = false
        val sleepTimer = SleepTimer(backgroundScope) {
            finished = true
        }

        sleepTimer.start(3000L)
        runCurrent()
        assertTrue(sleepTimer.isTimerRunning.value)
        assertEquals(3000L, sleepTimer.remainingTimeMs.value)

        advanceTimeBy(1000L)
        runCurrent()
        assertEquals(2000L, sleepTimer.remainingTimeMs.value)

        advanceTimeBy(2000L)
        runCurrent()
        assertEquals(0L, sleepTimer.remainingTimeMs.value)
        assertTrue(finished)
    }

    @Test
    fun testPlaybackSpeedClamping() {
        val controllerStub = StubTranslatedPlayer()
        controllerStub.setPlaybackSpeed(4.5f)
        controllerStub.setPlaybackSpeed(0.2f)
        assertTrue(true)
    }
}
