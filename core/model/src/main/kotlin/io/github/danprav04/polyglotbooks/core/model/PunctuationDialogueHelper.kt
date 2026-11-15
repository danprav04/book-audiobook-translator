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
        val core = stripWrappers(trimmed)
        if (core.isEmpty()) {
            return ResolvedPunctuationUtterance(
                kind = PunctuationKind.SILENT_DIVIDER,
                spokenText = rawText,
                styleCue = cleanExisting
            )
        }

        val normalizedCore = normalizePunctuation(core)

        if (isSilentDivider(core, normalizedCore, wasWrapped)) {
            return ResolvedPunctuationUtterance(
                kind = PunctuationKind.SILENT_DIVIDER,
                spokenText = rawText,
                styleCue = cleanExisting
            )
        }

        val hasQuestion = '?' in normalizedCore
        val hasExclaim = '!' in normalizedCore
        val hasEllipsis = normalizedCore.contains("..") || '…' in core || isSpacedThreeDotEllipsis(normalizedCore)
        val hasDash = normalizedCore.any { it in DASH_CHARS }

        if (!hasQuestion && !hasExclaim && !hasEllipsis && !hasDash) {
            return ResolvedPunctuationUtterance(
                kind = PunctuationKind.SILENT_DIVIDER,
                spokenText = rawText,
                styleCue = cleanExisting
            )
        }

        val (spokenText, baseCue) = when {
            hasQuestion && hasExclaim -> {
                val cue = if (hasEllipsis) {
                    "hesitant then startled, incredulous gasp"
                } else {
                    "startled, incredulous gasp"
                }
                "Huh?!" to cue
            }
            hasQuestion && hasEllipsis -> "Hmm...?" to "hesitant, puzzled questioning murmur"
            hasQuestion && hasDash -> "Huh?" to "abrupt, startled questioning inflection"
            hasQuestion -> {
                val multiQ = normalizedCore.count { it == '?' } >= 2
                if (multiQ) {
                    "Huh?" to "confused, emphatic questioning inflection"
                } else {
                    "Hmm?" to "curious, questioning inflection"
                }
            }
            hasExclaim && hasEllipsis -> "Oh...!" to "hesitant, breathy surprised gasp"
            hasExclaim && hasDash -> "Ah!" to "abrupt, startled exclamation"
            hasExclaim -> {
                val multiEx = normalizedCore.count { it == '!' } >= 2
                if (multiEx) {
                    "Oh!" to "sharp, intensely surprised exclamation"
                } else {
                    "Oh!" to "sharp, surprised exclamation"
                }
            }
            hasEllipsis -> "Mmm..." to "quiet, hesitant sigh, trailing off softly"
            else -> "Ah—" to "abrupt, breathless cut-off gasp"
        }

        val mergedStyle = when {
            cleanExisting == null -> baseCue
            cleanExisting.contains(baseCue, ignoreCase = true) -> cleanExisting
            else -> "$cleanExisting, $baseCue"
        }

        return ResolvedPunctuationUtterance(
            kind = PunctuationKind.EXPRESSIVE_DIALOGUE,
            spokenText = spokenText,
            styleCue = mergedStyle
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
