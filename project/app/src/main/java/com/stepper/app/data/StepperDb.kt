package com.stepper.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Небольшой управляемый SQLite-слой.
 *
 * Важный принцип v0.4: импортированные источники никогда не затирают показания
 * встроенного датчика. Дневной canonical-агрегат восстанавливается из событий
 * сенсора и импортированных записей по приоритету источника.
 */
class StepperDb(context: Context) : SQLiteOpenHelper(context.applicationContext, "stepper.db", null, 5) {
    init { setWriteAheadLoggingEnabled(true) }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
        db.execSQL("PRAGMA busy_timeout=5000")
        db.execSQL("PRAGMA synchronous=FULL")
        db.execSQL("PRAGMA wal_autocheckpoint=1000")
        db.execSQL("PRAGMA foreign_keys=ON")
    }

    override fun onCreate(db: SQLiteDatabase) {
        createSchemaV1(db)
        upgradeToV2(db)
        upgradeToV4(db)
        upgradeToV5(db)
    }

    private fun createSchemaV1(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS boot_sessions(
                boot_id TEXT PRIMARY KEY,
                started_utc TEXT NOT NULL,
                last_sensor_value INTEGER,
                last_sensor_timestamp_nanos INTEGER,
                last_elapsed_realtime_ms INTEGER
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sensor_samples(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                boot_id TEXT NOT NULL,
                sensor_value INTEGER NOT NULL,
                sensor_timestamp_nanos INTEGER NOT NULL,
                elapsed_realtime_ms INTEGER NOT NULL,
                wall_clock_utc TEXT NOT NULL,
                local_date TEXT NOT NULL,
                timezone_id TEXT NOT NULL,
                timezone_offset_minutes INTEGER NOT NULL,
                source TEXT NOT NULL,
                UNIQUE(boot_id, sensor_timestamp_nanos, sensor_value)
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS step_events(
                event_key TEXT PRIMARY KEY,
                boot_id TEXT NOT NULL,
                occurred_utc TEXT NOT NULL,
                local_date TEXT NOT NULL,
                timezone_id TEXT NOT NULL,
                timezone_offset_minutes INTEGER NOT NULL,
                steps INTEGER NOT NULL,
                distance_meters REAL NOT NULL,
                active_seconds INTEGER NOT NULL DEFAULT 0,
                calories_kcal REAL NOT NULL DEFAULT 0,
                source TEXT NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS daily_summary(
                local_date TEXT PRIMARY KEY,
                steps INTEGER NOT NULL DEFAULT 0,
                distance_meters REAL NOT NULL DEFAULT 0,
                calories_kcal REAL NOT NULL DEFAULT 0,
                active_seconds INTEGER NOT NULL DEFAULT 0,
                goal_reached INTEGER NOT NULL DEFAULT 0,
                goal_steps INTEGER NOT NULL DEFAULT 10000,
                timezone_id TEXT NOT NULL,
                source TEXT NOT NULL DEFAULT 'canonical',
                updated_utc TEXT NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS hourly_summary(
                local_date TEXT NOT NULL,
                hour INTEGER NOT NULL,
                steps INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(local_date, hour)
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS time_anomalies(
                id TEXT PRIMARY KEY,
                detected_at_utc TEXT NOT NULL,
                boot_id TEXT NOT NULL,
                elapsed_delta_ms INTEGER NOT NULL,
                wall_delta_ms INTEGER NOT NULL,
                kind TEXT NOT NULL,
                details TEXT NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS import_batches(
                id TEXT PRIMARY KEY,
                source TEXT NOT NULL,
                created_utc TEXT NOT NULL,
                rows_read INTEGER NOT NULL,
                rows_imported INTEGER NOT NULL,
                warnings INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS imported_records(
                source TEXT NOT NULL,
                source_key TEXT NOT NULL,
                local_date TEXT NOT NULL,
                steps INTEGER NOT NULL,
                distance_meters REAL NOT NULL,
                calories_kcal REAL NOT NULL,
                active_seconds INTEGER NOT NULL,
                import_batch_id TEXT NOT NULL,
                PRIMARY KEY(source, source_key)
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS unresolved_step_events(
                event_key TEXT PRIMARY KEY,
                boot_id TEXT NOT NULL,
                observed_utc TEXT NOT NULL,
                observed_local_date TEXT NOT NULL,
                observed_timezone_id TEXT NOT NULL,
                steps INTEGER NOT NULL,
                sensor_value_before INTEGER NOT NULL,
                sensor_value_after INTEGER NOT NULL,
                reason TEXT NOT NULL,
                resolved INTEGER NOT NULL DEFAULT 0,
                resolution_note TEXT,
                resolved_utc TEXT,
                resolution_date TEXT
            )
        """.trimIndent())
    }

    private fun upgradeToV2(db: SQLiteDatabase) {
        addColumnIfMissing(db, "step_events", "active_seconds", "INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing(db, "step_events", "calories_kcal", "REAL NOT NULL DEFAULT 0")
        addColumnIfMissing(db, "daily_summary", "goal_steps", "INTEGER NOT NULL DEFAULT 10000")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sensor_samples_date ON sensor_samples(local_date)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_step_events_date ON step_events(local_date)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_imported_records_date ON imported_records(local_date)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_unresolved_steps_date ON unresolved_step_events(observed_local_date)")
    }

    private fun addColumnIfMissing(db: SQLiteDatabase, table: String, column: String, definition: String) {
        val exists = db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            var found = false
            while (c.moveToNext()) if (c.getString(1).equals(column, true)) found = true
            found
        }
        if (!exists) db.execSQL("ALTER TABLE $table ADD COLUMN $column $definition")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) upgradeToV2(db)
        if (oldVersion < 3) upgradeToV3(db)
        if (oldVersion < 4) upgradeToV4(db)
        if (oldVersion < 5) upgradeToV5(db)
    }

    private fun upgradeToV3(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS unresolved_step_events(
                event_key TEXT PRIMARY KEY,
                boot_id TEXT NOT NULL,
                observed_utc TEXT NOT NULL,
                observed_local_date TEXT NOT NULL,
                observed_timezone_id TEXT NOT NULL,
                steps INTEGER NOT NULL,
                sensor_value_before INTEGER NOT NULL,
                sensor_value_after INTEGER NOT NULL,
                reason TEXT NOT NULL,
                resolved INTEGER NOT NULL DEFAULT 0,
                resolution_note TEXT
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_unresolved_steps_date ON unresolved_step_events(observed_local_date)")
    }

    private fun upgradeToV4(db: SQLiteDatabase) {
        addColumnIfMissing(db, "unresolved_step_events", "resolved_utc", "TEXT")
        addColumnIfMissing(db, "unresolved_step_events", "resolution_date", "TEXT")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_unresolved_steps_resolved ON unresolved_step_events(resolved, observed_local_date)")
    }


    private fun upgradeToV5(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS health_sync_state(
                local_date TEXT PRIMARY KEY,
                exported_steps INTEGER NOT NULL DEFAULT 0,
                export_version INTEGER NOT NULL DEFAULT 0,
                last_exported_utc TEXT
            )
        """.trimIndent())
    }

    /** Гарантирует существование текущего дня даже до первого показания сенсора. */
    fun ensureDay(date: LocalDate, goal: Long, zoneId: String = ZoneId.systemDefault().id) {
        val db = writableDatabase
        db.insertWithOnConflict("daily_summary", null, ContentValues().apply {
            put("local_date", date.toString())
            put("steps", 0L)
            put("distance_meters", 0.0)
            put("calories_kcal", 0.0)
            put("active_seconds", 0L)
            put("goal_reached", 0)
            put("goal_steps", goal)
            put("timezone_id", zoneId)
            put("source", "canonical")
            put("updated_utc", Instant.now().toString())
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun lastSensorState(bootId: String): Pair<Long, Long?>? = readableDatabase.rawQuery(
        "SELECT last_sensor_value, last_elapsed_realtime_ms FROM boot_sessions WHERE boot_id = ? LIMIT 1",
        arrayOf(bootId)
    ).use { c ->
        if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) to (if (c.isNull(1)) null else c.getLong(1)) else null
    }

    fun registerBoot(bootId: String, started: Instant) {
        writableDatabase.insertWithOnConflict(
            "boot_sessions", null,
            ContentValues().apply {
                put("boot_id", bootId)
                put("started_utc", started.toString())
            }, SQLiteDatabase.CONFLICT_IGNORE
        )
    }

    /**
     * Полностью атомарно принимает одно показание TYPE_STEP_COUNTER.
     * Здесь нельзя разделять обновление last_sensor_value и запись delta: иначе
     * аварийное завершение процесса между двумя операциями потеряет шаги.
     */

    /** Атомарно принимает одно событие TYPE_STEP_DETECTOR как одну дельту шага. */
    fun processStepDetectorEvent(
        sample: SensorSample,
        timeAnomaly: Boolean,
        stepLengthMeters: Double,
        bodyWeightKg: Double,
        goal: Long
    ): SensorProcessResult {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val eventKey = "detector|${sample.bootId}|${sample.sensorTimestampNanos}"
            val inserted = if (timeAnomaly) {
                db.insertWithOnConflict("unresolved_step_events", null, ContentValues().apply {
                    put("event_key", eventKey)
                    put("boot_id", sample.bootId)
                    put("observed_utc", sample.wallClockUtc.toString())
                    put("observed_local_date", sample.localDate.toString())
                    put("observed_timezone_id", sample.timezoneId)
                    put("steps", 1L)
                    put("sensor_value_before", -1L)
                    put("sensor_value_after", -1L)
                    put("reason", "TIME_ANOMALY")
                    put("resolved", 0)
                }, SQLiteDatabase.CONFLICT_IGNORE) != -1L
            } else {
                val distance = StepMath.distanceMeters(1L, stepLengthMeters)
                // Для одиночного события TYPE_STEP_DETECTOR мы не знаем
                // достоверную длительность активного интервала, поэтому не
                // приписываем пользователю искусственные минуты и калории.
                val calories = 0.0
                val row = db.insertWithOnConflict("step_events", null, ContentValues().apply {
                    put("event_key", eventKey)
                    put("boot_id", sample.bootId)
                    put("occurred_utc", sample.wallClockUtc.toString())
                    put("local_date", sample.localDate.toString())
                    put("timezone_id", sample.timezoneId)
                    put("timezone_offset_minutes", sample.timezoneOffsetMinutes)
                    put("steps", 1L)
                    put("distance_meters", distance)
                    put("active_seconds", 0L)
                    put("calories_kcal", calories)
                    put("source", "step_detector")
                }, SQLiteDatabase.CONFLICT_IGNORE)
                if (row != -1L) {
                    db.execSQL(
                        "INSERT INTO hourly_summary(local_date, hour, steps) VALUES(?, ?, 1) " +
                            "ON CONFLICT(local_date, hour) DO UPDATE SET steps = steps + 1",
                        arrayOf<String>(sample.localDate.toString(), sample.wallClockUtc.atZone(ZoneId.of(sample.timezoneId)).hour.toString())
                    )
                    rebuildCanonicalDayLocked(db, sample.localDate, goal, sample.timezoneId)
                    true
                } else false
            }

            db.setTransactionSuccessful()
            return SensorProcessResult(
                initialized = true,
                deltaSteps = if (inserted) 1L else 0L,
                anomalyKind = if (timeAnomaly) "TIME_ANOMALY" else null,
                inserted = inserted,
                localDate = sample.localDate
            )
        } finally {
            db.endTransaction()
        }
    }

    fun processSensorReading(
        sample: SensorSample,
        timeAnomaly: Boolean,
        anomalyKind: String?,
        stepLengthMeters: Double,
        bodyWeightKg: Double,
        goal: Long
    ): SensorProcessResult {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val previous = db.rawQuery(
                "SELECT last_sensor_value, last_elapsed_realtime_ms FROM boot_sessions WHERE boot_id = ? LIMIT 1",
                arrayOf(sample.bootId)
            ).use { c ->
                if (c.moveToFirst() && !c.isNull(0)) {
                    c.getLong(0) to (if (c.isNull(1)) null else c.getLong(1))
                } else null
            }

            db.insertWithOnConflict("daily_summary", null, ContentValues().apply {
                put("local_date", sample.localDate.toString())
                put("steps", 0L)
                put("distance_meters", 0.0)
                put("calories_kcal", 0.0)
                put("active_seconds", 0L)
                put("goal_reached", 0)
                put("goal_steps", goal.coerceIn(100L, 100_000L))
                put("timezone_id", sample.timezoneId)
                put("source", "canonical")
                put("updated_utc", sample.wallClockUtc.toString())
            }, SQLiteDatabase.CONFLICT_IGNORE)

            db.insertWithOnConflict("sensor_samples", null, ContentValues().apply {
                put("boot_id", sample.bootId)
                put("sensor_value", sample.sensorValue)
                put("sensor_timestamp_nanos", sample.sensorTimestampNanos)
                put("elapsed_realtime_ms", sample.elapsedRealtimeMillis)
                put("wall_clock_utc", sample.wallClockUtc.toString())
                put("local_date", sample.localDate.toString())
                put("timezone_id", sample.timezoneId)
                put("timezone_offset_minutes", sample.timezoneOffsetMinutes)
                put("source", sample.source)
            }, SQLiteDatabase.CONFLICT_IGNORE)

            var delta = 0L
            var inserted = false
            var resultAnomaly = anomalyKind

            if (previous != null) {
                val previousValue = previous.first
                val elapsedDelta = previous.second?.let { sample.elapsedRealtimeMillis - it }
                when {
                    sample.sensorValue < previousValue -> {
                        resultAnomaly = "SENSOR_COUNTER_RESET"
                    }
                    sample.sensorValue > previousValue -> {
                        delta = sample.sensorValue - previousValue
                        if (timeAnomaly) {
                            val eventKey = "unresolved|${sample.bootId}|${sample.sensorTimestampNanos}|${sample.sensorValue}"
                            db.insertWithOnConflict("unresolved_step_events", null, ContentValues().apply {
                                put("event_key", eventKey)
                                put("boot_id", sample.bootId)
                                put("observed_utc", sample.wallClockUtc.toString())
                                put("observed_local_date", sample.localDate.toString())
                                put("observed_timezone_id", sample.timezoneId)
                                put("steps", delta)
                                put("sensor_value_before", previousValue)
                                put("sensor_value_after", sample.sensorValue)
                                put("reason", anomalyKind ?: "TIME_ANOMALY")
                                put("resolved", 0)
                            }, SQLiteDatabase.CONFLICT_IGNORE)
                            resultAnomaly = anomalyKind ?: "TIME_ANOMALY"
                            delta = 0L
                        } else {
                            val distance = StepMath.distanceMeters(delta, stepLengthMeters)
                            val activeSeconds = StepMath.activeSeconds(delta, elapsedDelta)
                            val calories = StepMath.calories(bodyWeightKg, activeSeconds)
                            val eventKey = "sensor|${sample.bootId}|${sample.sensorTimestampNanos}|${sample.sensorValue}"
                            val row = db.insertWithOnConflict("step_events", null, ContentValues().apply {
                                put("event_key", eventKey)
                                put("boot_id", sample.bootId)
                                put("occurred_utc", sample.wallClockUtc.toString())
                                put("local_date", sample.localDate.toString())
                                put("timezone_id", sample.timezoneId)
                                put("timezone_offset_minutes", sample.timezoneOffsetMinutes)
                                put("steps", delta)
                                put("distance_meters", distance)
                                put("active_seconds", activeSeconds)
                                put("calories_kcal", calories)
                                put("source", "sensor")
                            }, SQLiteDatabase.CONFLICT_IGNORE)
                            inserted = row != -1L
                            if (inserted) {
                                db.execSQL(
                                    "INSERT INTO hourly_summary(local_date, hour, steps) VALUES(?, ?, ?) " +
                                        "ON CONFLICT(local_date, hour) DO UPDATE SET steps = steps + excluded.steps",
                                    arrayOf<String>(sample.localDate.toString(), sample.wallClockUtc.atZone(ZoneId.of(sample.timezoneId)).hour.toString(), delta.toString())
                                )
                                rebuildCanonicalDayLocked(db, sample.localDate, goal, sample.timezoneId)
                            } else {
                                delta = 0L
                            }
                        }
                    }
                }
            }

            if (resultAnomaly == "SENSOR_COUNTER_RESET") {
                val anomalyId = "sensor-reset|${sample.bootId}|${sample.sensorTimestampNanos}|${sample.sensorValue}"
                db.insertWithOnConflict("time_anomalies", null, ContentValues().apply {
                    put("id", anomalyId)
                    put("detected_at_utc", sample.wallClockUtc.toString())
                    put("boot_id", sample.bootId)
                    put("elapsed_delta_ms", 0L)
                    put("wall_delta_ms", 0L)
                    put("kind", "SENSOR_COUNTER_RESET")
                    put("details", "Счётчик TYPE_STEP_COUNTER уменьшился; дельта не добавлена")
                }, SQLiteDatabase.CONFLICT_IGNORE)
            }

            // ВАЖНО: состояние счётчика продвигается в той же транзакции.
            db.update("boot_sessions", ContentValues().apply {
                put("last_sensor_value", sample.sensorValue)
                put("last_sensor_timestamp_nanos", sample.sensorTimestampNanos)
                put("last_elapsed_realtime_ms", sample.elapsedRealtimeMillis)
            }, "boot_id = ?", arrayOf(sample.bootId))

            db.setTransactionSuccessful()
            return SensorProcessResult(
                initialized = previous == null,
                deltaSteps = delta,
                anomalyKind = resultAnomaly,
                inserted = inserted,
                localDate = sample.localDate
            )
        } finally {
            db.endTransaction()
        }
    }

    /** Добавляет импортированную строку идемпотентно, затем пересобирает день. */
    fun addImportedDay(day: ImportedDay, batchId: String, goal: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertWithOnConflict("imported_records", null, ContentValues().apply {
                put("source", day.source)
                put("source_key", day.sourceKey)
                put("local_date", day.date.toString())
                put("steps", day.steps.coerceAtLeast(0L))
                put("distance_meters", day.distanceMeters.coerceAtLeast(0.0))
                put("calories_kcal", day.caloriesKcal.coerceAtLeast(0.0))
                put("active_seconds", day.activeSeconds.coerceAtLeast(0L))
                put("import_batch_id", batchId)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            rebuildCanonicalDayLocked(db, day.date, goal, ZoneId.systemDefault().id)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun addImportedDays(days: List<ImportedDay>, batchId: String, goal: Long) {
        if (days.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            days.forEach { day ->
                db.insertWithOnConflict("imported_records", null, ContentValues().apply {
                    put("source", day.source)
                    put("source_key", day.sourceKey)
                    put("local_date", day.date.toString())
                    put("steps", day.steps.coerceAtLeast(0L))
                    put("distance_meters", day.distanceMeters.coerceAtLeast(0.0))
                    put("calories_kcal", day.caloriesKcal.coerceAtLeast(0.0))
                    put("active_seconds", day.activeSeconds.coerceAtLeast(0L))
                    put("import_batch_id", batchId)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            days.map { it.date }.distinct().forEach { rebuildCanonicalDayLocked(db, it, goal, ZoneId.systemDefault().id) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun rebuildCanonicalDayLocked(db: SQLiteDatabase, date: LocalDate, goal: Long, zoneId: String) {
        val dateKey = date.toString()
        var hasLiveEvents = false
        var regularSensorEvents = 0L
        var recoveredEvents = 0L
        var steps = 0L
        var distance = 0.0
        var calories = 0.0
        var activeSeconds = 0L

        db.rawQuery(
            "SELECT COUNT(*), SUM(CASE WHEN source = 'sensor' THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN source = 'recovered_time_anomaly' THEN 1 ELSE 0 END), " +
                "COALESCE(SUM(steps),0), COALESCE(SUM(distance_meters),0), " +
                "COALESCE(SUM(calories_kcal),0), COALESCE(SUM(active_seconds),0) " +
                "FROM step_events WHERE local_date = ? AND source IN ('sensor','recovered_time_anomaly')",
            arrayOf(dateKey)
        ).use { c ->
            if (c.moveToFirst()) {
                hasLiveEvents = c.getLong(0) > 0L
                regularSensorEvents = c.getLong(1)
                recoveredEvents = c.getLong(2)
                steps = c.getLong(3)
                distance = c.getDouble(4)
                calories = c.getDouble(5)
                activeSeconds = c.getLong(6)
            }
        }

        var source = when {
            regularSensorEvents > 0L && recoveredEvents > 0L -> "sensor+recovered"
            regularSensorEvents > 0L -> "sensor"
            recoveredEvents > 0L -> "recovered_time_anomaly"
            else -> "canonical"
        }
        if (!hasLiveEvents) {
            // Для импортов выбираем источник с максимальным числом шагов, а не
            // суммируем источники. Это исключает двойной учёт одной прогулки.
            db.rawQuery(
                "SELECT source, steps, distance_meters, calories_kcal, active_seconds " +
                    "FROM imported_records WHERE local_date = ? " +
                    "ORDER BY CASE source " +
                    "WHEN 'accupedo.db' THEN 100 " +
                    "WHEN 'accupedo.csv' THEN 90 " +
                    "WHEN 'health_connect' THEN 10 " +
                    "ELSE 50 END DESC, steps DESC LIMIT 1",
                arrayOf(dateKey)
            ).use { c ->
                if (c.moveToFirst()) {
                    source = c.getString(0)
                    steps = c.getLong(1)
                    distance = c.getDouble(2)
                    calories = c.getDouble(3)
                    activeSeconds = c.getLong(4)
                } else {
                    source = "canonical"
                    steps = 0L
                    distance = 0.0
                    calories = 0.0
                    activeSeconds = 0L
                }
            }
        }

        db.insertWithOnConflict("daily_summary", null, ContentValues().apply {
            put("local_date", dateKey)
            put("steps", steps)
            put("distance_meters", distance)
            put("calories_kcal", calories)
            put("active_seconds", activeSeconds)
            put("goal_reached", if (steps >= goal) 1 else 0)
            put("goal_steps", goal)
            put("timezone_id", zoneId)
            put("source", source)
            put("updated_utc", Instant.now().toString())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun recordImportBatch(id: String, summary: ImportSummary) {
        writableDatabase.insertOrThrow("import_batches", null, ContentValues().apply {
            put("id", id)
            put("source", summary.source)
            put("created_utc", Instant.now().toString())
            put("rows_read", summary.rowsRead)
            put("rows_imported", summary.rowsImported)
            put("warnings", summary.warnings.size)
        })
    }

    fun history(limit: Int = 3650): List<DailySummary> = dailyBetween(LocalDate.of(1970, 1, 1), LocalDate.of(9999, 12, 31), limit)

    fun dailyBetween(startInclusive: LocalDate, endExclusive: LocalDate, limit: Int = 5000): List<DailySummary> {
        val list = ArrayList<DailySummary>()
        readableDatabase.rawQuery(
            "SELECT local_date, steps, distance_meters, calories_kcal, active_seconds, goal_reached, source " +
                "FROM daily_summary WHERE local_date >= ? AND local_date < ? ORDER BY local_date DESC LIMIT ?",
            arrayOf(startInclusive.toString(), endExclusive.toString(), limit.toString())
        ).use { c -> while (c.moveToNext()) list += readDay(c) }
        return list
    }

    fun day(date: LocalDate): DailySummary? = readableDatabase.rawQuery(
        "SELECT local_date, steps, distance_meters, calories_kcal, active_seconds, goal_reached, source " +
            "FROM daily_summary WHERE local_date = ? LIMIT 1", arrayOf(date.toString())
    ).use { if (it.moveToFirst()) readDay(it) else null }

    fun hourly(date: LocalDate): List<Long> {
        val result = MutableList(24) { 0L }
        readableDatabase.rawQuery("SELECT hour, steps FROM hourly_summary WHERE local_date = ?", arrayOf(date.toString())).use { c ->
            while (c.moveToNext()) result[c.getInt(0).coerceIn(0, 23)] = c.getLong(1)
        }
        return result
    }

    fun periodSummary(startInclusive: LocalDate, endExclusive: LocalDate, goal: Long): PeriodSummary {
        val days = dailyBetween(startInclusive, endExclusive, 100_000)
        var totalSteps = 0L
        var totalDistance = 0.0
        var totalCalories = 0.0
        var activeSeconds = 0L
        var activeDays = 0
        var goalDays = 0
        var maxDaySteps = 0L
        for (day in days) {
            totalSteps += day.steps
            totalDistance += day.distanceMeters
            totalCalories += day.caloriesKcal
            activeSeconds += day.activeSeconds
            if (day.steps > 0L) activeDays++
            if (day.steps >= goal) goalDays++
            if (day.steps > maxDaySteps) maxDaySteps = day.steps
        }
        return PeriodSummary(
            startInclusive, endExclusive, totalSteps, totalDistance, totalCalories,
            activeSeconds,
            if (endExclusive.isAfter(startInclusive)) totalSteps.toDouble() / (endExclusive.toEpochDay() - startInclusive.toEpochDay()) else 0.0,
            if (activeDays > 0) totalSteps.toDouble() / activeDays else 0.0,
            maxDaySteps,
            goalDays,
            (endExclusive.toEpochDay() - startInclusive.toEpochDay()).toInt().coerceAtLeast(0),
            activeDays
        )
    }

    /** Сумма канонических сенсорных шагов между двумя мгновениями UTC. */
    fun stepsBetween(startInclusive: Instant, endExclusive: Instant): Long = readableDatabase.rawQuery(
        "SELECT COALESCE(SUM(steps),0) FROM step_events WHERE source = 'sensor' AND occurred_utc >= ? AND occurred_utc < ?",
        arrayOf(startInclusive.toString(), endExclusive.toString())
    ).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    fun exportCsv(): String {
        val b = StringBuilder("year,month,day,steps,distance_km,calories_kcal,active_seconds,source\n")
        readableDatabase.rawQuery(
            "SELECT local_date, steps, distance_meters, calories_kcal, active_seconds, source FROM daily_summary ORDER BY local_date", null
        ).use { c ->
            while (c.moveToNext()) {
                val d = LocalDate.parse(c.getString(0))
                b.append(d.year).append(',').append(d.monthValue).append(',').append(d.dayOfMonth).append(',')
                    .append(c.getLong(1)).append(',')
                    .append("%.3f".format(java.util.Locale.US, c.getDouble(2) / 1000.0)).append(',')
                    .append("%.1f".format(java.util.Locale.US, c.getDouble(3))).append(',')
                    .append(c.getLong(4)).append(',')
                    .append(c.getString(5)).append('\n')
            }
        }
        return b.toString()
    }

    /** Экспорт canonical-дней в JSON, удобный для машинной обработки и миграций. */
    fun exportJson(): String {
        val array = org.json.JSONArray()
        readableDatabase.rawQuery(
            "SELECT local_date, steps, distance_meters, calories_kcal, active_seconds, source FROM daily_summary ORDER BY local_date", null
        ).use { c ->
            while (c.moveToNext()) {
                array.put(org.json.JSONObject().apply {
                    put("date", c.getString(0))
                    put("steps", c.getLong(1))
                    put("distanceMeters", c.getDouble(2))
                    put("caloriesKcal", c.getDouble(3))
                    put("activeSeconds", c.getLong(4))
                    put("source", c.getString(5))
                })
            }
        }
        return org.json.JSONObject().apply {
            put("formatVersion", 1)
            put("unitSystem", "metric")
            put("days", array)
        }.toString(2)
    }

    /** Табличный TSV-экспорт без риска конфликтов с десятичной запятой. */
    fun exportTsv(): String {
        val b = StringBuilder("date\tsteps\tdistance_meters\tcalories_kcal\tactive_seconds\tsource\n")
        readableDatabase.rawQuery(
            "SELECT local_date, steps, distance_meters, calories_kcal, active_seconds, source FROM daily_summary ORDER BY local_date", null
        ).use { c ->
            while (c.moveToNext()) {
                b.append(c.getString(0)).append('\t')
                    .append(c.getLong(1)).append('\t')
                    .append("%.3f".format(java.util.Locale.US, c.getDouble(2))).append('\t')
                    .append("%.1f".format(java.util.Locale.US, c.getDouble(3))).append('\t')
                    .append(c.getLong(4)).append('\t')
                    .append(c.getString(5)).append('\n')
            }
        }
        return b.toString()
    }

    /** Экспорт в близком к Accupedo CSV виде: целая и сотая часть километра. */
    fun exportAccupedoCsv(): String {
        val b = StringBuilder()
        readableDatabase.rawQuery(
            "SELECT local_date, steps, distance_meters, calories_kcal, active_seconds FROM daily_summary ORDER BY local_date", null
        ).use { c ->
            while (c.moveToNext()) {
                val date = LocalDate.parse(c.getString(0))
                val totalKmHundredths = kotlin.math.round(c.getDouble(2) / 10.0).toLong().coerceAtLeast(0L)
                val wholeKm = totalKmHundredths / 100L
                val fractional = totalKmHundredths % 100L
                val duration = c.getLong(4).coerceAtLeast(0L)
                val hours = duration / 3600L
                val minutes = (duration % 3600L) / 60L
                b.append(date.year).append(',').append(date.monthValue).append(',').append(date.dayOfMonth).append(',')
                    .append(c.getLong(1)).append(',').append(wholeKm).append(',')
                    .append("%02d".format(java.util.Locale.US, fractional)).append(',')
                    .append("%.0f".format(java.util.Locale.US, c.getDouble(3))).append(',')
                    .append(String.format(java.util.Locale.US, "%d:%02d", hours, minutes)).append('\n')
            }
        }
        return b.toString()
    }

    fun unresolvedEvents(limit: Int = 100): List<UnresolvedStepEvent> {
        val result = ArrayList<UnresolvedStepEvent>()
        readableDatabase.rawQuery(
            "SELECT event_key, boot_id, observed_utc, observed_local_date, observed_timezone_id, steps, " +
                "sensor_value_before, sensor_value_after, reason, resolution_note " +
                "FROM unresolved_step_events WHERE resolved = 0 ORDER BY observed_utc LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                result += UnresolvedStepEvent(
                    eventKey = c.getString(0),
                    bootId = c.getString(1),
                    observedUtc = Instant.parse(c.getString(2)),
                    observedLocalDate = LocalDate.parse(c.getString(3)),
                    observedTimezoneId = c.getString(4),
                    steps = c.getLong(5),
                    sensorValueBefore = c.getLong(6),
                    sensorValueAfter = c.getLong(7),
                    reason = c.getString(8),
                    resolutionNote = if (c.isNull(9)) null else c.getString(9)
                )
            }
        }
        return result
    }

    /**
     * Переносит сохранённую дельту на явно выбранную пользователем дату.
     * Достоверно восстановить расстояние/калории после ручного перевода часов нельзя,
     * поэтому мы сохраняем только шаги и не выдумываем остальные показатели.
     */
    fun resolveUnresolvedToDate(eventKey: String, targetDate: LocalDate, goal: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val event = db.rawQuery(
                "SELECT steps FROM unresolved_step_events WHERE event_key = ? AND resolved = 0 LIMIT 1",
                arrayOf(eventKey)
            ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
                ?: error("Событие уже обработано или не найдено")

            val recoveredKey = "recovered|$eventKey|$targetDate"
            db.insertWithOnConflict("step_events", null, ContentValues().apply {
                put("event_key", recoveredKey)
                put("boot_id", "recovered")
                put("occurred_utc", Instant.now().toString())
                put("local_date", targetDate.toString())
                put("timezone_id", ZoneId.systemDefault().id)
                put("timezone_offset_minutes", ZoneId.systemDefault().rules.getOffset(Instant.now()).totalSeconds / 60)
                put("steps", event)
                put("distance_meters", 0.0)
                put("active_seconds", 0L)
                put("calories_kcal", 0.0)
                put("source", "recovered_time_anomaly")
            }, SQLiteDatabase.CONFLICT_IGNORE)

            db.execSQL(
                "UPDATE unresolved_step_events SET resolved = 1, resolved_utc = ?, resolution_date = ?, " +
                    "resolution_note = ? WHERE event_key = ? AND resolved = 0",
                arrayOf(Instant.now().toString(), targetDate.toString(), "Назначено пользователем: шаги восстановлены; прочие метрики не восстанавливались", eventKey)
            )
            rebuildCanonicalDayLocked(db, targetDate, goal, ZoneId.systemDefault().id)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun ignoreUnresolved(eventKey: String) {
        writableDatabase.execSQL(
            "UPDATE unresolved_step_events SET resolved = 1, resolved_utc = ?, resolution_note = ? " +
                "WHERE event_key = ? AND resolved = 0",
            arrayOf(Instant.now().toString(), "Пропущено пользователем: дата события недостоверна", eventKey)
        )
    }

    fun recordTimeAnomaly(id: String, bootId: String, elapsedDeltaMs: Long, wallDeltaMs: Long, kind: String, details: String) {
        writableDatabase.insertWithOnConflict("time_anomalies", null, ContentValues().apply {
            put("id", id)
            put("detected_at_utc", Instant.now().toString())
            put("boot_id", bootId)
            put("elapsed_delta_ms", elapsedDeltaMs)
            put("wall_delta_ms", wallDeltaMs)
            put("kind", kind)
            put("details", details)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun checkpointWal() {
        writableDatabase.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { if (it.moveToFirst()) it.getString(0) }
    }

    fun integrityOk(filePath: String): Boolean {
        val database = SQLiteDatabase.openDatabase(filePath, null, SQLiteDatabase.OPEN_READONLY)
        return try {
            database.rawQuery("PRAGMA integrity_check", null).use { it.moveToFirst() && it.getString(0).equals("ok", true) }
        } finally {
            database.close()
        }
    }


    /** Локальные показания или явно импортированная история имеют приоритет над Health Connect. */
    fun hasAuthoritativeLocalData(date: LocalDate): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM step_events WHERE local_date = ? AND source IN ('sensor','recovered_time_anomaly') LIMIT 1",
        arrayOf(date.toString())
    ).use { sensor ->
        if (sensor.moveToFirst()) true else readableDatabase.rawQuery(
            "SELECT 1 FROM imported_records WHERE local_date = ? AND source <> 'health_connect' LIMIT 1",
            arrayOf(date.toString())
        ).use { it.moveToFirst() }
    }

    /** После изменения цели пересчитывает отображение исторических дней. */
    fun updateGoalForAllDays(goal: Long) {
        writableDatabase.execSQL(
            "UPDATE daily_summary SET goal_steps = ?, goal_reached = CASE WHEN steps >= ? THEN 1 ELSE 0 END, updated_utc = ?",
            arrayOf<String>(goal.toString(), goal.toString(), Instant.now().toString())
        )
    }

    fun unresolvedStepCount(): Long = readableDatabase.rawQuery(
        "SELECT COALESCE(SUM(steps),0) FROM unresolved_step_events WHERE resolved = 0", null
    ).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    fun hasSensorData(date: LocalDate): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM step_events WHERE local_date = ? LIMIT 1", arrayOf(date.toString())
    ).use { it.moveToFirst() }

    fun hasExportableLocalData(date: LocalDate): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM step_events WHERE local_date = ? AND source IN ('sensor','recovered_time_anomaly') LIMIT 1",
        arrayOf(date.toString())
    ).use { sensor ->
        if (sensor.moveToFirst()) true else readableDatabase.rawQuery(
            "SELECT 1 FROM imported_records WHERE local_date = ? AND source <> 'health_connect' LIMIT 1",
            arrayOf(date.toString())
        ).use { it.moveToFirst() }
    }

    private fun readDay(c: Cursor) = DailySummary(
        date = LocalDate.parse(c.getString(0)),
        steps = c.getLong(1),
        distanceMeters = c.getDouble(2),
        caloriesKcal = c.getDouble(3),
        activeSeconds = c.getLong(4),
        goalReached = c.getInt(5) != 0,
        source = c.getString(6)
    )

    /** Возвращает следующую версию экспорта без изменения состояния БД. */
    fun healthExportVersion(date: LocalDate, steps: Long): Long {
        val current = readableDatabase.rawQuery(
            "SELECT exported_steps, export_version FROM health_sync_state WHERE local_date = ?",
            arrayOf(date.toString())
        ).use { c ->
            if (c.moveToFirst()) c.getLong(0) to c.getLong(1) else null
        }
        return if (current == null) 1L else if (current.first == steps) current.second else current.second + 1L
    }

    /** Фиксирует успешный экспорт только после подтверждения Health Connect. */
    fun commitHealthExportState(exports: List<HealthExportState>) {
        if (exports.isEmpty()) return
        val db = writableDatabase
        val now = Instant.now().toString()
        db.beginTransaction()
        try {
            exports.forEach { state ->
                db.insertWithOnConflict(
                    "health_sync_state", null, ContentValues().apply {
                        put("local_date", state.date.toString())
                        put("exported_steps", state.steps)
                        put("export_version", state.version)
                        put("last_exported_utc", now)
                    }, SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }



    data class HealthExportState(
        val date: LocalDate,
        val steps: Long,
        val version: Long
    )

}
