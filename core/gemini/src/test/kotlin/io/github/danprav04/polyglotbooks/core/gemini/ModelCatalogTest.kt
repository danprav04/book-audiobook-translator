package io.github.danprav04.polyglotbooks.core.gemini

import io.github.danprav04.polyglotbooks.core.model.ModelRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `matchesRole for LIVE_NARRATION excludes transcribe, genie, robotics, and extended-thinking models`() {
        val models = listOf(
            ModelItem("models/gemini-3.8-live", supportedGenerationMethods = listOf("bidiGenerateContent")),
            ModelItem("models/gemini-3.1-flash-live-preview", supportedGenerationMethods = listOf("bidiGenerateContent")),
            ModelItem("models/gemini-2.5-flash-native-audio-latest", supportedGenerationMethods = listOf("bidiGenerateContent")),
            ModelItem("models/gemini-3.5-transcribe-live", supportedGenerationMethods = listOf("bidiGenerateContent")),
            ModelItem("models/gemini-3.8-live-extended-thinking", supportedGenerationMethods = listOf("bidiGenerateContent")),
            ModelItem("models/genie-4", supportedGenerationMethods = listOf("bidiGenerateContent")),
            ModelItem("models/gemini-robotics-er-2-streaming-preview", supportedGenerationMethods = listOf("bidiGenerateContent")),
            ModelItem("models/gemini-3.5-live-translate-preview", supportedGenerationMethods = listOf("bidiGenerateContent"))
        )

        val liveNarrationIds = ModelCatalog.filterModelsForRole(models, ModelRole.LIVE_NARRATION).map { it.id }
        assertEquals(
            listOf(
                "gemini-3.8-live",
                "gemini-3.1-flash-live-preview",
                "gemini-2.5-flash-native-audio-latest"
            ),
            liveNarrationIds
        )
    }

    @Test
    fun `recommendedModelsForRole and liveAudioCandidatePool exclude removed nonexistent live models by default`() {
        val recommended = ModelCatalog.recommendedModelsForRole(ModelRole.LIVE_NARRATION)
        assertFalse(recommended.contains("gemini-2.5-flash-native-audio-dialog"))
        assertFalse(recommended.contains("gemini-3-flash-live"))
        assertTrue(recommended.contains("gemini-3.1-flash-live-preview"))
        assertTrue(recommended.contains("gemini-2.5-flash-native-audio-latest"))

        val pool = GeminiQuotaTracker().liveAudioCandidatePool("gemini-3.1-flash-live-preview")
        assertFalse(pool.contains("gemini-2.5-flash-native-audio-dialog"))
        assertFalse(pool.contains("gemini-3-flash-live"))
        assertTrue(pool.contains("gemini-3.1-flash-live-preview"))
        assertTrue(pool.contains("gemini-2.5-flash-native-audio-latest"))
        assertTrue(pool.contains("gemini-3.8-live"))
    }
}
