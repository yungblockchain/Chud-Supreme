package com.m3u.tv

import android.content.Context
import android.os.Build
import android.os.Process
import androidx.compose.runtime.Immutable
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/* -------------------------------------------------------------------------------------------------
 * Crash and error reports: kept on the Fire TV gzip-compressed, with logins, passwords, tokens
 * and keys blanked out, and sent as issues to the person's own (private) GitHub repository with
 * their own token, when they choose to.
 * ---------------------------------------------------------------------------------------------- */

@Immutable
data class CrashReport(
    val file: File,
    val kind: String,
    val title: String,
    val time: Long,
    val sent: Boolean,
)

internal object CrashReports {
    private const val DIRECTORY = "crash-reports"
    private const val MAX_REPORTS = 30
    private const val MAX_LOG_LINES = 300
    private const val MAX_ISSUE_CHARS = 60_000
    const val KIND_CRASH = "crash"
    const val KIND_ERROR = "error"

    /** Saves a report for any crash, then lets Android handle it as usual. */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                write(
                    context = app,
                    kind = KIND_CRASH,
                    title = "${error.javaClass.simpleName}: ${error.message.orEmpty()}".take(120),
                    details = "Thread: ${thread.name}\n\n${stackTrace(error)}",
                    withLog = true,
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** A problem that didn't crash the app (a stream that wouldn't play, an import that failed). */
    fun recordError(context: Context, title: String, details: String) {
        runCatching { write(context.applicationContext, KIND_ERROR, title, details, withLog = true) }
    }

    fun list(context: Context): List<CrashReport> =
        directory(context).listFiles { file -> file.name.endsWith(".txt.gz") }
            .orEmpty()
            .mapNotNull { file ->
                // Name: <time>_<kind>[_sent].txt.gz; the title is the report's first line.
                val parts = file.name.removeSuffix(".txt.gz").split('_')
                val time = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
                CrashReport(
                    file = file,
                    kind = parts.getOrNull(1) ?: KIND_ERROR,
                    title = runCatching { read(file).lineSequence().firstOrNull() }.getOrNull().orEmpty(),
                    time = time,
                    sent = parts.getOrNull(2) == "sent",
                )
            }
            .sortedByDescending { it.time }

    fun read(file: File): String =
        GZIPInputStream(file.inputStream()).bufferedReader().use { it.readText() }

    fun deleteAll(context: Context) {
        directory(context).listFiles()?.forEach { it.delete() }
    }

    /**
     * Sends each unsent report as an issue in [repository] ("owner/name"). Returns how many went.
     * Throws [IOException] if GitHub refuses (bad token, no access, rate limit).
     */
    suspend fun upload(context: Context, token: String, repository: String): Int = withContext(Dispatchers.IO) {
        val repo = repository.trim().removePrefix("https://github.com/").trim('/')
        require(repo.count { it == '/' } == 1) { "Repository must look like owner/name" }
        var sent = 0
        list(context).filterNot { it.sent }.sortedBy { it.time }.forEach { report ->
            val text = read(report.file)
            val body = buildJsonObject {
                put("title", "[${report.kind}] ${report.title}".take(200))
                put("body", "```\n" + text.take(MAX_ISSUE_CHARS) + "\n```")
                put("labels", JsonArray(listOf(JsonPrimitive("chud-streams"), JsonPrimitive(report.kind))))
            }.toString()
            val connection = (URL("https://api.github.com/repos/$repo/issues").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 30_000
                doOutput = true
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("Authorization", "Bearer ${token.trim()}")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("User-Agent", "ChudStreams")
                setRequestProperty("Content-Type", "application/json")
            }
            try {
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                if (code !in 200..299) throw IOException("GitHub returned HTTP $code")
            } finally {
                connection.disconnect()
            }
            report.file.renameTo(File(report.file.parentFile, report.file.name.replace(".txt.gz", "_sent.txt.gz")))
            sent++
        }
        sent
    }

    private fun write(context: Context, kind: String, title: String, details: String, withLog: Boolean) {
        val now = System.currentTimeMillis()
        val text = buildString {
            append(redact(title)).append('\n')
            append('\n')
            append("When: ").append(isoTime(now)).append('\n')
            append("App: ").append(appVersion(context)).append('\n')
            append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" (").append(Build.DEVICE).append(")\n")
            append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT)
                .append("), build ").append(Build.DISPLAY).append('\n')
            append('\n').append(redact(details)).append('\n')
            if (withLog) {
                append("\n--- Recent log ---\n")
                append(redact(recentLog()))
            }
        }
        val directory = directory(context)
        GZIPOutputStream(File(directory, "${now}_$kind.txt.gz").outputStream()).bufferedWriter().use {
            it.write(text)
        }
        // Keep the folder small.
        directory.listFiles()
            ?.sortedByDescending { it.name }
            ?.drop(MAX_REPORTS)
            ?.forEach { it.delete() }
    }

    /** This app's own log lines (Android lets an app read its own). */
    private fun recentLog(): String = runCatching {
        val process = ProcessBuilder(
            "logcat", "-d", "-v", "time", "-t", MAX_LOG_LINES.toString(), "--pid=${Process.myPid()}",
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor(3, TimeUnit.SECONDS)
        output
    }.getOrDefault("(log not available)")

    private val redactions = listOf(
        // Xtream stream paths: /live/<user>/<pass>/..., /movie/..., /series/..., /timeshift/...
        Regex("""(/(?:live|movie|series|timeshift)/)[^/\s]+/[^/\s]+/""") to "$1***/***/",
        // Short form: http://host:port/<user>/<pass>/<stream id>
        Regex("""(https?://[^/\s]+/)[^/\s]+/[^/\s]+/(\d+(?:\.\w+)?)\b""") to "$1***/***/$2",
        Regex("""(?i)((?:username|password|pass|user|api_key|apikey|token|key|auth)=)[^&\s"']+""") to "$1***",
        Regex("""(?i)(authorization:\s*(?:bearer|basic)\s+)\S+""") to "$1***",
        Regex("""(?i)(x-api-key:\s*)\S+""") to "$1***",
        Regex("""\bbot\d{6,}:[A-Za-z0-9_-]{20,}""") to "bot***",
        Regex("""\bsk-ant-[A-Za-z0-9_-]+""") to "sk-ant-***",
        Regex("""\bgh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}""") to "gh***",
        Regex("""(https?://)[^/\s:@]+:[^/\s@]+@""") to "$1***:***@",
    )

    fun redact(text: String): String =
        redactions.fold(text) { current, (pattern, replacement) -> pattern.replace(current, replacement) }

    private fun stackTrace(error: Throwable): String {
        val writer = StringWriter()
        error.printStackTrace(PrintWriter(writer))
        return writer.toString()
    }

    private fun appVersion(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${PackageInfoCompat.getLongVersionCode(info)})"
    }.getOrDefault("unknown")

    private fun isoTime(time: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(time))

    private fun directory(context: Context): File =
        File(context.filesDir, DIRECTORY).apply { mkdirs() }
}
