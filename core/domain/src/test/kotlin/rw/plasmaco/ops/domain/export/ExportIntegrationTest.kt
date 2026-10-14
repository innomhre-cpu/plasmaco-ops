package rw.plasmaco.ops.domain.export

import org.junit.Assume.assumeTrue
import org.junit.Test
import rw.plasmaco.ops.domain.TestData
import rw.plasmaco.ops.domain.analytics.DataSet
import rw.plasmaco.ops.domain.analytics.Filters
import rw.plasmaco.ops.domain.common.DateRange
import rw.plasmaco.ops.domain.inventory.Ledger
import rw.plasmaco.ops.domain.legacy.ImportPlanner
import rw.plasmaco.ops.domain.legacy.LegacyStockParser
import rw.plasmaco.ops.domain.legacy.XlsxReader
import rw.plasmaco.ops.domain.master.ItemKind
import rw.plasmaco.ops.domain.reports.ReportBuilder
import rw.plasmaco.ops.domain.reports.ReportType
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Stock reports from PLASMACO's real imported history, exported to PDF and Excel and read back. */
class ExportIntegrationTest {
    private val folder = System.getProperty("plasmaco.workbooks")?.let(::File)
    private val out = System.getProperty("plasmaco.out")?.let(::File)

    private val data: DataSet by lazy {
        assumeTrue(folder != null && folder.isDirectory)
        val f = folder!!.listFiles()!!.first { it.name.endsWith("Stock-43.xlsm") }
        val wb = LegacyStockParser.parse(f.readBytes(), f.name)
        val master = TestData.masterFromCatalogue()
        val plan = ImportPlanner.plan(wb, ImportPlanner.Context(master, TestData.RESIN.id, emptyMap(), "importer", Instant.parse("2026-10-13T10:00:00Z")))
        DataSet(master, emptyList(), plan.transactions, userNames = mapOf("importer" to "Workbook import"))
    }

    @Test
    fun `finished goods stock report reconciles with the ledger and exports`() {
        val sep = DateRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30))
        val doc = ReportBuilder.build(ReportBuilder.Request(ReportType.FINISHED_GOODS_STOCK, Filters(sep), "Test run", Instant.parse("2026-10-13T12:00:00Z")), data)
        val table = doc.tables.first()
        val closingIdx = table.columns.indexOfFirst { it.header == "Closing" }
        val ledger = Ledger(data.transactions, includeHistorical = true)
        val expected = data.master.products.fold(BigDecimal.ZERO) { a, p -> a + ledger.balance(ItemKind.FINISHED_GOOD, p.id, sep.end) }
        assertEquals(0, expected.compareTo(table.totals[closingIdx]), "report closing total vs ledger")
        // The workbook's own 30 Sep closing column, summed, is the same number.
        val f = folder!!.listFiles()!!.first { it.name.endsWith("Stock-43.xlsm") }
        val wbClosing = LegacyStockParser.parse(f.readBytes(), f.name).days.last { it.date == sep.end }.rows.mapNotNull { it.closing.value }.fold(BigDecimal.ZERO, BigDecimal::add)
        assertEquals(0, wbClosing.compareTo(expected), "workbook 30 Sep closing total")

        val xlsx = Exporter.export(doc, ExportFormat.XLSX)
        val pdf = Exporter.export(doc, ExportFormat.PDF)
        assertTrue(String(pdf.bytes.copyOfRange(0, 8), Charsets.ISO_8859_1).startsWith("%PDF-1.4"))
        // Read the Excel file back with the import reader: the totals row equals the document total.
        val reader = XlsxReader.open(xlsx.bytes.inputStream())
        val sheet = reader.readSheet(reader.sheets.first { it.name == "Finished goods" })
        val totalRow = (1..sheet.maxRow).first { sheet[it, 1]?.text == "Total" }
        val excelTotal = sheet[totalRow, closingIdx + 1]!!.number!!
        assertEquals(0, excelTotal.compareTo(table.totals[closingIdx]!!.stripTrailingZeros()))
        out?.let { o -> o.mkdirs(); File(o, xlsx.fileName).writeBytes(xlsx.bytes); File(o, pdf.fileName).writeBytes(pdf.bytes) }
    }

    @Test
    fun `raw material stock and movement reports export`() {
        val range = DateRange(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 30))
        for (type in listOf(ReportType.RAW_MATERIAL_STOCK, ReportType.INVENTORY_MOVEMENT)) {
            val doc = ReportBuilder.build(ReportBuilder.Request(type, Filters(range), "Test run", Instant.parse("2026-10-13T12:00:00Z")), data)
            val pdf = Exporter.export(doc, ExportFormat.PDF)
            val xlsx = Exporter.export(doc, ExportFormat.XLSX)
            assertTrue(pdf.bytes.size > 1000 && xlsx.bytes.size > 1000)
            out?.let { o -> File(o, pdf.fileName).writeBytes(pdf.bytes); File(o, xlsx.fileName).writeBytes(xlsx.bytes) }
        }
    }
}
