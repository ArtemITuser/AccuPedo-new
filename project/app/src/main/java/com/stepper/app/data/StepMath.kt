package com.stepper.app.data

import kotlin.math.roundToLong

/** Чистая математическая логика, не зависящая от Android API. */
object StepMath {
    fun counterDelta(previous: Long?, current: Long): Long {
        val now = current.coerceAtLeast(0L)
        return when {
            previous == null -> 0L
            now < previous -> 0L
            else -> now - previous
        }
    }

    fun distanceMeters(steps: Long, stepLengthMeters: Double): Double =
        steps.coerceAtLeast(0L) * stepLengthMeters.coerceIn(0.30, 1.50)

    fun activeSeconds(deltaSteps: Long, elapsedMillis: Long?): Long {
        val steps = deltaSteps.coerceAtLeast(0L)
        if (steps == 0L) return 0L
        val inferred = (steps.toDouble() / (100.0 / 60.0)).roundToLong().coerceAtLeast(1L).coerceAtMost(300L)
        val elapsed = ((elapsedMillis ?: 0L).coerceAtLeast(0L) / 1000L)
        return if (elapsed > 0L) inferred.coerceAtMost(elapsed) else inferred
    }

    fun calories(weightKg: Double, activeSeconds: Long): Double {
        val weight = weightKg.coerceIn(20.0, 300.0)
        return 3.3 * 3.5 * weight / 200.0 * activeSeconds.coerceAtLeast(0L) / 60.0
    }

    fun goalProgress(steps: Long, goal: Long): Double =
        (steps.coerceAtLeast(0L).toDouble() / goal.coerceAtLeast(1L).toDouble()).coerceIn(0.0, 1.0)
}
