package com.stepper.app.location

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.Instant

/** Отдельная база приватных GPS-треков. Её можно удалить независимо от шагов. */
class LocationDb(context: Context) : SQLiteOpenHelper(context.applicationContext, NAME, null, 1) {
    init { setWriteAheadLoggingEnabled(true) }

    private val appContext = context.applicationContext

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
        db.execSQL("PRAGMA busy_timeout=5000")
        db.execSQL("PRAGMA synchronous=FULL")
        db.execSQL("PRAGMA foreign_keys=ON")
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE gps_sessions(
                id TEXT PRIMARY KEY,
                started_utc TEXT NOT NULL,
                ended_utc TEXT,
                activity TEXT NOT NULL,
                distance_meters REAL NOT NULL DEFAULT 0,
                points INTEGER NOT NULL DEFAULT 0,
                avg_accuracy_meters REAL NOT NULL DEFAULT 0,
                step_count INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE gps_points(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id TEXT NOT NULL,
                recorded_utc TEXT NOT NULL,
                latitude REAL NOT NULL,
                longitude REAL NOT NULL,
                accuracy_meters REAL NOT NULL,
                altitude_meters REAL,
                speed_mps REAL,
                FOREIGN KEY(session_id) REFERENCES gps_sessions(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_gps_points_session_time ON gps_points(session_id, recorded_utc)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /**
     * Закрывает GPS-сессии, оставшиеся без ended_utc после аварийного завершения
     * процесса. Точки не удаляются: расстояние пересчитывается по сохранённому
     * треку, поэтому внезапная остановка не превращает маршрут в потерянные данные.
     */
    fun recoverUnfinishedSessions(at: Instant = Instant.now()): Int {
        val ids = ArrayList<String>()
        readableDatabase.rawQuery("SELECT id FROM gps_sessions WHERE ended_utc IS NULL", null).use { c ->
            while (c.moveToNext()) ids += c.getString(0)
        }
        var recovered = 0
        ids.forEach { id ->
            val points = track(id)
            if (points.isEmpty()) {
                writableDatabase.update(
                    "gps_sessions",
                    ContentValues().apply { put("ended_utc", at.toString()); put("points", 0) },
                    "id = ? AND ended_utc IS NULL",
                    arrayOf(id)
                )
            } else {
                var distance = 0.0
                for (index in 1 until points.size) {
                    distance += LocationMath.distanceMeters(
                        points[index - 1].latitude, points[index - 1].longitude,
                        points[index].latitude, points[index].longitude
                    )
                }
                val avgAccuracy = points.map { it.accuracyMeters }.average().coerceAtLeast(0.0)
                writableDatabase.update(
                    "gps_sessions",
                    ContentValues().apply {
                        put("ended_utc", at.toString())
                        put("distance_meters", distance.coerceAtLeast(0.0))
                        put("points", points.size)
                        put("avg_accuracy_meters", avgAccuracy)
                    },
                    "id = ? AND ended_utc IS NULL",
                    arrayOf(id)
                )
            }
            recovered++
        }
        return recovered
    }

    fun startSession(id: String, startedUtc: Instant, activity: String) {
        writableDatabase.insertOrThrow("gps_sessions", null, ContentValues().apply {
            put("id", id)
            put("started_utc", startedUtc.toString())
            put("activity", activity)
        })
    }

    fun addPoint(
        sessionId: String,
        recordedUtc: Instant,
        latitude: Double,
        longitude: Double,
        accuracyMeters: Double,
        altitudeMeters: Double?,
        speedMps: Double?
    ) {
        writableDatabase.insertOrThrow("gps_points", null, ContentValues().apply {
            put("session_id", sessionId)
            put("recorded_utc", recordedUtc.toString())
            put("latitude", latitude)
            put("longitude", longitude)
            put("accuracy_meters", accuracyMeters)
            if (altitudeMeters == null) putNull("altitude_meters") else put("altitude_meters", altitudeMeters)
            if (speedMps == null) putNull("speed_mps") else put("speed_mps", speedMps)
        })
    }

    fun finishSession(id: String, endedUtc: Instant, distanceMeters: Double, averageAccuracyMeters: Double, stepCount: Long) {
        writableDatabase.update("gps_sessions", ContentValues().apply {
            put("ended_utc", endedUtc.toString())
            put("distance_meters", distanceMeters.coerceAtLeast(0.0))
            put("points", pointCount(id))
            put("avg_accuracy_meters", averageAccuracyMeters.coerceAtLeast(0.0))
            put("step_count", stepCount.coerceAtLeast(0L))
        }, "id = ?", arrayOf(id))
    }

    fun pointCount(sessionId: String): Int = writableDatabase.rawQuery(
        "SELECT COUNT(*) FROM gps_points WHERE session_id = ?", arrayOf(sessionId)
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun lastPoint(sessionId: String): TrackPoint? = readableDatabase.rawQuery(
        "SELECT recorded_utc, latitude, longitude, accuracy_meters, altitude_meters, speed_mps " +
            "FROM gps_points WHERE session_id = ? ORDER BY recorded_utc DESC LIMIT 1",
        arrayOf(sessionId)
    ).use { c ->
        if (!c.moveToFirst()) return@use null
        TrackPoint(
            Instant.parse(c.getString(0)), c.getDouble(1), c.getDouble(2), c.getDouble(3),
            if (c.isNull(4)) null else c.getDouble(4), if (c.isNull(5)) null else c.getDouble(5)
        )
    }

    fun track(sessionId: String): List<TrackPoint> {
        val result = ArrayList<TrackPoint>()
        readableDatabase.rawQuery(
            "SELECT recorded_utc, latitude, longitude, accuracy_meters, altitude_meters, speed_mps " +
                "FROM gps_points WHERE session_id = ? ORDER BY recorded_utc",
            arrayOf(sessionId)
        ).use { c ->
            while (c.moveToNext()) {
                result += TrackPoint(
                    Instant.parse(c.getString(0)), c.getDouble(1), c.getDouble(2), c.getDouble(3),
                    if (c.isNull(4)) null else c.getDouble(4), if (c.isNull(5)) null else c.getDouble(5)
                )
            }
        }
        return result
    }

    fun allSessions(): List<TrackSession> {
        val result = ArrayList<TrackSession>()
        readableDatabase.rawQuery(
            "SELECT id, started_utc, ended_utc, activity, distance_meters, points, avg_accuracy_meters, step_count " +
                "FROM gps_sessions ORDER BY started_utc DESC", null
        ).use { c ->
            while (c.moveToNext()) {
                result += TrackSession(
                    id = c.getString(0),
                    startedUtc = Instant.parse(c.getString(1)),
                    endedUtc = if (c.isNull(2)) null else Instant.parse(c.getString(2)),
                    activity = c.getString(3),
                    distanceMeters = c.getDouble(4),
                    points = c.getInt(5),
                    averageAccuracyMeters = c.getDouble(6),
                    stepCount = c.getLong(7)
                )
            }
        }
        return result
    }

    fun deleteAll() {
        writableDatabase.delete("gps_points", null, null)
        writableDatabase.delete("gps_sessions", null, null)
    }

    fun databaseFile(): java.io.File = appContext.getDatabasePath(NAME)

    data class TrackPoint(
        val recordedUtc: Instant,
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Double,
        val altitudeMeters: Double?,
        val speedMps: Double?
    )

    data class TrackSession(
        val id: String,
        val startedUtc: Instant,
        val endedUtc: Instant?,
        val activity: String,
        val distanceMeters: Double,
        val points: Int,
        val averageAccuracyMeters: Double,
        val stepCount: Long
    )

    companion object {
        const val NAME = "stepper_location.db"
    }
}
