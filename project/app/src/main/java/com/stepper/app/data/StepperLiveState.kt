package com.stepper.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate

/**
 * Лёгкое внутрипроцессное состояние для экрана «Сегодня».
 *
 * Сервис подсчёта обновляет это состояние после успешной транзакции SQLite.
 * Activity подписывается на него вместо постоянного опроса БД раз в несколько
 * секунд. Благодаря этому интерфейс получает изменения по событию, а не будит
 * приложение без необходимости.
 */
object StepperLiveState {
    data class Snapshot(
        val date: LocalDate,
        val steps: Long,
        val distanceMeters: Double,
        val caloriesKcal: Double,
        val activeSeconds: Long,
        val goalReached: Boolean,
        val updatedAtElapsedRealtime: Long
    )

    private val _snapshot = MutableStateFlow<Snapshot?>(null)
    val snapshot: StateFlow<Snapshot?> = _snapshot.asStateFlow()

    fun publish(day: DailySummary, updatedAtElapsedRealtime: Long) {
        _snapshot.value = Snapshot(
            date = day.date,
            steps = day.steps,
            distanceMeters = day.distanceMeters,
            caloriesKcal = day.caloriesKcal,
            activeSeconds = day.activeSeconds,
            goalReached = day.goalReached,
            updatedAtElapsedRealtime = updatedAtElapsedRealtime
        )
    }

    fun clear() {
        _snapshot.value = null
    }
}
