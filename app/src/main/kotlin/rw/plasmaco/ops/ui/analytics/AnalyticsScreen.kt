package rw.plasmaco.ops.ui.analytics

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import rw.plasmaco.ops.domain.metrics.Metric
import rw.plasmaco.ops.domain.security.Permission
import rw.plasmaco.ops.ui.common.ChartCard
import rw.plasmaco.ops.ui.common.FiltersBar
import rw.plasmaco.ops.ui.common.Fmt
import rw.plasmaco.ops.ui.common.KeyValueRow
import rw.plasmaco.ops.ui.common.KpiGrid
import rw.plasmaco.ops.ui.common.ScreenList
import rw.plasmaco.ops.ui.common.route
import rw.plasmaco.ops.ui.components.LoadingState
import rw.plasmaco.ops.ui.components.PageHeaderCard
import rw.plasmaco.ops.ui.components.SectionCard
import rw.plasmaco.ops.ui.components.charts.Share
import rw.plasmaco.ops.ui.components.charts.SplitProgressBar
import rw.plasmaco.ops.ui.dashboard.DashboardViewModel
import rw.plasmaco.ops.ui.navigation.Navigator
import rw.plasmaco.ops.ui.navigation.Routes
import rw.plasmaco.ops.ui.theme.Plasmaco

/** Chart order on the analytics screen; ids come from the domain's dashboard builder. */
private val CHART_ORDER = listOf(
    "daily_trend", "achievement_by_product", "achievement_by_shift", "by_product", "by_line", "good_vs_rejected",
    "material_trend", "downtime", "fg_movements", "rm_movements",
)
private val INVENTORY_CHARTS = setOf("fg_movements", "rm_movements")

@Composable
fun AnalyticsScreen(nav: Navigator, vm: DashboardViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val actor = ui.actor
    val snapshot = ui.snapshot
    if (ui.loading || actor == null || snapshot == null) {
        LoadingState(label = "Loading analytics")
        return
    }
    val canInventory = actor.can(Permission.INVENTORY_VIEW)
    val kpis = snapshot.kpis.filter { canInventory || it.metricKey !in setOf("fg_receipts", "fg_dispatches", "fg_stock", "rm_stock", "reorder_alerts") }
    val s = snapshot.summary
    ScreenList {
        item {
            PageHeaderCard(
                "Analytics",
                "${Fmt.date(snapshot.filters.range.start)} – ${Fmt.date(snapshot.filters.range.end)} · ${s.reports} report(s) in scope",
                actionText = if (actor.can(Permission.REPORTS_EXPORT)) "Export" else null,
                onAction = if (actor.can(Permission.REPORTS_EXPORT)) ({ nav.top(Routes.REPORTS) }) else null,
            )
        }
        item { FiltersBar(ui.selection, ui.master, ui.today, vm::select) }
        item { KpiGrid(kpis, onOpen = null) }
        item {
            SectionCard("Output quality", subtitle = "Good and rejected share of gross output") {
                val c = Plasmaco.colors
                SplitProgressBar(
                    listOf(Share("Good", s.good.toDouble(), c.chart.primary), Share("Rejected", s.rejected.toDouble(), c.chart.negative)),
                    total = s.gross.toDouble(), unit = "m",
                )
                Spacer(Modifier.height(8.dp))
                KeyValueRow("Gross output", Fmt.number(s.gross, 1) + " m", emphasise = true)
                KeyValueRow("Entries without a target", s.entriesWithoutTarget.toString())
                if (s.runRateMissing) Text("Performance and OEE need an ideal run rate for every line and product in scope.", style = Plasmaco.type.label, color = c.textSecondary)
            }
        }
        CHART_ORDER.filter { canInventory || it !in INVENTORY_CHARTS }.mapNotNull { snapshot.charts[it] }.forEach { spec ->
            item(key = spec.id) { ChartCard(spec, onDrill = { nav.open(it.route()) }) }
        }
        item {
            SectionCard("How these numbers are calculated") {
                Metric.entries.forEach { m -> KeyValueRow(m.label + (if (m.unit.isNotBlank()) " (${m.unit})" else ""), m.definition) }
                KeyValueRow("Scope", "Submitted and approved reports unless \"Approved only\" is selected. Drafts, returned and rejected reports are excluded.")
            }
        }
    }
}
