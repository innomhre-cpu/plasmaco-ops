package rw.plasmaco.ops.domain.export.pdf

import rw.plasmaco.ops.domain.analytics.ChartKind
import rw.plasmaco.ops.domain.analytics.ChartSpec
import rw.plasmaco.ops.domain.analytics.SeriesRole
import rw.plasmaco.ops.domain.export.ExportFormat
import rw.plasmaco.ops.domain.reports.CellValue
import rw.plasmaco.ops.domain.reports.Column
import rw.plasmaco.ops.domain.reports.ColumnKind
import rw.plasmaco.ops.domain.reports.ReportDocument
import rw.plasmaco.ops.domain.reports.Section
import rw.plasmaco.ops.domain.units.Formatting
import java.math.BigDecimal
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Renders a [ReportDocument] as a branded, paginated PDF: header and footer on every page, "Page x of y", KPI
 * summary, tables with repeated headers and totals, charts drawn as vectors, approvals and metric definitions.
 * Colours are the Plasmaco design tokens (docs/design-system.md).
 */
class ReportPdfRenderer(private val zone: ZoneId = ZoneId.of("Africa/Kigali")) {
    private object C {
        val primary = PdfColor.hex("#17504E")
        val primaryTint = PdfColor.hex("#E3EEED")
        val text = PdfColor.hex("#111414")
        val secondary = PdfColor.hex("#626B6B")
        val border = PdfColor.hex("#D9DEDF")
        val inset = PdfColor.hex("#F5F7F7")
        val zebra = PdfColor.hex("#F8FAFA")
        val grid = PdfColor.hex("#E6E9EA")
        val context = PdfColor.hex("#C9D3D2")
        val negative = PdfColor.hex("#E5484D")
        val other = PdfColor.hex("#9AA3A3")
        val categorical = listOf("#00876F", "#C47D04", "#2A75BA", "#D45B3D", "#795BBF").map(PdfColor::hex)
    }

    private lateinit var pdf: PdfWriter
    private lateinit var page: PdfWriter.Page
    private lateinit var doc: ReportDocument
    private var cursor = 0f
    private val left = 36f
    private val right get() = pdf.pageWidth - 36f
    private val contentWidth get() = right - left
    private val bottomLimit get() = pdf.pageHeight - 52f
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun render(document: ReportDocument): ByteArray {
        doc = document
        pdf = if (document.landscape) PdfWriter(841.89f, 595.28f) else PdfWriter(595.28f, 841.89f)
        newPage(first = true)
        titleBlock()
        kpis()
        document.sections.forEach { section ->
            when (section) {
                is Section.Table -> table(section)
                is Section.Chart -> chart(section.title, section.spec)
                is Section.Text -> paragraphs(section.title, section.paragraphs)
                is Section.KeyValues -> table(Section.Table(section.title, listOf(Column("Item", weight = 1f), Column("Value", weight = 2.5f)), section.pairs.map { listOf(CellValue.Text(it.first), CellValue.Text(it.second)) }))
            }
        }
        if (document.remarks.isNotEmpty()) paragraphs("Remarks", document.remarks)
        if (document.approvals.isNotEmpty()) table(Section.Table("Approval history", listOf(Column("Action", weight = 1.6f), Column("By", weight = 1.4f), Column("At", weight = 1.2f), Column("Reason", weight = 3f)),
            document.approvals.map { listOf(CellValue.Text(it.action), CellValue.Text(it.by), CellValue.Text(stamp.format(it.at.atZone(zone))), it.reason?.let { r -> CellValue.Text(r) } ?: CellValue.Empty) }))
        if (document.definitions.isNotEmpty()) table(Section.Table("Metric definitions", listOf(Column("Metric", weight = 1.4f), Column("Definition", weight = 4f)),
            document.definitions.map { listOf(CellValue.Text(it.first), CellValue.Text(it.second)) }))
        footers()
        return pdf.build(document.title, document.generatedBy, "PLASMACO LTD — ${document.period.start} to ${document.period.end}", document.generatedAt)
    }

    // ------------------------------------------------------------------ page furniture

    private fun newPage(first: Boolean = false) {
        page = pdf.newPage()
        page.text(left, 34f, "PLASMACO LTD", 10.5f, PdfFont.BOLD, C.primary)
        page.textRight(right, 34f, doc.title + (doc.subtitle?.let { " · $it" } ?: ""), 8f, PdfFont.REGULAR, C.secondary)
        page.line(left, 44f, right, 44f, C.border, 0.6f)
        cursor = if (first) 62f else 60f
    }

    private fun footers() {
        val total = pdf.pageCount
        val generated = "Generated ${stamp.format(doc.generatedAt.atZone(zone))} by ${doc.generatedBy} · Plasmaco Operations · ${ExportFormat.PDF.label}"
        for (i in 0 until total) {
            val p = pdf.page(i)
            p.line(left, pdf.pageHeight - 34f, right, pdf.pageHeight - 34f, C.border, 0.5f)
            p.text(left, pdf.pageHeight - 22f, PdfWriter.fit(generated, contentWidth - 80f, 7f), 7f, PdfFont.REGULAR, C.secondary)
            p.textRight(right, pdf.pageHeight - 22f, "Page ${i + 1} of $total", 7f, PdfFont.REGULAR, C.secondary)
        }
    }

    private fun ensure(height: Float) {
        if (cursor + height > bottomLimit) newPage()
    }

    // ------------------------------------------------------------------ title and KPIs

    private fun titleBlock() {
        page.text(left, cursor + 16f, doc.title, 18f, PdfFont.BOLD, C.text)
        cursor += 24f
        doc.subtitle?.let { page.text(left, cursor + 12f, it, 11f, PdfFont.REGULAR, C.text); cursor += 16f }
        val period = "Period: ${doc.period.start} to ${doc.period.end}"
        page.text(left, cursor + 11f, period, 9f, PdfFont.REGULAR, C.secondary)
        cursor += 14f
        val filters = doc.filters.filter { it.first != "Period" }.joinToString("   ·   ") { "${it.first}: ${it.second}" }
        PdfWriter.wrap(filters, contentWidth, 8f).take(3).forEach { line ->
            page.text(left, cursor + 10f, line, 8f, PdfFont.REGULAR, C.secondary); cursor += 11f
        }
        cursor += 8f
    }

    private fun kpis() {
        if (doc.kpis.isEmpty()) return
        val cols = if (doc.landscape) 6 else 4
        val gap = 8f
        val w = (contentWidth - gap * (cols - 1)) / cols
        val h = 50f
        doc.kpis.chunked(cols).forEach { row ->
            ensure(h + gap)
            row.forEachIndexed { i, k ->
                val x = left + i * (w + gap)
                page.roundedRect(x, cursor, w, h, 6f, fill = C.inset, stroke = C.border)
                page.text(x + 8f, cursor + 13f, PdfWriter.fit(k.label, w - 16f, 7.5f), 7.5f, PdfFont.REGULAR, C.secondary)
                val noData = k.value == "No data"
                page.text(x + 8f, cursor + 31f, PdfWriter.fit(k.value, w - 16f, if (noData) 10f else 13f, PdfFont.BOLD), if (noData) 10f else 13f, PdfFont.BOLD, if (noData) C.secondary else C.text)
                k.note?.let { page.text(x + 8f, cursor + 43f, PdfWriter.fit(it, w - 16f, 6.5f), 6.5f, PdfFont.REGULAR, C.secondary) }
            }
            cursor += h + gap
        }
        cursor += 6f
    }

    // ------------------------------------------------------------------ tables

    private fun sectionTitle(title: String) {
        page.text(left, cursor + 13f, title, 11.5f, PdfFont.BOLD, C.text)
        cursor += 20f
    }

    /** Column widths from content: numbers and dates get the width they need; text columns share the rest by weight. */
    private data class Layout(val widths: List<Float>, val fontSize: Float)

    private fun layout(t: Section.Table, pad: Float): Layout {
        var size = 7.5f
        while (true) {
            val headerSize = size - 0.5f
            val needed = t.columns.mapIndexed { i, c ->
                val cells = t.rows.map { cellText(it[i], c) } + listOfNotNull(t.totals.getOrNull(i)?.let { cellText(CellValue.Num(it), c) })
                val cellW = cells.maxOfOrNull { PdfWriter.width(it, size, PdfFont.BOLD) } ?: 0f
                max(cellW, headerWidth(c.headerWithUnit, headerSize)) + 2 * pad + 1.5f
            }
            val fixed = t.columns.indices.filter { t.columns[it].kind != ColumnKind.TEXT }
            val flex = t.columns.indices.filter { t.columns[it].kind == ColumnKind.TEXT }
            val fixedTotal = fixed.sumOf { needed[it].toDouble() }.toFloat()
            val remaining = contentWidth - fixedTotal
            val flexMin = flex.sumOf { min(needed[it], 54f).toDouble() }.toFloat()
            if (remaining >= flexMin || size <= 6f) {
                val widths = MutableList(t.columns.size) { 0f }
                fixed.forEach { widths[it] = needed[it] }
                if (flex.isEmpty()) {
                    val extra = (contentWidth - fixedTotal) / max(1, fixed.size)
                    fixed.forEach { widths[it] += extra }
                } else {
                    // Give each text column what it needs when it fits; otherwise share by weight with a floor.
                    val desired = flex.sumOf { needed[it].toDouble() }.toFloat()
                    if (desired <= remaining) {
                        val extra = remaining - desired
                        val wsum = flex.sumOf { t.columns[it].weight.toDouble() }.toFloat()
                        flex.forEach { widths[it] = needed[it] + extra * t.columns[it].weight / wsum }
                    } else {
                        var pool = remaining
                        val open = flex.toMutableList()
                        // Columns needing less than their share keep their need; the rest split what is left.
                        while (open.isNotEmpty()) {
                            val wsum = open.sumOf { t.columns[it].weight.toDouble() }.toFloat()
                            val satisfied = open.filter { needed[it] <= pool * t.columns[it].weight / wsum }
                            if (satisfied.isEmpty()) { open.forEach { widths[it] = max(min(needed[it], 54f), pool * t.columns[it].weight / wsum) }; break }
                            satisfied.forEach { widths[it] = needed[it]; pool -= needed[it] }
                            open.removeAll(satisfied)
                        }
                    }
                }
                return Layout(widths, size)
            }
            size -= 0.5f
        }
    }

    /** Narrowest width at which [text] wraps onto at most two lines. */
    private fun headerWidth(text: String, size: Float): Float {
        var w = text.split(' ').maxOf { PdfWriter.width(it, size, PdfFont.BOLD) }
        val full = PdfWriter.width(text, size, PdfFont.BOLD)
        while (w < full && PdfWriter.wrap(text, w, size, PdfFont.BOLD).size > 2) w += 2f
        return w + 0.5f
    }

    private fun table(t: Section.Table) {
        val rowH = 14f
        val pad = 4f
        val lay = layout(t, pad)
        val fontSize = lay.fontSize
        val headerSize = fontSize - 0.5f
        val xs = lay.widths.runningFold(left) { a, w -> a + w }
        val headerLines = t.columns.mapIndexed { i, c -> PdfWriter.wrap(c.headerWithUnit, lay.widths[i] - 2 * pad, headerSize, PdfFont.BOLD).take(2) }
        val headerH = if (headerLines.any { it.size > 1 }) 24f else 16f

        fun header(continued: Boolean) {
            sectionTitle(if (continued) "${t.title} (continued)" else t.title)
            page.rect(left, cursor, contentWidth, headerH, fill = C.primaryTint)
            t.columns.forEachIndexed { i, c ->
                headerLines[i].forEachIndexed { li, line ->
                    val top = cursor + 10f + li * 9f
                    if (c.kind == ColumnKind.TEXT || c.kind == ColumnKind.DATE) page.text(xs[i] + pad, top, line, headerSize, PdfFont.BOLD, C.primary)
                    else page.textRight(xs[i + 1] - pad, top, line, headerSize, PdfFont.BOLD, C.primary)
                }
            }
            cursor += headerH
        }

        ensure(20f + headerH + rowH * 2)
        header(false)
        if (t.rows.isEmpty()) {
            page.text(left + pad, cursor + 11f, "No records for the selected period and filters.", fontSize, PdfFont.REGULAR, C.secondary)
            cursor += rowH + 10f
            return
        }
        t.rows.forEachIndexed { r, row ->
            if (cursor + rowH > bottomLimit) { newPage(); header(true) }
            if (r % 2 == 1) page.rect(left, cursor, contentWidth, rowH, fill = C.zebra)
            row.forEachIndexed { i, cell -> drawCell(cell, t.columns[i], xs[i], xs[i + 1], cursor, fontSize, pad, PdfFont.REGULAR) }
            cursor += rowH
        }
        if (t.showTotals && t.columns.any { it.total }) {
            if (cursor + rowH > bottomLimit) { newPage(); header(true) }
            page.line(left, cursor, right, cursor, C.primary, 0.8f)
            val labelCol = t.columns.indexOfFirst { !it.total && it.kind == ColumnKind.TEXT }.takeIf { it >= 0 } ?: 0
            page.text(xs[labelCol] + pad, cursor + 10f, "Total", fontSize, PdfFont.BOLD, C.text)
            t.totals.forEachIndexed { i, v -> if (v != null) drawCell(CellValue.Num(v), t.columns[i], xs[i], xs[i + 1], cursor, fontSize, pad, PdfFont.BOLD) }
            cursor += rowH
        }
        page.line(left, cursor, right, cursor, C.border, 0.5f)
        cursor += 6f
        t.note?.let { note ->
            PdfWriter.wrap(note, contentWidth, 7f).forEach { line -> ensure(10f); page.text(left, cursor + 8f, line, 7f, PdfFont.REGULAR, C.secondary); cursor += 9f }
        }
        cursor += 10f
    }

    private fun drawCell(cell: CellValue, col: Column, x0: Float, x1: Float, top: Float, size: Float, pad: Float, font: PdfFont) {
        val text = cellText(cell, col)
        if (text.isEmpty()) return
        val maxW = x1 - x0 - 2 * pad
        when (col.kind) {
            ColumnKind.NUMBER, ColumnKind.PERCENT -> page.textRight(x1 - pad, top + 10f, PdfWriter.fit(text, maxW, size, font), size, font, C.text)
            else -> page.text(x0 + pad, top + 10f, PdfWriter.fit(text, maxW, size, font), size, font, C.text)
        }
    }

    private fun cellText(cell: CellValue, col: Column): String = when (cell) {
        is CellValue.Empty -> ""
        is CellValue.Text -> cell.text
        is CellValue.Day -> cell.date.toString()
        is CellValue.Num -> cell.value?.let { v -> Formatting.number(v, col.decimals) + if (col.kind == ColumnKind.PERCENT) "%" else "" } ?: ""
    }

    // ------------------------------------------------------------------ text

    private fun paragraphs(title: String, paras: List<String>) {
        ensure(40f)
        sectionTitle(title)
        paras.forEach { p ->
            PdfWriter.wrap(p, contentWidth, 9f).forEach { line -> ensure(12f); page.text(left, cursor + 9f, line, 9f, PdfFont.REGULAR, C.text); cursor += 12f }
            cursor += 4f
        }
        cursor += 8f
    }

    // ------------------------------------------------------------------ charts

    private fun colorFor(role: SeriesRole): PdfColor = when (role) {
        SeriesRole.PRIMARY -> C.primary
        SeriesRole.CONTEXT -> C.context
        SeriesRole.TARGET -> C.text
        SeriesRole.NEGATIVE -> C.negative
        SeriesRole.CATEGORY_1 -> C.categorical[0]
        SeriesRole.CATEGORY_2 -> C.categorical[1]
        SeriesRole.CATEGORY_3 -> C.categorical[2]
        SeriesRole.CATEGORY_4 -> C.categorical[3]
        SeriesRole.CATEGORY_5 -> C.categorical[4]
        SeriesRole.OTHER -> C.other
    }

    private fun chart(title: String, spec: ChartSpec) {
        val horizontal = spec.kind == ChartKind.HBAR || spec.kind == ChartKind.STACKED_HBAR
        val height = if (horizontal) max(120f, 26f + spec.xLabels.size * 16f).coerceAtMost(340f) else 200f
        ensure(height + 30f)
        sectionTitle(title)
        val top0 = cursor
        if (spec.isEmpty) {
            page.roundedRect(left, top0, contentWidth, 60f, 6f, fill = C.inset, stroke = C.border)
            page.textCentered(left + contentWidth / 2, top0 + 34f, spec.emptyReason ?: "No data", 9f, PdfFont.REGULAR, C.secondary)
            cursor += 72f
            return
        }
        // Legend (always present for two or more series).
        var lx = left
        if (spec.series.size > 1 || spec.reference != null) {
            spec.series.forEach { s ->
                val c = colorFor(s.role)
                if (s.asLine) page.line(lx, top0 + 5f, lx + 12f, top0 + 5f, c, 1.5f, if (s.role == SeriesRole.TARGET) floatArrayOf(3f, 2f) else null)
                else page.rect(lx, top0 + 1f, 8f, 8f, fill = c)
                page.text(lx + 15f, top0 + 8f, s.name, 7.5f, PdfFont.REGULAR, C.text)
                lx += 15f + PdfWriter.width(s.name, 7.5f) + 14f
            }
        }
        val legendH = if (spec.series.size > 1 || spec.reference != null) 16f else 2f
        val plotTop = top0 + legendH + 14f
        val plotBottom = top0 + height - 18f
        if (horizontal) hbar(spec, plotTop, plotBottom) else vertical(spec, plotTop, plotBottom)
        cursor = top0 + height + 14f
    }

    private fun niceTicks(minV: Double, maxV: Double, count: Int = 4): List<Double> {
        val lo = min(0.0, minV)
        val hi = if (maxV <= lo) lo + 1 else maxV
        val raw = (hi - lo) / count
        val mag = 10.0.pow(floor(log10(raw)))
        val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * mag }.first { it >= raw }
        val start = floor(lo / step) * step
        val end = ceil(hi / step) * step
        return generateSequence(start) { it + step }.takeWhile { it <= end + step / 2 }.toList()
    }

    private fun tickLabel(v: Double, unit: String): String = Formatting.compact(BigDecimal.valueOf(v)) + if (unit == "%") "%" else ""

    private fun vertical(spec: ChartSpec, plotTop: Float, plotBottom: Float) {
        val n = spec.xLabels.size
        val stacked = spec.kind == ChartKind.STACKED_BAR
        val bars = spec.series.filter { !it.asLine }
        val lines = spec.series.filter { it.asLine }
        val values = mutableListOf<Double>()
        for (i in 0 until n) {
            if (stacked) values += bars.sumOf { it.values[i]?.toDouble()?.coerceAtLeast(0.0) ?: 0.0 }
            else bars.forEach { s -> s.values[i]?.let { values += it.toDouble() } }
            lines.forEach { s -> s.values[i]?.let { values += it.toDouble() } }
        }
        spec.reference?.let { values += it.toDouble() }
        val ticks = niceTicks(values.minOrNull() ?: 0.0, values.maxOrNull() ?: 1.0)
        val lo = ticks.first(); val hi = ticks.last()
        val axisW = 44f
        val x0 = left + axisW
        val x1 = right
        fun yOf(v: Double) = (plotBottom - (v - lo) / (hi - lo) * (plotBottom - plotTop)).toFloat()
        ticks.forEach { t ->
            val y = yOf(t)
            page.line(x0, y, x1, y, if (t == 0.0) C.secondary else C.grid, if (t == 0.0) 0.6f else 0.4f)
            page.textRight(x0 - 4f, y + 2.5f, tickLabel(t, spec.unit), 6.5f, PdfFont.REGULAR, C.secondary)
        }
        page.textRight(x0 - 4f, plotTop - 7f, spec.unit, 6.5f, PdfFont.BOLD, C.secondary)
        val slot = (x1 - x0) / max(1, n)
        val groupW = slot * 0.7f
        val barW = if (stacked || bars.size <= 1 || spec.kind == ChartKind.DIVERGING_BAR) groupW else groupW / bars.size
        for (i in 0 until n) {
            val cx = x0 + slot * i + slot / 2
            if (stacked) {
                var acc = 0.0
                bars.forEach { s ->
                    val v = s.values[i]?.toDouble() ?: return@forEach
                    if (v <= 0) return@forEach
                    val yTop = yOf(acc + v); val yBot = yOf(acc)
                    page.rect(cx - barW / 2, yTop, barW, max(0.5f, yBot - yTop - 0.8f), fill = colorFor(s.role))
                    acc += v
                }
            } else if (spec.kind == ChartKind.DIVERGING_BAR) {
                bars.forEach { s ->
                    val v = s.values[i]?.toDouble() ?: return@forEach
                    val a = yOf(0.0); val b = yOf(v)
                    page.rect(cx - barW / 2, min(a, b), barW, max(0.5f, abs(a - b)), fill = colorFor(s.role))
                }
            } else {
                bars.forEachIndexed { k, s ->
                    val v = s.values[i]?.toDouble() ?: return@forEachIndexed
                    val a = yOf(0.0); val b = yOf(v)
                    val bx = cx - groupW / 2 + k * barW
                    page.rect(bx, min(a, b), max(0.8f, barW - 1f), max(0.5f, abs(a - b)), fill = colorFor(s.role))
                }
            }
        }
        lines.forEach { s ->
            val pts = (0 until n).map { i -> s.values[i]?.let { (x0 + slot * i + slot / 2) to yOf(it.toDouble()) } }
            page.polyline(pts, colorFor(s.role), if (s.role == SeriesRole.TARGET) 1f else 1.6f, if (s.role == SeriesRole.TARGET) floatArrayOf(3f, 2f) else null)
            if (pts.count { it != null } <= 40) pts.filterNotNull().forEach { (x, y) -> page.dot(x, y, 1.6f, colorFor(s.role)) }
        }
        spec.reference?.let { ref ->
            val y = yOf(ref.toDouble())
            page.line(x0, y, x1, y, C.text, 0.7f, floatArrayOf(4f, 3f))
            spec.referenceLabel?.let { page.textRight(x1, y - 3f, it, 6.5f, PdfFont.REGULAR, C.text) }
        }
        val maxLabels = max(1, ((x1 - x0) / 34f).toInt())
        val every = ceil(n / maxLabels.toDouble()).toInt().coerceAtLeast(1)
        spec.xLabels.forEachIndexed { i, label ->
            if (i % every == 0) page.textCentered(x0 + slot * i + slot / 2, plotBottom + 10f, PdfWriter.fit(label, slot * every - 2f, 6.5f), 6.5f, PdfFont.REGULAR, C.secondary)
        }
    }

    private fun hbar(spec: ChartSpec, plotTop: Float, plotBottom: Float) {
        val n = spec.xLabels.size
        val stacked = spec.kind == ChartKind.STACKED_HBAR
        val totals = (0 until n).map { i -> if (stacked) spec.series.sumOf { it.values[i]?.toDouble() ?: 0.0 } else spec.series.maxOf { it.values[i]?.toDouble() ?: 0.0 } }
        val ticks = niceTicks(0.0, max(totals.maxOrNull() ?: 1.0, spec.reference?.toDouble() ?: 0.0))
        val hi = ticks.last()
        val labelW = min(180f, contentWidth * 0.32f)
        val x0 = left + labelW
        val x1 = right - 50f
        fun xOf(v: Double) = (x0 + v / hi * (x1 - x0)).toFloat()
        val rowH = (plotBottom - plotTop) / max(1, n)
        ticks.forEach { t ->
            page.line(xOf(t), plotTop, xOf(t), plotBottom, C.grid, 0.4f)
            page.textCentered(xOf(t), plotBottom + 9f, tickLabel(t, spec.unit), 6.5f, PdfFont.REGULAR, C.secondary)
        }
        for (i in 0 until n) {
            val top = plotTop + i * rowH
            val bh = min(11f, rowH * 0.66f)
            val cy = top + rowH / 2
            page.textRight(x0 - 6f, cy + 2.5f, PdfWriter.fit(spec.xLabels[i], labelW - 10f, 7f), 7f, PdfFont.REGULAR, C.text)
            var acc = 0.0
            spec.series.forEach { s ->
                val v = s.values[i]?.toDouble() ?: return@forEach
                val start = if (stacked) acc else 0.0
                page.rect(xOf(start), cy - bh / 2, max(0.8f, xOf(start + v) - xOf(start) - if (stacked) 0.8f else 0f), bh, fill = colorFor(s.role))
                if (stacked) acc += v
            }
            val total = totals[i]
            page.text(xOf(if (stacked) acc else total) + 4f, cy + 2.5f, Formatting.number(BigDecimal.valueOf(total), if (spec.unit == "%") 1 else 0) + if (spec.unit == "%") "%" else "", 6.5f, PdfFont.REGULAR, C.secondary)
        }
        spec.reference?.let { ref ->
            val x = xOf(ref.toDouble())
            page.line(x, plotTop, x, plotBottom, C.text, 0.7f, floatArrayOf(4f, 3f))
        }
    }
}
