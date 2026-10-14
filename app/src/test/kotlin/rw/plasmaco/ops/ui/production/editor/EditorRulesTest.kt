package rw.plasmaco.ops.ui.production.editor

import rw.plasmaco.ops.domain.TestData
import rw.plasmaco.ops.domain.production.MaterialConsumption
import rw.plasmaco.ops.domain.units.UnitConversion
import rw.plasmaco.ops.domain.units.UnitOfMeasure
import java.math.BigDecimal
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EditorRulesTest {
    @Test
    fun `validation issues open the step that holds the field`() {
        assertEquals(EditorStep.PRODUCTION, EditorStep.of("entries[0].goodQuantity"))
        assertEquals(EditorStep.MATERIALS, EditorStep.of("materials[1].issued"))
        assertEquals(EditorStep.DOWNTIME, EditorStep.of("downtime[0].end"))
        assertEquals(EditorStep.QUALITY, EditorStep.of("inspections[0].result"))
        assertEquals(EditorStep.HANDOVER, EditorStep.of("handover.notes"))
        assertEquals(EditorStep.IDENTIFICATION, EditorStep.of("shiftId"))
    }

    @Test
    fun `times on a night shift after midnight belong to the next day`() {
        val night = TestData.report(shift = TestData.NIGHT)
        assertEquals(night.date.atTime(22, 30), ReportEditorViewModel.resolve(night, LocalTime.of(22, 30)))
        assertEquals(night.date.plusDays(1).atTime(2, 15), ReportEditorViewModel.resolve(night, LocalTime.of(2, 15)))
        val day = TestData.report(shift = TestData.DAY)
        assertEquals(day.date.atTime(7, 0), ReportEditorViewModel.resolve(day, LocalTime.of(7, 0)))
    }

    @Test
    fun `entry units are the base unit plus every configured conversion, once each`() {
        val conversions = listOf(UnitConversion(UnitOfMeasure.COIL, UnitOfMeasure.METRE, BigDecimal(100)), UnitConversion(UnitOfMeasure.METRE, UnitOfMeasure.KILOGRAM, BigDecimal("2.16")))
        assertEquals(listOf(UnitOfMeasure.METRE, UnitOfMeasure.COIL, UnitOfMeasure.KILOGRAM), unitsFor(UnitOfMeasure.METRE, conversions))
        assertEquals(listOf(UnitOfMeasure.METRE), unitsFor(UnitOfMeasure.METRE, emptyList()))
    }

    @Test
    fun `resin consumed is summed in kilograms, and unknown when a unit cannot be converted`() {
        val master = TestData.masterFromCatalogue()
        val kg = TestData.report(materials = listOf(MaterialConsumption("m1", TestData.RESIN.id, issued = BigDecimal(1000), returned = BigDecimal(50))))
        assertEquals(0, BigDecimal(950).compareTo(resinConsumedKg(kg, master)))
        val bags = TestData.report(materials = listOf(MaterialConsumption("m1", TestData.RESIN.id, issued = BigDecimal(4), unit = UnitOfMeasure.BAG)))
        assertEquals(0, BigDecimal(100).compareTo(resinConsumedKg(bags, master)))
        val metres = TestData.report(materials = listOf(MaterialConsumption("m1", TestData.RESIN.id, issued = BigDecimal(4), unit = UnitOfMeasure.METRE)))
        assertNull(resinConsumedKg(metres, master))
    }
}
