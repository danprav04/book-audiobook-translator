package io.github.danprav04.polyglotbooks.core.gemini

import io.github.danprav04.polyglotbooks.core.model.ModelRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStreamReader

class ModelCatalogTest {

    private fun loadFixture(filename: String): String {
        val stream = javaClass.classLoader?.getResourceAsStream("fixtures/$filename")
            ?: throw IllegalArgumentException("Fixture not found: $filename")
        return InputStreamReader(stream, Charsets.UTF_8).readText()
    }

    @Test
    fun testParseModelsListJson() {
        val rawJson = loadFixture("rest_models_list.json")
        val response = ModelCatalog.parseModelsJson(rawJson)

        assertEquals(5, response.models.size)

        val liveTranslate = response.models.find { it.name == "models/gemini-3.5-live-translate-preview" }
        assertNotNull(liveTranslate)
        assertEquals("gemini-3.5-live-translate-preview", liveTranslate?.id)
        assertEquals(131072, liveTranslate?.inputTokenLimit)
        assertEquals(65536, liveTranslate?.outputTokenLimit)
        assertEquals(listOf("bidiGenerateContent"), liveTranslate?.supportedGenerationMethods)

        val flashTts = response.models.find { it.name == "models/gemini-3.8-flash-tts" }
        assertNotNull(flashTts)
        assertEquals("gemini-3.8-flash-tts", flashTts?.id)
        assertTrue(flashTts?.supportedGenerationMethods?.contains("interactions") == true)
    }

    @Test
    fun testFilterModelsForLiveTranslateRole() {
        val rawJson = loadFixture("rest_models_list.json")
        val response = ModelCatalog.parseModelsJson(rawJson)

        val liveTranslateModels = ModelCatalog.filterModelsForRole(response.models, ModelRole.LIVE_TRANSLATE)
        assertEquals(1, liveTranslateModels.size)
        assertEquals("models/gemini-3.5-live-translate-preview", liveTranslateModels.first().name)
    }

    @Test
    fun testFilterModelsForTtsRole() {
        val rawJson = loadFixture("rest_models_list.json")
        val response = ModelCatalog.parseModelsJson(rawJson)

        val ttsModels = ModelCatalog.filterModelsForRole(response.models, ModelRole.TTS)
        assertEquals(1, ttsModels.size)
        assertEquals("models/gemini-3.8-flash-tts", ttsModels.first().name)
    }

    @Test
    fun testFilterModelsForLiveNarrationRole() {
        val rawJson = loadFixture("rest_models_list.json")
        val response = ModelCatalog.parseModelsJson(rawJson)

        val liveModels = ModelCatalog.filterModelsForRole(response.models, ModelRole.LIVE_NARRATION)
        val names = liveModels.map { it.name }
        assertTrue(names.contains("models/gemini-3.8-live"))
        assertTrue(!names.contains("models/gemini-3.5-live-translate-preview"))
    }

    @Test
    fun testFilterModelsForTextAnalysisRole() {
        val rawJson = loadFixture("rest_models_list.json")
        val response = ModelCatalog.parseModelsJson(rawJson)

        val textModels = ModelCatalog.filterModelsForRole(response.models, ModelRole.TEXT_ANALYSIS)
        val names = textModels.map { it.name }
        assertTrue(names.contains("models/gemini-3.5-flash-lite"))
    }
}
