package rw.plasmaco.ops.domain.analytics

import rw.plasmaco.ops.domain.common.DateRange
import rw.plasmaco.ops.domain.common.Decimals
import rw.plasmaco.ops.domain.common.Id
import rw.plasmaco.ops.domain.inventory.InventoryTransaction
import rw.plasmaco.ops.domain.inventory.Ledger
import rw.plasmaco.ops.domain.inventory.MovementGroup
import rw.plasmaco.ops.domain.inventory.StockStatus
import rw.plasmaco.ops.domain.inventory.StockStatusRules
import rw.plasmaco.ops.domain.inventory.TxnStatus
import rw.plasmaco.ops.domain.inventory.TxnType
import rw.plasmaco.ops.domain.master.ItemKind
import rw.plasmaco.ops.domain.master.MasterData
import rw.plasmaco.ops.domain.metrics.Metric
import rw.plasmaco.ops.domain.metrics.MetricValue
import rw.plasmaco.ops.domain.metrics.ProductionMetrics
import rw.plasmaco.ops.domain.metrics.ProductionSummary
import rw.plasmaco.ops.domain.production.DowntimeMath
import rw.plasmaco.ops.domain.production.ProductionReport
import rw.plasmaco.ops.domain.production.ReportStatus
import rw.plasmaco.ops.domain.workflow.ApprovalRecord
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Everything the analytics, reports and exports read. One snapshot → identical numbers everywhere. */
data class DataSet(
    val master: MasterData,
    val reports: List<ProductionReport>,
    val transactions: List<InventoryTransaction>,
    val approvals: List<ApprovalRecord> = emptyList(),
    val userNames: Map<Id, String> = emptyMap(),
) {
    fun userName(id: Id?): String = id?.let { userNames[it] } ?: "—"
}

data class Filters(
    val range: DateRange,
    val lineIds: Set<Id> = emptySet(),
    val productIds: Set<Id> = emptySet(),
    val shiftIds: Set<Id> = emptySet(),
    /** Report statuses included in production figures. Default: submitted and approved. */
    val statuses: Set<ReportStatus> = setOf(ReportStatus.SUBMITTED, ReportStatus.APPROVED),
) {
    val productFiltered: Boolean get() = productIds.isNotEmpty()

    /** Filter summary printed on reports. Stock reports pass [production] = false: lines, shifts and report status do not apply. */
    fun describe(master: MasterData, production: Boolean = true): List<Pair<String, String>> = buildList {
        add("Period" to "${range.start} to ${range.end}")
        if (production) add("Lines" to if (lineIds.isEmpty()) "All" else lineIds.mapNotNull { master.line(it)?.name }.joinToString())
        add("Products" to if (productIds.isEmpty()) "All" else productIds.mapNotNull { master.product(it)?.name }.joinToString())
        if (production) add("Shifts" to if (shiftIds.isEmpty()) "All" else shiftIds.mapNotNull { master.shift(it)?.name }.joinToString())
        if (production) add("Report status" to statuses.joinToString { it.label })
    }
}

data class AlertThresholds(
    val minAchievementPct: BigDecimal = BigDecimal(85),
    val maxRejectionPct: BigDecimal = BigDecimal(5),
    val maxDowntimeMinutesPerReport: Long = 60,
    val pendingApprovalHours: Long = 24,
    val workingDays: Set<DayOfWeek> = DayOfWeek.entries.toSet() - DayOfWeek.SUNDAY,
)

enum class AlertType(val label: String) {
    LOW_STOCK("Stock"), UNDERPERFORMANCE("Underperformance"), HIGH_REJECTION("High rejection"),
    EXCESSIVE_DOWNTIME("Downtime"), MISSING_REPORT("Missing report"), PENDING_APPROVAL("Pending approval"),
    SYNC_FAILURE("Sync"), MATERIAL_VARIANCE("Material variance"),
}

enum class AlertSeverity { CRITICAL, WARNING, INFO }

data class Alert(
    val key: String,
    val type: AlertType,
    val severity: AlertSeverity,
    val title: String,
    val message: String,
    val date: LocalDate?,
    val target: DrillTarget? = null,
    val reportId: Id? = null,
)

data class Kpi(val metricKey: String, val label: String, val value: MetricValue, val definition: String)

data class DashboardSnapshot(
    val filters: Filters,
    val summary: ProductionSummary,
    val kpis: List<Kpi>,
    val charts: Map<String, ChartSpec>,
    val alerts: List<Alert>,
)

object Analytics {
    private val dayLabel = DateTimeFormatter.ofPattern("d MMM")

    /** Reports in scope, with entries narrowed to the product filter. Materials and downtime cannot be split by product. */
    fun scopedReports(data: DataSet, f: Filters): List<ProductionReport> =
        data.reports.asSequence()
            .filter { it.date in f.range && it.status in f.statuses }
            .filter { f.lineIds.isEmpty() || it.lineId in f.lineIds }
            .filter { f.shiftIds.isEmpty() || it.shiftId in f.shiftIds }
            .mapNotNull { r ->
                if (!f.productFiltered) r else {
                    val entries = r.entries.filter { it.productId in f.productIds }
                    if (entries.isEmpty()) null else r.copy(entries = entries, materials = emptyList(), downtime = emptyList())
                }
            }.toList()

    fun dashboard(data: DataSet, f: Filters, today: LocalDate, thresholds: AlertThresholds = AlertThresholds()): DashboardSnapshot {
        val reports = scopedReports(data, f)
        val summary = ProductionMetrics.summarize(reports, data.master)
        val live = Ledger(data.transactions)
        val flows = Ledger(data.transactions, includeHistorical = true)
        val period = f.range

        fun flow(kind: ItemKind, group: MovementGroup, types: Set<TxnType>? = null): BigDecimal =
            flows.items(kind).filter { kind != ItemKind.FINISHED_GOOD || !f.productFiltered || it.second in f.productIds }
                .sumOf { (k, id) ->
                    flows.statement(k, id, period).lines.filter { it.txn.type.group == group && (types == null || it.txn.type in types) }
                        .fold(BigDecimal.ZERO) { a, l -> a + l.txn.quantity }
                }

        val fgItems = data.master.products.filter { it.active && (!f.productFiltered || it.id in f.productIds) }
        val rmItems = data.master.materials.filter { it.active }
        val fgStock = fgItems.fold(BigDecimal.ZERO) { a, p -> a + live.balance(ItemKind.FINISHED_GOOD, p.id) }
        val rmStock = rmItems.fold(BigDecimal.ZERO) { a, m -> a + live.balance(ItemKind.RAW_MATERIAL, m.id) }
        val stockAlerts = stockAlerts(data, live)
        val pendingReports = data.reports.count { it.status == ReportStatus.SUBMITTED }
        val pendingTxns = data.transactions.count { it.status == TxnStatus.PENDING_APPROVAL }
        val hasInventory = data.transactions.any { it.affectsBalance }

        val kpis = listOf(
            kpi(Metric.GROSS_OUTPUT, summary),
            kpi(Metric.TARGET, summary),
            kpi(Metric.TARGET_ACHIEVEMENT, summary),
            kpi(Metric.GOOD_OUTPUT, summary),
            kpi(Metric.REJECTED_OUTPUT, summary),
            kpi(Metric.DOWNTIME, summary).let { if (f.productFiltered) it.copy(value = MetricValue.NoData("Not attributable to a single product")) else it },
            Kpi("rm_consumption", "Raw material consumption",
                when {
                    f.productFiltered -> MetricValue.NoData("Not attributable to a single product")
                    reports.none { it.materials.isNotEmpty() } -> MetricValue.NoData("No material issue recorded")
                    else -> MetricValue.Value(summary.materialConsumedKg, "kg")
                }, Metric.ACTUAL_CONSUMPTION.definition),
            Kpi("fg_receipts", "Finished goods receipts", if (hasInventory) MetricValue.Value(flow(ItemKind.FINISHED_GOOD, MovementGroup.RECEIPT), "m") else MetricValue.NoData("No stock movements"), "Production receipts posted to the store in the period"),
            Kpi("fg_dispatches", "Finished goods dispatches", if (hasInventory) MetricValue.Value(flow(ItemKind.FINISHED_GOOD, MovementGroup.ISSUE).negate(), "m") else MetricValue.NoData("No stock movements"), "Dispatches posted in the period"),
            Kpi("fg_stock", "Finished goods stock", if (hasInventory) MetricValue.Value(fgStock, "m") else MetricValue.NoData("No stock movements"), "Current live balance, all finished goods"),
            Kpi("rm_stock", "Raw material stock", if (hasInventory) MetricValue.Value(rmStock, "kg") else MetricValue.NoData("No stock movements"), "Current live balance, all raw materials"),
            Kpi("pending_approvals", "Pending approvals", MetricValue.Value(BigDecimal(pendingReports + pendingTxns), "items"), "Submitted reports plus adjustments awaiting approval"),
            Kpi("reorder_alerts", "Reorder alerts", MetricValue.Value(BigDecimal(stockAlerts.size), "items"), "Items out of stock, below minimum or at reorder point"),
        )

        val charts = listOf(
            dailyTrend(reports, data, f), outputByProduct(reports, data), outputByLine(reports, data),
            materialTrend(reports, data, f), goodVsRejected(reports, f), inventoryMovements(data, f, ItemKind.FINISHED_GOOD),
            inventoryMovements(data, f, ItemKind.RAW_MATERIAL), achievementByShift(reports, data), achievementByProduct(reports, data),
            downtimeByMachineAndCause(reports, data, f),
        ).associateBy { it.id }

        val alerts = stockAlerts + productionAlerts(reports, data, thresholds) + missingReports(data, f, today, thresholds) + pendingAlerts(data, today, thresholds)
        return DashboardSnapshot(f, summary, kpis, charts, alerts.sortedWith(compareBy<Alert> { it.severity }.thenByDescending { it.date }))
    }

    private fun kpi(m: Metric, s: ProductionSummary) = Kpi(m.name.lowercase(), m.label, s.value(m), m.definition)

    // ---------------------------------------------------------------- charts

    fun dailyTrend(reports: List<ProductionReport>, data: DataSet, f: Filters): ChartSpec {
        val days = f.range.days().toList()
        val byDay = reports.groupBy { it.date }
        val good = days.map { d -> byDay[d]?.let { ProductionMetrics.summarize(it, data.master).good } }
        val target = days.map { d -> byDay[d]?.let { ProductionMetrics.summarize(it, data.master).target.takeIf { t -> t.signum() > 0 } } }
        return ChartSpec(
            "daily_trend", "Daily good output vs target", ChartKind.COMBO_BAR_LINE, "m",
            days.map { it.format(dayLabel) },
            listOf(Series("Good output", good, SeriesRole.PRIMARY), Series("Target", target, SeriesRole.TARGET, asLine = true)),
            drill = days.map { DrillTarget.Day(it) },
            emptyReason = if (reports.isEmpty()) "No reports in this period" else null,
        )
    }

    fun outputByProduct(reports: List<ProductionReport>, data: DataSet, top: Int = 8): ChartSpec {
        val totals = reports.flatMap { it.entries }.groupBy { it.productId }.mapValues { (_, es) -> es.fold(BigDecimal.ZERO) { a, e -> a + e.good } }
            .entries.sortedByDescending { it.value }
        val shown = totals.take(top)
        val other = totals.drop(top).fold(BigDecimal.ZERO) { a, e -> a + e.value }
        val labels = shown.map { data.master.product(it.key)?.let { p -> shortName(p.name) } ?: "Unknown" } + (if (other.signum() > 0) listOf("Other (${totals.size - top})") else emptyList())
        val values: List<BigDecimal?> = shown.map { it.value } + (if (other.signum() > 0) listOf(other) else emptyList())
        return ChartSpec(
            "by_product", "Good output by product", ChartKind.HBAR, "m", labels,
            listOf(Series("Good output", values, SeriesRole.PRIMARY)),
            drill = shown.map { DrillTarget.Product(it.key) } + (if (other.signum() > 0) listOf(null) else emptyList()),
            emptyReason = if (totals.isEmpty()) "No output in this period" else null,
        )
    }

    fun outputByLine(reports: List<ProductionReport>, data: DataSet): ChartSpec {
        val byLine = reports.groupBy { it.lineId }.mapValues { (_, rs) -> ProductionMetrics.summarize(rs, data.master) }.entries.sortedByDescending { it.value.good }
        return ChartSpec(
            "by_line", "Good output by line", ChartKind.HBAR, "m",
            byLine.map { data.master.line(it.key)?.name ?: "Unknown line" },
            listOf(Series("Good output", byLine.map { it.value.good }, SeriesRole.PRIMARY)),
            drill = byLine.map { DrillTarget.Line(it.key) },
            emptyReason = if (byLine.isEmpty()) "No output in this period" else null,
        )
    }

    fun materialTrend(reports: List<ProductionReport>, data: DataSet, f: Filters): ChartSpec {
        val days = f.range.days().toList()
        val byDay = reports.groupBy { it.date }
        val consumed = days.map { d -> byDay[d]?.takeIf { rs -> rs.any { it.materials.isNotEmpty() } }?.let { rs -> rs.flatMap { it.materials }.fold(BigDecimal.ZERO) { a, m -> a + m.consumed } } }
        val theoretical = days.map { d -> byDay[d]?.let { ProductionMetrics.summarize(it, data.master).theoreticalResinKg?.setScale(1, RoundingMode.HALF_UP) } }
        return ChartSpec(
            "material_trend", "Raw material consumed vs theoretical", ChartKind.LINE, "kg", days.map { it.format(dayLabel) },
            listOf(Series("Consumed", consumed, SeriesRole.CATEGORY_1, asLine = true), Series("Theoretical resin", theoretical, SeriesRole.CATEGORY_2, asLine = true)),
            drill = days.map { DrillTarget.Day(it) },
            emptyReason = when {
                f.productFiltered -> "Material use cannot be split by product"
                consumed.all { it == null } -> "No material issue recorded in this period"
                else -> null
            },
        )
    }

    fun goodVsRejected(reports: List<ProductionReport>, f: Filters): ChartSpec {
        val days = f.range.days().toList()
        val byDay = reports.groupBy { it.date }
        fun sum(d: LocalDate, pick: (rw.plasmaco.ops.domain.production.ProductionEntry) -> BigDecimal) =
            byDay[d]?.let { rs -> rs.flatMap { it.entries }.fold(BigDecimal.ZERO) { a, e -> a + pick(e) } }
        return ChartSpec(
            "good_vs_rejected", "Good vs rejected output", ChartKind.STACKED_BAR, "m", days.map { it.format(dayLabel) },
            listOf(Series("Good", days.map { sum(it) { e -> e.good } }, SeriesRole.PRIMARY), Series("Rejected", days.map { sum(it) { e -> e.rejected } }, SeriesRole.NEGATIVE)),
            drill = days.map { DrillTarget.Day(it) },
            emptyReason = if (reports.isEmpty()) "No reports in this period" else null,
        )
    }

    fun inventoryMovements(data: DataSet, f: Filters, kind: ItemKind): ChartSpec {
        val ledger = Ledger(data.transactions, includeHistorical = true)
        val days = f.range.days().toList()
        val items = ledger.items(kind).filter { kind != ItemKind.FINISHED_GOOD || !f.productFiltered || it.second in f.productIds }
        val statements = items.map { (k, id) -> ledger.statement(k, id, f.range) }
        val lines = statements.flatMap { it.lines }
        val unit = if (kind == ItemKind.FINISHED_GOOD) "m" else "kg"
        fun dayTotal(d: LocalDate, positive: Boolean): BigDecimal? {
            val l = lines.filter { it.txn.date == d && (it.txn.quantity.signum() > 0) == positive && it.txn.type.group != MovementGroup.OPENING }
            return if (l.isEmpty()) null else l.fold(BigDecimal.ZERO) { a, x -> a + x.txn.quantity }
        }
        val opening = statements.fold(BigDecimal.ZERO) { a, s -> a + s.opening }
        var running = opening
        val balance = days.map { d -> running += lines.filter { it.txn.date == d }.fold(BigDecimal.ZERO) { a, x -> a + x.txn.quantity }; running }
        val title = if (kind == ItemKind.FINISHED_GOOD) "Finished goods: receipts, issues and balance" else "Raw materials: receipts, issues and balance"
        return ChartSpec(
            if (kind == ItemKind.FINISHED_GOOD) "fg_movements" else "rm_movements", title, ChartKind.DIVERGING_BAR, unit, days.map { it.format(dayLabel) },
            listOf(
                Series("Receipts and returns", days.map { dayTotal(it, true) }, SeriesRole.CATEGORY_1),
                Series("Issues and dispatches", days.map { dayTotal(it, false) }, SeriesRole.CATEGORY_2),
                Series("Closing balance", balance, SeriesRole.TARGET, asLine = true),
            ),
            drill = days.map { DrillTarget.Day(it) },
            emptyReason = if (items.isEmpty()) "No stock movements recorded" else null,
        )
    }

    fun achievementByShift(reports: List<ProductionReport>, data: DataSet): ChartSpec {
        val rows = reports.groupBy { it.shiftId }.mapValues { (_, rs) -> ProductionMetrics.summarize(rs, data.master) }
            .entries.filter { it.value.target.signum() > 0 }.sortedBy { data.master.shift(it.key)?.start }
        return ChartSpec(
            "achievement_by_shift", "Target achievement by shift", ChartKind.HBAR, "%",
            rows.map { data.master.shift(it.key)?.name ?: "Unknown shift" },
            listOf(Series("Achievement", rows.map { it.value.value(Metric.TARGET_ACHIEVEMENT).orNull() }, SeriesRole.PRIMARY)),
            drill = rows.map { DrillTarget.Shift(it.key) }, reference = BigDecimal(100), referenceLabel = "Target",
            emptyReason = if (rows.isEmpty()) "No targets set for this period" else null,
        )
    }

    fun achievementByProduct(reports: List<ProductionReport>, data: DataSet, top: Int = 10): ChartSpec {
        val byProduct = reports.flatMap { it.entries }.filter { it.target != null && it.target.signum() > 0 }.groupBy { it.productId }
            .mapValues { (_, es) -> Decimals.percent(es.fold(BigDecimal.ZERO) { a, e -> a + e.good }, es.fold(BigDecimal.ZERO) { a, e -> a + e.target!! }) }
            .entries.sortedBy { it.value }.take(top)
        return ChartSpec(
            "achievement_by_product", "Target achievement by product (lowest first)", ChartKind.HBAR, "%",
            byProduct.map { data.master.product(it.key)?.let { p -> shortName(p.name) } ?: "Unknown" },
            listOf(Series("Achievement", byProduct.map { it.value }, SeriesRole.PRIMARY)),
            drill = byProduct.map { DrillTarget.Product(it.key) }, reference = BigDecimal(100), referenceLabel = "Target",
            emptyReason = if (byProduct.isEmpty()) "No targets set for this period" else null,
        )
    }

    fun downtimeByMachineAndCause(reports: List<ProductionReport>, data: DataSet, f: Filters, maxCauses: Int = 4): ChartSpec {
        val events = reports.flatMap { it.downtime }.filter { it.end != null }
        val machines = events.groupBy { it.machineId }.entries.sortedByDescending { (_, es) -> DowntimeMath.mergedMinutes(es) }
        val causeTotals = events.groupBy { it.categoryId }.mapValues { (_, es) -> es.sumOf { it.minutes ?: 0L } }.entries.sortedByDescending { it.value }
        val shownCauses = causeTotals.take(maxCauses).map { it.key }
        val roles = listOf(SeriesRole.CATEGORY_1, SeriesRole.CATEGORY_2, SeriesRole.CATEGORY_3, SeriesRole.CATEGORY_4, SeriesRole.CATEGORY_5)
        val series = shownCauses.mapIndexed { i, cause ->
            Series(cause?.let { data.master.downtimeCategory(it)?.name } ?: "Uncategorised",
                machines.map { (_, es) -> es.filter { it.categoryId == cause }.sumOf { it.minutes ?: 0L }.takeIf { it > 0 }?.let { BigDecimal(it) } },
                roles[i])
        } + if (causeTotals.size > maxCauses) listOf(Series("Other causes",
            machines.map { (_, es) -> es.filter { it.categoryId !in shownCauses }.sumOf { it.minutes ?: 0L }.takeIf { it > 0 }?.let { BigDecimal(it) } },
            SeriesRole.OTHER)) else emptyList()
        return ChartSpec(
            "downtime", "Downtime by machine and cause", ChartKind.STACKED_HBAR, "min",
            machines.map { (m, _) -> m?.let { data.master.machine(it)?.name } ?: "Line (no machine)" }, series,
            drill = machines.map { DrillTarget.Machine(it.key) },
            emptyReason = when {
                f.productFiltered -> "Downtime cannot be split by product"
                events.isEmpty() -> "No downtime recorded in this period"
                else -> null
            },
        )
    }

    // ---------------------------------------------------------------- alerts

    fun stockAlerts(data: DataSet, live: Ledger = Ledger(data.transactions)): List<Alert> {
        if (data.transactions.none { it.affectsBalance && !it.historical }) return emptyList()
        val out = mutableListOf<Alert>()
        data.master.products.filter { it.active }.forEach { p ->
            val bal = live.balance(ItemKind.FINISHED_GOOD, p.id)
            val t = data.master.threshold(ItemKind.FINISHED_GOOD, p.id)
            val status = StockStatusRules.classify(bal, t?.minimum, t?.reorderPoint)
            // Without a threshold, zero stock of a product nobody has made is not news; alert only on negative or below a set minimum.
            if (status != StockStatus.IN_STOCK && (t != null || bal.signum() < 0)) out += stockAlert(ItemKind.FINISHED_GOOD, p.id, p.name, bal, "m", status)
        }
        data.master.materials.filter { it.active }.forEach { m ->
            val bal = live.balance(ItemKind.RAW_MATERIAL, m.id)
            val t = data.master.threshold(ItemKind.RAW_MATERIAL, m.id)
            val status = StockStatusRules.classify(bal, t?.minimum, t?.reorderPoint)
            if (status != StockStatus.IN_STOCK) out += stockAlert(ItemKind.RAW_MATERIAL, m.id, m.name, bal, "kg", status)
        }
        return out
    }

    private fun stockAlert(kind: ItemKind, id: Id, name: String, bal: BigDecimal, unit: String, status: StockStatus) = Alert(
        key = "stock:$kind:$id", type = AlertType.LOW_STOCK,
        severity = if (status == StockStatus.OUT_OF_STOCK || status == StockStatus.LOW_STOCK) AlertSeverity.CRITICAL else AlertSeverity.WARNING,
        title = "${status.label}: $name", message = "Balance ${bal.setScale(1, RoundingMode.HALF_UP).toPlainString()} $unit",
        date = null, target = DrillTarget.Item(kind, id),
    )

    fun productionAlerts(reports: List<ProductionReport>, data: DataSet, t: AlertThresholds): List<Alert> = reports.flatMap { r ->
        val s = ProductionMetrics.summarize(listOf(r), data.master)
        val line = data.master.line(r.lineId)?.name ?: "line"
        buildList {
            s.value(Metric.TARGET_ACHIEVEMENT).orNull()?.let {
                if (it < t.minAchievementPct) add(Alert("under:${r.id}", AlertType.UNDERPERFORMANCE, AlertSeverity.WARNING,
                    "Below target: ${r.reference}", "$line reached ${it.toPlainString()}% of target", r.date, DrillTarget.Day(r.date), r.id))
            }
            s.value(Metric.REJECTION_RATE).orNull()?.let {
                if (it > t.maxRejectionPct) add(Alert("reject:${r.id}", AlertType.HIGH_REJECTION, AlertSeverity.WARNING,
                    "High rejection: ${r.reference}", "${it.toPlainString()}% of output rejected on $line", r.date, DrillTarget.Day(r.date), r.id))
            }
            if (s.downtimeMinutes > t.maxDowntimeMinutesPerReport) add(Alert("down:${r.id}", AlertType.EXCESSIVE_DOWNTIME, AlertSeverity.WARNING,
                "Downtime ${s.downtimeMinutes} min: ${r.reference}", "$line stopped for ${s.downtimeMinutes} minutes", r.date, DrillTarget.Day(r.date), r.id))
            s.value(Metric.MATERIAL_VARIANCE_PCT).orNull()?.let {
                if (it.abs() > BigDecimal(10)) add(Alert("matvar:${r.id}", AlertType.MATERIAL_VARIANCE, AlertSeverity.INFO,
                    "Material variance ${it.toPlainString()}%: ${r.reference}", "Resin consumed differs from theoretical", r.date, DrillTarget.Day(r.date), r.id))
            }
        }
    }

    /** A report is missing when an active line has no report for an active shift on a working day up to yesterday. */
    fun missingReports(data: DataSet, f: Filters, today: LocalDate, t: AlertThresholds): List<Alert> {
        val lines = data.master.lines.filter { it.active && (f.lineIds.isEmpty() || it.id in f.lineIds) }
        val shifts = data.master.shifts.filter { it.active && (f.shiftIds.isEmpty() || it.id in f.shiftIds) }
        if (lines.isEmpty() || shifts.isEmpty()) return emptyList()
        val existing = data.reports.filter { it.status != ReportStatus.REJECTED }.map { Triple(it.date, it.lineId, it.shiftId) }.toSet()
        val lastDay = minOf(f.range.end, today.minusDays(1))
        if (lastDay.isBefore(f.range.start)) return emptyList()
        return DateRange(f.range.start, lastDay).days().filter { it.dayOfWeek in t.workingDays }.flatMap { d ->
            lines.flatMap { l -> shifts.mapNotNull { s ->
                if (Triple(d, l.id, s.id) in existing) null else Alert("missing:$d:${l.id}:${s.id}", AlertType.MISSING_REPORT, AlertSeverity.WARNING,
                    "No report: ${l.name}, ${s.name}", "Nothing recorded for ${d.format(dayLabel)}", d, DrillTarget.Day(d))
            } }
        }.toList()
    }

    fun pendingAlerts(data: DataSet, today: LocalDate, t: AlertThresholds): List<Alert> =
        data.reports.filter { it.status == ReportStatus.SUBMITTED }.map { r ->
            val waitingDays = java.time.temporal.ChronoUnit.DAYS.between(r.date, today)
            Alert("pending:${r.id}", AlertType.PENDING_APPROVAL, if (waitingDays * 24 > t.pendingApprovalHours) AlertSeverity.WARNING else AlertSeverity.INFO,
                "Awaiting approval: ${r.reference}", "Report for ${r.date.format(dayLabel)}, waiting $waitingDays day(s)", r.date, reportId = r.id)
        }

    /** "HDPE PIPE PE-PE100 DN110 PN10" → "DN110 PN10" for axis labels; the full name stays in tooltips and tables. */
    fun shortName(name: String): String = Regex("""DN\s*\d+\s*PN\s*[\d.]+""", RegexOption.IGNORE_CASE).find(name)?.value?.replace(Regex("\\s+"), " ") ?: name
}
