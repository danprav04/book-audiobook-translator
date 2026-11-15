package io.github.danprav04.polyglotbooks.core.model

import java.util.Locale

/**
 * Structured bug report context payload shared across Reader, Audiobook Player, Library, and Settings.
 */
data class BugReportContext(
    val appVersion: String,
    val androidVersion: String,
    val deviceModel: String,
    val screenOrArea: String,
    val bookFormat: String? = null,
    val engineOrModelInfo: String? = null,
    val quotaStatus: String? = null,
    val lastError: String? = null,
    val userNotes: String? = null,
    val logLimit: Int = 60,
    val logLinesOverride: String? = null
)

/**
 * Formats sanitized device, engine, error, and diagnostic log context matching the GitHub bug template.
 */
object BugReportContextFormatter {
    const val GITHUB_BUG_REPORT_URL: String =
        "https://github.com/danprav04/book-audiobook-translator/issues/new?template=bug.yml"
    const val GITHUB_AI_REPORT_URL: String =
        "https://github.com/danprav04/book-audiobook-translator/issues/new?template=ai_output_report.yml"

    fun formatAndroidApiLabel(sdkInt: Int): String = when (sdkInt) {
        35 -> "Android 15 (API 35)"
        34 -> "Android 14 (API 34)"
        33 -> "Android 13 (API 33)"
        31, 32 -> "Android 12 / 12L (API 31/32)"
        30 -> "Android 11 (API 30)"
        29 -> "Android 10 (API 29)"
        28 -> "Android 9 Pie (API 28)"
        26, 27 -> "Android 8 Oreo (API 26/27)"
        else -> "Android API $sdkInt (Other)"
    }

    fun formatBookFormatLabel(rawFormat: String?): String {
        val norm = rawFormat?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return when (norm) {
            "" -> "None / Not applicable"
            "epub" -> "EPUB"
            "mobi", "azw", "azw3" -> "MOBI / AZW3"
            "pdf" -> "PDF"
            "fb2" -> "FB2"
            "m4b" -> "M4B"
            "mp3" -> "MP3"
            "flac", "ogg", "opus" -> "FLAC / OGG / OPUS"
            else -> "TXT / Other ($norm)"
        }
    }

    fun format(context: BugReportContext): String {
        val sb = StringBuilder()
        sb.appendLine("### Diagnostic Context")
        sb.appendLine("- **App Version**: ${cleanField(context.appVersion, "unknown")}")
        sb.appendLine("- **Android Version**: ${cleanField(context.androidVersion, "unknown")}")
        sb.appendLine("- **Device Model**: ${cleanField(context.deviceModel, "unknown")}")
        sb.appendLine("- **Screen / Area**: ${cleanField(context.screenOrArea, "unknown")}")
        sb.appendLine("- **Book / Audio Format**: ${formatBookFormatLabel(context.bookFormat)}")
        context.engineOrModelInfo?.takeIf { it.isNotBlank() }?.let {
            sb.appendLine("- **Engine / Model Configuration**: ${DiagnosticLogBuffer.sanitize(it)}")
        }
        context.quotaStatus?.takeIf { it.isNotBlank() }?.let {
            sb.appendLine("- **Quota Status**: ${DiagnosticLogBuffer.sanitize(it)}")
        }
        context.lastError?.takeIf { it.isNotBlank() }?.let {
            sb.appendLine("- **Last Error**: ${DiagnosticLogBuffer.sanitize(it)}")
        }
        context.userNotes?.takeIf { it.isNotBlank() }?.let {
            sb.appendLine("- **User Notes**: ${DiagnosticLogBuffer.sanitize(it)}")
        }
        sb.appendLine()
        sb.appendLine("### Recent Diagnostic Logs")
        sb.appendLine("```text")
        val rawLogs = context.logLinesOverride?.let { override ->
            override.lines()
                .filter { it.isNotBlank() }
                .joinToString("\n") { DiagnosticLogBuffer.sanitize(it) }
                .ifBlank { "(no diagnostic logs captured yet)" }
        } ?: DiagnosticLogBuffer.dumpText(context.logLimit)
        sb.appendLine(rawLogs)
        sb.append("```")
        return sb.toString()
    }

    private fun cleanField(value: String, fallback: String): String {
        val cleaned = DiagnosticLogBuffer.sanitize(value)
        return cleaned.ifBlank { fallback }
    }
}
