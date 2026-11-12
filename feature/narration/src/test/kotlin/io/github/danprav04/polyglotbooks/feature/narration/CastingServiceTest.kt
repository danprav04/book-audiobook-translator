package io.github.danprav04.polyglotbooks.feature.narration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CastingServiceTest {

    private val castingService = CastingService()

    @Test
    fun testPromptJsonSchemaStructure() {
        val schema = castingService.getPromptJsonSchema()
        assertTrue(schema.contains("characters"))
        assertTrue(schema.contains("lines"))
        assertTrue(schema.contains("voiceTraits"))
    }

    @Test
    fun testValidParsingWithExplorerSampleFixture() {
        val fixtureFile = File("/home/antigravity/danprav04/book-audiobook-translator/.agents/teamwork/explorer_survey_3/fixtures/casting_response_sample.json")
        val jsonContent = if (fixtureFile.exists()) {
            fixtureFile.readText()
        } else {
            """
            {
              "characters": [
                { "id": "c1", "name": "Elizabeth Bennet", "gender": "female", "age": "young adult", "voiceTraits": "bright" },
                { "id": "c2", "name": "Mr. Darcy", "gender": "male", "age": "adult", "voiceTraits": "deep" }
              ],
              "lines": [
                { "para": 0, "start": 0, "end": 48, "speaker": "narrator", "style": "calm" },
                { "para": 0, "start": 49, "end": 97, "speaker": "c1", "style": "teasing" },
                { "para": 1, "start": 0, "end": 64, "speaker": "c2", "style": "stiff" },
                { "para": 1, "start": 65, "end": 120, "speaker": "narrator", "style": "observant" }
              ]
            }
            """.trimIndent()
        }

        // Paragraph 0 length = 97, Paragraph 1 length = 120
        val paragraph0 = "a".repeat(97)
        val paragraph1 = "b".repeat(120)

        val result = castingService.validateAndNormalize(
            rawJson = jsonContent,
            paragraphTexts = listOf(paragraph0, paragraph1),
            bookId = "book1",
            chapterId = "chap1"
        )

        assertEquals(2, result.characters.size)
        assertEquals("Elizabeth Bennet", result.characters[0].name)
        assertEquals("Mr. Darcy", result.characters[1].name)

        // Notice that between 48 and 49 there is a 1-character gap in paragraph 0!
        // CastingService must fill that gap with "narrator"!
        val para0Lines = result.lines.filter { it.paraIndex == 0 }
        assertTrue("Expected gap filling between index 48 and 49", para0Lines.size >= 3)
        assertEquals(0, para0Lines[0].startOffset)
        assertEquals(48, para0Lines[0].endOffset)
        assertEquals("narrator", para0Lines[0].speakerId)

        assertEquals(48, para0Lines[1].startOffset)
        assertEquals(49, para0Lines[1].endOffset)
        assertEquals("narrator", para0Lines[1].speakerId)

        assertEquals(49, para0Lines[2].startOffset)
        assertEquals(97, para0Lines[2].endOffset)
        assertEquals("c1", para0Lines[2].speakerId)
    }

    @Test
    fun testOverlapResolution() {
        val rawJson = """
        {
          "characters": [
            { "id": "c1", "name": "Character 1", "gender": "male", "age": "adult", "voiceTraits": "deep" }
          ],
          "lines": [
            { "para": 0, "start": 10, "end": 30, "speaker": "c1" },
            { "para": 0, "start": 20, "end": 40, "speaker": "c1" }
          ]
        }
        """.trimIndent()

        val paragraph = "x".repeat(50)
        val result = castingService.validateAndNormalize(rawJson, listOf(paragraph))

        // Invariants:
        // Gap [0, 10) filled with narrator
        // Span [10, 30) for c1
        // Overlapping span [20, 40) is clipped to [30, 40)
        // Gap [40, 50) filled with narrator
        val lines = result.lines
        assertEquals(4, lines.size)

        assertEquals(0, lines[0].startOffset)
        assertEquals(10, lines[0].endOffset)
        assertEquals("narrator", lines[0].speakerId)

        assertEquals(10, lines[1].startOffset)
        assertEquals(30, lines[1].endOffset)
        assertEquals("c1", lines[1].speakerId)

        assertEquals(30, lines[2].startOffset)
        assertEquals(40, lines[2].endOffset)
        assertEquals("c1", lines[2].speakerId)

        assertEquals(40, lines[3].startOffset)
        assertEquals(50, lines[3].endOffset)
        assertEquals("narrator", lines[3].speakerId)
    }

    @Test
    fun testOutOfBoundsAndUnknownSpeakerFallback() {
        val rawJson = """
        {
          "characters": [],
          "lines": [
            { "para": 0, "start": -5, "end": 20, "speaker": "unknown_ghost" },
            { "para": 0, "start": 10, "end": 200, "speaker": "unknown_ghost" },
            { "para": 0, "start": 5, "end": 15, "speaker": "unknown_ghost" }
          ]
        }
        """.trimIndent()

        val paragraph = "y".repeat(30)
        val result = castingService.validateAndNormalize(rawJson, listOf(paragraph))

        // -5 start is dropped or clipped
        // 200 end exceeds 30, dropped
        // Valid span is [5, 15) with unknown speaker falling back to narrator
        for (line in result.lines) {
            assertTrue(line.startOffset >= 0)
            assertTrue(line.endOffset <= 30)
            assertEquals("narrator", line.speakerId)
        }
    }

    @Test
    fun testMalformedJsonFallback() {
        val malformed = "{ this is definitely not valid json }"
        val paragraph = "Hello world paragraph."
        val result = castingService.validateAndNormalize(malformed, listOf(paragraph))

        assertEquals(1, result.lines.size)
        assertEquals(0, result.lines[0].startOffset)
        assertEquals(paragraph.length, result.lines[0].endOffset)
        assertEquals("narrator", result.lines[0].speakerId)
    }
}
