package rw.plasmaco.ops.ui.common

import rw.plasmaco.ops.domain.analytics.Alert
import rw.plasmaco.ops.domain.analytics.DrillTarget
import rw.plasmaco.ops.domain.master.ItemKind
import rw.plasmaco.ops.ui.navigation.Routes
import java.time.LocalDate

/** Where a chart bar, KPI or alert leads: the records behind the number (requirements: drill-down). */
fun DrillTarget.route(): String = when (this) {
    is DrillTarget.Day -> Routes.history(date = date)
    is DrillTarget.Product -> Routes.history(productId = productId)
    is DrillTarget.Line -> Routes.history(lineId = lineId)
    is DrillTarget.Shift -> Routes.history(shiftId = shiftId)
    // History has no machine filter; the downtime report lists stoppages by machine.
    is DrillTarget.Machine -> Routes.builder(rw.plasmaco.ops.domain.reports.ReportType.DOWNTIME)
    is DrillTarget.Item -> Routes.item(kind, itemId)
}

fun Alert.route(): String? = reportId?.let { Routes.detail(it) } ?: target?.route()

/** Decodes the stored notification target ("item:FINISHED_GOOD:<id>", "day:2026-10-07", "sync", …). */
fun notificationRoute(reportId: String?, target: String?): String? {
    if (reportId != null) return Routes.detail(reportId)
    val parts = target?.split(':') ?: return null
    return when (parts.firstOrNull()) {
        "item" -> parts.getOrNull(2)?.let { id -> runCatching { ItemKind.valueOf(parts[1]) }.getOrNull()?.let { Routes.item(it, id) } }
        "day" -> parts.getOrNull(1)?.let { d -> runCatching { LocalDate.parse(d) }.getOrNull()?.let { Routes.history(date = it) } }
        "product" -> parts.getOrNull(1)?.let { Routes.history(productId = it) }
        "line" -> parts.getOrNull(1)?.let { Routes.history(lineId = it) }
        "shift" -> parts.getOrNull(1)?.let { Routes.history(shiftId = it) }
        "sync" -> Routes.SYNC
        else -> null
    }
}
