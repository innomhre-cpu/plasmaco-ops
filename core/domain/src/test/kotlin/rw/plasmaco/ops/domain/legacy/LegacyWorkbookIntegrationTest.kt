package rw.plasmaco.ops.domain.legacy

import org.junit.Assume.assumeTrue
import org.junit.Test
import rw.plasmaco.ops.domain.TestData
import rw.plasmaco.ops.domain.common.DateRange
import rw.plasmaco.ops.domain.inventory.Ledger
import rw.plasmaco.ops.domain.inventory.TxnType
import rw.plasmaco.ops.domain.master.ItemKind
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs against PLASMACO's real workbooks when -Dplasmaco.workbooks=<folder> is given (skipped otherwise, e.g. in CI).
 * Expected figures were established independently with openpyxl during discovery (tools/legacy-analysis).
 */
class LegacyWorkbookIntegrationTest {
    private val folder = System.getProperty("plasmaco.workbooks")?.let(::File)

    private fun workbook(suffix: String): File {
        assumeTrue("set -Dplasmaco.workbooks to run", folder != null && folder.isDirectory)
        return folder!!.listFiles()!!.first { it.name.endsWith("Stock-$suffix.xlsm") }
    }

    private val parsed by lazy { workbook("43").let { LegacyStockParser.parse(it.readBytes(), it.name) } }

    @Test
    fun `finds every daily block and repairs every date`() {
        val wb = parsed
        assertEquals(2026, wb.year)
        val perSheet = wb.days.groupBy { it.sheet }.mapValues { it.value.size }
        assertEquals(mapOf("feb" to 24, "mar" to 23, "apr" to 22, "may" to 17, "jun" to 25, "july" to 19, "aug" to 26, "sep" to 28, "oct" to 7), perSheet)
        assertEquals(191, wb.days.size)
        val unresolved = wb.days.filter { it.date == null }
        assertTrue(unresolved.isEmpty(), "unresolved: ${unresolved.map { it.sheet to it.blockIndex }}")
        // 1 Sep was stored as 9 Jan (day/month swap); 13 Sep as text.
        val sep = wb.days.filter { it.sheet == "sep" }
        assertEquals(LocalDate.of(2026, 9, 1), sep.first().date)
        assertEquals(DateSource.DAY_MONTH_SWAPPED, sep.first().dateSource)
        assertEquals(LocalDate.of(2026, 9, 30), sep.last().date)
        assertEquals(LocalDate.of(2026, 10, 7), wb.days.last().date)
        // Every August block lacked a date; all are recovered.
        assertTrue(wb.days.filter { it.sheet == "aug" }.all { it.date?.monthValue == 8 })
        // Dates strictly increase across the whole file.
        wb.days.mapNotNull { it.date }.zipWithNext().forEach { (a, b) -> assertTrue(b.isAfter(a), "$a then $b") }
    }

    @Test
    fun `reads cached values that match openpyxl`() {
        val sep1 = parsed.days.first { it.sheet == "sep" }
        assertEquals(130, sep1.rows.size)
        val dn20 = sep1.rows.first { it.name == "HDPE PIPE PE-PE100 DN20 PN16" }
        assertEquals("=aug!G3283", dn20.opening.formula)
        assertEquals(CellOrigin.CROSS_SHEET, dn20.opening.origin)
        // September TOTAL omits the first two product rows.
        assertEquals("=SUM(I10:I137)", sep1.totalKg?.formula)
        val dn75pn16 = sep1.rows.first { it.name == "HDPE PIPE PE-PE100 DN75 PN16" }
        assertEquals(CellOrigin.TYPED_SUM, dn75pn16.outward.origin)
        assertEquals(BigDecimal("6700"), dn75pn16.outward.value!!.stripTrailingZeros().let { BigDecimal(it.toPlainString()) })
    }

    @Test
    fun `import plan reproduces the workbook's closing balances`() {
        val wb = parsed
        val master = TestData.masterFromCatalogue()
        val plan = ImportPlanner.plan(wb, ImportPlanner.Context(master, TestData.RESIN.id, emptyMap(), "importer", Instant.parse("2026-10-13T10:00:00Z")))
        assertTrue(plan.blocking.none { it.code != "product.unparsed" }, plan.blocking.toString())
        val ledger = Ledger(plan.transactions, includeHistorical = true)
        // Every product's imported balance on its last day equals the workbook's closing cell for that day.
        val last = wb.days.last()
        var checked = 0
        for (row in last.rows) {
            val pid = plan.productMatches.first { it.legacyName == row.name }.productId ?: continue
            val expected = row.closing.value ?: continue
            assertEquals(0, expected.compareTo(ledger.balance(ItemKind.FINISHED_GOOD, pid, last.date)), row.name)
            checked++
        }
        assertEquals(132, checked)
        // Discontinuities are explicit, not hidden.
        assertTrue(plan.stats.reconciliations > 100, "reconciliations=${plan.stats.reconciliations}")
        assertTrue(plan.issues.any { it.code == "rm.receipt_in_opening" })
        assertTrue(plan.transactions.all { it.historical && it.sourceRef != null })
        // Statement reconciles for every product over the whole period.
        val range = DateRange(plan.stats.firstDate!!, plan.stats.lastDate!!)
        ledger.items(ItemKind.FINISHED_GOOD).forEach { (k, id) -> assertTrue(ledger.statement(k, id, range).reconciles) }
        println("import: ${plan.stats}; issues=${plan.issues.groupingBy { it.code }.eachCount()}; conflicts=${plan.conflicts.size}")
    }

    @Test
    fun `re-importing a later save adds only new days`() {
        val older = workbook("46").let { LegacyStockParser.parse(it.readBytes(), it.name) }
        val master = TestData.masterFromCatalogue()
        val ctx = ImportPlanner.Context(master, TestData.RESIN.id, emptyMap(), "importer", Instant.parse("2026-10-13T10:00:00Z"))
        val first = ImportPlanner.plan(older, ctx)
        val keys = first.transactions.associate { it.idempotencyKey to it.quantity }
        val second = ImportPlanner.plan(parsed, ctx.copy(existingKeys = keys))
        assertTrue(second.skippedAlreadyImported > 0)
        val newDates = second.transactions.filter { it.itemKind == ItemKind.FINISHED_GOOD && it.type != TxnType.OPENING_BALANCE }.map { it.date }.toSet()
        assertTrue(newDates.all { !it.isBefore(LocalDate.of(2026, 9, 29)) }, "new FG dates: ${newDates.sorted().take(5)}")
        println("second import: ${second.transactions.size} new, ${second.skippedAlreadyImported} skipped, ${second.conflicts.size} conflicts")
    }
}
