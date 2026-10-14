package rw.plasmaco.ops.domain.export

import rw.plasmaco.ops.domain.export.pdf.ReportPdfRenderer
import rw.plasmaco.ops.domain.export.xlsx.XlsxWriter
import rw.plasmaco.ops.domain.export.xlsx.XlsxWriter.Style
import rw.plasmaco.ops.domain.export.xlsx.XlsxWriter.XCell
import rw.plasmaco.ops.domain.reports.CellValue
import rw.plasmaco.ops.domain.reports.ColumnKind
import rw.plasmaco.ops.domain.reports.ReportDocument
import rw.plasmaco.ops.domain.reports.Section
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class ExportFormat(val label: String, val mimeType: String, val extension: String) {
    PDF("PDF", "application/pdf", "pdf"),
    XLSX("Excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx"),
}

data class ExportResult(val bytes: ByteArray, val fileName: String, val mimeType: String)

object Exporter {
    fun export(document: ReportDocument, format: ExportFormat, zone: ZoneId = ZoneId.of("Africa/Kigali")): ExportResult {
        val bytes = when (format) {
            ExportFormat.PDF -> ReportPdfRenderer(zone).render(document)
            ExportFormat.XLSX -> ReportXlsxRenderer(zone).render(document)
        }
        return ExportResult(bytes, fileName(document, format), format.mimeType)
    }

    /** e.g. "Plasmaco_Finished-Goods-Stock-Report_2026-09-01_2026-09-30.xlsx" */
    fun fileName(document: ReportDocument, format: ExportFormat): String {
        val title = document.title.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-')
        val period = if (document.period.start == document.period.end) "${document.period.start}" else "${document.period.start}_${document.period.end}"
        val sub = document.subtitle?.replace(Regex("[^A-Za-z0-9-]+"), "-")?.trim('-')?.let { "_$it" } ?: ""
        return "Plasmaco_${title}${sub}_$period.${format.extension}"
    }
}

/**
 * Excel rendering: a Summary sheet (title, period, filters, KPIs, definitions, approvals) and one sheet per table
 * section as a structured Excel table with typed numbers, units in headers, frozen header row and filters. Values are
 * exactly the [ReportDocument] values; percentages are stored as fractions with a % format.
 */
class ReportXlsxRenderer(private val zone: ZoneId = ZoneId.of("Africa/Kigali")) {
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun render(doc: ReportDocument): ByteArray {
        val w = XlsxWriter()
        val summary = w.sheet("Summary")
        summary.row(XCell.Str("PLASMACO LTD — ${doc.title}", Style.TITLE))
        doc.subtitle?.let { summary.row(XCell.Str(it, Style.BOLD)) }
        summary.row(XCell.Str("Period", Style.BOLD), XCell.Day(doc.period.start), XCell.Str("to"), XCell.Day(doc.period.end))
        summary.row(XCell.Str("Generated", Style.BOLD), XCell.Str("${stamp.format(doc.generatedAt.atZone(zone))} by ${doc.generatedBy}"))
        doc.filters.filter { it.first != "Period" }.forEach { (k, v) -> summary.row(XCell.Str(k, Style.BOLD), XCell.Str(v)) }
        summary.blankRow()
        if (doc.kpis.isNotEmpty()) {
            summary.row(XCell.Str("Key figures", Style.HEADER), XCell.Str("Value", Style.HEADER), XCell.Str("Note", Style.HEADER))
            doc.kpis.forEach { summary.row(XCell.Str(it.label), XCell.Str(it.value), it.note?.let { n -> XCell.Str(n, Style.MUTED) } ?: XCell.Blank) }
            summary.blankRow()
        }
        if (doc.remarks.isNotEmpty()) {
            summary.row(XCell.Str("Remarks", Style.HEADER))
            doc.remarks.forEach { summary.row(XCell.Str(it)) }
            summary.blankRow()
        }
        if (doc.approvals.isNotEmpty()) {
            summary.row(XCell.Str("Approval", Style.HEADER), XCell.Str("By", Style.HEADER), XCell.Str("At", Style.HEADER), XCell.Str("Reason", Style.HEADER))
            doc.approvals.forEach { summary.row(XCell.Str(it.action), XCell.Str(it.by), XCell.Str(stamp.format(it.at.atZone(zone))), XCell.Str(it.reason ?: "")) }
            summary.blankRow()
        }
        if (doc.definitions.isNotEmpty()) {
            summary.row(XCell.Str("Metric", Style.HEADER), XCell.Str("Definition", Style.HEADER))
            doc.definitions.forEach { summary.row(XCell.Str(it.first), XCell.Str(it.second)) }
        }
        summary.columnWidths(listOf(30.0, 40.0, 18.0, 40.0))

        doc.sections.forEachIndexed { si, section ->
            when (section) {
                is Section.Table -> tableSheet(w, section, si)
                is Section.Chart -> chartDataSheet(w, section, si)
                is Section.Text -> w.sheet(section.title).also { s -> s.row(XCell.Str(section.title, Style.TITLE)); section.paragraphs.forEach { s.row(XCell.Str(it)) }; s.columnWidths(listOf(120.0)) }
                is Section.KeyValues -> w.sheet(section.title).also { s -> section.pairs.forEach { (k, v) -> s.row(XCell.Str(k, Style.BOLD), XCell.Str(v)) } }
            }
        }
        return w.build(doc.title, doc.generatedBy, doc.generatedAt)
    }

    private fun tableSheet(w: XlsxWriter, t: Section.Table, index: Int) {
        val s = w.sheet(t.sheetName)
        val headers = dedupe(t.columns.map { it.headerWithUnit })
        s.row(headers.map { XCell.Str(it, Style.HEADER) })
        t.rows.forEach { row -> s.row(row.mapIndexed { i, c -> cell(c, t.columns[i].kind, t.columns[i].decimals, false) }) }
        val lastDataRow = t.rows.size + 1
        s.freezeTopRows(1)
        s.table(XlsxWriter.TableSpec(XlsxWriter.tableName(t.title, index + 1), 1, 1, t.columns.size, lastDataRow, headers))
        if (t.showTotals && t.columns.any { it.total } && t.rows.isNotEmpty()) {
            s.row(t.columns.mapIndexed { i, c ->
                when {
                    i == 0 -> XCell.Str("Total", Style.BOLD)
                    t.totals[i] != null -> cell(CellValue.Num(t.totals[i]), c.kind, c.decimals, true)
                    else -> XCell.Str("", Style.BOLD)
                }
            })
        }
        t.note?.let { s.blankRow(); s.row(XCell.Str(it, Style.MUTED)) }
        s.columnWidths(t.columns.mapIndexed { i, c ->
            val longest = (t.rows.map { r -> textLength(r[i]) } + headers[i].length).maxOrNull() ?: 10
            (longest + 2.0).coerceIn(if (c.kind == ColumnKind.TEXT) 10.0 else 9.0, 60.0)
        })
    }

    /** Charts are exported as their underlying data so they can be re-plotted; no picture is embedded. */
    private fun chartDataSheet(w: XlsxWriter, section: Section.Chart, index: Int) {
        val spec = section.spec
        val s = w.sheet("Chart data ${index + 1}")
        val headers = dedupe(listOf("Category") + spec.series.map { "${it.name} (${spec.unit})" })
        s.row(headers.map { XCell.Str(it, Style.HEADER) })
        spec.xLabels.forEachIndexed { i, label ->
            s.row(listOf<XCell>(XCell.Str(label)) + spec.series.map { ser -> ser.values.getOrNull(i)?.let { XCell.Num(it, Style.DEC1) } ?: XCell.Blank })
        }
        s.freezeTopRows(1)
        if (spec.xLabels.isNotEmpty()) s.table(XlsxWriter.TableSpec(XlsxWriter.tableName("Chart_${spec.id}", index + 1), 1, 1, headers.size, spec.xLabels.size + 1, headers))
        s.blankRow()
        s.row(XCell.Str(section.title, Style.MUTED))
        spec.emptyReason?.let { s.row(XCell.Str(it, Style.MUTED)) }
        s.columnWidths(listOf(28.0) + spec.series.map { 18.0 })
    }

    private fun cell(c: CellValue, kind: ColumnKind, decimals: Int, bold: Boolean): XCell = when (c) {
        is CellValue.Empty -> XCell.Blank
        is CellValue.Text -> XCell.Str(c.text, if (bold) Style.BOLD else Style.DEFAULT)
        is CellValue.Day -> XCell.Day(c.date)
        is CellValue.Num -> c.value?.let { v ->
            if (kind == ColumnKind.PERCENT) XCell.Num(v.divide(BigDecimal(100), 6, RoundingMode.HALF_UP), if (decimals >= 2) Style.PCT2 else Style.PCT1)
            else XCell.Num(v, numberStyle(decimals, bold))
        } ?: XCell.Blank
    }

    private fun numberStyle(decimals: Int, bold: Boolean) = when {
        bold && decimals == 0 -> Style.BOLD_INT
        bold && decimals == 1 -> Style.BOLD_DEC1
        bold -> Style.BOLD_DEC2
        decimals == 0 -> Style.INT
        decimals == 1 -> Style.DEC1
        decimals == 2 -> Style.DEC2
        else -> Style.DEC3
    }

    private fun textLength(c: CellValue): Int = when (c) {
        is CellValue.Text -> c.text.length
        is CellValue.Num -> (c.value?.toPlainString()?.length ?: 0) + 3
        is CellValue.Day -> 10
        CellValue.Empty -> 0
    }

    private fun dedupe(names: List<String>): List<String> {
        val seen = mutableMapOf<String, Int>()
        return names.map { n ->
            val base = n.ifBlank { "Column" }
            val count = seen.merge(base.lowercase(), 1, Int::plus)!!
            if (count == 1) base else "$base $count"
        }
    }
}
