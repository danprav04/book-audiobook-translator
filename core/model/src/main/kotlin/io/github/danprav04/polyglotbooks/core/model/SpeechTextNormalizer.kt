package io.github.danprav04.polyglotbooks.core.model

/**
 * Normalizes written book punctuation into clean speech-synthesis input without mutating
 * user-visible reader text.
 *
 * In particular, narration engines treat ASCII/curly paired single quotes (`'...'` / `‘...’`)
 * inconsistently when combined with hyphenated names, brackets, or contractions. Converting
 * paired quotation single quotes to curly double quotes (`“...”`) preserves genuine apostrophes
 * in contractions, possessives, decade abbreviations, and leading elisions while removing quote
 * ambiguity for speech models.
 */
object SpeechTextNormalizer {

    private val APOSTROPHE_LOOKALIKES = charArrayOf(
        '\u02BC', // Modifier letter apostrophe (ʼ)
        '\u02BB', // Modifier letter turned comma (ʻ)
        '\u02BD', // Modifier letter reversed comma (ʽ)
        '\uFF07', // Fullwidth apostrophe (＇)
        '\u2032'  // Prime (′)
    )

    private val LEADING_ELISIONS = setOf(
        "em",
        "til",
        "till",
        "tis",
        "twas",
        "twere",
        "twill",
        "twould",
        "cause",
        "bout",
        "round",
        "mid",
        "midst",
        "neath",
        "gainst",
        "hood",
        "n"
    )

    private val INVALID_AFTER_OPENER = setOf(
        '\'', '\u2018', '\u2019',
        ',', ';', ':', ']', ')', '}'
    )

    private val DECADE_REGEX = Regex("^\\d{2}s$")

    private enum class CandidateKind {
        OPENER,
        CLOSER
    }

    private data class QuoteCandidate(
        val index: Int,
        val kind: CandidateKind
    )

    /**
     * Returns [text] normalized for speech synthesis.
     */
    fun normalizeForSpeech(text: String): String {
        if (text.isEmpty()) return text

        val normalized = normalizeLookalikeApostrophes(text)
        if (!containsSingleQuoteChar(normalized)) return normalized

        val candidates = collectCandidates(normalized)
        if (candidates.size < 2) return normalized

        val replacements = pairCandidates(normalized, candidates)
        if (replacements.isEmpty()) return normalized

        val sb = StringBuilder(normalized.length)
        for (i in normalized.indices) {
            sb.append(replacements[i] ?: normalized[i])
        }
        return sb.toString()
    }

    private fun normalizeLookalikeApostrophes(text: String): String {
        var needsNormalization = false
        for (c in text) {
            if (c in APOSTROPHE_LOOKALIKES) {
                needsNormalization = true
                break
            }
        }
        if (!needsNormalization) return text

        val chars = text.toCharArray()
        for (i in chars.indices) {
            if (chars[i] in APOSTROPHE_LOOKALIKES) {
                chars[i] = '\''
            }
        }
        return String(chars)
    }

    private fun containsSingleQuoteChar(text: String): Boolean {
        for (c in text) {
            if (c == '\'' || c == '\u2018' || c == '\u2019') return true
        }
        return false
    }

    private fun collectCandidates(text: String): List<QuoteCandidate> {
        val candidates = mutableListOf<QuoteCandidate>()
        for (i in text.indices) {
            val ch = text[i]
            if (ch != '\'' && ch != '\u2018' && ch != '\u2019') continue

            val prev = if (i > 0) text[i - 1] else null
            val next = if (i + 1 < text.length) text[i + 1] else null

            // Mid-word apostrophes (`wasn't`, `Kim Namwoon's`, `I'll`) are never quote delimiters.
            if (isWordChar(prev) && isWordChar(next)) continue

            // Skip closing quote of `'n'` contraction (`rock 'n' roll`).
            if (isShortNElisionCloser(text, i)) continue

            if (isValidOpener(text, i, ch, prev, next)) {
                candidates.add(QuoteCandidate(i, CandidateKind.OPENER))
            } else if (isValidCloser(ch, prev, next)) {
                candidates.add(QuoteCandidate(i, CandidateKind.CLOSER))
            }
        }
        return candidates
    }

    private fun isShortNElisionCloser(text: String, index: Int): Boolean {
        if (index < 2) return false
        val ch = text[index]
        if (ch != '\'' && ch != '\u2019') return false
        val middle = text[index - 1]
        if (middle != 'n' && middle != 'N') return false
        val openQuote = text[index - 2]
        if (openQuote != '\'' && openQuote != '\u2018' && openQuote != '\u2019') return false
        val beforeOpen = if (index >= 3) text[index - 3] else null
        return !isWordChar(beforeOpen)
    }

    private fun isValidOpener(
        text: String,
        index: Int,
        ch: Char,
        prev: Char?,
        next: Char?
    ): Boolean {
        if (ch == '\u2019') return false
        if (next == null || next.isWhitespace() || next in INVALID_AFTER_OPENER) return false
        if (isWordChar(prev)) return false
        if (prev == '\'' || prev == '\u2019' || prev == '\u201D') return false

        val word = extractLeadingWord(text, index + 1)
        if (word.isNotEmpty()) {
            val lower = word.lowercase()
            if (lower in LEADING_ELISIONS || DECADE_REGEX.matches(lower)) {
                return false
            }
        }
        return true
    }

    private fun isValidCloser(ch: Char, prev: Char?, next: Char?): Boolean {
        if (ch == '\u2018') return false
        if (prev == null || prev.isWhitespace()) return false
        if (prev == '\'' || prev == '\u2018' || prev == '[' || prev == '(' || prev == '{' || prev == '\u201C') {
            return false
        }
        if (isWordChar(next)) return false
        return true
    }

    private fun pairCandidates(
        text: String,
        candidates: List<QuoteCandidate>
    ): Map<Int, Char> {
        val replacements = HashMap<Int, Char>(candidates.size)
        var openOpenerIndex = -1

        for (pos in candidates.indices) {
            val candidate = candidates[pos]
            when (candidate.kind) {
                CandidateKind.OPENER -> {
                    if (openOpenerIndex == -1) {
                        openOpenerIndex = candidate.index
                    }
                }
                CandidateKind.CLOSER -> {
                    if (openOpenerIndex == -1) {
                        // Unmatched plural possessive (`boys'`) or dialect elision (`doin'`).
                        continue
                    }
                    if (isPluralPossessiveInsideOuterQuote(text, candidates, pos)) {
                        continue
                    }
                    replacements[openOpenerIndex] = '\u201C'
                    replacements[candidate.index] = '\u201D'
                    openOpenerIndex = -1
                }
            }
        }

        return replacements
    }

    /**
     * Distinguishes a plural possessive inside outer single quotes (`'Constellations' Pride'`)
     * from a closing quote whose word happens to end in `'s'` (`'Yes' and 'No'`).
     *
     * When `s'` is followed by whitespace and another closer appears later on the same line
     * with no intervening opener, `s'` belongs to the inner plural possessive.
     */
    private fun isPluralPossessiveInsideOuterQuote(
        text: String,
        candidates: List<QuoteCandidate>,
        candidatePos: Int
    ): Boolean {
        val idx = candidates[candidatePos].index
        if (idx == 0) return false
        val prev = text[idx - 1]
        if (prev != 's' && prev != 'S') return false
        val next = text.getOrNull(idx + 1) ?: return false
        if (!next.isWhitespace() || next == '\n' || next == '\r') return false

        for (lookahead in (candidatePos + 1) until candidates.size) {
            val upcoming = candidates[lookahead]
            // Do not match across paragraph/line breaks.
            for (k in idx until upcoming.index) {
                if (text[k] == '\n') return false
            }
            when (upcoming.kind) {
                CandidateKind.OPENER -> return false
                CandidateKind.CLOSER -> {
                    // Only treat another inner plural possessive identically if a final closer follows it.
                    if (looksLikePluralPossessiveToken(text, upcoming.index) &&
                        lookahead + 1 < candidates.size
                    ) {
                        continue
                    }
                    return true
                }
            }
        }
        return false
    }

    private fun looksLikePluralPossessiveToken(text: String, idx: Int): Boolean {
        if (idx == 0) return false
        val prev = text[idx - 1]
        if (prev != 's' && prev != 'S') return false
        val next = text.getOrNull(idx + 1) ?: return false
        return next.isWhitespace() && next != '\n' && next != '\r'
    }

    private fun extractLeadingWord(text: String, startIndex: Int): String {
        var end = startIndex
        while (end < text.length && text[end].isLetterOrDigit()) {
            end++
        }
        return if (end > startIndex) text.substring(startIndex, end) else ""
    }

    private fun isWordChar(c: Char?): Boolean = c != null && c.isLetterOrDigit()
}
