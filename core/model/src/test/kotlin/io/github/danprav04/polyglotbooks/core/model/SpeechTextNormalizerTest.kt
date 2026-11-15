package io.github.danprav04.polyglotbooks.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SpeechTextNormalizerTest {

    @Test
    fun convertsSingleQuotedPhraseInBracketsToDoubleQuotes() {
        val input = "[The constellation 'Demon-like Judge of Fire' is disappointed in you.]"
        val expected = "[The constellation \u201CDemon-like Judge of Fire\u201D is disappointed in you.]"
        assertEquals(expected, SpeechTextNormalizer.normalizeForSpeech(input))
    }

    @Test
    fun convertsCurlySingleQuotesAndPreservesMidWordApostrophes() {
        val curlyInput = "[The constellation \u2018Demon-like Judge of Fire\u2019 is disappointed in you.]"
        val expectedCurly = "[The constellation \u201CDemon-like Judge of Fire\u201D is disappointed in you.]"
        assertEquals(expectedCurly, SpeechTextNormalizer.normalizeForSpeech(curlyInput))

        val contractions = "Kim Namwoon's plan wasn't working, and he'll admit it\u2019s true."
        assertEquals(
            "Kim Namwoon\u2019s plan wasn\u2019t working, and he\u2019ll admit it\u2019s true.",
            SpeechTextNormalizer.normalizeForSpeech(contractions)
        )
    }

    @Test
    fun normalizesLookalikeApostrophesToTypographic() {
        val input = "Kim Namwoon\u02BCs skill \u2032Activate\uFF07 failed"
        val expected = "Kim Namwoon\u2019s skill \u201CActivate\u201D failed"
        assertEquals(expected, SpeechTextNormalizer.normalizeForSpeech(input))
    }

    @Test
    fun distinguishesPluralPossessivesFromAdjacentQuotedWords() {
        assertEquals(
            "\u201CConstellations\u2019 Pride\u201D",
            SpeechTextNormalizer.normalizeForSpeech("'Constellations' Pride'")
        )
        assertEquals(
            "\u201CYes\u201D and \u201CNo\u201D",
            SpeechTextNormalizer.normalizeForSpeech("'Yes' and 'No'")
        )
        assertEquals(
            "The boys\u2019 uniforms were torn.",
            SpeechTextNormalizer.normalizeForSpeech("The boys' uniforms were torn.")
        )
    }

    @Test
    fun preservesLeadingElisionsAndDecadeAbbreviations() {
        assertEquals(
            "\u201CWait \u2019til I\u2019m home\u201D",
            SpeechTextNormalizer.normalizeForSpeech("'Wait 'til I'm home'")
        )
        assertEquals("Go get \u2019em", SpeechTextNormalizer.normalizeForSpeech("Go get 'em"))
        assertEquals("\u201990s", SpeechTextNormalizer.normalizeForSpeech("'90s"))
        assertEquals("\u2019twas a dark night", SpeechTextNormalizer.normalizeForSpeech("'twas a dark night"))
        assertEquals(
            "\u201CRock \u2019n\u2019 roll\u201D",
            SpeechTextNormalizer.normalizeForSpeech("'Rock 'n' roll'")
        )
    }

    @Test
    fun normalizesCommonApostrophesWithZeroAsciiApostrophesRemaining() {
        val samples = listOf(
            "I don't know if it's O'Brien's or the dogs' toy.",
            "I'm sure I\u2019ll tell O\u2019Brien he\u2019s right.",
            "[Skill]'s cooldown isn't ready."
        )
        val expected = listOf(
            "I don\u2019t know if it\u2019s O\u2019Brien\u2019s or the dogs\u2019 toy.",
            "I\u2019m sure I\u2019ll tell O\u2019Brien he\u2019s right.",
            "[Skill]\u2019s cooldown isn\u2019t ready."
        )
        samples.zip(expected).forEach { (input, exp) ->
            val actual = SpeechTextNormalizer.normalizeForSpeech(input)
            assertEquals(exp, actual)
            assertFalse("Raw ASCII apostrophe leaked in: $actual", actual.contains('\''))
        }
    }

    @Test
    fun convertsQuotedTitlesWithPossessiveAndNestedSingleQuotes() {
        assertEquals(
            "Kim Dokja read \u201CWays of Survival\u201D\u2019s prologue.",
            SpeechTextNormalizer.normalizeForSpeech("Kim Dokja read 'Ways of Survival's prologue.")
        )
        assertEquals(
            "[The constellation \u201CDemon-like Judge of Fire\u201D\u2019s gaze lingered.]",
            SpeechTextNormalizer.normalizeForSpeech("[The constellation 'Demon-like Judge of Fire's gaze lingered.]")
        )
        assertEquals(
            "\u201CHe said \u2018hello\u2019 earlier\u201D",
            SpeechTextNormalizer.normalizeForSpeech("'He said 'hello' earlier'")
        )
        assertEquals(
            "\u201CHe shouted \u2018Yes\u2019 today\u201D",
            SpeechTextNormalizer.normalizeForSpeech("'He shouted 'Yes' today'")
        )
        assertEquals(
            "\u201CIt\u2019s a \u2018test\u2019 string\u201D",
            SpeechTextNormalizer.normalizeForSpeech("'It's a 'test' string'")
        )
        val unclosed = SpeechTextNormalizer.normalizeForSpeech("'Unclosed quote don't break")
        assertEquals("\u201CUnclosed quote don\u2019t break", unclosed)
        assertFalse(unclosed.contains('\''))
    }
}
