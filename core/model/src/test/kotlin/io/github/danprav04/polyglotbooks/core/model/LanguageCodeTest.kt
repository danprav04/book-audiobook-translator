package io.github.danprav04.polyglotbooks.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageCodeTest {

    @Test
    fun testLiveTranslateSupportedLanguagesCount() {
        // Supported languages list should have 70+ languages
        assertTrue(
            "Expected at least 70 supported languages, found ${LanguageCode.SUPPORTED_LIVE_LANGUAGES.size}",
            LanguageCode.SUPPORTED_LIVE_LANGUAGES.size >= 70
        )
    }

    @Test
    fun testLookupStandardLanguages() {
        val english = LanguageCode.fromCode("en")
        assertEquals("English", english.displayName)
        assertTrue(english.isLiveTranslateSupported)

        val hebrew = LanguageCode.fromCode("he")
        assertEquals("Hebrew", hebrew.displayName)
        assertTrue(hebrew.isLiveTranslateSupported)

        val polish = LanguageCode.fromCode("pl")
        assertEquals("Polish", polish.displayName)
        assertTrue(polish.isLiveTranslateSupported)

        val spanish = LanguageCode.fromCode("es")
        assertEquals("Spanish", spanish.displayName)
        assertTrue(spanish.isLiveTranslateSupported)

        val ptBR = LanguageCode.fromCode("pt-BR")
        assertEquals("Portuguese (Brazil)", ptBR.displayName)
        assertTrue(ptBR.isLiveTranslateSupported)
    }

    @Test
    fun testLookupCaseInsensitive() {
        val hebrew = LanguageCode.fromCode("HE")
        assertEquals("he", hebrew.code)
        assertEquals("Hebrew", hebrew.displayName)
        assertTrue(hebrew.isLiveTranslateSupported)

        val zhHans = LanguageCode.fromCode("ZH-HANS")
        assertEquals("zh-Hans", zhHans.code)
        assertEquals("Chinese (Simplified)", zhHans.displayName)
        assertTrue(zhHans.isLiveTranslateSupported)
    }

    @Test
    fun testUnknownLanguageFallback() {
        val unknown = LanguageCode.fromCode("xyz")
        assertEquals("xyz", unknown.code)
        assertEquals("xyz", unknown.displayName)
        assertFalse(unknown.isLiveTranslateSupported)
    }
}
