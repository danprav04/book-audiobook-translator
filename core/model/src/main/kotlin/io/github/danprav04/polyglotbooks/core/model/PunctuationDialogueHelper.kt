package io.github.danprav04.polyglotbooks.core.model

/**
 * Classifies and resolves punctuation-only text lines so structural scene dividers stay
 * silent while expressive punctuation-only dialogue renders as natural non-verbal vocalizations.
 */
object PunctuationDialogueHelper {

    enum class PunctuationKind {
        EMPTY,
        REGULAR_TEXT,
        SILENT_DIVIDER,
        EXPRESSIVE_DIALOGUE
    }

    data class ResolvedPunctuationUtterance(
        val kind: PunctuationKind,
        val spokenText: String,
        val styleCue: String? = null
    )

    private val WRAPPER_CHARS = setOf(
        '"', '\'', '“', '”', '‘', '’', '«', '»', '„', '‚',
        '「', '」', '『', '』', '‹', '›', '(', ')', '[', ']', '（', '）'
    )

    private val DIVIDER_GLYPHS = setOf(
        '*', '=', '_', '~', '#', '•', '·', '◦', '✦', '❦', '❧', '❖',
        '+', '|', '─', '━', '═', '─', '■', '◆', '◇', '○', '●', '★', '☆'
    )

    private val DASH_CHARS = setOf('-', '–', '—', '―')

    fun classify(rawText: String): PunctuationKind = resolve(rawText).kind

    fun resolve(rawText: String, existingStyle: String? = null): ResolvedPunctuationUtterance {
        val cleanExisting = cleanStyle(existingStyle)
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) {
            return ResolvedPunctuationUtterance(
                kind = PunctuationKind.EMPTY,
                spokenText = "",
                styleCue = cleanExisting
            )
        }
        if (trimmed.any { it.isLetterOrDigit() }) {
            return ResolvedPunctuationUtterance(
                kind = PunctuationKind.REGULAR_TEXT,
                spokenText = rawText,
                styleCue = cleanExisting
            )
        }

        val wasWrapped = trimmed.first() in WRAPPER_CHARS && trimmed.last() in WRAPPER_CHARS && trimmed.length >= 2
        // Punctuation-only lines (e.g. "...", "...?", "?!", "—", "---", "* * *") contain no
        // spoken words and must stay silent pauses rather than inventing interjections like "Hmm...".
        return ResolvedPunctuationUtterance(
            kind = PunctuationKind.SILENT_DIVIDER,
            spokenText = rawText,
            styleCue = cleanExisting
        )
    }

    private fun stripWrappers(text: String): String {
        var s = text.trim()
        while (s.isNotEmpty() && (s.first().isWhitespace() || s.first() in WRAPPER_CHARS)) {
            s = s.drop(1).trim()
        }
        while (s.isNotEmpty() && (s.last().isWhitespace() || s.last() in WRAPPER_CHARS)) {
            s = s.dropLast(1).trim()
        }
        return s
    }

    private fun normalizePunctuation(core: String): String = buildString(core.length) {
        for (ch in core) {
            when (ch) {
                '…' -> append("...")
                '？' -> append('?')
                '！' -> append('!')
                '。', '．' -> append('.')
                '―' -> append('—')
                else -> append(ch)
            }
        }
    }

    private fun isSpacedThreeDotEllipsis(normalizedCore: String): Boolean {
        val compact = normalizedCore.replace(Regex("\\s+"), "")
        return compact == "..." && normalizedCore.count { it == '.' } == 3
    }

    private fun isSilentDivider(rawCore: String, normalizedCore: String, wasWrapped: Boolean): Boolean {
        val hasQuestionOrExclaim = '?' in normalizedCore || '!' in normalizedCore
        if (hasQuestionOrExclaim) {
            return false
        }
        if (normalizedCore.any { it in DIVIDER_GLYPHS }) {
            return true
        }
        val compact = normalizedCore.replace(Regex("\\s+"), "")
        if (compact.isEmpty()) return true

        val allDashes = compact.all { it in DASH_CHARS }
        if (allDashes) {
            val hasInternalSpace = normalizedCore.any { it.isWhitespace() }
            if (hasInternalSpace && compact.length >= 2) return true
            if (compact.all { it == '-' }) {
                if (compact.length >= 3) return true
                if (!wasWrapped && compact.length == 1) return true
            }
            if (!wasWrapped && compact.length >= 3 && compact.all { it == '—' || it == '–' }) {
                return true
            }
            return false
        }

        val allDots = compact.all { it == '.' }
        if (allDots) {
            val hasInternalSpace = normalizedCore.any { it.isWhitespace() }
            if (hasInternalSpace && compact.length >= 4) return true
            if (compact.length == 1 && '…' !in rawCore) return true
            return false
        }

        // Mixed or unrecognized non-expressive punctuation (e.g. commas, slashes, "- . -")
        return false
    }

    private fun cleanStyle(raw: String?): String? {
        val cleaned = raw?.trim()?.takeIf { it.isNotEmpty() && !it.equals("neutral", ignoreCase = true) }
        return cleaned
    }
}
