package io.github.danprav04.polyglotbooks.feature.narration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CastingServiceStressTest {

    private val castingService = CastingService()

    @Test
    fun testOverlappingSpansStrictResolution() {
        val rawJson = """
        {
          "characters": [
            { "id": "c1", "name": "Alice", "gender": "female", "age": "20", "voiceTraits": "clear" },
            { "id": "c2", "name": "Bob", "gender": "male", "age": "25", "voiceTraits": "deep" }
          ],
          "lines": [
            { "para": 0, "start": 5, "end": 25, "speaker": "c1" },
            { "para": 0, "start": 10, "end": 20, "speaker": "c2" },
            { "para": 0, "start": 15, "end": 35, "speaker": "c2" }
          ]
        }
        """.trimIndent()

        val text = "0123456789".repeat(5) // length 50
        val result = castingService.validateAndNormalize(rawJson, listOf(text))

        // Total reconstructed length must equal paragraph length exactly (no text dropped)
        val lines = result.lines
        var currentOffset = 0
        for (line in lines) {
            assertEquals("Lines must be contiguous", currentOffset, line.startOffset)
            assertTrue("start < end", line.startOffset < line.endOffset)
            assertTrue("end <= paragraph length", line.endOffset <= text.length)
            currentOffset = line.endOffset
        }
        assertEquals("Total coverage must reach paragraph end", text.length, currentOffset)

        // Verify span contents:
        // [0, 5) -> narrator
        // [5, 25) -> c1 (first span)
        // [10, 20) -> eclipsed by [5, 25) (c1), dropped
        // [15, 35) -> clipped to [25, 35) (c2)
        // [35, 50) -> narrator
        assertEquals(4, lines.size)
        assertEquals(0, lines[0].startOffset)
        assertEquals(5, lines[0].endOffset)
        assertEquals("narrator", lines[0].speakerId)

        assertEquals(5, lines[1].startOffset)
        assertEquals(25, lines[1].endOffset)
        assertEquals("c1", lines[1].speakerId)

        assertEquals(25, lines[2].startOffset)
        assertEquals(35, lines[2].endOffset)
        assertEquals("c2", lines[2].speakerId)

        assertEquals(35, lines[3].startOffset)
        assertEquals(50, lines[3].endOffset)
        assertEquals("narrator", lines[3].speakerId)
    }

    @Test
    fun testOutOfBoundsStartEndOffsets() {
        val rawJson = """
        {
          "characters": [
            { "id": "c1", "name": "Alice", "gender": "female", "age": "20", "voiceTraits": "clear" }
          ],
          "lines": [
            { "para": 0, "start": -10, "end": 10, "speaker": "c1" },
            { "para": 0, "start": 5, "end": 100, "speaker": "c1" },
            { "para": 0, "start": 100, "end": 200, "speaker": "c1" }
          ]
        }
        """.trimIndent()

        val text = "Short text." // length 11
        val result = castingService.validateAndNormalize(rawJson, listOf(text))

        // None of the invalid out-of-bounds spans should crash or produce out-of-bounds lines
        // Text is length 11.
        val lines = result.lines
        for (line in lines) {
            assertTrue(line.startOffset >= 0)
            assertTrue(line.endOffset <= text.length)
            assertTrue(line.startOffset < line.endOffset)
        }

        // Entire paragraph should be covered safely without dropping text
        val totalLengthCovered = lines.sumOf { it.endOffset - it.startOffset }
        assertEquals(text.length, totalLengthCovered)
    }

    @Test
    fun testInvertedSpansStartGreaterThanEnd() {
        val rawJson = """
        {
          "characters": [
            { "id": "c1", "name": "Alice", "gender": "female", "age": "20", "voiceTraits": "clear" }
          ],
          "lines": [
            { "para": 0, "start": 20, "end": 10, "speaker": "c1" },
            { "para": 0, "start": 15, "end": 15, "speaker": "c1" }
          ]
        }
        """.trimIndent()

        val text = "A paragraph with twenty five characters here."
        val result = castingService.validateAndNormalize(rawJson, listOf(text))

        // Inverted or zero-width spans must be filtered out; paragraph falls back to narrator for full span
        val lines = result.lines
        assertEquals(1, lines.size)
        assertEquals(0, lines[0].startOffset)
        assertEquals(text.length, lines[0].endOffset)
        assertEquals("narrator", lines[0].speakerId)
    }

    @Test
    fun testEmptyParagraphs() {
        val rawJson = """
        {
          "characters": [
            { "id": "c1", "name": "Alice", "gender": "female", "age": "20", "voiceTraits": "clear" }
          ],
          "lines": [
            { "para": 0, "start": 0, "end": 0, "speaker": "c1" },
            { "para": 1, "start": 0, "end": 5, "speaker": "c1" }
          ]
        }
        """.trimIndent()

        // Three paragraphs: first empty, second non-empty, third empty
        val paragraphs = listOf("", "Hello", "")
        val result = castingService.validateAndNormalize(rawJson, paragraphs)

        // Empty paragraphs must not produce 0-length lines
        val lines = result.lines
        assertEquals(1, lines.size)
        assertEquals(1, lines[0].paraIndex)
        assertEquals(0, lines[0].startOffset)
        assertEquals(5, lines[0].endOffset)
        assertEquals("c1", lines[0].speakerId)
    }

    @Test
    fun testUnknownCharacterIdsFallBackToNarrator() {
        val rawJson = """
        {
          "characters": [
            { "id": "known_char", "name": "Known", "gender": "female", "age": "30", "voiceTraits": "calm" }
          ],
          "lines": [
            { "para": 0, "start": 0, "end": 10, "speaker": "unknown_ghost" },
            { "para": 0, "start": 10, "end": 20, "speaker": "known_char" },
            { "para": 0, "start": 20, "end": 30, "speaker": "another_ghost" }
          ]
        }
        """.trimIndent()

        val text = "x".repeat(30)
        val result = castingService.validateAndNormalize(rawJson, listOf(text))

        val lines = result.lines
        assertEquals(3, lines.size)
        assertEquals("narrator", lines[0].speakerId)
        assertEquals("known_char", lines[1].speakerId)
        assertEquals("narrator", lines[2].speakerId)

        // No text dropped
        assertEquals(0, lines[0].startOffset)
        assertEquals(10, lines[0].endOffset)
        assertEquals(10, lines[1].startOffset)
        assertEquals(20, lines[1].endOffset)
        assertEquals(20, lines[2].startOffset)
        assertEquals(30, lines[2].endOffset)
    }
}
