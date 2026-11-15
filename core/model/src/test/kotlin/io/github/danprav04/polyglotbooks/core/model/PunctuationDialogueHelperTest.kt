package io.github.danprav04.polyglotbooks.core.model

import io.github.danprav04.polyglotbooks.core.model.PunctuationDialogueHelper.PunctuationKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PunctuationDialogueHelperTest {

    @Test
    fun `keeps structural scene dividers silent`() {
        val dividers = listOf(
            "---",
            "----",
            "- - -",
            "— — —",
            "* * *",
            "***",
            "===",
            "___",
            "~~~",
            "###",
            "• • •",
            "· · ·",
            "✦",
            ". . . . .",
            ",",
            "."
        )
        dividers.forEach { divider ->
            assertEquals(
                "Expected '$divider' to classify as SILENT_DIVIDER",
                PunctuationKind.SILENT_DIVIDER,
                PunctuationDialogueHelper.classify(divider)
            )
        }
    }

    @Test
    fun `resolves expressive punctuation-only dialogue into natural non-verbal cues`() {
        val ellipsis = PunctuationDialogueHelper.resolve("\"...\"")
        assertEquals(PunctuationKind.EXPRESSIVE_DIALOGUE, ellipsis.kind)
        assertEquals("Mmm...", ellipsis.spokenText)
        assertTrue(ellipsis.styleCue.orEmpty().contains("sigh", ignoreCase = true))

        val puzzledEllipsis = PunctuationDialogueHelper.resolve("\"......?\"")
        assertEquals(PunctuationKind.EXPRESSIVE_DIALOGUE, puzzledEllipsis.kind)
        assertEquals("Hmm...?", puzzledEllipsis.spokenText)
        assertTrue(puzzledEllipsis.styleCue.orEmpty().contains("puzzled", ignoreCase = true))

        val startled = PunctuationDialogueHelper.resolve("\"?!" + "\"")
        assertEquals(PunctuationKind.EXPRESSIVE_DIALOGUE, startled.kind)
        assertEquals("Huh?!", startled.spokenText)
        assertTrue(startled.styleCue.orEmpty().contains("startled", ignoreCase = true))

        val question = PunctuationDialogueHelper.resolve("\"?\"")
        assertEquals(PunctuationKind.EXPRESSIVE_DIALOGUE, question.kind)
        assertEquals("Hmm?", question.spokenText)

        val exclaim = PunctuationDialogueHelper.resolve("\"!\"")
        assertEquals(PunctuationKind.EXPRESSIVE_DIALOGUE, exclaim.kind)
        assertEquals("Oh!", exclaim.spokenText)

        val cutOffDash = PunctuationDialogueHelper.resolve("\"—\"")
        assertEquals(PunctuationKind.EXPRESSIVE_DIALOGUE, cutOffDash.kind)
        assertEquals("Ah—", cutOffDash.spokenText)
        assertTrue(cutOffDash.styleCue.orEmpty().contains("cut-off", ignoreCase = true))

        val cjkEllipsis = PunctuationDialogueHelper.resolve("「……？」")
        assertEquals(PunctuationKind.EXPRESSIVE_DIALOGUE, cjkEllipsis.kind)
        assertEquals("Hmm...?", cjkEllipsis.spokenText)
    }

    @Test
    fun `merges existing emotional style and leaves regular prose intact`() {
        val merged = PunctuationDialogueHelper.resolve("...?", existingStyle = "whispered")
        assertEquals(PunctuationKind.EXPRESSIVE_DIALOGUE, merged.kind)
        assertEquals("Hmm...?", merged.spokenText)
        assertNotNull(merged.styleCue)
        assertTrue(merged.styleCue!!.startsWith("whispered, "))

        val regular = PunctuationDialogueHelper.resolve("Hello...", existingStyle = "neutral")
        assertEquals(PunctuationKind.REGULAR_TEXT, regular.kind)
        assertEquals("Hello...", regular.spokenText)
        assertEquals(null, regular.styleCue)

        val blank = PunctuationDialogueHelper.resolve("   \n\t ")
        assertEquals(PunctuationKind.EMPTY, blank.kind)
        assertFalse(blank.spokenText.isNotEmpty())
    }
}
