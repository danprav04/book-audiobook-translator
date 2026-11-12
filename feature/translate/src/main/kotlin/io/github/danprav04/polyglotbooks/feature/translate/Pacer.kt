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
    val maxSendSpeed: Float = 1.5f
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
     * Calculates the wall-clock delay in milliseconds before sending the next audio chunk.
     * E.g. For a 100 ms chunk at 1.5x speed, delay is 100 / 1.5 = 66 ms.
     * At 1.0x speed, delay is 100 / 1.0 = 100 ms.
     */
    fun calculateDelayMs(chunkDurationMs: Long, currentBufferAheadMs: Long): Long {
        require(chunkDurationMs >= 0) { "chunkDurationMs must be non-negative" }
        val speed = calculateSpeedMultiplier(currentBufferAheadMs)
        return (chunkDurationMs / speed).toLong()
    }
}
