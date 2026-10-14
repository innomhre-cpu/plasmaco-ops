package rw.plasmaco.ops.domain.production

import org.junit.Test
import rw.plasmaco.ops.domain.TestData
import rw.plasmaco.ops.domain.TestData.entry
import rw.plasmaco.ops.domain.TestData.report
import rw.plasmaco.ops.domain.common.Severity
import rw.plasmaco.ops.domain.production.ReportValidator.Stage
import java.math.BigDecimal
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReportValidatorTest {
    private val master = TestData.masterFromCatalogue()

    private fun codes(r: ProductionReport, stage: Stage = Stage.SUBMIT, others: List<ProductionReport> = emptyList()) =
        ReportValidator.validate(r, master, TestData.TODAY, others, stage).map { it.code to it.severity }

    @Test
    fun `a complete report passes`() {
        assertEquals(emptyList(), codes(report()).filter { it.second == Severity.ERROR })
    }

    @Test
    fun `good plus rejected must equal gross`() {
        val r = report(entries = listOf(entry(gross = "420", good = "400", rejected = "10")))
        assertTrue("qty.gross_mismatch" to Severity.ERROR in codes(r))
    }

    @Test
    fun `negative quantities are refused even in a draft`() {
        val r = report(entries = listOf(entry(gross = "-5", good = "-5", rejected = "0")))
        assertTrue(codes(r, Stage.DRAFT).any { it.first == "qty.negative" && it.second == Severity.ERROR })
    }

    @Test
    fun `future dates are refused`() {
        assertTrue("date.future" to Severity.ERROR in codes(report(date = TestData.TODAY.plusDays(1))))
    }

    @Test
    fun `a second report for the same date shift and line is blocked at submit but only warned in draft`() {
        val existing = report(id = "other", status = ReportStatus.SUBMITTED)
        val mine = report(id = "mine")
        assertTrue("report.duplicate" to Severity.ERROR in codes(mine, Stage.SUBMIT, listOf(existing)))
        assertTrue("report.duplicate" to Severity.WARNING in codes(mine, Stage.DRAFT, listOf(existing)))
        // A rejected report does not block a fresh one.
        assertTrue(codes(mine, Stage.SUBMIT, listOf(existing.copy(status = ReportStatus.REJECTED))).none { it.first == "report.duplicate" })
    }

    @Test
    fun `several products in one report are legitimate`() {
        val r = report(entries = listOf(entry("e1", "PE100-DN110-PN10"), entry("e2", "PE100-DN63-PN16")))
        assertTrue(codes(r).none { it.first == "entry.repeated" })
    }

    @Test
    fun `submit needs at least one entry and times`() {
        val r = report(entries = emptyList()).copy(startTime = null, endTime = null)
        val c = codes(r).map { it.first }
        assertTrue("entries.empty" in c && "time.missing" in c)
    }

    @Test
    fun `material returns cannot exceed what was available and scrap cannot exceed consumption`() {
        val bad = MaterialConsumption("m1", TestData.RESIN.id, issued = BigDecimal(100), returned = BigDecimal(150), scrap = BigDecimal(5))
        assertTrue("material.return_exceeds" to Severity.ERROR in codes(report(materials = listOf(bad))))
        val scrap = MaterialConsumption("m1", TestData.RESIN.id, issued = BigDecimal(100), scrap = BigDecimal(120))
        assertTrue("material.scrap_exceeds" to Severity.ERROR in codes(report(materials = listOf(scrap))))
    }

    @Test
    fun `the same material and batch twice is refused to prevent double counting`() {
        val m = MaterialConsumption("m1", TestData.RESIN.id, issued = BigDecimal(100))
        assertTrue("material.repeated" to Severity.ERROR in codes(report(materials = listOf(m, m.copy(id = "m2")))))
        assertTrue(codes(report(materials = listOf(m, m.copy(id = "m2", batch = "LOT-2")))).none { it.first == "material.repeated" })
    }

    @Test
    fun `consumption is issued minus returned plus line-side change`() {
        val m = MaterialConsumption("m1", TestData.RESIN.id, issued = BigDecimal(1000), returned = BigDecimal(50), lineOpening = BigDecimal(30), lineClosing = BigDecimal(80))
        assertEquals(0, BigDecimal(900).compareTo(m.consumed))
    }

    @Test
    fun `downtime across midnight is measured correctly and overlaps are merged`() {
        val night = TestData.NIGHT
        val d1 = DowntimeEvent("d1", TestData.MACHINE.id, LocalDateTime.of(2026, 10, 6, 23, 30), LocalDateTime.of(2026, 10, 7, 0, 45), TestData.BREAKDOWN.id)
        val d2 = DowntimeEvent("d2", TestData.MACHINE.id, LocalDateTime.of(2026, 10, 7, 0, 30), LocalDateTime.of(2026, 10, 7, 1, 0), TestData.BREAKDOWN.id)
        assertEquals(75, d1.minutes)
        assertEquals(90, DowntimeMath.mergedMinutes(listOf(d1, d2)))
        val r = report(date = java.time.LocalDate.of(2026, 10, 6), shift = night, downtime = listOf(d1, d2))
        val c = codes(r)
        assertTrue("downtime.overlap" to Severity.WARNING in c)
        assertTrue(c.none { it.first == "time.order" })
    }

    @Test
    fun `an open stoppage blocks submission`() {
        val d = DowntimeEvent("d1", TestData.MACHINE.id, LocalDateTime.of(2026, 10, 7, 9, 0), null, TestData.BREAKDOWN.id)
        assertTrue("downtime.open" to Severity.ERROR in codes(report(downtime = listOf(d))))
        assertTrue(codes(report(downtime = listOf(d)), Stage.DRAFT).none { it.first == "downtime.open" })
    }

    @Test
    fun `theoretical resin uses the ISO kg per metre`() {
        // DN110 PN10 = 2.16 kg/m in the workbook's ISO 4427-2 sheet; 420 m gross.
        val r = report(entries = listOf(entry(gross = "420", good = "400", rejected = "20")))
        assertEquals(0, BigDecimal("907.2").compareTo(ReportValidator.theoreticalResinKg(r, master)))
        // DN50 PN6 has no ISO weight: the catalogue falls back to the stock sheet's 0.3 kg/m with a note.
        val dn50pn6 = master.products.first { it.code == "PE100-DN50-PN6" }
        assertTrue(dn50pn6.note!!.contains("No ISO 4427-2 weight"))
        assertNull(master.products.firstOrNull { it.name.contains("DN PN") })
    }

    @Test
    fun `quality inspections must add up`() {
        val q = QualityInspection("q1", TestData.product("PE100-DN110-PN10").id, time = LocalDateTime.of(2026, 10, 7, 10, 0),
            sampleQty = BigDecimal(10), accepted = BigDecimal(8), rejected = BigDecimal(1), result = InspectionResult.FAIL)
        val c = codes(report().copy(inspections = listOf(q))).map { it.first }
        assertTrue("qc.sum" in c && "qc.defect" in c)
    }
}
