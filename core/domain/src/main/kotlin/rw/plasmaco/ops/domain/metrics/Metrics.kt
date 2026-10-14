package rw.plasmaco.ops.domain.metrics

import rw.plasmaco.ops.domain.common.Decimals
import rw.plasmaco.ops.domain.master.MasterData
import rw.plasmaco.ops.domain.master.MaterialCategory
import rw.plasmaco.ops.domain.production.DowntimeMath
import rw.plasmaco.ops.domain.production.ProductionReport
import rw.plasmaco.ops.domain.production.ReportStatus
import rw.plasmaco.ops.domain.units.Quantity
import rw.plasmaco.ops.domain.units.UnitConverter
import java.math.BigDecimal
import java.math.RoundingMode

/** A computed figure or the reason it cannot be computed. The UI shows the reason instead of a misleading zero. */
sealed interface MetricValue {
    data class Value(val value: BigDecimal, val unit: String) : MetricValue
    data class NoData(val reason: String) : MetricValue

    fun orNull(): BigDecimal? = (this as? Value)?.value
}

/** Every metric the app shows, with the definition printed beside it in the app and in exports. */
enum class Metric(val label: String, val unit: String, val definition: String) {
    GROSS_OUTPUT("Gross output", "m", "Good output + rejected output"),
    GOOD_OUTPUT("Good output", "m", "Output that passed inspection"),
    REJECTED_OUTPUT("Rejected output", "m", "Output rejected on the line or at inspection"),
    TARGET("Production target", "m", "Sum of entry targets; entries without a target are excluded from achievement"),
    TARGET_ACHIEVEMENT("Target achievement", "%", "Good output ÷ target × 100, over entries that have a target"),
    TARGET_VARIANCE("Target variance", "m", "Good output − target, over entries that have a target"),
    REJECTION_RATE("Rejection rate", "%", "Rejected output ÷ gross output × 100"),
    THEORETICAL_RESIN("Theoretical resin", "kg", "Σ gross metres × kg per metre from the product master (ISO 4427-2)"),
    ACTUAL_CONSUMPTION("Material consumed", "kg", "Issued − returned + line-side opening − line-side closing"),
    MATERIAL_VARIANCE("Material variance", "kg", "Resin consumed − theoretical resin"),
    MATERIAL_VARIANCE_PCT("Material variance", "%", "(Resin consumed − theoretical) ÷ theoretical × 100"),
    SCRAP("Scrap and purge", "kg", "Scrap recorded on material lines (part of consumption)"),
    DOWNTIME("Downtime", "min", "Stoppage minutes with overlaps on the same machine merged"),
    UNPLANNED_DOWNTIME("Unplanned downtime", "min", "Downtime in categories not marked as planned"),
    AVAILABILITY("Availability", "%", "(Planned time − unplanned downtime) ÷ planned time; planned time = report window − planned downtime"),
    PERFORMANCE("Performance", "%", "(Gross metres ÷ run hours) ÷ ideal metres per hour"),
    QUALITY("Quality rate", "%", "Good output ÷ gross output"),
    OEE("OEE", "%", "Availability × performance × quality, approved reports only, when every factor is available"),
}

data class ProductionSummary(
    val reports: Int,
    val gross: BigDecimal,
    val good: BigDecimal,
    val rejected: BigDecimal,
    val targetedGood: BigDecimal,
    val target: BigDecimal,
    val entriesWithoutTarget: Int,
    val theoreticalResinKg: BigDecimal?,
    val resinConsumedKg: BigDecimal?,
    val materialConsumedKg: BigDecimal,
    val scrapKg: BigDecimal,
    val downtimeMinutes: Long,
    val unplannedDowntimeMinutes: Long,
    val plannedMinutes: Long?,
    val runRateMissing: Boolean,
    val idealMetres: BigDecimal?,
    val runHours: BigDecimal?,
    val allApproved: Boolean,
) {
    fun value(metric: Metric): MetricValue = when (metric) {
        Metric.GROSS_OUTPUT -> if (reports == 0) MetricValue.NoData("No reports") else MetricValue.Value(gross, "m")
        Metric.GOOD_OUTPUT -> if (reports == 0) MetricValue.NoData("No reports") else MetricValue.Value(good, "m")
        Metric.REJECTED_OUTPUT -> if (reports == 0) MetricValue.NoData("No reports") else MetricValue.Value(rejected, "m")
        Metric.TARGET -> if (target.signum() == 0) MetricValue.NoData("No target set") else MetricValue.Value(target, "m")
        Metric.TARGET_ACHIEVEMENT -> Decimals.percent(targetedGood, target)?.let { MetricValue.Value(it, "%") } ?: MetricValue.NoData("No target set")
        Metric.TARGET_VARIANCE -> if (target.signum() == 0) MetricValue.NoData("No target set") else MetricValue.Value(targetedGood - target, "m")
        Metric.REJECTION_RATE -> Decimals.percent(rejected, gross, 2)?.let { MetricValue.Value(it, "%") } ?: MetricValue.NoData("No output")
        Metric.THEORETICAL_RESIN -> theoreticalResinKg?.let { MetricValue.Value(it.setScale(2, RoundingMode.HALF_UP), "kg") } ?: MetricValue.NoData("A product has no kg/m")
        Metric.ACTUAL_CONSUMPTION -> resinConsumedKg?.let { MetricValue.Value(it, "kg") } ?: MetricValue.NoData("No material issue recorded")
        Metric.MATERIAL_VARIANCE -> if (theoreticalResinKg != null && resinConsumedKg != null) MetricValue.Value((resinConsumedKg - theoreticalResinKg).setScale(2, RoundingMode.HALF_UP), "kg")
            else MetricValue.NoData(if (resinConsumedKg == null) "No material issue recorded" else "A product has no kg/m")
        Metric.MATERIAL_VARIANCE_PCT -> if (theoreticalResinKg != null && resinConsumedKg != null) Decimals.percent(resinConsumedKg - theoreticalResinKg, theoreticalResinKg)?.let { MetricValue.Value(it, "%") } ?: MetricValue.NoData("No output")
            else MetricValue.NoData("Insufficient data")
        Metric.SCRAP -> MetricValue.Value(scrapKg, "kg")
        Metric.DOWNTIME -> if (reports == 0) MetricValue.NoData("No reports") else MetricValue.Value(BigDecimal(downtimeMinutes), "min")
        Metric.UNPLANNED_DOWNTIME -> if (reports == 0) MetricValue.NoData("No reports") else MetricValue.Value(BigDecimal(unplannedDowntimeMinutes), "min")
        Metric.AVAILABILITY -> availability()?.let { MetricValue.Value(it, "%") } ?: MetricValue.NoData("Report start and end times missing")
        Metric.PERFORMANCE -> performance()?.let { MetricValue.Value(it, "%") } ?: MetricValue.NoData(if (runRateMissing) "Ideal run rate not configured" else "Insufficient data")
        Metric.QUALITY -> Decimals.percent(good, gross)?.let { MetricValue.Value(it, "%") } ?: MetricValue.NoData("No output")
        Metric.OEE -> oee()
    }

    private fun availability(): BigDecimal? {
        val planned = plannedMinutes ?: return null
        if (planned <= 0) return null
        return Decimals.percent(BigDecimal(planned - unplannedDowntimeMinutes), BigDecimal(planned))
    }

    private fun performance(): BigDecimal? {
        if (runRateMissing) return null
        val ideal = idealMetres ?: return null
        return Decimals.percent(gross, ideal)
    }

    private fun oee(): MetricValue {
        if (!allApproved) return MetricValue.NoData("Includes reports that are not approved")
        val a = availability() ?: return MetricValue.NoData("Availability unavailable")
        val p = performance() ?: return MetricValue.NoData(if (runRateMissing) "Ideal run rate not configured" else "Performance unavailable")
        val q = Decimals.percent(good, gross) ?: return MetricValue.NoData("No output")
        val oee = a.multiply(p).multiply(q).divide(BigDecimal(10_000), 1, RoundingMode.HALF_UP)
        return MetricValue.Value(oee, "%")
    }
}

object ProductionMetrics {
    /** Aggregates any set of reports (one report, a day, a line, a month) into a [ProductionSummary]. */
    fun summarize(reports: List<ProductionReport>, master: MasterData): ProductionSummary {
        var gross = BigDecimal.ZERO
        var good = BigDecimal.ZERO
        var rejected = BigDecimal.ZERO
        var targetedGood = BigDecimal.ZERO
        var target = BigDecimal.ZERO
        var withoutTarget = 0
        var theoretical: BigDecimal? = BigDecimal.ZERO
        var resin: BigDecimal? = null
        var materialAll = BigDecimal.ZERO
        var scrap = BigDecimal.ZERO
        var downtime = 0L
        var unplanned = 0L
        var plannedMinutes: Long? = 0L
        var runRateMissing = false
        var ideal: BigDecimal? = BigDecimal.ZERO
        var runHours: BigDecimal? = BigDecimal.ZERO

        for (r in reports) {
            var reportGross = BigDecimal.ZERO
            for (e in r.entries) {
                val product = master.product(e.productId)
                val toMetres = { q: BigDecimal ->
                    if (product == null) q else UnitConverter.convert(Quantity(q, e.unit), product.baseUnit, product.conversions)?.value ?: q
                }
                val g = toMetres(e.gross)
                val gd = toMetres(e.good)
                val rj = toMetres(e.rejected)
                gross += g; good += gd; rejected += rj; reportGross += g
                if (e.target != null && e.target.signum() > 0) {
                    target += toMetres(e.target); targetedGood += gd
                } else withoutTarget++
                if (g.signum() > 0) {
                    val kg = product?.kgPerMetre
                    theoretical = if (kg == null || theoretical == null) null else theoretical!! + g * kg
                }
            }
            for (m in r.materials) {
                materialAll += m.consumed; scrap += m.scrap
                if (master.material(m.materialId)?.category == MaterialCategory.RESIN) resin = (resin ?: BigDecimal.ZERO) + m.consumed
            }
            val merged = DowntimeMath.mergedMinutes(r.downtime)
            downtime += merged
            val unplannedEvents = r.downtime.filter { d -> d.categoryId?.let { master.downtimeCategory(it)?.planned } != true }
            val plannedEvents = r.downtime.filter { d -> d.categoryId?.let { master.downtimeCategory(it)?.planned } == true }
            val unplannedMin = DowntimeMath.mergedMinutes(unplannedEvents)
            unplanned += unplannedMin
            val windowMin = r.plannedDuration?.toMinutes()
            if (windowMin == null) plannedMinutes = null
            else plannedMinutes = plannedMinutes?.plus(windowMin - DowntimeMath.mergedMinutes(plannedEvents))

            // Performance: ideal metres = run hours × ideal rate for the line.
            val rate = master.runRate(r.lineId, r.entries.singleOrNull()?.productId)
            if (rate == null) runRateMissing = true
            if (windowMin == null) { ideal = null; runHours = null } else {
                val runMin = windowMin - DowntimeMath.mergedMinutes(r.downtime)
                val hours = BigDecimal(runMin.coerceAtLeast(0)).divide(BigDecimal(60), 4, RoundingMode.HALF_UP)
                runHours = runHours?.plus(hours)
                ideal = if (rate == null || ideal == null) null else ideal!! + hours * rate.metresPerHour
            }
        }
        if (reports.isEmpty()) { plannedMinutes = null; ideal = null; runHours = null }
        return ProductionSummary(
            reports = reports.size, gross = gross, good = good, rejected = rejected, targetedGood = targetedGood, target = target,
            entriesWithoutTarget = withoutTarget, theoreticalResinKg = theoretical, resinConsumedKg = resin,
            materialConsumedKg = materialAll, scrapKg = scrap, downtimeMinutes = downtime, unplannedDowntimeMinutes = unplanned,
            plannedMinutes = plannedMinutes, runRateMissing = runRateMissing || reports.isEmpty(), idealMetres = ideal, runHours = runHours,
            allApproved = reports.isNotEmpty() && reports.all { it.status == ReportStatus.APPROVED },
        )
    }

    /** Convenience for one entry: achievement % or null without a target. */
    fun entryAchievement(good: BigDecimal, target: BigDecimal?): BigDecimal? =
        if (target == null || target.signum() <= 0) null else Decimals.percent(good, target)
}
