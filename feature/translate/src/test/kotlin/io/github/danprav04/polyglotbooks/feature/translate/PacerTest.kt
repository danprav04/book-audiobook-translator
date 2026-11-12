package io.github.danprav04.polyglotbooks.feature.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PacerTest {

    private val pacer = Pacer(
        minBufferAheadMs = 10_000L,
        targetBufferAheadMs = 30_000L,
        maxSendSpeed = 1.5f
    )

    @Test
    fun testSpeedCurveAtKeyPoints() {
        // When buffer is 0 ms: max speed 1.5x
        assertEquals(1.5f, pacer.calculateSpeedMultiplier(0L), 0.001f)

        // When buffer is 5,000 ms: still < minBuffer (10s), so 1.5x
        assertEquals(1.5f, pacer.calculateSpeedMultiplier(5_000L), 0.001f)

        // When buffer is exactly minBufferAheadMs (10,000 ms): 1.5x
        assertEquals(1.5f, pacer.calculateSpeedMultiplier(10_000L), 0.001f)

        // When buffer is halfway (20,000 ms): linear midpoint between 1.5x and 1.0x -> 1.25x
        assertEquals(1.25f, pacer.calculateSpeedMultiplier(20_000L), 0.001f)

        // When buffer reaches targetBufferAheadMs (30,000 ms): exactly 1.0x
        assertEquals(1.0f, pacer.calculateSpeedMultiplier(30_000L), 0.001f)

        // When buffer exceeds targetBufferAheadMs (45,000 ms): clamped to 1.0x
        assertEquals(1.0f, pacer.calculateSpeedMultiplier(45_000L), 0.001f)
    }

    @Test
    fun testDelayCalculationsFor100MsChunk() {
        val chunkDurationMs = 100L

        // Under-buffered (e.g. 5s): 1.5x speed -> 100 / 1.5 = 66 ms delay
        val delayUnderBuffered = pacer.calculateDelayMs(chunkDurationMs, 5_000L)
        assertEquals(66L, delayUnderBuffered)

        // Half-buffered (20s): 1.25x speed -> 100 / 1.25 = 80 ms delay
        val delayHalfBuffered = pacer.calculateDelayMs(chunkDurationMs, 20_000L)
        assertEquals(80L, delayHalfBuffered)

        // Target buffered (30s+): 1.0x speed -> 100 / 1.0 = 100 ms delay
        val delayTargetBuffered = pacer.calculateDelayMs(chunkDurationMs, 30_000L)
        assertEquals(100L, delayTargetBuffered)

        val delayOverBuffered = pacer.calculateDelayMs(chunkDurationMs, 50_000L)
        assertEquals(100L, delayOverBuffered)
    }

    @Test
    fun testDriftCompensation() {
        val chunkDurationMs = 100L
        // At 1.0x speed, nominal delay is 100ms.
        // With 30ms positive drift (latency), delay is reduced to 70ms to catch up.
        val delayWithDrift = pacer.calculateDelayMs(chunkDurationMs, 30_000L, driftMs = 30L)
        assertEquals(70L, delayWithDrift)

        // Excessive drift larger than nominal delay clamps to 0ms (cannot have negative delay)
        val delayOverDrift = pacer.calculateDelayMs(chunkDurationMs, 30_000L, driftMs = 200L)
        assertEquals(0L, delayOverDrift)
    }

    @Test
    fun testBackpressureSupportWhenBufferExceedsTarget() {
        // Below target (25s): backpressure is not active
        assertFalse(pacer.isBackpressureActive(25_000L))
        assertFalse(pacer.shouldThrottle(25_000L))
        assertFalse(pacer.shouldPause(25_000L))
        assertEquals(0L, pacer.calculateBackpressureDelayMs(25_000L))

        // At target (30s): backpressure active
        assertTrue(pacer.isBackpressureActive(30_000L))
        assertTrue(pacer.shouldThrottle(30_000L))
        assertTrue(pacer.shouldPause(30_000L))

        // Over target (35s): backpressure active, throttles with excess buffer delay
        assertTrue(pacer.isBackpressureActive(35_000L))
        val backpressureDelay = pacer.calculateBackpressureDelayMs(35_000L, chunkDurationMs = 100L)
        assertEquals(5_000L, backpressureDelay)

        // calculateDelayMs with applyBackpressure = true throttles
        val delayWithBackpressure = pacer.calculateDelayMs(100L, 35_000L, applyBackpressure = true)
        assertTrue(delayWithBackpressure >= 5_000L)

        // Pacer with enableBackpressure = true in constructor
        val backpressurePacer = Pacer(enableBackpressure = true)
        val bpDelay = backpressurePacer.calculateDelayMs(100L, 35_000L)
        assertTrue(bpDelay >= 5_000L)
    }

    @Test
    fun testMonotonicity() {
        // Speed multiplier should be non-increasing as buffer increases
        var prevSpeed = Float.MAX_VALUE
        for (buffer in 0L..40_000L step 1_000L) {
            val speed = pacer.calculateSpeedMultiplier(buffer)
            assertTrue("Speed should be non-increasing, was $speed after $prevSpeed", speed <= prevSpeed)
            prevSpeed = speed
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun testInvalidConfiguration() {
        Pacer(minBufferAheadMs = 20_000L, targetBufferAheadMs = 10_000L)
    }
}
