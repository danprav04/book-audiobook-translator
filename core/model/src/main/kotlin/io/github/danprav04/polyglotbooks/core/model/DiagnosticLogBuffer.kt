package io.github.danprav04.polyglotbooks.core.model

import java.time.Instant

/**
 * Thread-safe bounded in-memory ring buffer storing sanitized diagnostic breadcrumbs for bug reports.
 *
 * Strictly redacts API keys, query/header secrets, bearer tokens, and filesystem/content URIs before
 * storing or exporting entries, and never stores raw book prose or user keys.
 */
object DiagnosticLogBuffer {
    const val MAX_ENTRIES: Int = 200
    private const val MAX_MESSAGE_CHARS: Int = 600

    data class Entry(
        val timestampUtc: String,
        val level: String,
        val tag: String,
        val message: String,
        val throwableSummary: String? = null
    )

    private val lock = Any()
    private val entries = ArrayDeque<Entry>(MAX_ENTRIES)

    private val googleApiKeyRegex = Regex("""AIza[0-9A-Za-z\-_]{10,}""")
    private val queryKeyRegex = Regex("""([?&](?:key|api_key|token)=)[^&\s"']+""", RegexOption.IGNORE_CASE)
    private val headerKeyRegex = Regex("""(x-goog-api-key\s*[:=]\s*)[^\s,;]+""", RegexOption.IGNORE_CASE)
    private val bearerRegex = Regex("""\b(Bearer\s+)[^\s,;]+""", RegexOption.IGNORE_CASE)
    private val uriSchemePathRegex = Regex("""(?:content|file)://[^\s"'<>]+""")
    private val localFilesystemPathRegex = Regex("""/(?:data|storage|sdcard|mnt|emulated)/[^\s"'<>]+""")

    fun sanitize(raw: String): String {
        if (raw.isEmpty()) return ""
        var result = raw
            .replace("\r\n", " ")
            .replace('\n', ' ')
            .replace('\r', ' ')
        result = googleApiKeyRegex.replace(result, "[REDACTED_API_KEY]")
        result = queryKeyRegex.replace(result) { match -> "${match.groupValues[1]}[REDACTED]" }
        result = headerKeyRegex.replace(result) { match -> "${match.groupValues[1]}[REDACTED]" }
        result = bearerRegex.replace(result) { match -> "${match.groupValues[1]}[REDACTED]" }
        result = uriSchemePathRegex.replace(result, "[REDACTED_PATH]")
        result = localFilesystemPathRegex.replace(result, "[REDACTED_PATH]")
        result = result.trim()
        return if (result.length > MAX_MESSAGE_CHARS) {
            result.take(MAX_MESSAGE_CHARS) + "..."
        } else {
            result
        }
    }

    fun record(
        tag: String,
        message: String,
        level: String = "INFO",
        throwable: Throwable? = null
    ) {
        val cleanTag = sanitize(tag).ifBlank { "App" }.take(64)
        val cleanLevel = sanitize(level).uppercase().ifBlank { "INFO" }.take(8)
        val cleanMessage = sanitize(message).ifBlank { "(empty)" }
        val cleanThrowable = throwable?.let { formatThrowableSummary(it) }
        val entry = Entry(
            timestampUtc = Instant.now().toString(),
            level = cleanLevel,
            tag = cleanTag,
            message = cleanMessage,
            throwableSummary = cleanThrowable
        )
        synchronized(lock) {
            if (entries.size >= MAX_ENTRIES) {
                entries.removeFirst()
            }
            entries.addLast(entry)
        }
    }

    fun recentEntries(limit: Int = 60): List<Entry> {
        if (limit <= 0) return emptyList()
        return synchronized(lock) {
            entries.takeLast(limit)
        }
    }

    fun dumpText(limit: Int = 60): String {
        val snapshot = recentEntries(limit)
        if (snapshot.isEmpty()) return "(no diagnostic logs captured yet)"
        return snapshot.joinToString("\n") { entry ->
            val errPart = entry.throwableSummary?.let { " | $it" }.orEmpty()
            "${entry.timestampUtc} ${entry.level}/${entry.tag}: ${entry.message}$errPart"
        }
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
        }
    }

    private fun formatThrowableSummary(throwable: Throwable): String {
        val topName = throwable::class.java.simpleName.ifBlank { "Throwable" }
        val topMsg = throwable.message?.let { sanitize(it) }?.takeIf { it.isNotBlank() }
        val top = if (topMsg != null) "$topName: $topMsg" else topName
        val cause = throwable.cause
        if (cause == null || cause === throwable) return top
        val causeName = cause::class.java.simpleName.ifBlank { "Throwable" }
        val causeMsg = cause.message?.let { sanitize(it) }?.takeIf { it.isNotBlank() }
        val causeText = if (causeMsg != null) "$causeName: $causeMsg" else causeName
        return "$top <- $causeText"
    }
}
