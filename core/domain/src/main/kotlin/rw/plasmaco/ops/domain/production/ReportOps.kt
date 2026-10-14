package rw.plasmaco.ops.domain.production

import rw.plasmaco.ops.domain.common.Id
import rw.plasmaco.ops.domain.common.newId
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

object ReportDuplicator {
    /**
     * Starts a new draft from a previous report: same line, shift and product list, empty quantities.
     * Quantities, times, materials issued, downtime, inspections, approvals and stock movements are never copied,
     * because they describe what happened on the original day.
     */
    fun duplicateAsDraft(
        source: ProductionReport,
        date: LocalDate,
        reference: String,
        actorId: Id,
        now: Instant,
    ): ProductionReport = ProductionReport(
        id = newId(),
        reference = reference,
        date = date,
        shiftId = source.shiftId,
        lineId = source.lineId,
        operatorId = actorId,
        supervisorId = null,
        startTime = null,
        endTime = null,
        status = ReportStatus.DRAFT,
        version = 0,
        createdBy = actorId,
        createdAt = now,
        updatedAt = now,
        remarks = "",
        entries = source.entries.distinctBy { it.productId to it.machineId }.map {
            ProductionEntry(
                id = newId(), productId = it.productId, machineId = it.machineId, target = it.target,
                gross = BigDecimal.ZERO, good = BigDecimal.ZERO, rejected = BigDecimal.ZERO, unit = it.unit,
            )
        },
        materials = source.materials.distinctBy { it.materialId }.map {
            MaterialConsumption(id = newId(), materialId = it.materialId, issued = BigDecimal.ZERO, unit = it.unit)
        },
        duplicatedFrom = source.id,
    )
}

/** Field-level differences between two versions of a report, for the audit trail and the version view. */
object ReportDiff {
    data class Change(val path: String, val before: String?, val after: String?)

    fun between(a: ProductionReport, b: ProductionReport): List<Change> = buildList {
        fun cmp(path: String, x: Any?, y: Any?) {
            val sx = x?.let(::fmt)
            val sy = y?.let(::fmt)
            if (sx != sy) add(Change(path, sx, sy))
        }
        cmp("date", a.date, b.date); cmp("shiftId", a.shiftId, b.shiftId); cmp("lineId", a.lineId, b.lineId)
        cmp("operatorId", a.operatorId, b.operatorId); cmp("supervisorId", a.supervisorId, b.supervisorId)
        cmp("startTime", a.startTime, b.startTime); cmp("endTime", a.endTime, b.endTime); cmp("remarks", a.remarks, b.remarks)
        diffList("entries", a.entries, b.entries, { it.id }) { p, x, y ->
            cmp("$p.productId", x?.productId, y?.productId); cmp("$p.target", x?.target, y?.target)
            cmp("$p.gross", x?.gross, y?.gross); cmp("$p.good", x?.good, y?.good); cmp("$p.rejected", x?.rejected, y?.rejected)
            cmp("$p.unit", x?.unit, y?.unit); cmp("$p.batch", x?.batch, y?.batch); cmp("$p.remarks", x?.remarks, y?.remarks)
        }
        diffList("materials", a.materials, b.materials, { it.id }) { p, x, y ->
            cmp("$p.materialId", x?.materialId, y?.materialId); cmp("$p.issued", x?.issued, y?.issued)
            cmp("$p.returned", x?.returned, y?.returned); cmp("$p.lineOpening", x?.lineOpening, y?.lineOpening)
            cmp("$p.lineClosing", x?.lineClosing, y?.lineClosing); cmp("$p.scrap", x?.scrap, y?.scrap)
        }
        diffList("downtime", a.downtime, b.downtime, { it.id }) { p, x, y ->
            cmp("$p.start", x?.start, y?.start); cmp("$p.end", x?.end, y?.end); cmp("$p.categoryId", x?.categoryId, y?.categoryId)
            cmp("$p.reason", x?.reason, y?.reason)
        }
        diffList("inspections", a.inspections, b.inspections, { it.id }) { p, x, y ->
            cmp("$p.sampleQty", x?.sampleQty, y?.sampleQty); cmp("$p.accepted", x?.accepted, y?.accepted)
            cmp("$p.rejected", x?.rejected, y?.rejected); cmp("$p.result", x?.result, y?.result)
        }
        cmp("handover", a.handover, b.handover)
    }

    private fun <T> MutableList<Change>.diffList(name: String, xs: List<T>, ys: List<T>, key: (T) -> String, each: (String, T?, T?) -> Unit) {
        val xm = xs.associateBy(key)
        val ym = ys.associateBy(key)
        (xm.keys + ym.keys).forEach { k ->
            val x = xm[k]
            val y = ym[k]
            val path = "$name[${k.take(8)}]"
            when {
                x == null -> add(Change(path, null, "added"))
                y == null -> add(Change(path, "removed", null))
                else -> each(path, x, y)
            }
        }
    }

    private fun fmt(v: Any): String = when (v) {
        is BigDecimal -> v.stripTrailingZeros().toPlainString()
        else -> v.toString()
    }
}
