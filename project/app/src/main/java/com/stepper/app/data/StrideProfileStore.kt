package com.stepper.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime

/**
 * Профили длины шага, обучаемые только по качественным GPS-прогулкам.
 *
 * Важный принцип: повседневный датчик шагов никогда не «угадывает» длину шага.
 * Автокоррекция допускается только после GPS-сессии с достаточным количеством
 * шагов, расстоянием и приемлемой точностью геопозиции.
 */
class StrideProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    enum class ActivityKind(val key: String, val title: String) {
        WALKING("walking", "Ходьба"),
        BRISK_WALKING("brisk", "Быстрая ходьба"),
        RUNNING("running", "Бег")
    }

    enum class DayPart(val key: String) {
        MORNING("morning"),
        DAY("day"),
        EVENING("evening")
    }

    data class Profile(val meters: Double, val samples: Int, val lastUpdatedUtc: String)

    var automaticCalibrationEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO, value).apply()

    fun resolve(kind: ActivityKind, dateTime: LocalDateTime, fallbackMeters: Double): Double {
        val profile = read(kind, dayPart(dateTime)) ?: read(kind, null)
        return profile?.meters?.coerceIn(0.30, 1.50) ?: fallbackMeters.coerceIn(0.30, 1.50)
    }

    /**
     * Обучает профиль по одной качественной GPS-сессии.
     * Возвращает новое значение длины шага либо null, если сессия не прошла фильтры.
     */
    fun learn(
        kind: ActivityKind,
        startedAt: LocalDateTime,
        steps: Long,
        distanceMeters: Double,
        durationSeconds: Long,
        averageAccuracyMeters: Double
    ): Double? {
        if (!automaticCalibrationEnabled) return null
        if (steps < 800L || distanceMeters < 500.0 || durationSeconds < 8 * 60L) return null
        if (averageAccuracyMeters <= 0.0 || averageAccuracyMeters > 25.0) return null

        val observed = (distanceMeters / steps).coerceIn(0.30, 1.50)
        val part = dayPart(startedAt)
        val old = read(kind, part)
        val oldMeters = old?.meters ?: observed

        // Медленная экспоненциальная коррекция не позволяет одному плохому GPS-сеансу
        // резко изменить профиль. Одно наблюдение влияет примерно на 20%.
        val updated = if (old == null) observed else oldMeters * 0.80 + observed * 0.20
        val bounded = updated.coerceIn(oldMeters * 0.85, oldMeters * 1.15).coerceIn(0.30, 1.50)
        val count = (old?.samples ?: 0) + 1
        write(kind, part, Profile(bounded, count, java.time.Instant.now().toString()))
        return bounded
    }

    fun activityForCadence(deltaSteps: Long, elapsedMillis: Long?): ActivityKind {
        val seconds = elapsedMillis?.takeIf { it > 0L }?.div(1000.0) ?: 0.0
        if (seconds <= 0.0) return ActivityKind.WALKING
        val cadence = deltaSteps / seconds
        return when {
            cadence >= 2.60 -> ActivityKind.RUNNING
            cadence >= 1.95 -> ActivityKind.BRISK_WALKING
            else -> ActivityKind.WALKING
        }
    }

    fun exportJson(): JSONObject = JSONObject().apply {
        put("automaticCalibrationEnabled", automaticCalibrationEnabled)
        val array = JSONArray()
        ActivityKind.entries.forEach { kind ->
            DayPart.entries.forEach { part ->
                read(kind, part)?.let { p ->
                    array.put(JSONObject().apply {
                        put("activity", kind.key)
                        put("dayPart", part.key)
                        put("meters", p.meters)
                        put("samples", p.samples)
                        put("lastUpdatedUtc", p.lastUpdatedUtc)
                    })
                }
            }
        }
        put("profiles", array)
    }

    fun importJson(json: JSONObject) {
        automaticCalibrationEnabled = json.optBoolean("automaticCalibrationEnabled", automaticCalibrationEnabled)
        val array = json.optJSONArray("profiles") ?: return
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val kind = ActivityKind.entries.firstOrNull { it.key == item.optString("activity") } ?: continue
            val part = DayPart.entries.firstOrNull { it.key == item.optString("dayPart") } ?: continue
            val meters = item.optDouble("meters", -1.0)
            if (meters in 0.30..1.50) {
                write(kind, part, Profile(meters, item.optInt("samples", 0).coerceAtLeast(0), item.optString("lastUpdatedUtc")))
            }
        }
    }

    private fun dayPart(value: LocalDateTime): DayPart = when (value.hour) {
        in 5..11 -> DayPart.MORNING
        in 12..17 -> DayPart.DAY
        else -> DayPart.EVENING
    }

    private fun read(kind: ActivityKind, part: DayPart?): Profile? {
        val key = if (part == null) "${kind.key}_default" else "${kind.key}_${part.key}"
        val json = prefs.getString("p_$key", null) ?: return null
        return runCatching {
            val o = JSONObject(json)
            Profile(o.getDouble("meters"), o.optInt("samples", 0), o.optString("updated"))
        }.getOrNull()
    }

    private fun write(kind: ActivityKind, part: DayPart, profile: Profile) {
        val key = "${kind.key}_${part.key}"
        prefs.edit().putString("p_$key", JSONObject().apply {
            put("meters", profile.meters)
            put("samples", profile.samples)
            put("updated", profile.lastUpdatedUtc)
        }.toString()).apply()
    }

    companion object {
        private const val FILE = "stride_profiles"
        private const val KEY_AUTO = "automatic_calibration"
    }
}
