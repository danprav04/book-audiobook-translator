package io.github.danprav04.polyglotbooks.core.gemini

import io.github.danprav04.polyglotbooks.core.model.ModelRole

/**
 * Calculates estimated token usage and dollar costs based on Google AI Studio pricing.
 */
object CostEstimator {

    // Pricing rates per million tokens / audio minute
    private const val LIVE_TRANSLATE_PRICE_PER_MINUTE = 0.0368 // ~$2.20 / hour
    private const val FLASH_TTS_PRICE_PER_MINUTE = 0.0135
    private const val FLASH_TEXT_INPUT_PRICE_PER_M_TOKENS = 0.10
    private const val FLASH_TEXT_OUTPUT_PRICE_PER_M_TOKENS = 0.40

    /**
     * Estimates cost in USD for a given duration in seconds for an audio model.
     */
    fun estimateAudioCost(role: ModelRole, durationSeconds: Double): Double {
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
     */
    fun estimateTextCost(inputTokens: Long, outputTokens: Long): Double {
        val inputCost = (inputTokens / 1_000_000.0) * FLASH_TEXT_INPUT_PRICE_PER_M_TOKENS
        val outputCost = (outputTokens / 1_000_000.0) * FLASH_TEXT_OUTPUT_PRICE_PER_M_TOKENS
        return inputCost + outputCost
    }
}
