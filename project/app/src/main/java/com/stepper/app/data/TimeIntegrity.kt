package com.stepper.app.data

import android.content.Context
import android.os.SystemClock
import java.time.Instant
import java.util.UUID
import kotlin.math.abs

/**
 * Сверяет часы реального времени с монотонной шкалой только внутри одной
 * загрузки устройства. Поэтому обычная перезагрузка не считается ручной
 * корректировкой даты.
 */
class TimeIntegrity(private val context: Context) {
    data class Result(
        val anomaly: Boolean,
        val kind: String,
        val details: String,
        val elapsedDeltaMs: Long = 0L,
        val wallDeltaMs: Long = 0L
    )

    fun observe(
        now: Instant = Instant.now(),
        elapsedMs: Long = SystemClock.elapsedRealtime(),
        bootId: String? = null
    ): Result {
        val prefs = context.getSharedPreferences("time_integrity", Context.MODE_PRIVATE)
        val lastWall = prefs.getLong("last_wall_ms", 0L)
        val lastElapsed = prefs.getLong("last_elapsed_ms", 0L)
        val lastBoot = prefs.getString("last_boot_id", null)

        val result = when {
            lastWall == 0L || lastElapsed == 0L || (bootId != null && lastBoot != null && lastBoot != bootId) -> {
                Result(false, "NONE", "Начало новой временной сессии")
            }
            elapsedMs < lastElapsed -> {
                Result(true, "MONOTONIC_RESET", "Монотонное время уменьшилось без смены bootId", elapsedMs - lastElapsed, now.toEpochMilli() - lastWall)
            }
            else -> {
                val wallDelta = now.toEpochMilli() - lastWall
                val elapsedDelta = elapsedMs - lastElapsed
                val drift = wallDelta - elapsedDelta
                if (abs(drift) >= 120_000L) {
                    val kind = if (drift > 0) "CLOCK_FORWARD" else "CLOCK_BACKWARD"
                    Result(true, kind, "Системные часы изменены примерно на ${drift / 1000} с", elapsedDelta, wallDelta)
                } else Result(false, "NONE", "", elapsedDelta, wallDelta)
            }
        }

        prefs.edit()
            .putLong("last_wall_ms", now.toEpochMilli())
            .putLong("last_elapsed_ms", elapsedMs)
            .apply {
                if (bootId != null) putString("last_boot_id", bootId)
            }
            .apply()
        return result
    }

    fun newId(): String = UUID.randomUUID().toString()
}
