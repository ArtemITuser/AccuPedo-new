package com.stepper.app.data

import java.time.Instant
import java.time.LocalDate

/** Каноническая дневная запись. Все расстояния внутри приложения хранятся в метрах. */
data class DailySummary(
    val date: LocalDate,
    val steps: Long,
    val distanceMeters: Double,
    val caloriesKcal: Double,
    val activeSeconds: Long,
    val goalReached: Boolean,
    val source: String = "canonical"
)

data class SensorSample(
    val bootId: String,
    val sensorValue: Long,
    val sensorTimestampNanos: Long,
    val elapsedRealtimeMillis: Long,
    val wallClockUtc: Instant,
    val localDate: LocalDate,
    val timezoneId: String,
    val timezoneOffsetMinutes: Int,
    val source: String = "sensor"
)

data class ImportedDay(
    val date: LocalDate,
    val steps: Long,
    val distanceMeters: Double,
    val caloriesKcal: Double,
    val activeSeconds: Long,
    val source: String,
    val sourceKey: String
)

data class ImportSummary(
    val source: String,
    val rowsRead: Int,
    val rowsImported: Int,
    val warnings: List<String>,
    val days: List<ImportedDay>
)

data class PeriodSummary(
    val start: LocalDate,
    val endExclusive: LocalDate,
    val totalSteps: Long,
    val totalDistanceMeters: Double,
    val totalCaloriesKcal: Double,
    val totalActiveSeconds: Long,
    val averagePerCalendarDay: Double,
    val averagePerActiveDay: Double,
    val maxDaySteps: Long,
    val goalDays: Int,
    val calendarDays: Int,
    val activeDays: Int
)

data class ChartPoint(val label: String, val value: Double)

data class UnresolvedStepEvent(
    val eventKey: String,
    val bootId: String,
    val observedUtc: Instant,
    val observedLocalDate: LocalDate,
    val observedTimezoneId: String,
    val steps: Long,
    val sensorValueBefore: Long,
    val sensorValueAfter: Long,
    val reason: String,
    val resolutionNote: String?
)

data class SensorProcessResult(
    val initialized: Boolean,
    val deltaSteps: Long,
    val anomalyKind: String? = null,
    val inserted: Boolean = false,
    val localDate: LocalDate
)
