package io.github.danprav04.polyglotbooks.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceProfileTest {

    @Test
    fun catalogContainsAll30UniqueStudioVoicesWithMetadata() {
        assertEquals(30, GeminiStudioVoices.catalog.size)
        assertEquals(30, GeminiStudioVoices.catalog.map { it.id }.distinct().size)
        for (voice in GeminiStudioVoices.catalog) {
            assertTrue(voice.id.isNotBlank())
            assertTrue(voice.name.isNotBlank())
            assertTrue(voice.gender in setOf("male", "female", "neutral"))
            assertTrue(voice.age in setOf("young", "adult", "mature"))
            assertTrue(voice.traits.isNotEmpty())
        }
    }

    @Test
    fun getVoiceResolvesCaseInsensitivelyAndDefaultIsCharon() {
        assertEquals("Charon", GeminiStudioVoices.DEFAULT_NARRATOR_VOICE_ID)
        assertNotNull(GeminiStudioVoices.getVoice("Charon"))
        assertEquals("Charon", GeminiStudioVoices.getVoice("charon")?.id)
        assertEquals("Aoede", GeminiStudioVoices.getVoice("  AOEDE ")?.id)
        assertNull(GeminiStudioVoices.getVoice("non-existent"))
        assertEquals("voice_previews/charon.m4a", GeminiStudioVoices.previewAssetPath("Charon"))
    }

    @Test
    fun correctedVoiceGendersMatchStudioVoiceCatalog() {
        assertEquals("male", GeminiStudioVoices.getVoice("Algieba")?.gender)
        assertEquals("female", GeminiStudioVoices.getVoice("Achernar")?.gender)
        assertEquals("female", GeminiStudioVoices.getVoice("Gacrux")?.gender)
        assertEquals("male", GeminiStudioVoices.getVoice("Zubenelgenubi")?.gender)

        val femaleCount = GeminiStudioVoices.catalog.count { it.gender == "female" }
        val maleCount = GeminiStudioVoices.catalog.count { it.gender == "male" }
        assertEquals(13, femaleCount)
        assertEquals(17, maleCount)
    }
}
