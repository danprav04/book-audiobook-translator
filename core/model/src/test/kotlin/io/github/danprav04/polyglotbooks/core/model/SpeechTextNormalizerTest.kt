package io.github.danprav04.polyglotbooks.core.model

import org.junit.Assert.assertEquals
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
        assertEquals(contractions, SpeechTextNormalizer.normalizeForSpeech(contractions))
    }

    @Test
    fun normalizesLookalikeApostrophesToAscii() {
        val input = "Kim Namwoon\u02BCs skill \u2032Activate\uFF07 failed"
        val expected = "Kim Namwoon's skill \u201CActivate\u201D failed"
        assertEquals(expected, SpeechTextNormalizer.normalizeForSpeech(input))
    }

    @Test
    fun distinguishesPluralPossessivesFromAdjacentQuotedWords() {
        assertEquals(
            "\u201CConstellations' Pride\u201D",
            SpeechTextNormalizer.normalizeForSpeech("'Constellations' Pride'")
        )
        assertEquals(
            "\u201CYes\u201D and \u201CNo\u201D",
            SpeechTextNormalizer.normalizeForSpeech("'Yes' and 'No'")
        )
        assertEquals(
            "The boys' uniforms were torn.",
            SpeechTextNormalizer.normalizeForSpeech("The boys' uniforms were torn.")
        )
    }

    @Test
    fun preservesLeadingElisionsAndDecadeAbbreviations() {
        assertEquals(
            "\u201CWait 'til I'm home\u201D",
            SpeechTextNormalizer.normalizeForSpeech("'Wait 'til I'm home'")
        )
        assertEquals("Go get 'em", SpeechTextNormalizer.normalizeForSpeech("Go get 'em"))
        assertEquals("'90s", SpeechTextNormalizer.normalizeForSpeech("'90s"))
        assertEquals("'twas a dark night", SpeechTextNormalizer.normalizeForSpeech("'twas a dark night"))
        assertEquals(
            "\u201CRock 'n' roll\u201D",
            SpeechTextNormalizer.normalizeForSpeech("'Rock 'n' roll'")
        )
    }
}
