package com.bookcover.diagnostic

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small local-only event log; image contents and document URIs must never be recorded. */
object DiagnosticLog {
    private const val TAG = "BookCoverDiagnostic"
    private const val MAX_BYTES = 128 * 1024
    private const val MAX_MESSAGE_CHARS = 8 * 1024
    private val documentUri = Regex("(?i)\\b(?:content|file)://\\S+")
    private val lines = ArrayDeque<String>()
    private var byteCount = 0
    private var logFile: File? = null
    private var initialized = false
    private var deviceMetadata = "Device metadata unavailable: logger has not been initialized."

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val appContext = context.applicationContext
        logFile = File(appContext.filesDir, "bookcover-diagnostic.log")
        try {
            val file = logFile!!
            if (file.exists()) {
                // Bound the read even if a previous version left a much larger file behind.
                file.inputStream().use { input ->
                    val skip = (file.length() - MAX_BYTES).coerceAtLeast(0L)
                    var remaining = skip
                    while (remaining > 0) {
                        val skipped = input.skip(remaining)
                        if (skipped == 0L) break
                        remaining -= skipped
                    }
                    val previous = input.readBytes().toString(Charsets.UTF_8)
                    val completeLines = if (skip > 0) previous.substringAfter('\n', "") else previous
                    completeLines.lineSequence().filter { it.isNotBlank() }.forEach { addLine(sanitize(it)) }
                }
            }
        } catch (_: Exception) {
            Log.w(TAG, "Previous diagnostic log could not be read; starting a new local log.")
        }

        val version = try {
            @Suppress("DEPRECATION")
            val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            "${info.versionName ?: "unknown"} (${info.longVersionCode})"
        } catch (_: Exception) {
            "unavailable"
        }
        deviceMetadata = sanitize("App version=$version") + "\n" +
            sanitize("Reported manufacturer=${Build.MANUFACTURER}; model=${Build.MODEL}; device=${Build.DEVICE}") + "\n" +
            sanitize("Android release=${Build.VERSION.RELEASE}; SDK=${Build.VERSION.SDK_INT}; build=${Build.DISPLAY}") + "\n" +
            sanitize("Build fingerprint=${Build.FINGERPRINT}")
        record("=== BookCover Diagnostic startup; app version=$version ===")
        record("Reported manufacturer=${Build.MANUFACTURER}; model=${Build.MODEL}; device=${Build.DEVICE}")
        record("Android release=${Build.VERSION.RELEASE}; SDK=${Build.VERSION.SDK_INT}; build=${Build.DISPLAY}")
        record("Build fingerprint=${Build.FINGERPRINT}")
        record("Prior sample-reader dual-display behavior was user-confirmed. Each EPUB build still needs physical observation; callbacks alone do not prove success.")
        record("Privacy: local diagnostic events only; selected image contents and document URIs are omitted.")
    }

    @Synchronized
    fun record(message: String) {
        val safeMessage = sanitize(message).take(MAX_MESSAGE_CHARS)
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date())
        val line = "$timestamp $safeMessage"
        addLine(line)
        Log.i(TAG, safeMessage)
        try {
            logFile?.writeText(lines.joinToString(separator = "\n", postfix = "\n"), Charsets.UTF_8)
        } catch (_: Exception) {
            // A diagnostic failure must not interfere with the display experiment.
            Log.w(TAG, "Diagnostic log could not be saved; events remain available in memory.")
        }
    }

    @Synchronized
    fun report(): String = buildString {
        appendLine("BookCover Diagnostic report")
        appendLine(deviceMetadata)
        appendLine("This report records API observations, not independent proof that the exterior display worked.")
        appendLine("Physical-device success must be confirmed by a person observing both screens while BookCover reads an EPUB.")
        appendLine("Selected image contents and document URIs are omitted. Times include the phone's UTC offset.")
        appendLine("The local log retains at most 128 KiB; older events may have been removed.")
        appendLine()
        lines.forEach { appendLine(it) }
    }

    private fun sanitize(message: String): String = documentUri.replace(message, "[document URI omitted]")
        .replace('\r', ' ')
        .replace('\n', ' ')

    private fun addLine(line: String) {
        lines.addLast(line)
        byteCount += line.toByteArray(Charsets.UTF_8).size + 1
        while (byteCount > MAX_BYTES && lines.isNotEmpty()) {
            byteCount -= lines.removeFirst().toByteArray(Charsets.UTF_8).size + 1
        }
    }
}
