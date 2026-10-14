package rw.plasmaco.ops.domain.reports

import rw.plasmaco.ops.domain.analytics.Analytics
import rw.plasmaco.ops.domain.analytics.DataSet
import rw.plasmaco.ops.domain.analytics.Filters
import rw.plasmaco.ops.domain.common.Decimals
import rw.plasmaco.ops.domain.common.Id
import rw.plasmaco.ops.domain.inventory.Ledger
import rw.plasmaco.ops.domain.inventory.StockStatusRules
import rw.plasmaco.ops.domain.inventory.TxnStatus
import rw.plasmaco.ops.domain.master.ItemKind
import rw.plasmaco.ops.domain.metrics.Metric
import rw.plasmaco.ops.domain.metrics.MetricValue
import rw.plasmaco.ops.domain.metrics.ProductionMetrics
import rw.plasmaco.ops.domain.metrics.ProductionSummary
import rw.plasmaco.ops.domain.production.DowntimeMath
import rw.plasmaco.ops.domain.production.ProductionReport
import rw.plasmaco.ops.domain.units.Formatting
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Builds every report from one [DataSet] snapshot. PDF and Excel both render the same [ReportDocument], and the
 * dashboard uses the same [ProductionMetrics]/[Ledger] functions, so the three always agree.
 */
object ReportBuilder {
    private val time = DateTimeFormatter.ofPattern("HH:mm")
    private val STOCK_TYPES = setOf(ReportType.FINISHED_GOODS_STOCK, ReportType.RAW_MATERIAL_STOCK, ReportType.INVENTORY_MOVEMENT)
    private val dateTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    data class Request(
        val type: ReportType,
        val filters: Filters,
        val generatedBy: String,
        val generatedAt: Instant,
        val zone: ZoneId = ZoneId.of("Africa/Kigali"),
        /** For a single-report export from the report detail screen. */
        val reportId: Id? = null,
    )

    fun build(req: Request, data: DataSet): ReportDocument = when (req.type) {
        ReportType.DAILY_PRODUCTION -> daily(req, data)
        ReportType.SHIFT_PRODUCTION -> shift(req, data)
        ReportType.PRODUCTION_PERFORMANCE -> performance(req, data)
        ReportType.RAW_MATERIAL_CONSUMPTION -> consumption(req, data)
        ReportType.RAW_MATERIAL_STOCK -> stock(req, data, ItemKind.RAW_MATERIAL)
        ReportType.FINISHED_GOODS_STOCK -> stock(req, data, ItemKind.FINISHED_GOOD)
        ReportType.INVENTORY_MOVEMENT -> movements(req, data)
        ReportType.QUALITY_CONTROL -> quality(req, data)
        ReportType.DOWNTIME -> downtime(req, data)
        ReportType.PRODUCTION_MATERIAL_VARIANCE -> variance(req, data)
        ReportType.MANAGEMENT_SUMMARY -> management(req, data)
    }

    // ------------------------------------------------------------------ helpers

    private fun doc(req: Request, data: DataSet, kpis: List<KpiCell>, sections: List<Section>, definitions: List<Metric>, subtitle: String? = null, approvals: List<ApprovalLine> = emptyList(), remarks: List<String> = emptyList()) =
        ReportDocument(
            type = req.type, title = req.type.title, subtitle = subtitle, period = req.filters.range, generatedAt = req.generatedAt,
            generatedBy = req.generatedBy, filters = req.filters.describe(data.master, production = req.type !in STOCK_TYPES), kpis = kpis, sections = sections,
            definitions = definitions.map { "${it.label} (${it.unit})" to it.definition }, approvals = approvals, remarks = remarks,
        )

    fun metricText(v: MetricValue, decimals: Int = 1): String = when (v) {
        is MetricValue.Value -> when (v.unit) {
            "%" -> Formatting.number(v.value, decimals) + "%"
            "min" -> Formatting.number(v.value, 0) + " min"
            else -> Formatting.number(v.value, decimals) + " " + v.unit
        }
        is MetricValue.NoData -> "No data"
    }

    private fun kpi(s: ProductionSummary, m: Metric, decimals: Int = 1): KpiCell {
        val v = s.value(m)
        return KpiCell(m.label, metricText(v, decimals), (v as? MetricValue.NoData)?.reason)
    }

    private fun num(v: BigDecimal?) = CellValue.Num(v)
    private fun text(s: String?) = if (s.isNullOrBlank()) CellValue.Empty else CellValue.Text(s)

    private fun productionKpis(s: ProductionSummary) = listOf(
        kpi(s, Metric.GROSS_OUTPUT), kpi(s, Metric.GOOD_OUTPUT), kpi(s, Metric.REJECTED_OUTPUT), kpi(s, Metric.TARGET),
        kpi(s, Metric.TARGET_ACHIEVEMENT), kpi(s, Metric.REJECTION_RATE, 2), kpi(s, Metric.DOWNTIME), kpi(s, Metric.ACTUAL_CONSUMPTION),
    )

    private fun reportsIn(req: Request, data: DataSet): List<ProductionReport> =
        if (req.reportId != null) data.reports.filter { it.id == req.reportId } else Analytics.scopedReports(data, req.filters)

    private val entryColumns = listOf(
        Column("Report", weight = 1.5f), Column("Date", ColumnKind.DATE, weight = 1.1f), Column("Line"), Column("Shift"),
        Column("Product", weight = 2.4f), Column("Machine"), Column("Target", ColumnKind.NUMBER, "m", 1, total = true),
        Column("Gross", ColumnKind.NUMBER, "m", 1, total = true), Column("Good", ColumnKind.NUMBER, "m", 1, total = true),
        Column("Rejected", ColumnKind.NUMBER, "m", 1, total = true), Column("Achievement", ColumnKind.PERCENT, "%", 1),
        Column("Rejection", ColumnKind.PERCENT, "%", 2), Column("Batch"),
    )

    private fun entryRows(reports: List<ProductionReport>, data: DataSet): List<List<CellValue>> = reports.sortedWith(compareBy({ it.date }, { it.reference })).flatMap { r ->
        r.entries.map { e ->
            listOf(
                text(r.reference), CellValue.Day(r.date), text(data.master.line(r.lineId)?.name), text(data.master.shift(r.shiftId)?.name),
                text(data.master.product(e.productId)?.name), text(e.machineId?.let { data.master.machine(it)?.name }),
                num(e.target), num(e.gross), num(e.good), num(e.rejected),
                num(ProductionMetrics.entryAchievement(e.good, e.target)), num(Decimals.percent(e.rejected, e.gross, 2)), text(e.batch),
            )
        }
    }

    private val materialColumns = listOf(
        Column("Report", weight = 1.5f), Column("Date", ColumnKind.DATE, weight = 1.1f), Column("Material", weight = 2f),
        Column("Issued", ColumnKind.NUMBER, "kg", 2, total = true), Column("Returned", ColumnKind.NUMBER, "kg", 2, total = true),
        Column("Line opening", ColumnKind.NUMBER, "kg", 2), Column("Line closing", ColumnKind.NUMBER, "kg", 2),
        Column("Consumed", ColumnKind.NUMBER, "kg", 2, total = true), Column("Scrap", ColumnKind.NUMBER, "kg", 2, total = true), Column("Batch"),
    )

    private fun materialRows(reports: List<ProductionReport>, data: DataSet) = reports.sortedBy { it.date }.flatMap { r ->
        r.materials.map { m ->
            listOf(text(r.reference), CellValue.Day(r.date), text(data.master.material(m.materialId)?.name), num(m.issued), num(m.returned),
                num(m.lineOpening), num(m.lineClosing), num(m.consumed), num(m.scrap), text(m.batch))
        }
    }

    private val downtimeColumns = listOf(
        Column("Report", weight = 1.5f), Column("Date", ColumnKind.DATE, weight = 1.1f), Column("Machine", weight = 1.4f), Column("Cause", weight = 1.6f),
        Column("Start"), Column("End"), Column("Duration", ColumnKind.NUMBER, "min", 0, total = true), Column("Corrective action", weight = 2.2f),
    )

    private fun downtimeRows(reports: List<ProductionReport>, data: DataSet) = reports.sortedBy { it.date }.flatMap { r ->
        r.downtime.sortedBy { it.start }.map { d ->
            listOf(text(r.reference), CellValue.Day(r.date), text(d.machineId?.let { data.master.machine(it)?.name } ?: "Line"),
                text(d.categoryId?.let { data.master.downtimeCategory(it)?.name } ?: d.reason), text(d.start.format(dateTime)),
                text(d.end?.format(dateTime) ?: "open"), num(d.minutes?.let { BigDecimal(it) }), text(d.correctiveAction))
        }
    }

    private val qcColumns = listOf(
        Column("Report", weight = 1.5f), Column("Time", weight = 1.3f), Column("Product", weight = 2.2f), Column("Batch"),
        Column("Sample", ColumnKind.NUMBER, "m", 1, total = true), Column("Accepted", ColumnKind.NUMBER, "m", 1, total = true),
        Column("Rejected", ColumnKind.NUMBER, "m", 1, total = true), Column("Defect", weight = 1.4f), Column("Result"), Column("Inspector"),
        Column("Corrective action", weight = 2f),
    )

    private fun qcRows(reports: List<ProductionReport>, data: DataSet) = reports.sortedBy { it.date }.flatMap { r ->
        r.inspections.sortedBy { it.time }.map { q ->
            listOf(text(r.reference), text(q.time.format(dateTime)), text(data.master.product(q.productId)?.name), text(q.batch), num(q.sampleQty),
                num(q.accepted), num(q.rejected), text(q.defectCategoryId?.let { id -> data.master.defectCategories.firstOrNull { it.id == id }?.name }),
                text(q.result.label), text(data.userName(q.inspectorId).takeIf { q.inspectorId != null }), text(q.correctiveAction))
        }
    }

    // ------------------------------------------------------------------ reports

    private fun daily(req: Request, data: DataSet): ReportDocument {
        val reports = reportsIn(req, data)
        val s = ProductionMetrics.summarize(reports, data.master)
        val headers = Section.Table("Reports", listOf(
            Column("Reference", weight = 1.6f), Column("Date", ColumnKind.DATE), Column("Line"), Column("Shift"), Column("Operator", weight = 1.4f),
            Column("Supervisor", weight = 1.4f), Column("Start"), Column("End"), Column("Status", weight = 1.3f), Column("Version", ColumnKind.NUMBER), Column("Remarks", weight = 2.5f),
        ), reports.sortedBy { it.reference }.map { r ->
            listOf(text(r.reference), CellValue.Day(r.date), text(data.master.line(r.lineId)?.name), text(data.master.shift(r.shiftId)?.name),
                text(data.userName(r.operatorId)), text(r.supervisorId?.let(data::userName)), text(r.startTime?.format(time)), text(r.endTime?.format(time)),
                text(r.status.label), num(BigDecimal(r.version)), text(r.remarks))
        })
        val handover = reports.filter { it.handover != null && !it.handover.isEmpty }.flatMap { r ->
            val h = r.handover!!
            listOfNotNull(
                h.outstandingProduction.takeIf { it.isNotBlank() }?.let { "${r.reference} — Outstanding production: $it" },
                h.machineCondition.takeIf { it.isNotBlank() }?.let { "${r.reference} — Machine condition: $it" },
                h.pendingMaintenance.takeIf { it.isNotBlank() }?.let { "${r.reference} — Pending maintenance: $it" },
                h.materialShortages.takeIf { it.isNotBlank() }?.let { "${r.reference} — Material shortages: $it" },
                h.qualityIssues.takeIf { it.isNotBlank() }?.let { "${r.reference} — Quality issues: $it" },
                h.safetyIncidents.takeIf { it.isNotBlank() }?.let { "${r.reference} — Safety: $it" },
                h.instructions.takeIf { it.isNotBlank() }?.let { "${r.reference} — Next shift: $it" },
            )
        }
        val approvals = data.approvals.filter { a -> reports.any { it.id == a.reportId } }.sortedBy { it.at }
            .map { ApprovalLine("${it.action.label} (v${it.version})", it.actorName, it.at, it.reason) }
        val sections = buildList {
            add(headers)
            add(Section.Table("Production", entryColumns, entryRows(reports, data), showTotals = true))
            add(Section.Table("Materials", materialColumns, materialRows(reports, data), showTotals = true))
            add(Section.Table("Downtime", downtimeColumns, downtimeRows(reports, data), showTotals = true))
            add(Section.Table("Quality", qcColumns, qcRows(reports, data), showTotals = true))
            if (handover.isNotEmpty()) add(Section.Text("Shift handover", handover))
        }
        val subtitle = if (req.reportId != null) reports.firstOrNull()?.reference else null
        return doc(req, data, productionKpis(s), sections,
            listOf(Metric.GROSS_OUTPUT, Metric.TARGET_ACHIEVEMENT, Metric.REJECTION_RATE, Metric.ACTUAL_CONSUMPTION, Metric.DOWNTIME), subtitle, approvals,
            reports.mapNotNull { r -> r.remarks.takeIf { it.isNotBlank() }?.let { "${r.reference}: $it" } })
    }

    private fun shift(req: Request, data: DataSet): ReportDocument {
        val reports = reportsIn(req, data)
        val byShift = reports.groupBy { it.shiftId }.toSortedMap(compareBy { data.master.shift(it)?.start })
        val rows = byShift.map { (sid, rs) ->
            val s = ProductionMetrics.summarize(rs, data.master)
            listOf(text(data.master.shift(sid)?.name), num(BigDecimal(rs.size)), num(s.target.takeIf { it.signum() > 0 }), num(s.gross), num(s.good), num(s.rejected),
                num(s.value(Metric.TARGET_ACHIEVEMENT).orNull()), num(s.value(Metric.REJECTION_RATE).orNull()), num(BigDecimal(s.downtimeMinutes)))
        }
        val table = Section.Table("By shift", listOf(
            Column("Shift", weight = 1.6f), Column("Reports", ColumnKind.NUMBER, total = true), Column("Target", ColumnKind.NUMBER, "m", 1, total = true),
            Column("Gross", ColumnKind.NUMBER, "m", 1, total = true), Column("Good", ColumnKind.NUMBER, "m", 1, total = true), Column("Rejected", ColumnKind.NUMBER, "m", 1, total = true),
            Column("Achievement", ColumnKind.PERCENT, "%", 1), Column("Rejection", ColumnKind.PERCENT, "%", 2), Column("Downtime", ColumnKind.NUMBER, "min", 0, total = true),
        ), rows, showTotals = true)
        return doc(req, data, productionKpis(ProductionMetrics.summarize(reports, data.master)),
            listOf(table, Section.Chart("Target achievement by shift", Analytics.achievementByShift(reports, data)), Section.Table("Entries", entryColumns, entryRows(reports, data), showTotals = true)),
            listOf(Metric.TARGET_ACHIEVEMENT, Metric.REJECTION_RATE, Metric.DOWNTIME))
    }

    private fun performance(req: Request, data: DataSet): ReportDocument {
        val reports = reportsIn(req, data)
        fun row(label: String, rs: List<ProductionReport>): List<CellValue> {
            val s = ProductionMetrics.summarize(rs, data.master)
            return listOf(text(label), num(s.target.takeIf { it.signum() > 0 }), num(s.good), num(s.value(Metric.TARGET_ACHIEVEMENT).orNull()),
                num(s.value(Metric.TARGET_VARIANCE).orNull()), num(s.value(Metric.REJECTION_RATE).orNull()), num(s.value(Metric.AVAILABILITY).orNull()),
                num(s.value(Metric.PERFORMANCE).orNull()), num(s.value(Metric.QUALITY).orNull()), num(s.value(Metric.OEE).orNull()))
        }
        val cols = listOf(
            Column("Line / product", weight = 2.4f), Column("Target", ColumnKind.NUMBER, "m", 1, total = true), Column("Good", ColumnKind.NUMBER, "m", 1, total = true),
            Column("Achievement", ColumnKind.PERCENT, "%", 1), Column("Variance", ColumnKind.NUMBER, "m", 1, total = true), Column("Rejection", ColumnKind.PERCENT, "%", 2),
            Column("Availability", ColumnKind.PERCENT, "%", 1), Column("Performance", ColumnKind.PERCENT, "%", 1), Column("Quality", ColumnKind.PERCENT, "%", 1), Column("OEE", ColumnKind.PERCENT, "%", 1),
        )
        val byLine = reports.groupBy { it.lineId }.map { (lid, rs) -> row(data.master.line(lid)?.name ?: "Unknown line", rs) }
        val byProduct = reports.flatMap { r -> r.entries.map { e -> e.productId to r.copy(entries = listOf(e), materials = emptyList(), downtime = emptyList()) } }
            .groupBy({ it.first }, { it.second })
            .map { (pid, rs) -> row(data.master.product(pid)?.name ?: "Unknown", rs).let { it.take(6) + List(4) { CellValue.Empty } } }
        val s = ProductionMetrics.summarize(reports, data.master)
        return doc(req, data, listOf(kpi(s, Metric.TARGET_ACHIEVEMENT), kpi(s, Metric.REJECTION_RATE, 2), kpi(s, Metric.AVAILABILITY), kpi(s, Metric.PERFORMANCE), kpi(s, Metric.QUALITY), kpi(s, Metric.OEE)),
            listOf(Section.Table("By line", cols, byLine, showTotals = true), Section.Table("By product", cols, byProduct, showTotals = true,
                note = "Availability, performance and OEE are line measures and are not split by product."), Section.Chart("Lowest achievement by product", Analytics.achievementByProduct(reports, data))),
            listOf(Metric.TARGET_ACHIEVEMENT, Metric.TARGET_VARIANCE, Metric.REJECTION_RATE, Metric.AVAILABILITY, Metric.PERFORMANCE, Metric.QUALITY, Metric.OEE))
    }

    private fun consumption(req: Request, data: DataSet): ReportDocument {
        val reports = reportsIn(req, data)
        val s = ProductionMetrics.summarize(reports, data.master)
        val byMaterial = reports.flatMap { it.materials }.groupBy { it.materialId }.map { (mid, ms) ->
            listOf(text(data.master.material(mid)?.name), num(ms.fold(BigDecimal.ZERO) { a, m -> a + m.issued }), num(ms.fold(BigDecimal.ZERO) { a, m -> a + m.returned }),
                num(ms.fold(BigDecimal.ZERO) { a, m -> a + m.consumed }), num(ms.fold(BigDecimal.ZERO) { a, m -> a + m.scrap }))
        }
        return doc(req, data, listOf(kpi(s, Metric.ACTUAL_CONSUMPTION), kpi(s, Metric.THEORETICAL_RESIN), kpi(s, Metric.MATERIAL_VARIANCE), kpi(s, Metric.MATERIAL_VARIANCE_PCT), kpi(s, Metric.SCRAP)),
            listOf(
                Section.Table("By material", listOf(Column("Material", weight = 2.4f), Column("Issued", ColumnKind.NUMBER, "kg", 2, total = true), Column("Returned", ColumnKind.NUMBER, "kg", 2, total = true),
                    Column("Consumed", ColumnKind.NUMBER, "kg", 2, total = true), Column("Scrap", ColumnKind.NUMBER, "kg", 2, total = true)), byMaterial, showTotals = true),
                Section.Chart("Consumed vs theoretical", Analytics.materialTrend(reports, data, req.filters)),
                Section.Table("Material lines", materialColumns, materialRows(reports, data), showTotals = true),
            ), listOf(Metric.ACTUAL_CONSUMPTION, Metric.THEORETICAL_RESIN, Metric.MATERIAL_VARIANCE, Metric.SCRAP))
    }

    /** Stock report in the legacy stock-sheet layout, computed from the ledger (live balances; history included for past periods). */
    private fun stock(req: Request, data: DataSet, kind: ItemKind): ReportDocument {
        val ledger = Ledger(data.transactions, includeHistorical = true)
        val range = req.filters.range
        val fg = kind == ItemKind.FINISHED_GOOD
        val unit = if (fg) "m" else "kg"
        val items: List<Triple<Id, String, String>> = if (fg) data.master.products.filter { (it.active || ledger.transactions(kind, it.id).isNotEmpty()) && (!req.filters.productFiltered || it.id in req.filters.productIds) }
            .sortedWith(compareBy({ it.dn ?: 0 }, { it.pn ?: BigDecimal.ZERO })).map { Triple(it.id, it.code, it.name) }
        else data.master.materials.map { Triple(it.id, it.code, it.name) }
        val rows = items.map { (id, code, name) ->
            val st = ledger.statement(kind, id, range)
            val threshold = data.master.threshold(kind, id)
            val status = StockStatusRules.classify(st.closing, threshold?.minimum, threshold?.reorderPoint)
            val spec = if (fg) data.master.product(id)?.sdr?.let { "SDR ${it.stripTrailingZeros().toPlainString()}" } else null
            listOfNotNull(text(code), text(name), if (fg) text(spec) else null, num(st.opening), num(st.receipts), num(st.issues), num(st.returns + st.transfers), num(st.adjustments), num(st.closing),
                if (fg) num(data.master.product(id)?.kgPerMetre?.let { it * st.closing }?.setScale(1, RoundingMode.HALF_UP)) else null,
                num(threshold?.minimum), text(status.label), CellValue.of(st.lastMovementDate ?: ledger.lastMovement(kind, id)))
        }
        val cols = listOfNotNull(
            Column("Code", weight = 1.4f), Column(if (fg) "Product" else "Material", weight = 3f), if (fg) Column("SDR", weight = 0.6f) else null,
            Column("Opening", ColumnKind.NUMBER, unit, 1, total = true), Column(if (fg) "Receipts" else "Received", ColumnKind.NUMBER, unit, 1, total = true),
            Column(if (fg) "Outward" else "Issued", ColumnKind.NUMBER, unit, 1, total = true), Column("Returns & transfers", ColumnKind.NUMBER, unit, 1, total = true),
            Column("Adjustments", ColumnKind.NUMBER, unit, 1, total = true), Column("Closing", ColumnKind.NUMBER, unit, 1, total = true),
            if (fg) Column("Closing weight", ColumnKind.NUMBER, "kg", 1, total = true) else null,
            Column("Minimum", ColumnKind.NUMBER, unit, 0), Column("Status", weight = 1.3f), Column("Last movement", ColumnKind.DATE, weight = 1.2f),
        )
        val closingCol = cols.indexOfFirst { it.header == "Closing" }
        val total = rows.fold(BigDecimal.ZERO) { a, r -> a + ((r[closingCol] as? CellValue.Num)?.value ?: BigDecimal.ZERO) }
        val belowMin = rows.count { (it[cols.indexOfFirst { c -> c.header == "Status" }] as? CellValue.Text)?.text != "In stock" }
        return doc(req, data, listOf(KpiCell("Closing stock", Formatting.number(total, 1) + " $unit"), KpiCell("Items", items.size.toString()), KpiCell("Out of stock or below minimum", belowMin.toString())),
            listOf(Section.Table(if (fg) "Finished goods" else "Raw materials", cols, rows, showTotals = true,
                note = "Closing = opening + receipts − outward + returns & transfers + adjustments. Includes imported workbook history for past dates."),
                Section.Chart("Receipts, issues and balance", Analytics.inventoryMovements(data, req.filters, kind))),
            emptyList())
    }

    private fun movements(req: Request, data: DataSet): ReportDocument {
        val txns = data.transactions.filter { it.date in req.filters.range && it.status != TxnStatus.VOID }
            .filter { it.itemKind != ItemKind.FINISHED_GOOD || !req.filters.productFiltered || it.itemId in req.filters.productIds }
            .sortedWith(compareBy({ it.date }, { it.createdAt }))
        val rows = txns.map { t ->
            listOf(CellValue.Day(t.date), text(if (t.itemKind == ItemKind.FINISHED_GOOD) "Finished goods" else "Raw material"), text(data.master.itemName(t.itemKind, t.itemId)),
                text(t.type.label), num(t.quantity), text(t.unit.code), text(t.reference), text(t.counterparty), text(t.status.name.replace('_', ' ').lowercase()),
                text(if (t.historical) "Imported history" else data.userName(t.createdBy)), text(t.sourceRef ?: t.note))
        }
        return doc(req, data, listOf(KpiCell("Movements", txns.size.toString()), KpiCell("Pending approval", txns.count { it.status == TxnStatus.PENDING_APPROVAL }.toString())),
            listOf(Section.Table("Movements", listOf(Column("Date", ColumnKind.DATE), Column("Kind"), Column("Item", weight = 2.6f), Column("Type", weight = 1.4f),
                Column("Quantity", ColumnKind.NUMBER, null, 2), Column("Unit", weight = 0.6f), Column("Reference", weight = 1.3f), Column("Counterparty", weight = 1.3f),
                Column("Status"), Column("Recorded by", weight = 1.3f), Column("Source / note", weight = 2f)), rows,
                note = "Quantities are signed: receipts positive, issues and dispatches negative.")), emptyList())
    }

    private fun quality(req: Request, data: DataSet): ReportDocument {
        val reports = reportsIn(req, data)
        val inspections = reports.flatMap { it.inspections }
        val sample = inspections.fold(BigDecimal.ZERO) { a, q -> a + q.sampleQty }
        val accepted = inspections.fold(BigDecimal.ZERO) { a, q -> a + q.accepted }
        val byDefect = inspections.filter { it.rejected.signum() > 0 }.groupBy { it.defectCategoryId }.map { (d, qs) ->
            listOf(text(d?.let { id -> data.master.defectCategories.firstOrNull { it.id == id }?.name } ?: "Unspecified"), num(BigDecimal(qs.size)), num(qs.fold(BigDecimal.ZERO) { a, q -> a + q.rejected }))
        }
        return doc(req, data, listOf(
            KpiCell("Inspections", inspections.size.toString()),
            KpiCell("Acceptance rate", Decimals.percent(accepted, sample)?.let { Formatting.number(it, 1) + "%" } ?: "No data"),
            KpiCell("Failed", inspections.count { it.result == rw.plasmaco.ops.domain.production.InspectionResult.FAIL }.toString()),
        ), listOf(Section.Table("By defect", listOf(Column("Defect", weight = 2f), Column("Inspections", ColumnKind.NUMBER, total = true), Column("Rejected", ColumnKind.NUMBER, "m", 1, total = true)), byDefect, showTotals = true),
            Section.Table("Inspections", qcColumns, qcRows(reports, data), showTotals = true)), listOf(Metric.QUALITY, Metric.REJECTION_RATE))
    }

    private fun downtime(req: Request, data: DataSet): ReportDocument {
        val reports = reportsIn(req, data)
        val events = reports.flatMap { it.downtime }.filter { it.end != null }
        val byCause = events.groupBy { it.categoryId }.map { (c, es) ->
            listOf(text(c?.let { data.master.downtimeCategory(it)?.name } ?: "Uncategorised"), text(if (c != null && data.master.downtimeCategory(c)?.planned == true) "Planned" else "Unplanned"),
                num(BigDecimal(es.size)), num(BigDecimal(es.sumOf { it.minutes ?: 0L })))
        }.sortedByDescending { (it[3] as CellValue.Num).value }
        val s = ProductionMetrics.summarize(reports, data.master)
        return doc(req, data, listOf(kpi(s, Metric.DOWNTIME), kpi(s, Metric.UNPLANNED_DOWNTIME), kpi(s, Metric.AVAILABILITY), KpiCell("Stoppages", events.size.toString())),
            listOf(Section.Table("By cause", listOf(Column("Cause", weight = 2.2f), Column("Type"), Column("Stoppages", ColumnKind.NUMBER, total = true), Column("Minutes", ColumnKind.NUMBER, "min", 0, total = true)), byCause, showTotals = true,
                note = "Per-cause minutes are summed as recorded; the headline downtime merges overlapping stoppages on the same machine (${DowntimeMath.mergedMinutes(events)} min)."),
                Section.Chart("Downtime by machine and cause", Analytics.downtimeByMachineAndCause(reports, data, req.filters)),
                Section.Table("Stoppages", downtimeColumns, downtimeRows(reports, data), showTotals = true)),
            listOf(Metric.DOWNTIME, Metric.UNPLANNED_DOWNTIME, Metric.AVAILABILITY))
    }

    private fun variance(req: Request, data: DataSet): ReportDocument {
        val reports = reportsIn(req, data).sortedBy { it.date }
        val rows = reports.map { r ->
            val s = ProductionMetrics.summarize(listOf(r), data.master)
            listOf(text(r.reference), CellValue.Day(r.date), text(data.master.line(r.lineId)?.name), num(s.gross), num(s.theoreticalResinKg?.setScale(2, RoundingMode.HALF_UP)),
                num(s.resinConsumedKg), num(s.value(Metric.MATERIAL_VARIANCE).orNull()), num(s.value(Metric.MATERIAL_VARIANCE_PCT).orNull()), num(s.scrapKg),
                num(s.value(Metric.TARGET_VARIANCE).orNull()))
        }
        val s = ProductionMetrics.summarize(reports, data.master)
        return doc(req, data, listOf(kpi(s, Metric.THEORETICAL_RESIN), kpi(s, Metric.ACTUAL_CONSUMPTION), kpi(s, Metric.MATERIAL_VARIANCE), kpi(s, Metric.MATERIAL_VARIANCE_PCT), kpi(s, Metric.TARGET_VARIANCE)),
            listOf(Section.Table("Per report", listOf(Column("Report", weight = 1.6f), Column("Date", ColumnKind.DATE), Column("Line"), Column("Gross", ColumnKind.NUMBER, "m", 1, total = true),
                Column("Theoretical resin", ColumnKind.NUMBER, "kg", 2, total = true), Column("Resin consumed", ColumnKind.NUMBER, "kg", 2, total = true),
                Column("Variance", ColumnKind.NUMBER, "kg", 2, total = true), Column("Variance", ColumnKind.PERCENT, "%", 1), Column("Scrap", ColumnKind.NUMBER, "kg", 2, total = true),
                Column("Target variance", ColumnKind.NUMBER, "m", 1, total = true)), rows, showTotals = true,
                note = "Blank cells mean the figure could not be computed (no kg/m, no material issue or no target), never zero.")),
            listOf(Metric.THEORETICAL_RESIN, Metric.ACTUAL_CONSUMPTION, Metric.MATERIAL_VARIANCE, Metric.MATERIAL_VARIANCE_PCT, Metric.TARGET_VARIANCE))
    }

    private fun management(req: Request, data: DataSet): ReportDocument {
        val snap = Analytics.dashboard(data, req.filters, req.filters.range.end.plusDays(1))
        val kpis = snap.kpis.map { KpiCell(it.label, metricText(it.value, if (it.metricKey == "rejection_rate") 2 else 1), (it.value as? MetricValue.NoData)?.reason) }
        val alerts = snap.alerts.take(20).map { listOf(text(it.type.label), text(it.severity.name.lowercase()), text(it.title), text(it.message), CellValue.of(it.date)) }
        return doc(req, data, kpis, listOfNotNull(
            snap.charts["daily_trend"]?.let { Section.Chart(it.title, it) },
            snap.charts["by_product"]?.let { Section.Chart(it.title, it) },
            snap.charts["good_vs_rejected"]?.let { Section.Chart(it.title, it) },
            Section.Table("Alerts", listOf(Column("Type", weight = 1.3f), Column("Severity"), Column("Alert", weight = 2.6f), Column("Detail", weight = 2.6f), Column("Date", ColumnKind.DATE)), alerts,
                note = if (snap.alerts.size > 20) "Showing 20 of ${snap.alerts.size} alerts." else null),
        ), listOf(Metric.GROSS_OUTPUT, Metric.TARGET_ACHIEVEMENT, Metric.REJECTION_RATE, Metric.DOWNTIME, Metric.ACTUAL_CONSUMPTION))
    }
}
