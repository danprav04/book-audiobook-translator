package io.github.danprav04.polyglotbooks.core.gemini

import io.github.danprav04.polyglotbooks.core.model.ModelRole
import org.junit.Assert.assertEquals
import org.junit.Test

class CostEstimatorTest {

    @Test
    fun testPaidAudioCostEstimation() {
        // 60 seconds (1 minute) of live translate should cost $0.0368
        val cost = CostEstimator.estimateAudioCost(ModelRole.LIVE_TRANSLATE, 60.0, isFreeTier = false)
        assertEquals(0.0368, cost, 0.0001)

        // 60 seconds of TTS should cost $0.0135
        val ttsCost = CostEstimator.estimateAudioCost(ModelRole.TTS, 60.0, isFreeTier = false)
        assertEquals(0.0135, ttsCost, 0.0001)
    }

    @Test
    fun testFreeTierAudioCostZero() {
        val cost = CostEstimator.estimateAudioCost(ModelRole.LIVE_TRANSLATE, 600.0, isFreeTier = true)
        assertEquals(0.0, cost, 0.00001)
    }

    @Test
    fun testPaidTextCostEstimation() {
        // 1,000,000 input tokens ($0.10) + 1,000,000 output tokens ($0.40) = $0.50
        val cost = CostEstimator.estimateTextCost(1_000_000L, 1_000_000L, isFreeTier = false)
        assertEquals(0.50, cost, 0.0001)
    }

    @Test
    fun testFreeTierTextCostZero() {
        val cost = CostEstimator.estimateTextCost(10_000_000L, 5_000_000L, isFreeTier = true)
        assertEquals(0.0, cost, 0.00001)
    }
}
