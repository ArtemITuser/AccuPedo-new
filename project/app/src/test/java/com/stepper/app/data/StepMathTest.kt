package com.stepper.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StepMathTest {
    @Test
    fun counterDeltaInitializesWithoutCountingHistory() {
        assertEquals(0L, StepMath.counterDelta(null, 1234L))
    }

    @Test
    fun counterDeltaRejectsCounterReset() {
        assertEquals(0L, StepMath.counterDelta(10_000L, 20L))
    }

    @Test
    fun counterDeltaCountsOnlyPositiveDifference() {
        assertEquals(543L, StepMath.counterDelta(10_000L, 10_543L))
    }

    @Test
    fun distanceUsesMetricStride() {
        assertEquals(760.0, StepMath.distanceMeters(1000L, 0.76), 1e-9)
    }

    @Test
    fun activeTimeIsBounded() {
        assertEquals(60L, StepMath.activeSeconds(100L, 120_000L))
        assertEquals(300L, StepMath.activeSeconds(10_000L, 3_600_000L))
    }

    @Test
    fun caloriesAreNonNegativeAndUseWeight() {
        assertTrue(StepMath.calories(70.0, 180L) > 0.0)
        assertEquals(0.0, StepMath.calories(70.0, 0L), 1e-12)
    }

    @Test
    fun goalProgressIsClamped() {
        assertEquals(0.0, StepMath.goalProgress(0L, 10_000L), 1e-12)
        assertEquals(0.5, StepMath.goalProgress(5_000L, 10_000L), 1e-12)
        assertEquals(1.0, StepMath.goalProgress(15_000L, 10_000L), 1e-12)
    }
}
