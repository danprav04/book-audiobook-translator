package io.github.danprav04.polyglotbooks.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class DefaultModelsTest {

    @Test
    fun testAllRolesHaveDefaultModels() {
        for (role in ModelRole.values()) {
            val modelId = DefaultModels.map[role]
            assertNotNull("Role $role should have a default model", modelId)
        }
    }

    @Test
    fun testApprovedModelMappings() {
        assertEquals("gemini-3.5-live-translate-preview", DefaultModels.get(ModelRole.LIVE_TRANSLATE))
        assertEquals("gemini-3.8-flash-tts", DefaultModels.get(ModelRole.TTS))
        assertEquals("gemini-3.8-live", DefaultModels.get(ModelRole.LIVE_NARRATION))
        assertEquals("gemini-3.8-flash", DefaultModels.get(ModelRole.TEXT_ANALYSIS))
        assertEquals("gemini-3.8-flash", DefaultModels.get(ModelRole.TEXT_TRANSLATION))
        assertEquals("gemini-3.5-transcribe-live", DefaultModels.get(ModelRole.LIVE_TRANSCRIBE))
    }
}
