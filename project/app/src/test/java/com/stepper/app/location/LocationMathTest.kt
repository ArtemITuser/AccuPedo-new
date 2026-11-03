package com.stepper.app.location

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocationMathTest {
    @Test
    fun samePointIsZero() {
        assertEquals(0.0, LocationMath.distanceMeters(55.75, 37.62, 55.75, 37.62), 1e-9)
    }

    @Test
    fun shortEquatorDistanceIsReasonable() {
        val meters = LocationMath.distanceMeters(0.0, 0.0, 0.0, 0.001)
        assertTrue(meters in 110.0..112.5)
    }

    @Test
    fun datelineCrossingUsesShortestArc() {
        val meters = LocationMath.distanceMeters(0.0, 179.999, 0.0, -179.999)
        assertTrue(meters < 300.0)
    }
}
