package rw.plasmaco.ops.domain.sync

import org.junit.Test
import rw.plasmaco.ops.domain.export.pdf.PdfWriter
import rw.plasmaco.ops.domain.export.xlsx.XlsxWriter
import rw.plasmaco.ops.domain.legacy.FormulaShifter
import rw.plasmaco.ops.domain.legacy.ImportPlanner
import rw.plasmaco.ops.domain.legacy.LegacyStockParser
import rw.plasmaco.ops.domain.legacy.XlsxReader
import rw.plasmaco.ops.domain.security.PinHasher
import rw.plasmaco.ops.domain.units.Formatting
import rw.plasmaco.ops.domain.units.IncompatibleUnitsException
import rw.plasmaco.ops.domain.units.Quantity
import rw.plasmaco.ops.domain.units.UnitOfMeasure
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SyncAndFilesTest {
    private val t0 = Instant.parse("2026-10-08T08:00:00Z")

    private fun op(id: String, entity: String, at: Long, state: OpState = OpState.QUEUED, next: Instant? = null) =
        OutboxOperation(id, "report", entity, OpKind.UPSERT, "{}", null, t0.plusSeconds(at), state = state, nextAttemptAt = next)

    @Test
    fun `back-off grows and is capped`() {
        assertEquals(30_000, SyncRules.backoffMillis(1))
        assertEquals(30 * 60_000L, SyncRules.backoffMillis(20))
        assertTrue(SyncRules.isTransient(503) && SyncRules.isTransient(null) && !SyncRules.isTransient(400))
    }

    @Test
    fun `transient failures retry, conflicts and validation errors stop`() {
        val o = op("1", "A", 0)
        assertEquals(OpState.QUEUED, SyncRules.afterFailure(o, 503, "busy", t0).state)
        assertEquals(OpState.CONFLICT, SyncRules.afterFailure(o, 409, "conflict", t0).state)
        assertEquals(OpState.FAILED, SyncRules.afterFailure(o, 422, "invalid", t0).state)
    }

    @Test
    fun `later changes to a record wait for earlier ones`() {
        val ops = listOf(op("1", "A", 0, OpState.QUEUED, next = t0.plusSeconds(600)), op("2", "A", 10), op("3", "B", 5))
        assertEquals(listOf("3"), SyncRules.pushOrder(ops, t0).map { it.id })
        assertEquals(listOf("1", "3", "2"), SyncRules.pushOrder(ops, t0.plusSeconds(700)).map { it.id })
    }

    @Test
    fun `a workflow action waits for the report content it acts on`() {
        // The report's content change is backing off; the submit action for the same report (another entity type)
        // must not overtake it, or the server would approve stale content.
        val content = op("1", "R1", 0, next = t0.plusSeconds(600))
        val submit = OutboxOperation("2", "report_action", "R1", OpKind.ACTION, "{}", null, t0.plusSeconds(10))
        assertEquals(emptyList(), SyncRules.pushOrder(listOf(content, submit), t0).map { it.id })
        assertEquals(listOf("1", "2"), SyncRules.pushOrder(listOf(content, submit), t0.plusSeconds(700)).map { it.id })
    }

    @Test
    fun `approved server records are never overwritten`() {
        assertIs<Resolution.AcceptServer>(SyncRules.resolve(VersionInfo(5, t0, false), 4, deviceHasUnsyncedChanges = false))
        assertIs<Resolution.PushLocal>(SyncRules.resolve(VersionInfo(5, t0, false), 5, true))
        assertTrue((SyncRules.resolve(VersionInfo(5, t0, locked = true), 5, true) as Resolution.Conflict).keepServerCopy)
        assertFalse((SyncRules.resolve(VersionInfo(6, t0, false), 5, true) as Resolution.Conflict).keepServerCopy)
    }

    @Test
    fun `xlsx written by the app reads back with types intact`() {
        val w = XlsxWriter()
        val s = w.sheet("Stock / Sept")
        s.row(XlsxWriter.XCell.Str("Product", XlsxWriter.Style.HEADER), XlsxWriter.XCell.Str("Closing (m)", XlsxWriter.Style.HEADER), XlsxWriter.XCell.Str("Date", XlsxWriter.Style.HEADER))
        s.row(XlsxWriter.XCell.Str("HDPE PIPE PE-PE100 DN110 PN10 & <test>"), XlsxWriter.XCell.Num(BigDecimal("1939.6")), XlsxWriter.XCell.Day(LocalDate.of(2026, 9, 1)))
        s.freezeTopRows(1)
        s.table(XlsxWriter.TableSpec("Stock_1", 1, 1, 3, 2, listOf("Product", "Closing (m)", "Date")))
        val bytes = w.build("Test", "Tester", t0)
        val r = XlsxReader.open(bytes.inputStream())
        assertEquals(listOf("Stock   Sept"), r.sheets.map { it.name })
        val sheet = r.readSheet(r.sheets.first())
        assertEquals("HDPE PIPE PE-PE100 DN110 PN10 & <test>", sheet[2, 1]!!.text)
        assertEquals(0, BigDecimal("1939.6").compareTo(sheet[2, 2]!!.number))
        assertEquals(LocalDate.of(2026, 9, 1), sheet[2, 3]!!.dateValue(false))
    }

    @Test
    fun `shared formulas are expanded relative to their master cell`() {
        assertEquals("D9+E9-F9", FormulaShifter.shift("D8+E8-F8", 1, 0))
        assertEquals("aug!G3284", FormulaShifter.shift("aug!G3283", 1, 0))
        assertEquals("\$A\$1+B3", FormulaShifter.shift("\$A\$1+B2", 1, 0))
        assertEquals("IF(G9<=J9,\"G8\",\"\")", FormulaShifter.shift("IF(G8<=J8,\"G8\",\"\")", 1, 0))
        assertEquals("SUM(I11:I138)", FormulaShifter.shift("SUM(I10:I137)", 1, 0))
    }

    @Test
    fun `legacy dates are repaired`() {
        fun cellText(t: String) = XlsxReader.Cell(1, 1, XlsxReader.CellType.STRING, t, null, false)
        fun cellDate(d: LocalDate) = XlsxReader.Cell(1, 1, XlsxReader.CellType.NUMBER, XlsxWriter.serial(d).toString(), null, true)
        assertEquals(LocalDate.of(2026, 9, 1) to rw.plasmaco.ops.domain.legacy.DateSource.DAY_MONTH_SWAPPED, LegacyStockParser.interpretDate(cellDate(LocalDate.of(2026, 1, 9)), 9, 2026, false))
        assertEquals(LocalDate.of(2026, 9, 13) to rw.plasmaco.ops.domain.legacy.DateSource.TEXT_PARSED, LegacyStockParser.interpretDate(cellText("13/9/2026"), 9, 2026, false))
        assertEquals(LocalDate.of(2026, 7, 31) to rw.plasmaco.ops.domain.legacy.DateSource.YEAR_CORRECTED, LegacyStockParser.interpretDate(cellText("31/7/2039"), 7, 2026, false))
        assertEquals(LocalDate.of(2026, 8, 1) to rw.plasmaco.ops.domain.legacy.DateSource.TEXT_PARSED, LegacyStockParser.interpretDate(cellText("${" ".repeat(64)}1/8/2026"), 8, 2026, false))
        assertEquals(null, LegacyStockParser.interpretDate(cellText("TOTAL"), 8, 2026, false).first)
    }

    @Test
    fun `bag counts are recovered from typed receipts`() {
        assertEquals(0, BigDecimal(990).compareTo(ImportPlanner.bagsOf("=990*25")))
        assertEquals(0, BigDecimal(5400).compareTo(ImportPlanner.bagsOf("=5*1080*25")))
        assertEquals(null, ImportPlanner.bagsOf("=990+25"))
    }

    @Test
    fun `untrusted files that are not workbooks are refused`() {
        assertFailsWith<Exception> { XlsxReader.open("not a zip".byteInputStream()) }
    }

    @Test
    fun `pdf text is encoded safely`() {
        val pdf = PdfWriter(595f, 842f)
        pdf.newPage().text(40f, 40f, "Closing (m) = opening + in − out ≤ 5 \\ Σ", 10f)
        val bytes = pdf.build("T", "A", creationDate = t0)
        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue(text.startsWith("%PDF-1.4") && text.trimEnd().endsWith("%%EOF"))
        assertTrue(PdfWriter.width("Plasmaco", 10f) > 30f)
        assertEquals("Ab…", PdfWriter.fit("Abcdefghijklmnop", 25f, 10f))
    }

    @Test
    fun `quantities refuse to mix units and format for display`() {
        assertFailsWith<IncompatibleUnitsException> { Quantity.of("1", UnitOfMeasure.METRE) + Quantity.of("1", UnitOfMeasure.KILOGRAM) }
        assertEquals("12,345.5 m", Formatting.quantity(BigDecimal("12345.46"), UnitOfMeasure.METRE))
        assertEquals("200k", Formatting.compact(BigDecimal(200000)))
        assertEquals("-1,234.57", Formatting.number(BigDecimal("-1234.567"), 2))
    }

    @Test
    fun `PIN hashing verifies and rejects`() {
        val h = PinHasher.hash("482913".toCharArray(), iterations = 1_000)
        assertTrue(PinHasher.verify("482913".toCharArray(), h))
        assertFalse(PinHasher.verify("482914".toCharArray(), h))
        assertTrue(PinHasher.strengthIssues("123456").isNotEmpty())
    }
}
