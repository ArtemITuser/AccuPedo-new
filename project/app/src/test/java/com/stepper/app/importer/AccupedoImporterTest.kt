package com.stepper.app.importer

import kotlin.test.Test
import kotlin.test.assertEquals
import java.io.ByteArrayInputStream

class AccupedoImporterTest {
    @Test
    fun parsesAccupedoCsvWithoutLosingMetricPrecision() {
        val csv = """
            2026, 8, 10, 15488, 11,80, 461, 2:24
            2026, 8, 14, 4669, 3,56, 138, 0:38
        """.trimIndent()
        val result = AccupedoCsvImporter.import(ByteArrayInputStream(csv.toByteArray()))
        assertEquals(2, result.days.size)
        assertEquals(20157L, result.days.sumOf { it.steps })
        assertEquals(15360.0, result.days.sumOf { it.distanceMeters })
        assertEquals(252, result.days.sumOf { it.caloriesKcal.toInt() })
        assertEquals(10920L, result.days.sumOf { it.activeSeconds })
        assertEquals(0, result.warnings.size)
    }
}
