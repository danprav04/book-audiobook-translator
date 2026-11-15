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

    @Test
    fun testBuildCastingPromptIncludesKnownCastAliasesAndReadOnlyContext() {
        val existingCast = listOf(
            io.github.danprav04.polyglotbooks.core.model.Character(
                id = "b1_elizabeth",
                bookId = "b1",
                name = "Elizabeth Bennet",
                aliases = listOf("Lizzy", "Miss Bennet"),
                gender = "female",
                age = "young adult",
                voiceTraits = "bright, sharp"
            )
        )
        val prompt = castingService.buildCastingPrompt(
            paragraphs = listOf(
                "—Impossible!— she countered sharply.",
                "«Never say never,» murmured Mr. Darcy."
            ),
            existingCharacters = existingCast,
            previousContextParagraphs = listOf("Elizabeth stood by the window waiting for his reply."),
            chapterTitle = "Chapter 3"
        )

        assertTrue(prompt.contains("Chapter title: Chapter 3"))
        assertTrue(prompt.contains("""id="b1_elizabeth""""))
        assertTrue(prompt.contains("Lizzy"))
        assertTrue(prompt.contains("Miss Bennet"))
        assertTrue(prompt.contains("[ctx-1] Elizabeth stood by the window waiting for his reply."))
        assertTrue(prompt.contains("DO NOT emit 'lines' for these"))
        assertTrue(prompt.contains("[0] —Impossible!— she countered sharply."))
        assertTrue(prompt.contains("[1] «Never say never,» murmured Mr. Darcy."))
    }

    @Test
    fun testParseAndValidateCastingReconcilesExistingCharactersByAliasAndStripsCodeFences() {
        val existingCast = listOf(
            io.github.danprav04.polyglotbooks.core.model.Character(
                id = "b1_alice",
                bookId = "b1",
                name = "Alice",
                aliases = listOf("Allie"),
                gender = "female",
                age = "unknown",
                voiceTraits = "",
                assignedVoiceId = "Kore"
            )
        )
        val p0 = "\"Wait!\" Allie whispered in the dark."
        val p1 = "\"Coming,\" Bob answered."
        val fencedJson = """
            ```json
            {
              "characters": [
                { "id": "", "name": "Narrator" },
                { "id": "c_allie", "name": "Alice L.", "aliases": ["Allie"], "age": "young", "voiceTraits": "soft, urgent" },
                { "id": "c_bob", "name": "Bob", "gender": "male", "age": "adult", "voiceTraits": "steady" }
              ],
              "lines": [
                { "para": 0, "start": 0, "end": 8, "speaker": "Allie", "style": "whispering" },
                { "para": 0, "start": 9, "end": ${p0.length}, "speaker": "narrator" },
                { "para": 1, "start": 0, "end": 10, "speaker": "c_bob", "style": "calm" }
              ]
            }
            ```
        """.trimIndent()

        val result = castingService.parseAndValidateCasting(
            jsonResponse = fencedJson,
            paragraphs = listOf(p0, p1),
            existingCharacters = existingCast,
            bookId = "b1",
            chapterId = "b1_chap_0"
        )

        val alice = result.characters.firstOrNull { it.id == "b1_alice" }
        assertNotNull(alice)
        assertEquals("Alice", alice!!.name)
        assertEquals("Kore", alice.assignedVoiceId)
        assertEquals("female", alice.gender)
        assertEquals("young", alice.age)
        assertEquals("soft, urgent", alice.voiceTraits)
        assertTrue(alice.aliases.contains("Allie"))
        assertTrue(alice.aliases.contains("Alice L."))

        val bob = result.characters.firstOrNull { it.name == "Bob" }
        assertNotNull(bob)

        val p0Spans = result.lines.filter { it.paraIndex == 0 }
        assertEquals("b1_alice", p0Spans[0].speakerId)
        assertEquals("whispering", p0Spans[0].style)
        assertEquals("narrator", p0Spans.last().speakerId)

        val p1Spans = result.lines.filter { it.paraIndex == 1 }
        assertEquals("c_bob", p1Spans[0].speakerId)
        assertEquals("narrator", p1Spans[1].speakerId)
        assertEquals(p1.length, p1Spans.last().endOffset)
    }

    @Test
    fun `buildCastingPrompt and parseAndValidateCasting carry storySummary, character personality, and tailored line styles`() {
        val existingCast = listOf(
            io.github.danprav04.polyglotbooks.core.model.Character(
                id = "b1_dokja",
                bookId = "b1",
                name = "Kim Dokja",
                aliases = listOf("Dokja"),
                gender = "male",
                age = "young adult",
                voiceTraits = "dry, quiet baritone"
            )
        )
        val prompt = castingService.buildCastingPrompt(
            paragraphs = listOf(
                "A cold message floated in midair.",
                "[You cannot activate the Attribute Window.]"
            ),
            existingCharacters = existingCast,
            previousStorySummary = "Dokja watched his subway car derail into chaos.",
            knownPersonalities = mapOf("b1_dokja" to "guarded, analytical survivor")
        )
        assertTrue(prompt.contains("Dokja watched his subway car derail into chaos."))
        assertTrue(prompt.contains("guarded, analytical survivor"))
        assertTrue(prompt.contains("storySummary"))
        assertTrue(prompt.contains("personality"))

        val response = """
            {
              "storySummary": "Inside the halted subway car, Dokja stays eerily calm as a system prompt blocks his status window.",
              "characters": [
                {
                  "id": "b1_dokja",
                  "name": "Kim Dokja",
                  "gender": "male",
                  "age": "young adult",
                  "personality": "unsurprised beneath rising dread",
                  "voiceTraits": "dry, quiet baritone"
                }
              ],
              "lines": [
                { "para": 0, "start": 0, "end": 33, "speaker": "narrator", "style": "quiet dread beneath outward calm" },
                { "para": 1, "start": 0, "end": 41, "speaker": "narrator", "style": "crisp, detached system announcement" }
              ]
            }
        """.trimIndent()

        val result = castingService.parseAndValidateCasting(
            jsonResponse = response,
            paragraphs = listOf("A cold message floated in midair.", "[You cannot activate the Attribute Window.]"),
            existingCharacters = existingCast,
            bookId = "b1",
            chapterId = "b1_chap_6",
            previousStorySummary = "Dokja watched his subway car derail into chaos.",
            knownPersonalities = mapOf("b1_dokja" to "guarded, analytical survivor")
        )

        assertTrue(result.storySummary.contains("system prompt blocks his status window"))
        val personality = result.characterPersonalities["b1_dokja"].orEmpty()
        assertTrue(personality.contains("guarded, analytical survivor"))
        assertTrue(personality.contains("unsurprised beneath rising dread"))
        assertEquals("quiet dread beneath outward calm", result.lines[0].style)
        assertEquals("crisp, detached system announcement", result.lines[1].style)
    }
}
