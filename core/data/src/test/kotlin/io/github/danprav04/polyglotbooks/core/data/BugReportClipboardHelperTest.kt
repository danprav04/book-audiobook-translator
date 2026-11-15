package io.github.danprav04.polyglotbooks.core.data

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import io.github.danprav04.polyglotbooks.core.model.BugReportContextFormatter
import io.github.danprav04.polyglotbooks.core.model.DiagnosticLogBuffer
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BugReportClipboardHelperTest {

    @Before
    fun setUp() {
        DiagnosticLogBuffer.clear()
    }

    @After
    fun tearDown() {
        DiagnosticLogBuffer.clear()
        unmockkAll()
    }

    @Test
    fun copyBugReportFormatsRedactedContextAndCopiesClipData() {
        DiagnosticLogBuffer.record(
            tag = "GeminiLive",
            level = "ERROR",
            message = "Failure on wss://host?key=AIzaSySecret1234567890 reading /storage/emulated/0/book.epub"
        )

        val clipboardManager = mockk<ClipboardManager>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        val clipData = mockk<ClipData>(relaxed = true)
        val labelSlot = slot<CharSequence>()
        val textSlot = slot<CharSequence>()

        every { context.getSystemService(Context.CLIPBOARD_SERVICE) } returns clipboardManager
        mockkStatic(ClipData::class)
        every { ClipData.newPlainText(capture(labelSlot), capture(textSlot)) } returns clipData

        val result = BugReportClipboardHelper.copyBugReport(
            context = context,
            screenOrArea = "Reader Narration",
            bookFormat = "epub",
            engineOrModelInfo = "engine=GEMINI_LIVE, speed=1.0x",
            quotaStatus = "RPD 12/1500",
            lastError = "Connection failed with x-goog-api-key: AIzaSyInlineKey999",
            userNotes = "Drops words on long paragraph",
            appVersionOverride = "0.2.0 (2)",
            androidVersionOverride = "Android 15 (API 35)",
            deviceModelOverride = "Google Pixel 8"
        )

        assertTrue(result.copiedToClipboard)
        assertEquals("Polyglot Books Diagnostic Context", labelSlot.captured.toString())
        val copied = textSlot.captured.toString()
        assertEquals(result.formattedReport, copied)
        assertTrue(copied.contains("- **App Version**: 0.2.0 (2)"))
        assertTrue(copied.contains("- **Android Version**: Android 15 (API 35)"))
        assertTrue(copied.contains("- **Device Model**: Google Pixel 8"))
        assertTrue(copied.contains("- **Screen / Area**: Reader Narration"))
        assertTrue(copied.contains("- **Book / Audio Format**: EPUB"))
        assertTrue(copied.contains("- **Engine / Model Configuration**: engine=GEMINI_LIVE, speed=1.0x"))
        assertTrue(copied.contains("- **Quota Status**: RPD 12/1500"))
        assertTrue(copied.contains("ERROR/GeminiLive:"))
        assertFalse(copied.contains("AIzaSy"))
        assertFalse(copied.contains("/storage/emulated"))
        verify(exactly = 1) { clipboardManager.setPrimaryClip(clipData) }
    }

    @Test
    fun copyBugReportReturnsFalseWhenClipboardServiceUnavailable() {
        val context = mockk<Context>(relaxed = true)
        every { context.getSystemService(Context.CLIPBOARD_SERVICE) } returns null

        val result = BugReportClipboardHelper.copyBugReport(
            context = context,
            screenOrArea = "Settings",
            lastError = "HTTP 429",
            appVersionOverride = "0.2.0",
            androidVersionOverride = "Android 14 (API 34)",
            deviceModelOverride = "Pixel"
        )

        assertFalse(result.copiedToClipboard)
        assertTrue(result.formattedReport.contains("- **Last Error**: HTTP 429"))
    }

    @Test
    fun resolveAppVersionReadsPackageInfoSafely() {
        val context = mockk<Context>(relaxed = true)
        val pm = mockk<PackageManager>()
        val pkgInfo = PackageInfo().apply {
            versionName = "0.2.0"
            @Suppress("DEPRECATION")
            versionCode = 2
        }
        every { context.packageName } returns "io.github.danprav04.polyglotbooks"
        every { context.packageManager } returns pm
        @Suppress("DEPRECATION")
        every { pm.getPackageInfo("io.github.danprav04.polyglotbooks", any<Int>()) } returns pkgInfo
        every { pm.getPackageInfo("io.github.danprav04.polyglotbooks", any<PackageManager.PackageInfoFlags>()) } returns pkgInfo

        val resolved = BugReportClipboardHelper.resolveAppVersion(context)
        assertTrue(resolved.contains("0.2.0"))
    }

    @Test
    fun openExternalUrlHandlesActivityStartAndFailureGracefully() {
        val context = mockk<Context>(relaxed = true)
        mockkStatic(Uri::class)
        val uri = mockk<Uri>(relaxed = true)
        every { Uri.parse(BugReportContextFormatter.GITHUB_AI_REPORT_URL) } returns uri
        every { context.startActivity(any()) } throws IllegalStateException("No browser installed")

        val opened = BugReportClipboardHelper.openExternalUrl(
            context,
            BugReportContextFormatter.GITHUB_AI_REPORT_URL
        )

        assertFalse(opened)
        val logs = DiagnosticLogBuffer.dumpText()
        assertTrue(logs.contains("Unable to open external issue URL"))
        verify(exactly = 1) { context.startActivity(any<Intent>()) }
    }
}
