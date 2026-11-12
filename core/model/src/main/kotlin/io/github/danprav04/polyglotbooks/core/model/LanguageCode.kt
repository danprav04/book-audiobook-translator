package io.github.danprav04.polyglotbooks.core.model

import kotlinx.serialization.Serializable

/**
 * Strongly-typed representation of a language with BCP-47 code and Live Translate support status.
 */
@Serializable
data class LanguageCode(
    val code: String,
    val displayName: String,
    val isLiveTranslateSupported: Boolean = false
) {
    companion object {
        val AFRIKAANS = LanguageCode("af", "Afrikaans", true)
        val AKAN = LanguageCode("ak", "Akan", true)
        val ALBANIAN = LanguageCode("sq", "Albanian", true)
        val AMHARIC = LanguageCode("am", "Amharic", true)
        val ARABIC = LanguageCode("ar", "Arabic", true)
        val ARMENIAN = LanguageCode("hy", "Armenian", true)
        val AZERBAIJANI = LanguageCode("az", "Azerbaijani", true)
        val BASQUE = LanguageCode("eu", "Basque", true)
        val BELARUSIAN = LanguageCode("be", "Belarusian", true)
        val BENGALI = LanguageCode("bn", "Bengali", true)
        val BULGARIAN = LanguageCode("bg", "Bulgarian", true)
        val BURMESE = LanguageCode("my", "Burmese (Myanmar)", true)
        val CATALAN = LanguageCode("ca", "Catalan", true)
        val CHINESE_SIMPLIFIED = LanguageCode("zh-Hans", "Chinese (Simplified)", true)
        val CHINESE_TRADITIONAL = LanguageCode("zh-Hant", "Chinese (Traditional)", true)
        val CROATIAN = LanguageCode("hr", "Croatian", true)
        val CZECH = LanguageCode("cs", "Czech", true)
        val DANISH = LanguageCode("da", "Danish", true)
        val DUTCH = LanguageCode("nl", "Dutch", true)
        val ENGLISH = LanguageCode("en", "English", true)
        val ESTONIAN = LanguageCode("et", "Estonian", true)
        val FILIPINO = LanguageCode("fil", "Filipino", true)
        val FINNISH = LanguageCode("fi", "Finnish", true)
        val FRENCH = LanguageCode("fr", "French", true)
        val GALICIAN = LanguageCode("gl", "Galician", true)
        val GEORGIAN = LanguageCode("ka", "Georgian", true)
        val GERMAN = LanguageCode("de", "German", true)
        val GREEK = LanguageCode("el", "Greek", true)
        val GUJARATI = LanguageCode("gu", "Gujarati", true)
        val HAUSA = LanguageCode("ha", "Hausa", true)
        val HEBREW = LanguageCode("he", "Hebrew", true)
        val HINDI = LanguageCode("hi", "Hindi", true)
        val HUNGARIAN = LanguageCode("hu", "Hungarian", true)
        val ICELANDIC = LanguageCode("is", "Icelandic", true)
        val INDONESIAN = LanguageCode("id", "Indonesian", true)
        val ITALIAN = LanguageCode("it", "Italian", true)
        val JAPANESE = LanguageCode("ja", "Japanese", true)
        val JAVANESE = LanguageCode("jv", "Javanese", true)
        val KANNADA = LanguageCode("kn", "Kannada", true)
        val KAZAKH = LanguageCode("kk", "Kazakh", true)
        val KHMER = LanguageCode("km", "Khmer", true)
        val KINYARWANDA = LanguageCode("rw", "Kinyarwanda", true)
        val KOREAN = LanguageCode("ko", "Korean", true)
        val LAO = LanguageCode("lo", "Lao", true)
        val LATVIAN = LanguageCode("lv", "Latvian", true)
        val LITHUANIAN = LanguageCode("lt", "Lithuanian", true)
        val MACEDONIAN = LanguageCode("mk", "Macedonian", true)
        val MALAY = LanguageCode("ms", "Malay", true)
        val MALAYALAM = LanguageCode("ml", "Malayalam", true)
        val MARATHI = LanguageCode("mr", "Marathi", true)
        val MONGOLIAN = LanguageCode("mn", "Mongolian", true)
        val NEPALI = LanguageCode("ne", "Nepali", true)
        val NORWEGIAN = LanguageCode("no", "Norwegian", true)
        val NORWEGIAN_BOKMAL = LanguageCode("nb", "Norwegian (Bokmål)", true)
        val PERSIAN = LanguageCode("fa", "Persian", true)
        val POLISH = LanguageCode("pl", "Polish", true)
        val PORTUGUESE_BRAZIL = LanguageCode("pt-BR", "Portuguese (Brazil)", true)
        val PORTUGUESE_PORTUGAL = LanguageCode("pt-PT", "Portuguese (Portugal)", true)
        val PUNJABI = LanguageCode("pa", "Punjabi", true)
        val ROMANIAN = LanguageCode("ro", "Romanian", true)
        val RUSSIAN = LanguageCode("ru", "Russian", true)
        val SERBIAN = LanguageCode("sr", "Serbian", true)
        val SINDHI = LanguageCode("sd", "Sindhi", true)
        val SINHALA = LanguageCode("si", "Sinhala", true)
        val SLOVAK = LanguageCode("sk", "Slovak", true)
        val SLOVENIAN = LanguageCode("sl", "Slovenian", true)
        val SPANISH = LanguageCode("es", "Spanish", true)
        val SUNDANESE = LanguageCode("su", "Sundanese", true)
        val SWAHILI = LanguageCode("sw", "Swahili", true)
        val SWEDISH = LanguageCode("sv", "Swedish", true)
        val TAMIL = LanguageCode("ta", "Tamil", true)
        val TELUGU = LanguageCode("te", "Telugu", true)
        val THAI = LanguageCode("th", "Thai", true)
        val TURKISH = LanguageCode("tr", "Turkish", true)
        val UKRAINIAN = LanguageCode("uk", "Ukrainian", true)
        val URDU = LanguageCode("ur", "Urdu", true)
        val UZBEK = LanguageCode("uz", "Uzbek", true)
        val VIETNAMESE = LanguageCode("vi", "Vietnamese", true)
        val ZULU = LanguageCode("zu", "Zulu", true)

        /**
         * List of all 70+ officially supported Gemini Live Translate languages.
         */
        val SUPPORTED_LIVE_LANGUAGES: List<LanguageCode> = listOf(
            AFRIKAANS, AKAN, ALBANIAN, AMHARIC, ARABIC, ARMENIAN, AZERBAIJANI, BASQUE,
            BELARUSIAN, BENGALI, BULGARIAN, BURMESE, CATALAN, CHINESE_SIMPLIFIED,
            CHINESE_TRADITIONAL, CROATIAN, CZECH, DANISH, DUTCH, ENGLISH, ESTONIAN,
            FILIPINO, FINNISH, FRENCH, GALICIAN, GEORGIAN, GERMAN, GREEK, GUJARATI,
            HAUSA, HEBREW, HINDI, HUNGARIAN, ICELANDIC, INDONESIAN, ITALIAN, JAPANESE,
            JAVANESE, KANNADA, KAZAKH, KHMER, KINYARWANDA, KOREAN, LAO, LATVIAN,
            LITHUANIAN, MACEDONIAN, MALAY, MALAYALAM, MARATHI, MONGOLIAN, NEPALI,
            NORWEGIAN, NORWEGIAN_BOKMAL, PERSIAN, POLISH, PORTUGUESE_BRAZIL,
            PORTUGUESE_PORTUGAL, PUNJABI, ROMANIAN, RUSSIAN, SERBIAN, SINDHI, SINHALA,
            SLOVAK, SLOVENIAN, SPANISH, SUNDANESE, SWAHILI, SWEDISH, TAMIL, TELUGU,
            THAI, TURKISH, UKRAINIAN, URDU, UZBEK, VIETNAMESE, ZULU
        )

        private val BY_CODE: Map<String, LanguageCode> = buildMap {
            for (lang in SUPPORTED_LIVE_LANGUAGES) {
                put(lang.code.lowercase(), lang)
            }
        }

        /**
         * Lookup a LanguageCode by BCP-47 code or fallback to custom code.
         */
        fun fromCode(code: String): LanguageCode {
            val normalized = code.trim().lowercase()
            return BY_CODE[normalized] ?: LanguageCode(code = code.trim(), displayName = code.trim(), isLiveTranslateSupported = false)
        }
    }
}
