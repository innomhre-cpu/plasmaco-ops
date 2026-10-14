package rw.plasmaco.ops.ui.production

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Factory
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import rw.plasmaco.ops.domain.production.ProductionReport
import rw.plasmaco.ops.domain.security.Permission
import rw.plasmaco.ops.domain.workflow.ReportWorkflow
import rw.plasmaco.ops.ui.common.Fmt
import rw.plasmaco.ops.ui.common.ListCard
import rw.plasmaco.ops.ui.common.ScreenList
import rw.plasmaco.ops.ui.common.SectionTitle
import rw.plasmaco.ops.ui.components.AppBottomSheet
import rw.plasmaco.ops.ui.components.DateField
import rw.plasmaco.ops.ui.components.DropdownField
import rw.plasmaco.ops.ui.components.EmptyState
import rw.plasmaco.ops.ui.components.FieldHint
import rw.plasmaco.ops.ui.components.LoadingState
import rw.plasmaco.ops.ui.components.PageHeaderCard
import rw.plasmaco.ops.ui.components.PrimaryButton
import rw.plasmaco.ops.ui.navigation.Navigator
import rw.plasmaco.ops.ui.navigation.Routes
import rw.plasmaco.ops.ui.theme.Plasmaco

@Composable
fun ProductionScreen(nav: Navigator, vm: ProductionViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var showNew by rememberSaveable { mutableStateOf(false) }
    // "New Daily Report" from the dashboard opens the form once; the flag is consumed so going back does not reopen it.
    LaunchedEffect(Unit) { if (vm.consumeOpenNew()) showNew = true }
    val actor = ui.actor
    if (ui.loading || actor == null) {
        LoadingState(label = "Loading reports")
        return
    }
    fun open(r: ProductionReport) = nav.open(if (ReportWorkflow.canEdit(r, actor)) Routes.editor(r.id) else Routes.detail(r.id))

    ScreenList {
        item {
            PageHeaderCard(
                "Daily Production",
                "${Fmt.date(ui.today)} · record output, materials, downtime, quality and the shift handover",
                actionText = "New Daily Report", actionIcon = Icons.Outlined.AddCircleOutline, onAction = { showNew = true },
            )
        }
        if (ui.master.lines.none { it.active } || ui.master.shifts.none { it.active }) {
            item {
                EmptyState(
                    "Production lines and shifts are not set up",
                    if (actor.can(Permission.MASTER_DATA_MANAGE)) "Add at least one line and one shift in master data before creating reports."
                    else "Ask an administrator to add production lines and shifts.",
                    icon = Icons.Outlined.Factory,
                    actionText = if (actor.can(Permission.MASTER_DATA_MANAGE)) "Open master data" else null,
                    onAction = if (actor.can(Permission.MASTER_DATA_MANAGE)) ({ nav.top(Routes.MASTER) }) else null,
                )
            }
        }
        section("Needs your attention", ui.needsAttention, ui, ::open)
        section("Drafts", ui.drafts, ui, ::open)
        section("Today", ui.todays, ui, ::open)
        section("Recently submitted", ui.recent, ui, ::open)
        if (ui.needsAttention.isEmpty() && ui.drafts.isEmpty() && ui.todays.isEmpty() && ui.recent.isEmpty()) {
            item { EmptyState("No reports yet", "Start today's report with \"New Daily Report\".") }
        }
        item { Text("Older reports are in Production History.", style = Plasmaco.type.label, color = Plasmaco.colors.textSecondary) }
    }

    if (showNew) {
        NewReportSheet(ui, busy, onDismiss = { showNew = false }) { date, line, shift, operator, from ->
            vm.create(date, line, shift, operator, from) { id ->
                showNew = false
                nav.open(Routes.editor(id))
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(
    title: String,
    reports: List<ProductionReport>,
    ui: ProductionUi,
    onOpen: (ProductionReport) -> Unit,
) {
    if (reports.isEmpty()) return
    item(key = "h-$title") { SectionTitle(title) }
    item(key = "l-$title") { ListCard(reports) { r -> ReportRow(r, ui.master, { onOpen(r) }) } }
}

@Composable
private fun NewReportSheet(
    ui: ProductionUi,
    busy: Boolean,
    onDismiss: () -> Unit,
    onCreate: (java.time.LocalDate, String?, String?, String?, String?) -> Unit,
) {
    val actor = ui.actor ?: return
    var date by rememberSaveable { mutableStateOf(ui.today) }
    var lineId by rememberSaveable { mutableStateOf(ui.master.lines.singleOrNull { it.active }?.id) }
    var shiftId by rememberSaveable { mutableStateOf(ui.currentShift?.id) }
    var operatorId by rememberSaveable { mutableStateOf(actor.userId) }
    var fromId by rememberSaveable { mutableStateOf<String?>(null) }
    val sources = ui.duplicable.filter { it.lineId == lineId && it.shiftId == shiftId }

    AppBottomSheet("New daily report", onDismiss) {
        DateField("Production date", date, { date = it }, maxDate = ui.today, required = true)
        Spacer(Modifier.height(12.dp))
        DropdownField("Production line", ui.master.lines.filter { it.active }, ui.master.line(lineId ?: ""), { lineId = it.id; fromId = null }, { it.name }, required = true)
        Spacer(Modifier.height(12.dp))
        DropdownField(
            "Shift", ui.master.shifts.filter { it.active }, ui.master.shift(shiftId ?: ""), { shiftId = it.id; fromId = null },
            { it.name }, required = true, itemSupporting = { "${Fmt.time(it.start)} – ${Fmt.time(it.end)}" },
        )
        if (actor.can(Permission.REPORT_EDIT_ANY_DRAFT) && ui.operators.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            DropdownField("Operator", ui.operators, ui.operators.firstOrNull { it.id == operatorId }, { operatorId = it.id }, { it.displayName })
        }
        Spacer(Modifier.height(12.dp))
        DropdownField(
            "Start from", listOf<ProductionReport?>(null) + sources, sources.firstOrNull { it.id == fromId },
            { fromId = it?.id }, { r -> r?.let { "${it.reference} (${Fmt.short(it.date)})" } ?: "Empty report" },
            placeholder = "Empty report", enabled = sources.isNotEmpty(),
        )
        FieldHint(
            if (sources.isEmpty()) "No earlier report for this line and shift to start from."
            else "Copies the line, shift and product list only. Quantities, times, materials and downtime are never copied.",
        )
        Spacer(Modifier.height(16.dp))
        PrimaryButton("Create draft", { onCreate(date, lineId, shiftId, operatorId, fromId) }, loading = busy, enabled = lineId != null && shiftId != null)
    }
}
