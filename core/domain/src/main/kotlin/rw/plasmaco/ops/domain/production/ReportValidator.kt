package rw.plasmaco.ops.domain.production

import rw.plasmaco.ops.domain.common.Decimals
import rw.plasmaco.ops.domain.common.Issue
import rw.plasmaco.ops.domain.common.Severity
import rw.plasmaco.ops.domain.master.MasterData
import rw.plasmaco.ops.domain.units.Dimension
import rw.plasmaco.ops.domain.units.UnitConverter
import rw.plasmaco.ops.domain.units.Quantity
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.LocalDate

/**
 * Validation rules for daily production reports. Two levels:
 *  - [Stage.DRAFT]: only what would make the saved data meaningless (negative numbers, impossible times).
 *  - [Stage.SUBMIT]: everything a supervisor needs to approve with confidence.
 */
object ReportValidator {
    enum class Stage { DRAFT, SUBMIT }

    data class Settings(
        /** Allowed difference between good + rejected and gross before it is an error. */
        val grossTolerance: BigDecimal = BigDecimal("0.01"),
        /** Material variance (actual vs theoretical) above this share raises a warning asking for a remark. */
        val materialVarianceWarnShare: BigDecimal = BigDecimal("0.10"),
        val maxShiftHours: Long = 16,
    )

    fun validate(
        report: ProductionReport,
        master: MasterData,
        today: LocalDate,
        otherReports: List<ProductionReport>,
        stage: Stage,
        settings: Settings = Settings(),
    ): List<Issue> = buildList {
        val submit = stage == Stage.SUBMIT
        fun err(field: String, code: String, msg: String) = add(Issue(field, code, msg, Severity.ERROR))
        fun warn(field: String, code: String, msg: String) = add(Issue(field, code, msg, Severity.WARNING))

        // ---- Identification
        if (report.date.isAfter(today)) err("date", "date.future", "Reporting date cannot be in the future")
        if (master.shift(report.shiftId) == null) err("shiftId", "shift.unknown", "Choose a shift")
        if (master.line(report.lineId) == null) err("lineId", "line.unknown", "Choose a production line or machine")
        if (report.operatorId.isBlank()) err("operatorId", "operator.missing", "Choose the operator")
        val window = report.window
        if (report.startTime != null && report.endTime != null && window != null) {
            val hours = Duration.between(window.first, window.second).toMinutes() / 60.0
            if (hours > settings.maxShiftHours) err("endTime", "time.too_long", "Report covers ${"%.1f".format(hours)} h; a shift cannot exceed ${settings.maxShiftHours} h")
            val shift = master.shift(report.shiftId)
            if (!report.endTime.isAfter(report.startTime) && shift != null && !shift.crossesMidnight) {
                warn("endTime", "time.crosses_midnight", "End time is before start time; the report is treated as running past midnight")
            }
        } else if (submit && (report.startTime == null || report.endTime == null)) {
            err("startTime", "time.missing", "Enter the start and end times")
        }

        // Duplicate report for the same date, shift and line (a rejected report does not block a new one).
        val duplicate = otherReports.firstOrNull {
            it.id != report.id && it.date == report.date && it.shiftId == report.shiftId && it.lineId == report.lineId && it.status != ReportStatus.REJECTED
        }
        if (duplicate != null) {
            val msg = "Report ${duplicate.reference} already exists for this date, shift and line"
            if (submit) err("date", "report.duplicate", msg) else warn("date", "report.duplicate", msg)
        }

        // ---- Production entries
        if (submit && report.entries.isEmpty()) err("entries", "entries.empty", "Add at least one production entry")
        val seen = mutableSetOf<String>()
        report.entries.forEachIndexed { i, e ->
            val f = "entries[$i]"
            val product = master.product(e.productId)
            if (product == null) err("$f.productId", "product.unknown", "Choose a product")
            else if (!product.active) err("$f.productId", "product.inactive", "${product.name} is no longer active")
            if (e.gross.signum() < 0) err("$f.gross", "qty.negative", "Gross output cannot be negative")
            if (e.good.signum() < 0) err("$f.good", "qty.negative", "Good output cannot be negative")
            if (e.rejected.signum() < 0) err("$f.rejected", "qty.negative", "Rejected output cannot be negative")
            if ((e.good + e.rejected - e.gross).abs() > settings.grossTolerance) {
                err("$f.gross", "qty.gross_mismatch", "Good (${e.good.toPlainString()}) + rejected (${e.rejected.toPlainString()}) must equal gross (${e.gross.toPlainString()})")
            }
            if (e.target != null && e.target.signum() <= 0) err("$f.target", "target.not_positive", "Target must be greater than zero, or left empty")
            if (product != null && e.unit != product.baseUnit) {
                val convertible = UnitConverter.convert(Quantity(BigDecimal.ONE, e.unit), product.baseUnit, product.conversions) != null
                if (!convertible) err("$f.unit", "unit.incompatible", "${e.unit.label} cannot be converted to ${product.baseUnit.label} for ${product.name}")
            }
            if (e.unit.dimension == Dimension.MASS) warn("$f.unit", "unit.mass_output", "Output is normally recorded in metres; check the unit")
            if (e.start != null && e.end != null && !e.end.isAfter(e.start)) err("$f.end", "time.order", "Entry end must be after its start")
            if (submit && e.gross.signum() == 0 && e.remarks.isBlank()) warn("$f.gross", "qty.zero", "Zero output — add a remark explaining why")
            val key = "${e.productId}|${e.machineId}|${e.batch.orEmpty()}"
            if (!seen.add(key)) warn("$f.productId", "entry.repeated", "This product, machine and batch is already entered; combine the lines unless they are separate runs")
        }

        // ---- Materials
        val materialKeys = mutableSetOf<String>()
        report.materials.forEachIndexed { i, m ->
            val f = "materials[$i]"
            val material = master.material(m.materialId)
            if (material == null) err("$f.materialId", "material.unknown", "Choose a material")
            listOf("issued" to m.issued, "returned" to m.returned, "scrap" to m.scrap).forEach { (name, v) ->
                if (v.signum() < 0) err("$f.$name", "qty.negative", "${name.replaceFirstChar { it.uppercase() }} cannot be negative")
            }
            if ((m.lineOpening?.signum() ?: 0) < 0) err("$f.lineOpening", "qty.negative", "Line-side opening cannot be negative")
            if ((m.lineClosing?.signum() ?: 0) < 0) err("$f.lineClosing", "qty.negative", "Line-side closing cannot be negative")
            val available = m.issued + (m.lineOpening ?: BigDecimal.ZERO)
            if (m.returned > available) err("$f.returned", "material.return_exceeds", "Returned (${m.returned.toPlainString()}) exceeds what was available (${available.toPlainString()})")
            if (m.consumed.signum() < 0) err("$f.lineClosing", "material.negative_consumption", "Consumption works out negative; check issued, returned and line-side stock")
            if (m.scrap > m.consumed.max(BigDecimal.ZERO)) err("$f.scrap", "material.scrap_exceeds", "Scrap cannot exceed the material consumed")
            if (material != null && m.unit != material.baseUnit && UnitConverter.convert(Quantity(BigDecimal.ONE, m.unit), material.baseUnit, material.conversions) == null) {
                err("$f.unit", "unit.incompatible", "${m.unit.label} cannot be converted to ${material.baseUnit.label} for ${material.name}")
            }
            val key = "${m.materialId}|${m.batch.orEmpty()}"
            if (!materialKeys.add(key)) err("$f.materialId", "material.repeated", "This material and batch is listed twice; combine them so consumption is not counted twice")
        }
        if (submit && report.entries.any { it.gross.signum() > 0 } && report.materials.isEmpty()) {
            warn("materials", "materials.missing", "Production recorded without any material issued; raw-material consumption will show as missing")
        }
        if (submit && report.entries.isEmpty() && report.materials.isNotEmpty()) {
            warn("materials", "materials.without_output", "Material consumed but no output recorded")
        }

        // Theoretical vs actual resin
        val theoretical = theoreticalResinKg(report, master)
        val actual = report.materials.filter { master.material(it.materialId)?.category == rw.plasmaco.ops.domain.master.MaterialCategory.RESIN }
            .fold(BigDecimal.ZERO) { a, m -> a + m.consumed }
        if (submit && theoretical != null && theoretical.signum() > 0 && actual.signum() > 0) {
            val share = (actual - theoretical).abs().divide(theoretical, 4, RoundingMode.HALF_UP)
            if (share > settings.materialVarianceWarnShare && report.remarks.isBlank()) {
                warn("materials", "material.variance", "Resin consumed differs from theoretical by ${share.multiply(Decimals.HUNDRED).setScale(1, RoundingMode.HALF_UP)}% — add a remark")
            }
        }

        // ---- Downtime
        report.downtime.forEachIndexed { i, d ->
            val f = "downtime[$i]"
            if (d.end == null) {
                if (submit) err("$f.end", "downtime.open", "Enter when the stoppage ended")
            } else {
                if (!d.end.isAfter(d.start)) err("$f.end", "time.order", "Downtime end must be after its start")
                else if (Duration.between(d.start, d.end).toHours() >= 24) err("$f.end", "downtime.too_long", "A single stoppage cannot exceed 24 hours; split it per report")
            }
            if (submit && d.categoryId == null && master.downtimeCategories.isNotEmpty()) err("$f.categoryId", "downtime.category", "Choose a downtime cause")
            if (submit && d.categoryId == null && master.downtimeCategories.isEmpty() && d.reason.isBlank()) err("$f.reason", "downtime.reason", "Describe the cause")
            if (window != null && d.end != null && (d.start.isBefore(window.first) || d.end.isAfter(window.second))) {
                warn("$f.start", "downtime.outside_window", "Stoppage falls partly outside the report's start and end times")
            }
        }
        DowntimeMath.overlaps(report.downtime).forEach { (a, b) ->
            warn("downtime[$b].start", "downtime.overlap", "Overlaps stoppage ${a + 1} on the same machine; overlapping time is counted once")
        }

        // ---- Quality
        report.inspections.forEachIndexed { i, q ->
            val f = "inspections[$i]"
            if (q.sampleQty.signum() <= 0) err("$f.sampleQty", "qc.sample", "Sample quantity must be greater than zero")
            if (q.accepted.signum() < 0 || q.rejected.signum() < 0) err("$f.accepted", "qty.negative", "Accepted and rejected cannot be negative")
            if ((q.accepted + q.rejected - q.sampleQty).abs() > settings.grossTolerance) err("$f.accepted", "qc.sum", "Accepted + rejected must equal the sample quantity")
            if (q.result == InspectionResult.FAIL && q.defectCategoryId == null && q.remarks.isBlank()) err("$f.defectCategoryId", "qc.defect", "A failed inspection needs a defect category or remark")
            if (q.result == InspectionResult.PASS && q.rejected.signum() > 0) warn("$f.result", "qc.pass_with_rejects", "Result is Pass although some samples were rejected")
        }
    }

    /** Σ gross metres × kg/m. Null when any product with output has no kg/m (then the metric shows "No data"). */
    fun theoreticalResinKg(report: ProductionReport, master: MasterData): BigDecimal? {
        var total = BigDecimal.ZERO
        for (e in report.entries) {
            if (e.gross.signum() == 0) continue
            val product = master.product(e.productId) ?: return null
            val kg = product.kgPerMetre ?: return null
            val metres = UnitConverter.convert(Quantity(e.gross, e.unit), product.baseUnit, product.conversions)?.value ?: return null
            total += metres * kg
        }
        return total
    }
}

object DowntimeMath {
    /** Index pairs (earlier, later) of events on the same machine whose time ranges overlap. */
    fun overlaps(events: List<DowntimeEvent>): List<Pair<Int, Int>> {
        val result = mutableListOf<Pair<Int, Int>>()
        for (i in events.indices) for (j in i + 1 until events.size) {
            val a = events[i]
            val b = events[j]
            if (a.machineId != b.machineId) continue
            val aEnd = a.end ?: continue
            val bEnd = b.end ?: continue
            if (a.start.isBefore(bEnd) && b.start.isBefore(aEnd)) result += i to j
        }
        return result
    }

    /** Total minutes per machine with overlapping stoppages merged, so no minute is counted twice. */
    fun mergedMinutes(events: List<DowntimeEvent>): Long =
        events.filter { it.end != null && it.end.isAfter(it.start) }.groupBy { it.machineId }.values.sumOf { group ->
            val sorted = group.sortedBy { it.start }
            var total = 0L
            var curStart = sorted.first().start
            var curEnd = sorted.first().end!!
            for (e in sorted.drop(1)) {
                if (e.start.isAfter(curEnd)) {
                    total += Duration.between(curStart, curEnd).toMinutes()
                    curStart = e.start; curEnd = e.end!!
                } else if (e.end!!.isAfter(curEnd)) curEnd = e.end
            }
            total + Duration.between(curStart, curEnd).toMinutes()
        }
}
