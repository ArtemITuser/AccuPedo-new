package com.stepper.app.diagnostics

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.io.OutputStream
import kotlin.concurrent.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Единая подсистема диагностики приложения.
 *
 * Правила:
 * 1. Любая ошибка пишется в ограниченный журнал на диске.
 * 2. Только CRITICAL сохраняются как активные проблемы и показываются на главном экране.
 * 3. Журнал ротируется до суммарного лимита 48 MiB, поэтому не превышает установленный предел 50 MiB.
 * 4. Критическая ошибка сохраняется независимо от исправности основной SQLite-БД.
 */
object AppErrorCenter {
    private const val TAG = "Stepper"
    private const val PREFS = "stepper_diagnostics"
    private const val CRITICALS_KEY = "critical_issues"
    private const val MAX_CRITICALS = 8
    private const val MAX_LOG_FILE_BYTES = 6L * 1024L * 1024L
    private const val MAX_LOG_FILES = 8 // 48 MiB максимум до накладных расходов файловой системы.
    private val lock = ReentrantLock()
    private val _criticalRevision = MutableStateFlow(0L)
    /** Увеличивается после изменения списка критических ошибок, чтобы UI мог обновиться сразу. */
    val criticalRevision: StateFlow<Long> = _criticalRevision.asStateFlow()

    enum class Severity { DEBUG, INFO, WARNING, ERROR, CRITICAL }

    data class CriticalIssue(
        val id: String,
        val timeUtc: Instant,
        val code: String,
        val message: String,
        val details: String?
    )

    fun installGlobalExceptionHandler(context: Context) {
        val application = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            report(
                application,
                Severity.CRITICAL,
                code = "UNCAUGHT_EXCEPTION",
                message = "Критическая ошибка в потоке ${thread.name}",
                throwable = throwable,
                persistCritical = true
            )
            // Сохраняем стандартное поведение Android после записи диагностики.
            previous?.uncaughtException(thread, throwable)
        }
        info(application, "DIAGNOSTICS_READY", "Подсистема диагностики запущена")
    }

    fun debug(context: Context, code: String, message: String) =
        report(context, Severity.DEBUG, code, message)

    fun info(context: Context, code: String, message: String) =
        report(context, Severity.INFO, code, message)

    fun warning(context: Context, code: String, message: String, throwable: Throwable? = null) =
        report(context, Severity.WARNING, code, message, throwable)

    fun error(context: Context, code: String, message: String, throwable: Throwable? = null) =
        report(context, Severity.ERROR, code, message, throwable)

    fun critical(context: Context, code: String, message: String, throwable: Throwable? = null) =
        report(context, Severity.CRITICAL, code, message, throwable, persistCritical = true)

    fun report(
        context: Context,
        severity: Severity,
        code: String,
        message: String,
        throwable: Throwable? = null,
        persistCritical: Boolean = severity == Severity.CRITICAL
    ) {
        val app = context.applicationContext
        val now = Instant.now()
        val details = throwable?.stackTraceString()
        val safeMessage = message.replace('\n', ' ').trim()

        try {
            writeLog(app, now, severity, code, safeMessage, details)
            Log.println(androidLogPriority(severity), TAG, "[$code] $safeMessage${if (details != null) "\\n$details" else ""}")
        } catch (logFailure: Throwable) {
            // Последний рубеж: даже если файл журнала недоступен, Android logcat всё равно получает ошибку.
            Log.e(TAG, "Не удалось записать файл журнала: ${logFailure.message}", logFailure)
            Log.e(TAG, "[$code] $safeMessage", throwable)
        }

        if (persistCritical) {
            runCatching { persistCriticalIssue(app, now, code, safeMessage, details) }
                .onFailure { Log.e(TAG, "Не удалось сохранить критическую ошибку", it) }
        }
    }

    fun getCriticalIssues(context: Context): List<CriticalIssue> = lock.withLock {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CRITICALS_KEY, null)
            ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val obj = array.getJSONObject(index)
                    add(
                        CriticalIssue(
                            id = obj.getString("id"),
                            timeUtc = Instant.parse(obj.getString("timeUtc")),
                            code = obj.getString("code"),
                            message = obj.getString("message"),
                            details = obj.optString("details", null)
                        )
                    )
                }
            }
        }.getOrElse {
            emptyList()
        }
    }

    fun clearCriticalIssues(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(CRITICALS_KEY).apply()
        _criticalRevision.value++
        info(context, "CRITICALS_CLEARED", "Список критических ошибок очищен пользователем")
    }

    fun logDirectory(context: Context): File {
        val directory = File(context.filesDir, "logs")
        if (!directory.exists()) directory.mkdirs()
        return directory
    }

    fun currentLogFile(context: Context): File = File(logDirectory(context), "stepper.log")

    /** Экспортирует все диагностические файлы одним ZIP; исходные файлы при этом не удаляются. */
    fun exportLogs(context: Context, output: OutputStream) {
        lock.withLock {
            ZipOutputStream(output.buffered()).use { zip ->
                val files = logDirectory(context).listFiles { file ->
                    file.isFile && (file.name == "stepper.log" || file.name.matches(Regex("stepper\\.log\\.[1-8]")))
                }?.sortedBy { it.name } ?: emptyList()
                if (files.isEmpty()) {
                    zip.putNextEntry(ZipEntry("stepper.log"))
                    zip.closeEntry()
                    return@use
                }
                files.forEach { file ->
                    zip.putNextEntry(ZipEntry(file.name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    fun logSizeBytes(context: Context): Long = lock.withLock {
        logDirectory(context).listFiles { file ->
            file.isFile && (file.name == "stepper.log" || file.name.matches(Regex("stepper\\.log\\.[1-8]")))
        }?.sumOf { it.length() } ?: 0L
    }

    private fun persistCriticalIssue(context: Context, time: Instant, code: String, message: String, details: String?) {
        lock.withLock {
            val existing = getCriticalIssuesUnlocked(context).toMutableList()
            existing.add(
                CriticalIssue(
                    id = "${time.toEpochMilli()}-${code.hashCode()}-${existing.size}",
                    timeUtc = time,
                    code = code,
                    message = message,
                    details = details
                )
            )
            val kept = existing.takeLast(MAX_CRITICALS)
            val array = JSONArray()
            kept.forEach { issue ->
                array.put(JSONObject().apply {
                    put("id", issue.id)
                    put("timeUtc", issue.timeUtc.toString())
                    put("code", issue.code)
                    put("message", issue.message)
                    put("details", issue.details)
                })
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(CRITICALS_KEY, array.toString()).commit()
        }
    }

    private fun getCriticalIssuesUnlocked(context: Context): List<CriticalIssue> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CRITICALS_KEY, null)
            ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val obj = array.getJSONObject(index)
                    add(
                        CriticalIssue(
                            id = obj.getString("id"),
                            timeUtc = Instant.parse(obj.getString("timeUtc")),
                            code = obj.getString("code"),
                            message = obj.getString("message"),
                            details = obj.optString("details", null)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun writeLog(
        context: Context,
        time: Instant,
        severity: Severity,
        code: String,
        message: String,
        details: String?
    ) = lock.withLock {
        val dir = logDirectory(context)
        val current = File(dir, "stepper.log")
        val line = buildString {
            append(time)
            append(" | ")
            append(severity.name)
            append(" | ")
            append(code)
            append(" | ")
            append(message)
            append('\n')
            if (details != null) append(details).append('\n')
        }
        rotateIfNeeded(dir, current, line.toByteArray(Charsets.UTF_8).size.toLong())
        current.appendText(line, Charsets.UTF_8)
        trimTotalSize(dir)
    }

    private fun rotateIfNeeded(dir: File, current: File, incomingBytes: Long) {
        if (!current.exists() || current.length() + incomingBytes <= MAX_LOG_FILE_BYTES) return
        for (index in MAX_LOG_FILES - 1 downTo 1) {
            val from = File(dir, if (index == 1) "stepper.log" else "stepper.log.${index - 1}")
            val to = File(dir, "stepper.log.$index")
            if (to.exists() && index == MAX_LOG_FILES - 1) to.delete()
            if (from.exists()) from.renameTo(to)
        }
    }

    private fun trimTotalSize(dir: File) {
        val files = dir.listFiles { file -> file.isFile && (file.name == "stepper.log" || file.name.matches(Regex("stepper\\.log\\.[1-8]"))) }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        var total = 0L
        files.forEach { file ->
            total += file.length()
            if (total > MAX_LOG_FILE_BYTES * MAX_LOG_FILES) file.delete()
        }
    }

    private fun androidLogPriority(severity: Severity): Int = when (severity) {
        Severity.DEBUG -> Log.DEBUG
        Severity.INFO -> Log.INFO
        Severity.WARNING -> Log.WARN
        Severity.ERROR, Severity.CRITICAL -> Log.ERROR
    }

    private fun Throwable.stackTraceString(): String = StringWriter().also { writer ->
        PrintWriter(writer).use { printWriter -> printStackTrace(printWriter) }
    }.toString()
}
