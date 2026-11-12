package io.github.danprav04.polyglotbooks.feature.translate

/**
 * Pacer controls transmission rate into the Gemini Live Translate WebSocket session.
 * It builds and maintains a look-ahead audio buffer ahead of current playback.
 *
 * Requirements:
 * - minBufferAheadMs = 10_000 (10s)
 * - targetBufferAheadMs = 30_000 (30s)
 * - maxSendSpeed = 1.5f
 *
 * Pacing logic:
 * - When currentBufferAheadMs < minBufferAheadMs: speedMultiplier is maxSendSpeed (1.5x)
 * - When currentBufferAheadMs >= targetBufferAheadMs: speedMultiplier is 1.0f
 * - Between minBufferAheadMs and targetBufferAheadMs: linear interpolation from maxSendSpeed down to 1.0f
 *
 * Delay calculation:
 * - delayMs = (chunkDurationMs / speedMultiplier)
 */
class Pacer(
    val minBufferAheadMs: Long = 10_000L,
    val targetBufferAheadMs: Long = 30_000L,
    val maxSendSpeed: Float = 1.5f,
    val enableBackpressure: Boolean = false
) {
    init {
        require(minBufferAheadMs >= 0) { "minBufferAheadMs must be non-negative" }
        require(targetBufferAheadMs > minBufferAheadMs) { "targetBufferAheadMs must be greater than minBufferAheadMs" }
        require(maxSendSpeed >= 1.0f) { "maxSendSpeed must be at least 1.0x" }
    }

    /**
     * Calculates the audio stream speed multiplier for the given buffer ahead amount.
     */
    fun calculateSpeedMultiplier(currentBufferAheadMs: Long): Float {
        if (currentBufferAheadMs <= minBufferAheadMs) {
            return maxSendSpeed
        }
        if (currentBufferAheadMs >= targetBufferAheadMs) {
            return 1.0f
        }

        // Linear interpolation between minBufferAheadMs and targetBufferAheadMs
        val progress = (currentBufferAheadMs - minBufferAheadMs).toFloat() / (targetBufferAheadMs - minBufferAheadMs).toFloat()
        // As progress goes 0.0 -> 1.0, speed goes maxSendSpeed -> 1.0f
        return maxSendSpeed - progress * (maxSendSpeed - 1.0f)
    }

    /**
     * Indicates whether backpressure should be active because the buffer has reached
     * or exceeded targetBufferAheadMs.
     */
    fun isBackpressureActive(currentBufferAheadMs: Long): Boolean =
        currentBufferAheadMs >= targetBufferAheadMs

    /**
     * Indicates whether sending should throttle due to buffer exceeding target threshold.
     */
    fun shouldThrottle(currentBufferAheadMs: Long): Boolean =
        isBackpressureActive(currentBufferAheadMs)

    /**
     * Indicates whether sending should pause due to buffer exceeding target threshold.
     */
    fun shouldPause(currentBufferAheadMs: Long): Boolean =
        isBackpressureActive(currentBufferAheadMs)

    /**
     * Calculates the wall-clock delay in milliseconds before sending the next audio chunk.
     * Supports drift compensation for network/transport latency and backpressure throttling
     * when [applyBackpressure] is enabled and buffer ahead meets or exceeds [targetBufferAheadMs].
     *
     * @param chunkDurationMs duration of the audio chunk in milliseconds.
     * @param currentBufferAheadMs current buffered audio ahead of playback in milliseconds.
     * @param driftMs transport/send latency drift (in ms). Positive drift reduces the delay
     *                so transmission catches up; negative drift increases delay.
     * @param applyBackpressure whether to apply backpressure throttling when buffer >= targetBufferAheadMs.
     */
    fun calculateDelayMs(
        chunkDurationMs: Long,
        currentBufferAheadMs: Long,
        driftMs: Long = 0L,
        applyBackpressure: Boolean = enableBackpressure
    ): Long {
        require(chunkDurationMs >= 0) { "chunkDurationMs must be non-negative" }
        val speed = calculateSpeedMultiplier(currentBufferAheadMs)
        val nominalDelay = (chunkDurationMs / speed).toLong()
        val driftAdjusted = (nominalDelay - driftMs).coerceAtLeast(0L)

        return if (applyBackpressure && isBackpressureActive(currentBufferAheadMs)) {
            val excessBuffer = currentBufferAheadMs - targetBufferAheadMs
            driftAdjusted + excessBuffer.coerceAtLeast(chunkDurationMs)
        } else {
            driftAdjusted
        }
    }

    /**
     * Calculates backpressure delay when buffer exceeds target threshold.
     */
    fun calculateBackpressureDelayMs(currentBufferAheadMs: Long, chunkDurationMs: Long = 100L): Long {
        if (!isBackpressureActive(currentBufferAheadMs)) return 0L
        val excess = currentBufferAheadMs - targetBufferAheadMs
        return excess.coerceAtLeast(chunkDurationMs)
    }
}
