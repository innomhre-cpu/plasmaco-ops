package rw.plasmaco.ops.domain.reports

import rw.plasmaco.ops.domain.analytics.ChartSpec
import rw.plasmaco.ops.domain.common.DateRange
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

enum class ReportType(val title: String, val landscape: Boolean, val description: String) {
    DAILY_PRODUCTION("Daily Production Report", true, "Every report for a day: output, materials, downtime, quality, handover and approvals"),
    SHIFT_PRODUCTION("Shift Production Report", true, "Output and downtime per shift over a period"),
    PRODUCTION_PERFORMANCE("Production Performance Report", true, "Target achievement, rejection, availability, performance and OEE by line and product"),
    RAW_MATERIAL_CONSUMPTION("Raw Material Consumption Report", true, "Issued, returned, consumed and scrap per material, with theoretical resin"),
    RAW_MATERIAL_STOCK("Raw Material Stock Report", false, "Opening, receipts, issues, returns, adjustments and closing per material"),
    FINISHED_GOODS_STOCK("Finished Goods Stock Report", true, "Opening, receipts, outward, adjustments and closing per product (legacy stock-sheet layout)"),
    INVENTORY_MOVEMENT("Inventory Movement Report", true, "Every stock transaction in the period with references"),
    QUALITY_CONTROL("Quality Control Report", true, "Inspections, results and defects"),
    DOWNTIME("Downtime Report", true, "Stoppages by machine and cause"),
    PRODUCTION_MATERIAL_VARIANCE("Production and Material Variance Report", true, "Theoretical vs consumed resin per report"),
    MANAGEMENT_SUMMARY("Management Summary Report", false, "Headline KPIs, trends, stock position and alerts"),
}

enum class ColumnKind { TEXT, NUMBER, PERCENT, DATE }

data class Column(
    val header: String,
    val kind: ColumnKind = ColumnKind.TEXT,
    val unit: String? = null,
    val decimals: Int = 0,
    /** Relative width for PDF layout. */
    val weight: Float = 1f,
    /** Include in the totals row. */
    val total: Boolean = false,
) {
    val headerWithUnit: String get() = if (unit.isNullOrEmpty()) header else "$header ($unit)"
}

sealed interface CellValue {
    data class Text(val text: String) : CellValue
    data class Num(val value: BigDecimal?) : CellValue
    data class Day(val date: LocalDate) : CellValue
    data object Empty : CellValue

    companion object {
        fun of(v: Any?): CellValue = when (v) {
            null -> Empty
            is CellValue -> v
            is BigDecimal -> Num(v)
            is Int -> Num(BigDecimal(v))
            is Long -> Num(BigDecimal(v))
            is LocalDate -> Day(v)
            else -> Text(v.toString())
        }
    }
}

sealed interface Section {
    val title: String

    data class Table(
        override val title: String,
        val columns: List<Column>,
        val rows: List<List<CellValue>>,
        val showTotals: Boolean = false,
        val note: String? = null,
        /** Excel sheet name (≤ 31 chars, unique per workbook). */
        val sheetName: String = title.take(31),
    ) : Section {
        init {
            rows.forEach { require(it.size == columns.size) { "Row has ${it.size} cells; table '$title' has ${columns.size} columns" } }
        }

        /** Totals per column (null where the column is not totalled). Computed once so PDF and Excel print the same figure. */
        val totals: List<BigDecimal?> by lazy {
            columns.mapIndexed { i, c ->
                if (!c.total) null else rows.fold(BigDecimal.ZERO) { a, r -> a + ((r[i] as? CellValue.Num)?.value ?: BigDecimal.ZERO) }
            }
        }
    }

    data class Chart(override val title: String, val spec: ChartSpec) : Section

    data class Text(override val title: String, val paragraphs: List<String>) : Section

    data class KeyValues(override val title: String, val pairs: List<Pair<String, String>>) : Section
}

data class KpiCell(val label: String, val value: String, val note: String? = null)

data class ApprovalLine(val action: String, val by: String, val at: Instant, val reason: String?)

data class ReportDocument(
    val type: ReportType,
    val title: String,
    val subtitle: String?,
    val period: DateRange,
    val generatedAt: Instant,
    val generatedBy: String,
    val filters: List<Pair<String, String>>,
    val kpis: List<KpiCell>,
    val sections: List<Section>,
    val definitions: List<Pair<String, String>>,
    val approvals: List<ApprovalLine> = emptyList(),
    val remarks: List<String> = emptyList(),
) {
    val landscape: Boolean get() = type.landscape
    val tables: List<Section.Table> get() = sections.filterIsInstance<Section.Table>()
}
