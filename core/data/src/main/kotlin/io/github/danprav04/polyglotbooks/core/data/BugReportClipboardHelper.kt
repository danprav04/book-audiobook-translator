package io.github.danprav04.polyglotbooks.core.data

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import io.github.danprav04.polyglotbooks.core.model.BugReportContext
import io.github.danprav04.polyglotbooks.core.model.BugReportContextFormatter
import io.github.danprav04.polyglotbooks.core.model.DiagnosticLogBuffer

/**
 * Resolves device/app metadata, formats a redacted diagnostic report via [BugReportContextFormatter],
 * and copies it to the Android clipboard across UI feature screens.
 */
object BugReportClipboardHelper {

    data class CopyResult(
        val context: BugReportContext,
        val formattedReport: String,
        val copiedToClipboard: Boolean
    )

    fun resolveAppVersion(context: Context): String {
        return try {
            val pm = context.packageManager ?: return "unknown"
            val pkgName = context.packageName?.takeIf { it.isNotBlank() } ?: return "unknown"
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(pkgName, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkgName, 0)
            }
            val versionName = info?.versionName?.takeIf { it.isNotBlank() }
            val versionCode = if (info != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else {
                    @Suppress("DEPRECATION")
                    info.versionCode.toLong()
                }
            } else {
                0L
            }
            when {
                versionName != null && versionCode > 0L -> "$versionName ($versionCode)"
                versionName != null -> versionName
                versionCode > 0L -> versionCode.toString()
                else -> "unknown"
            }
        } catch (_: Throwable) {
            "unknown"
        }
    }

    fun resolveAndroidVersionLabel(): String {
        return try {
            BugReportContextFormatter.formatAndroidApiLabel(Build.VERSION.SDK_INT)
        } catch (_: Throwable) {
            "Android (Unknown)"
        }
    }

    fun resolveDeviceModel(): String {
        return try {
            val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
            val model = Build.MODEL?.trim().orEmpty()
            when {
                manufacturer.isBlank() && model.isBlank() -> "Android Device"
                model.isBlank() -> manufacturer
                manufacturer.isBlank() || model.startsWith(manufacturer, ignoreCase = true) -> model
                else -> "$manufacturer $model"
            }
        } catch (_: Throwable) {
            "Android Device"
        }
    }

    fun buildContext(
        context: Context,
        screenOrArea: String,
        bookFormat: String? = null,
        engineOrModelInfo: String? = null,
        quotaStatus: String? = null,
        lastError: String? = null,
        userNotes: String? = null,
        logLimit: Int = 60,
        logLinesOverride: String? = null,
        appVersionOverride: String? = null,
        androidVersionOverride: String? = null,
        deviceModelOverride: String? = null
    ): BugReportContext = BugReportContext(
        appVersion = appVersionOverride ?: resolveAppVersion(context),
        androidVersion = androidVersionOverride ?: resolveAndroidVersionLabel(),
        deviceModel = deviceModelOverride ?: resolveDeviceModel(),
        screenOrArea = screenOrArea,
        bookFormat = bookFormat,
        engineOrModelInfo = engineOrModelInfo,
        quotaStatus = quotaStatus,
        lastError = lastError,
        userNotes = userNotes,
        logLimit = logLimit,
        logLinesOverride = logLinesOverride
    )

    fun copyTextToClipboard(
        context: Context,
        label: String,
        text: String,
        clipboardManagerOverride: ClipboardManager? = null
    ): Boolean {
        return try {
            val clipboard = clipboardManagerOverride
                ?: (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                ?: return false
            clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun copyBugReport(
        context: Context,
        screenOrArea: String,
        bookFormat: String? = null,
        engineOrModelInfo: String? = null,
        quotaStatus: String? = null,
        lastError: String? = null,
        userNotes: String? = null,
        logLimit: Int = 60,
        logLinesOverride: String? = null,
        clipboardLabel: String = "Polyglot Books Diagnostic Context",
        clipboardManagerOverride: ClipboardManager? = null,
        appVersionOverride: String? = null,
        androidVersionOverride: String? = null,
        deviceModelOverride: String? = null
    ): CopyResult {
        val reportContext = buildContext(
            context = context,
            screenOrArea = screenOrArea,
            bookFormat = bookFormat,
            engineOrModelInfo = engineOrModelInfo,
            quotaStatus = quotaStatus,
            lastError = lastError,
            userNotes = userNotes,
            logLimit = logLimit,
            logLinesOverride = logLinesOverride,
            appVersionOverride = appVersionOverride,
            androidVersionOverride = androidVersionOverride,
            deviceModelOverride = deviceModelOverride
        )
        val formatted = BugReportContextFormatter.format(reportContext)
        val copied = copyTextToClipboard(
            context = context,
            label = clipboardLabel,
            text = formatted,
            clipboardManagerOverride = clipboardManagerOverride
        )
        return CopyResult(
            context = reportContext,
            formattedReport = formatted,
            copiedToClipboard = copied
        )
    }

    fun openExternalUrl(context: Context, url: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                if (context !is Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            context.startActivity(intent)
            true
        } catch (t: Throwable) {
            DiagnosticLogBuffer.record(
                tag = "BugReportClipboardHelper",
                message = "Unable to open external issue URL",
                level = "WARN",
                throwable = t
            )
            false
        }
    }
}
