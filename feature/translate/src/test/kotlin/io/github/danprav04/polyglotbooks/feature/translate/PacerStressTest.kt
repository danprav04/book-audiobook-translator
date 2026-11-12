package io.github.danprav04.polyglotbooks.feature.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PacerStressTest {

    private val pacer = Pacer(
        minBufferAheadMs = 10_000L,
        targetBufferAheadMs = 30_000L,
        maxSendSpeed = 1.5f
    )

    @Test
    fun testPacerFullBoundaryRanges() {
        // Required test points: negative buffer, 0ms, 9999ms, 10000ms, 20000ms, 30000ms, 50000ms, 1,000,000ms
        val testPoints = listOf(
            -5000L to 1.5f,
            -1L to 1.5f,
            0L to 1.5f,
            9999L to 1.5f,
            10000L to 1.5f,
            20000L to 1.25f,
            30000L to 1.0f,
            50000L to 1.0f,
            1_000_000L to 1.0f
        )

        for ((bufferMs, expectedSpeed) in testPoints) {
            val speed = pacer.calculateSpeedMultiplier(bufferMs)
            // Clamp invariant: speed is always within [1.0f, maxSendSpeed]
            assertTrue("Speed must be >= 1.0f", speed >= 1.0f)
            assertTrue("Speed must be <= 1.5f", speed <= 1.5f)
            assertEquals("Speed mismatch at buffer $bufferMs", expectedSpeed, speed, 0.001f)

            // Test delay calculation for 100ms chunk
            val delay = pacer.calculateDelayMs(100L, bufferMs)
            assertTrue("Delay must be > 0", delay > 0)
            val expectedDelay = (100L / expectedSpeed).toLong()
            assertEquals("Delay mismatch at buffer $bufferMs", expectedDelay, delay)

            // Test delay calculation for 0ms chunk
            val delayZero = pacer.calculateDelayMs(0L, bufferMs)
            assertEquals(0L, delayZero)
        }
    }

    @Test
    fun testPacerMonotonicDeceleration() {
        var prevSpeed = 1.5f
        for (buffer in -1000L..50_000L step 50L) {
            val speed = pacer.calculateSpeedMultiplier(buffer)
            assertTrue("Speed should monotonically decrease/stay equal. Was $speed after $prevSpeed at buffer $buffer", speed <= prevSpeed + 1e-6f)
            assertTrue("Speed must remain clamped in [1.0, 1.5]", speed in 1.0f..1.5f)
            prevSpeed = speed
        }
    }

    @Test
    fun testPacerExtremeValuesAndNoDivisionByZero() {
        val extremeBuffers = listOf(
            Long.MIN_VALUE,
            -1_000_000_000L,
            Long.MAX_VALUE,
            1_000_000_000L
        )

        for (buffer in extremeBuffers) {
            val speed = pacer.calculateSpeedMultiplier(buffer)
            assertTrue("Speed must be clamped for buffer $buffer", speed in 1.0f..1.5f)

            val delay100 = pacer.calculateDelayMs(100L, buffer)
            assertTrue("Delay must be positive for positive duration", delay100 > 0)
        }
    }
}
