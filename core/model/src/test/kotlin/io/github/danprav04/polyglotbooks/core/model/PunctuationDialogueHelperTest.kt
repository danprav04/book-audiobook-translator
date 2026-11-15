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
    fun `keeps punctuation-only dialogue silent without fabricating interjections`() {
        val inputs = listOf(
            "\"...\"",
            "\"......?\"",
            "\"?!\"",
            "\"?\"",
            "\"!\"",
            "\"—\"",
            "「……？」"
        )
        inputs.forEach { raw ->
            val resolved = PunctuationDialogueHelper.resolve(raw)
            assertEquals(
                "Expected punctuation-only '$raw' to stay silent instead of inventing words",
                PunctuationKind.SILENT_DIVIDER,
                resolved.kind
            )
            assertEquals(raw, resolved.spokenText)
        }
    }

    @Test
    fun `preserves existing style on punctuation-only lines and leaves regular prose intact`() {
        val paused = PunctuationDialogueHelper.resolve("...?", existingStyle = "whispered")
        assertEquals(PunctuationKind.SILENT_DIVIDER, paused.kind)
        assertEquals("...?", paused.spokenText)
        assertEquals("whispered", paused.styleCue)

        val regular = PunctuationDialogueHelper.resolve("Hello...", existingStyle = "neutral")
        assertEquals(PunctuationKind.REGULAR_TEXT, regular.kind)
        assertEquals("Hello...", regular.spokenText)
        assertEquals(null, regular.styleCue)

        val blank = PunctuationDialogueHelper.resolve("   \n\t ")
        assertEquals(PunctuationKind.EMPTY, blank.kind)
        assertFalse(blank.spokenText.isNotEmpty())
    }
}
