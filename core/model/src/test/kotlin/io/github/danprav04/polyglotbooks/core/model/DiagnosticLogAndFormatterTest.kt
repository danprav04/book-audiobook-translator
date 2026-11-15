package io.github.danprav04.polyglotbooks.core.model

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DiagnosticLogAndFormatterTest {

    @Before
    fun setUp() {
        DiagnosticLogBuffer.clear()
    }

    @After
    fun tearDown() {
        DiagnosticLogBuffer.clear()
    }

    @Test
    fun bufferCapsAt200EntriesAndPreservesChronologicalTail() {
        for (i in 1..250) {
            DiagnosticLogBuffer.record(tag = "Engine", message = "event-$i")
        }
        val recent = DiagnosticLogBuffer.recentEntries(limit = 300)
        assertEquals(DiagnosticLogBuffer.MAX_ENTRIES, recent.size)
        assertEquals("event-51", recent.first().message)
        assertEquals("event-250", recent.last().message)
    }

    @Test
    fun sanitizerRedactsApiKeysBearerTokensAndLocalPaths() {
        val raw = "Failed connecting to wss://host?key=AIzaSyAbcdef1234567890XYZ with " +
            "x-goog-api-key: AIzaSyAnotherSecret999 Bearer ya29.secretTokenValue " +
            "reading content://com.android.providers/doc/12 and /storage/emulated/0/Books/secret.epub"
        val sanitized = DiagnosticLogBuffer.sanitize(raw)

        assertFalse("AIza key must be redacted: $sanitized", sanitized.contains("AIzaSy"))
        assertFalse("Query key must be redacted: $sanitized", sanitized.contains("1234567890"))
        assertFalse("Header key must be redacted: $sanitized", sanitized.contains("AnotherSecret"))
        assertFalse("Bearer token must be redacted: $sanitized", sanitized.contains("ya29.secretTokenValue"))
        assertFalse("Content URI must be redacted: $sanitized", sanitized.contains("content://"))
        assertFalse("Storage path must be redacted: $sanitized", sanitized.contains("/storage/emulated"))
        assertTrue(sanitized.contains("[REDACTED"))
    }

    @Test
    fun formatterProducesStructuredMarkdownAndRedactsAllFields() {
        DiagnosticLogBuffer.record(
            tag = "LiveSession",
            level = "ERROR",
            message = "Open failed for /data/user/0/app/cache/ch1.pcm?key=AIzaSySecretKey123456",
            throwable = IllegalStateException("HTTP 403 x-goog-api-key=AIzaSyHeaderSecret123")
        )

        val report = BugReportContextFormatter.format(
            BugReportContext(
                appVersion = "0.2.0",
                androidVersion = BugReportContextFormatter.formatAndroidApiLabel(35),
                deviceModel = "Google Pixel 8",
                screenOrArea = "Reader Narration",
                bookFormat = "epub",
                engineOrModelInfo = "engine=GEMINI_LIVE, speed=1.0",
                quotaStatus = "RPD 10/1500",
                lastError = "Failure on file:///storage/emulated/0/mybook.epub with key=AIzaSyInlineSecret999",
                userNotes = "Dropped words around chapter 3"
            )
        )

        assertTrue(report.contains("### Diagnostic Context"))
        assertTrue(report.contains("- **App Version**: 0.2.0"))
        assertTrue(report.contains("- **Android Version**: Android 15 (API 35)"))
        assertTrue(report.contains("- **Device Model**: Google Pixel 8"))
        assertTrue(report.contains("- **Screen / Area**: Reader Narration"))
        assertTrue(report.contains("- **Book / Audio Format**: EPUB"))
        assertTrue(report.contains("- **Engine / Model Configuration**: engine=GEMINI_LIVE, speed=1.0"))
        assertTrue(report.contains("- **Quota Status**: RPD 10/1500"))
        assertTrue(report.contains("- **User Notes**: Dropped words around chapter 3"))
        assertTrue(report.contains("### Recent Diagnostic Logs"))
        assertTrue(report.contains("ERROR/LiveSession:"))
        assertFalse(report.contains("AIzaSy"))
        assertFalse(report.contains("/data/user/0"))
        assertFalse(report.contains("file:///storage"))
    }
}
