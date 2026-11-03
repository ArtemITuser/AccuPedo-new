package com.stepper.app.importer

import kotlin.test.Test
import kotlin.test.assertEquals
import java.io.ByteArrayInputStream

class AccupedoImporterTest {
    @Test
    fun parsesAccupedoCsvWithoutLosingMetricPrecision() {
        // Реальный экспорт Accupedo — RFC-4180 CSV с кавычками в дробных полях (запятая как десятичный разделитель).
        val csv = """
            2026,8,10,15488,"11,80",461,2:24
            2026,8,14,4669,"3,56",138,0:38
        """.trimIndent()
        val result = AccupedoCsvImporter.import(ByteArrayInputStream(csv.toByteArray()))
        assertEquals(2, result.days.size)
        assertEquals(20157L, result.days.sumOf { it.steps })
        // distance_integer + distance_fraction/100 (см. docs/IMPORT_ACCUPEDO.md): 11.80 км + 3.56 км
        assertEquals(15360.0, result.days.sumOf { it.distanceMeters })
        assertEquals(599, result.days.sumOf { it.caloriesKcal.toInt() })
        assertEquals(10920L, result.days.sumOf { it.activeSeconds })
        assertEquals(0, result.warnings.size)
    }
}
