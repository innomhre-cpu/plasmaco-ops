package rw.plasmaco.ops.ui.dashboard

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Factory
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import rw.plasmaco.ops.domain.master.ItemKind
import rw.plasmaco.ops.domain.security.Permission
import rw.plasmaco.ops.domain.units.UnitOfMeasure
import rw.plasmaco.ops.ui.common.AlertsCard
import rw.plasmaco.ops.ui.common.ChartCard
import rw.plasmaco.ops.ui.common.FiltersBar
import rw.plasmaco.ops.ui.common.Fmt
import rw.plasmaco.ops.ui.common.KpiGrid
import rw.plasmaco.ops.ui.common.ScreenList
import rw.plasmaco.ops.ui.common.route
import rw.plasmaco.ops.ui.components.HeroStatusCard
import rw.plasmaco.ops.ui.components.LoadingState
import rw.plasmaco.ops.ui.components.NoticeBanner
import rw.plasmaco.ops.ui.components.PageHeaderCard
import rw.plasmaco.ops.ui.components.RingProgress
import rw.plasmaco.ops.ui.components.TextLink
import rw.plasmaco.ops.ui.components.Tone
import rw.plasmaco.ops.ui.navigation.Navigator
import rw.plasmaco.ops.ui.navigation.Routes

private val PRODUCTION_KPIS = setOf("gross_output", "target", "target_achievement", "good_output", "rejected_output", "downtime", "rm_consumption")
private val INVENTORY_KPIS = setOf("fg_receipts", "fg_dispatches", "fg_stock", "rm_stock", "reorder_alerts")

@Composable
fun DashboardScreen(nav: Navigator, vm: DashboardViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val actor = ui.actor
    val snapshot = ui.snapshot
    if (ui.loading || actor == null || snapshot == null) {
        LoadingState(label = "Loading dashboard")
        return
    }
    val canAnalytics = actor.can(Permission.ANALYTICS_VIEW)
    val canInventory = actor.can(Permission.INVENTORY_VIEW)
    val canApprove = actor.can(Permission.REPORT_APPROVE) || actor.can(Permission.ADJUSTMENT_APPROVE)
    val kpis = snapshot.kpis.filter { k ->
        when (k.metricKey) {
            in INVENTORY_KPIS -> canInventory
            "pending_approvals" -> canApprove
            else -> true
        }
    }
    val charts = buildList {
        add("daily_trend")
        add("by_product")
        if (canInventory) add("fg_movements")
        if (!canAnalytics) add("downtime")
    }.mapNotNull { snapshot.charts[it] }

    ScreenList {
        item {
            PageHeaderCard(
                title = "Dashboard",
                subtitle = "${Fmt.date(ui.today)} · ${snapshot.filters.range.let { "${Fmt.short(it.start)} – ${Fmt.short(it.end)}" }}",
                actionText = if (actor.can(Permission.REPORT_CREATE)) "New Daily Report" else null,
                actionIcon = Icons.Outlined.AddCircleOutline,
                onAction = if (actor.can(Permission.REPORT_CREATE)) ({ nav.top(Routes.production(openNew = true)) }) else null,
            )
        }
        if (ui.setupGaps.isNotEmpty() && actor.can(Permission.MASTER_DATA_MANAGE)) {
            item {
                Column {
                    NoticeBanner("Finish setup: ${ui.setupGaps.joinToString("; ")}", Tone.WARNING, icon = Icons.Outlined.Settings)
                    TextLink("Open master data", { nav.top(Routes.MASTER) })
                }
            }
        }
        item { FiltersBar(ui.selection, ui.master, ui.today, vm::select) }
        ui.current?.let { cs ->
            item {
                val pct = cs.achievementPct
                HeroStatusCard(
                    title = "${cs.shift.name} · ${Fmt.short(cs.reportDate)}",
                    statusText = "${cs.linesWithReport} of ${cs.activeLines} lines reported",
                    leadingIcon = Icons.Outlined.Factory,
                    metaLines = listOf(
                        Icons.Outlined.Schedule to "${Fmt.time(cs.shift.start)} – ${Fmt.time(cs.shift.end)}",
                        Icons.Outlined.EditNote to "${cs.drafts} draft, ${cs.submitted} submitted",
                        Icons.Outlined.CalendarMonth to "Good output ${Fmt.qty(cs.good, UnitOfMeasure.METRE)}",
                    ),
                    ring = {
                        RingProgress(
                            fraction = pct?.toFloat()?.div(100f), centerText = pct?.let { "${Fmt.number(it, 0)}%" } ?: "—",
                            caption = if (pct == null) "no target" else "of target",
                            trackColor = Color.White.copy(alpha = 0.25f),
                        )
                    },
                    onOpen = { nav.top(Routes.history(date = cs.reportDate, shiftId = cs.shift.id)) },
                )
            }
        }
        item {
            KpiGrid(kpis, onOpen = { k ->
                when (k.metricKey) {
                    "pending_approvals" -> nav.top(Routes.APPROVALS)
                    "reorder_alerts" -> nav.top(Routes.NOTIFICATIONS)
                    "fg_receipts", "fg_dispatches", "fg_stock" -> nav.top(Routes.inventory(ItemKind.FINISHED_GOOD))
                    "rm_stock" -> nav.top(Routes.inventory(ItemKind.RAW_MATERIAL))
                    in PRODUCTION_KPIS -> nav.top(if (canAnalytics) Routes.ANALYTICS else Routes.HISTORY)
                    else -> Unit
                }
            })
        }
        charts.forEach { spec -> item(key = spec.id) { ChartCard(spec, onDrill = { nav.open(it.route()) }) } }
        if (canAnalytics) item { TextLink("Open analytics (all charts)", { nav.top(Routes.ANALYTICS) }) }
        item {
            AlertsCard(snapshot.alerts, onOpen = { a -> a.route()?.let(nav::open) }, onSeeAll = { nav.top(Routes.NOTIFICATIONS) })
        }
    }
}
