package io.github.danprav04.polyglotbooks.feature.narration

import io.github.danprav04.polyglotbooks.core.model.Character
import io.github.danprav04.polyglotbooks.core.model.NarrationLine
import io.github.danprav04.polyglotbooks.core.model.PcmChunk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NarrationAndTranslationTest {

    @Test
    fun `VoiceCaster assigns distinct voices from 30 studio catalog and supports custom voices`() = runTest {
        assertEquals(30, GeminiStudioVoices.catalog.size)
        val caster = VoiceCaster()
        val characters = listOf(
            Character(id = "narrator", bookId = "b1", name = "Narrator", gender = "male", age = "mature", voiceTraits = "deep", assignedVoiceId = null),
            Character(id = "c1", bookId = "b1", name = "Elizabeth", gender = "female", age = "young", voiceTraits = "bright", assignedVoiceId = null),
            Character(id = "c2", bookId = "b1", name = "Darcy", gender = "male", age = "adult", voiceTraits = "steady", assignedVoiceId = null)
        )

        val map = caster.autoAssignVoices(characters)
        assertEquals("Charon", map["narrator"])
        assertNotEquals(map["narrator"], map["c1"])
        assertNotEquals(map["c1"], map["c2"])

        val custom = caster.assignCustomDesignedVoice("c2", "Custom Darcy", "male", "adult", listOf("aristocratic"))
        assertEquals(custom.id, caster.getVoice("c2"))
    }

    @Test
    fun `GeminiLiveEngine falls back to TTS engine when transcript similarity drops below 0_75`() = runTest {
        val fallbackMarker = ByteArray(12) { 42 }
        val fakeFallback = object : NarrationEngine {
            override val id = EngineId.GEMINI_TTS
            override val capabilities = EngineCapabilities(true, true, true, 24000)
            override suspend fun voices(): List<VoiceInfo> = emptyList()
            override fun synthesize(request: SynthesisRequest): Flow<PcmChunk> =
                flowOf(PcmChunk(fallbackMarker, 24000, 1))
        }

        val paraphrasingEngine = GeminiLiveEngine(
            fallbackEngine = fakeFallback,
            transcriptProvider = { "Something completely different and unrelated" }
        )

        val chunk = paraphrasingEngine.synthesize(
            SynthesisRequest(text = "It is a truth universally acknowledged", voiceId = "Kore")
        ).first()
        assertEquals(12, chunk.data.size)
        assertEquals(42.toByte(), chunk.data[0])
    }

    @Test
    fun `ChapterTranslator returns bilingual paragraph mapping and preserves lines`() = runTest {
        val translator = ChapterTranslator()
        val paragraphs = mapOf("p1" to "Hello world", "p2" to "Good morning")
        val lines = listOf(
            NarrationLine(
                id = "l1",
                chapterId = "ch1",
                paraIndex = 0,
                startOffset = 0,
                endOffset = 11,
                speakerId = "narrator",
                style = "warm"
            )
        )

        val result = translator.translateChapter(paragraphs, lines, "es")
        assertEquals(2, result.bilingualParagraphs.size)
        assertTrue(result.bilingualParagraphs["p1"]!!.contains("Hello world"))
        assertEquals(1, result.translatedLines.size)
        assertEquals("narrator", result.translatedLines[0].speakerId)
    }
}
