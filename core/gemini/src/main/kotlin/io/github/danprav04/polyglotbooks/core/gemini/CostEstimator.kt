package io.github.danprav04.polyglotbooks.core.gemini

import io.github.danprav04.polyglotbooks.core.model.ModelRole

/**
 * Tracks feature usage and calculates estimated dollar costs based on Google AI Studio pricing.
 */
object CostEstimator {

    // Pricing rates per million tokens / audio minute
    private const val LIVE_TRANSLATE_PRICE_PER_MINUTE = 0.0368 // ~$2.20 / hour
    private const val FLASH_TTS_PRICE_PER_MINUTE = 0.0135
    private const val FLASH_TEXT_INPUT_PRICE_PER_M_TOKENS = 0.10
    private const val FLASH_TEXT_OUTPUT_PRICE_PER_M_TOKENS = 0.40

    /**
     * Estimates cost in USD for a given duration in seconds for an audio model.
     * If isFreeTier is true, cost is $0.00.
     */
    fun estimateAudioCost(role: ModelRole, durationSeconds: Double, isFreeTier: Boolean = false): Double {
        if (isFreeTier) return 0.0
        val minutes = durationSeconds / 60.0
        return when (role) {
            ModelRole.LIVE_TRANSLATE -> minutes * LIVE_TRANSLATE_PRICE_PER_MINUTE
            ModelRole.TTS -> minutes * FLASH_TTS_PRICE_PER_MINUTE
            ModelRole.LIVE_NARRATION -> minutes * LIVE_TRANSLATE_PRICE_PER_MINUTE
            else -> 0.0
        }
    }

    /**
     * Estimates cost in USD for text analysis / translation based on token counts.
     * If isFreeTier is true, cost is $0.00.
     */
    fun estimateTextCost(inputTokens: Long, outputTokens: Long, isFreeTier: Boolean = false): Double {
        if (isFreeTier) return 0.0
        val inputCost = (inputTokens / 1_000_000.0) * FLASH_TEXT_INPUT_PRICE_PER_M_TOKENS
        val outputCost = (outputTokens / 1_000_000.0) * FLASH_TEXT_OUTPUT_PRICE_PER_M_TOKENS
        return inputCost + outputCost
    }
}
