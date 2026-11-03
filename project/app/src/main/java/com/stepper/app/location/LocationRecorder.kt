package com.stepper.app.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.HandlerThread
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.time.Instant
import java.util.UUID

/**
 * Записывает только явно запущенную пользователем GPS-прогулку.
 *
 * Обычный подсчёт шагов не использует GPS. Все callback-и Fused Location
 * обслуживаются на отдельном потоке, поэтому тяжёлая работа с SQLite и
 * геоданными не блокирует главный поток приложения.
 */
class LocationRecorder(context: Context) {
    private val appContext = context.applicationContext
    private val fused: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(appContext)
    private val db = LocationDb(appContext)
    private val locationThread = HandlerThread("StepperLocation").apply { start() }
    private val locationHandler = Handler(locationThread.looper)
    private val lock = Any()

    @Volatile
    private var closed = false
    private var sessionId: String? = null
    private var startedUtc: Instant? = null
    private var activity: String = "walking"
    private var distance = 0.0
    private var accuracySum = 0.0
    private var accuracyCount = 0

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            // Callback уже приходит на StepperLocation, но дополнительная
            // постановка в очередь исключает работу с SQLite в неизвестном потоке
            // при изменении поведения провайдера местоположения.
            locationHandler.post {
                if (closed) return@post
                result.locations.forEach { location ->
                    val id = synchronized(lock) { sessionId }
                    if (id != null) addLocation(id, location)
                }
            }
        }
    }

    fun isRecording(): Boolean = synchronized(lock) { sessionId != null }

    fun currentStartedUtc(): Instant? = synchronized(lock) { startedUtc }

    @SuppressLint("MissingPermission")
    fun start(activity: String) {
        synchronized(lock) {
            check(!closed) { "Средство записи GPS уже закрыто." }
            check(sessionId == null) { "Прогулка уже записывается." }
            this.activity = activity
            val id = "gps-${UUID.randomUUID()}"
            val now = Instant.now()
            sessionId = id
            startedUtc = now
            distance = 0.0
            accuracySum = 0.0
            accuracyCount = 0
            db.startSession(id, now, activity)

            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000L)
                .setMinUpdateIntervalMillis(2_000L)
                .setMinUpdateDistanceMeters(5f)
                .setWaitForAccurateLocation(false)
                .build()

            fused.requestLocationUpdates(request, callback, locationThread.looper)
                .addOnFailureListener { error ->
                    synchronized(lock) {
                        if (sessionId == id) {
                            runCatching { db.finishSession(id, Instant.now(), distance, averageAccuracy(), 0L) }
                            sessionId = null
                            startedUtc = null
                        }
                    }
                    android.util.Log.e("StepperLocation", "Не удалось запустить GPS", error)
                }
        }
    }

    fun stop(stepCount: Long): CompletedLocationSession? {
        val id: String
        val started: Instant
        val activityName: String
        val distanceMeters: Double
        val averageAccuracyMeters: Double
        synchronized(lock) {
            id = sessionId ?: return null
            started = startedUtc ?: Instant.now()
            activityName = activity
            distanceMeters = distance
            averageAccuracyMeters = averageAccuracy()
            // Сразу переводим состояние в неактивное: отложенный callback больше
            // не сможет записать точку после завершения сессии.
            sessionId = null
            startedUtc = null
        }

        runCatching { fused.removeLocationUpdates(callback) }
        val ended = Instant.now()
        db.finishSession(id, ended, distanceMeters, averageAccuracyMeters, stepCount)
        return CompletedLocationSession(
            id = id,
            startedUtc = started,
            endedUtc = ended,
            activity = activityName,
            distanceMeters = distanceMeters,
            averageAccuracyMeters = averageAccuracyMeters,
            stepCount = stepCount,
            durationSeconds = ((ended.toEpochMilli() - started.toEpochMilli()) / 1000L).coerceAtLeast(0L)
        )
    }

    fun close() {
        val shouldClose = synchronized(lock) {
            if (closed) false else {
                closed = true
                sessionId = null
                startedUtc = null
                true
            }
        }
        if (!shouldClose) return
        runCatching { fused.removeLocationUpdates(callback) }
        locationThread.quitSafely()
        db.close()
    }

    private fun addLocation(session: String?, location: Location) {
        val id = session ?: return
        synchronized(lock) {
            if (closed || sessionId != id) return

            if (!location.hasAccuracy() || location.accuracy <= 0f || location.accuracy > 100f) return
            val previous = db.lastPoint(id)
            if (previous != null) {
                val segment = LocationMath.distanceMeters(
                    previous.latitude,
                    previous.longitude,
                    location.latitude,
                    location.longitude
                )
                // Защита от явных GPS-скачков.
                if (segment > 250.0 && previous.speedMps?.let { it < 25.0 } != false) return
                if (segment >= 1.0) distance += segment
            }

            // Используем время GPS, если оно валидно; это точнее для маршрута,
            // чем момент обработки callback на телефоне. При недостоверной метке
            // откатываемся к текущему UTC-времени.
            val recordedUtc = location.time.takeIf { it > 0L }?.let { candidate ->
                runCatching { Instant.ofEpochMilli(candidate) }.getOrNull()
            } ?: Instant.now()

            db.addPoint(
                id,
                recordedUtc,
                location.latitude,
                location.longitude,
                location.accuracy.toDouble(),
                if (location.hasAltitude()) location.altitude else null,
                if (location.hasSpeed()) location.speed.toDouble() else null
            )
            accuracySum += location.accuracy
            accuracyCount++
        }
    }

    private fun averageAccuracy(): Double =
        if (accuracyCount > 0) accuracySum / accuracyCount else 0.0

    data class CompletedLocationSession(
        val id: String,
        val startedUtc: Instant,
        val endedUtc: Instant,
        val activity: String,
        val distanceMeters: Double,
        val averageAccuracyMeters: Double,
        val stepCount: Long,
        val durationSeconds: Long
    )
}
