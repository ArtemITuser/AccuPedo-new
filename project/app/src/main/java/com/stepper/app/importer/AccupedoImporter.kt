package com.stepper.app.importer

import android.database.sqlite.SQLiteDatabase
import com.stepper.app.data.ImportSummary
import com.stepper.app.data.ImportedDay
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.time.LocalDate
import java.util.Locale

/** Импортер CSV Accupedo: формат из выгруженного файла Accupedo. */
object AccupedoCsvImporter {
    /**
     * Разбор строки RFC-4180 CSV с поддержкой кавычек и экранирования `""`.
     * Требуется, потому что Accupedo экспортирует дробные значения с локальной
     * запятой (например `2026,8,10,15488,"11,80",461,2:24`) — наивный split(',')
     * разрезал бы такое поле пополам и терял строки.
     */
    private fun splitCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes -> when {
                    c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                    c == '"' -> inQuotes = false
                    else -> sb.append(c)
                }
                c == '"' -> inQuotes = true
                c == ',' -> { fields += sb.toString(); sb.setLength(0) }
                else -> sb.append(c)
            }
            i++
        }
        fields += sb.toString()
        return fields
    }

    fun import(input: InputStream): ImportSummary {
        val days = mutableListOf<ImportedDay>()
        val warnings = mutableListOf<String>()
        var rows = 0
        BufferedReader(InputStreamReader(input, Charsets.UTF_8)).useLines { lines ->
            lines.forEachIndexed { index, raw ->
                val lineNo = index + 1
                if (raw.isBlank()) return@forEachIndexed
                rows++
                // Кавычки присутствуют -> RFC-4180 парсер; иначе — простой split с recovery ниже.
                val quoted = raw.contains('"')
                var fields = (if (quoted) splitCsvLine(raw) else raw.split(',')).map { it.trim() }
                // Формат без кавычек с десятичной запятой: `2026, 8, 10, 15488, 11,80, 461, 2:24`
                // (8 или 9 полей из-за пробелов/без) — сливаем поля целого и дроби расстояния в одно.
                if (!quoted && (fields.size == 8 || fields.size == 9) &&
                    fields[4].toLongOrNull() != null && fields[5].matches(Regex("\\d{1,2}"))) {
                    fields = fields.subList(0, 4) + listOf("${fields[4]}.${fields[5]}") + fields.subList(6, fields.size)
                }
                if (fields.size != 7) {
                    warnings += "Строка $lineNo: ожидалось 7 полей, получено ${fields.size}."
                    return@forEachIndexed
                }
                try {
                    val year = fields[0].toInt()
                    val month = fields[1].toInt()
                    val day = fields[2].toInt()
                    val steps = fields[3].toLong()
                    // distance_integer + distance_fraction/100 (см. docs/IMPORT_ACCUPEDO.md): `11,80` -> 11.80 км.
                    val distField = fields[4].replace(',', '.')
                    val km = distField.toDouble()
                    val calories = fields[5].replace(',', '.').toDouble()
                    val duration = fields[6].split(':')
                    if (duration.size != 2) error("неверное время активности")
                    val activeSeconds = duration[0].toLong() * 3600 + duration[1].toLong() * 60
                    require(steps >= 0) { "отрицательное число шагов" }
                    require(km >= 0) { "отрицательное расстояние" }
                    days += ImportedDay(
                        LocalDate.of(year, month, day), steps, km * 1000.0, calories.coerceAtLeast(0.0), activeSeconds,
                        "accupedo.csv", "%04d-%02d-%02d".format(Locale.US, year, month, day)
                    )
                } catch (e: Exception) {
                    warnings += "Строка $lineNo: ${e.message ?: "ошибка разбора"}"
                }
            }
        }
        return ImportSummary("accupedo.csv", rows, rows - warnings.size, warnings, days)
    }
}

/**
 * Импортер исходной SQLite-базы Accupedo.
 * Важное правило: diaries.steps — накопительное поле. За каждый день берём
 * последнюю запись, отсортированную по времени и _id.
 */
object AccupedoSqliteImporter {
    fun inspectAndImport(databaseFile: String): ImportSummary {
        val db = SQLiteDatabase.openDatabase(databaseFile, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val integrity = db.rawQuery("PRAGMA integrity_check", null).use { c ->
                if (c.moveToFirst()) c.getString(0) else ""
            }
            require(integrity.equals("ok", true)) { "Accupedo.db повреждена: integrity_check = $integrity" }

            val columns = mutableSetOf<String>()
            db.rawQuery("PRAGMA table_info(diaries)", null).use { c ->
                while (c.moveToNext()) columns += c.getString(1).lowercase(Locale.US)
            }
            val required = setOf("_id", "year", "month", "day", "hour", "minute", "steps", "distance", "calories", "steptime")
            require(columns.containsAll(required)) { "В таблице diaries отсутствуют обязательные поля: ${required - columns}" }

            val days = mutableListOf<ImportedDay>()
            val warnings = mutableListOf<String>()
            var rowsRead = 0
            db.rawQuery(
                "SELECT year, month, day, hour, minute, _id, steps, distance, calories, steptime " +
                    "FROM diaries ORDER BY year, month, day, hour, minute, _id", null
            ).use { c ->
                var current: LocalDate? = null
                var last: ImportedDay? = null
                while (c.moveToNext()) {
                    rowsRead++
                    try {
                        val date = LocalDate.of(c.getInt(0), c.getInt(1), c.getInt(2))
                        val steps = c.getLong(6).coerceAtLeast(0L)
                        val distanceMeters = c.getDouble(7).coerceAtLeast(0.0) * 1000.0
                        val calories = c.getDouble(8).coerceAtLeast(0.0)
                        val activeSeconds = (c.getLong(9) / 1000L).coerceIn(0L, Int.MAX_VALUE.toLong())
                        val key = "%s|%02d:%02d|%d".format(Locale.US, date, c.getInt(3), c.getInt(4), c.getLong(5))
                        val item = ImportedDay(date, steps, distanceMeters, calories, activeSeconds, "accupedo.db", key)
                        if (current != null && date != current) last?.let(days::add)
                        last = item
                        current = date
                    } catch (e: Exception) { warnings += "Пропущена запись: ${e.message}" }
                }
                last?.let(days::add)
            }
            return ImportSummary("accupedo.db", rowsRead, days.size, warnings, days)
        } finally { db.close() }
    }
}
