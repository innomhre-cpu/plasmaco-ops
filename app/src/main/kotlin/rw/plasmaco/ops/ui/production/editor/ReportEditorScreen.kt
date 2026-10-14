package rw.plasmaco.ops.ui.production.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import rw.plasmaco.ops.domain.analytics.Analytics
import rw.plasmaco.ops.domain.common.Decimals
import rw.plasmaco.ops.domain.common.Issue
import rw.plasmaco.ops.domain.common.Severity
import rw.plasmaco.ops.domain.common.hasErrors
import rw.plasmaco.ops.domain.common.sumDecimal
import rw.plasmaco.ops.domain.master.MasterData
import rw.plasmaco.ops.domain.metrics.ProductionMetrics
import rw.plasmaco.ops.domain.production.DowntimeMath
import rw.plasmaco.ops.domain.production.ProductionReport
import rw.plasmaco.ops.domain.production.ReportValidator
import rw.plasmaco.ops.domain.production.ShiftHandover
import rw.plasmaco.ops.domain.security.Permission
import rw.plasmaco.ops.domain.security.Role as UserRole
import rw.plasmaco.ops.domain.units.UnitOfMeasure
import rw.plasmaco.ops.ui.common.ActionBar
import rw.plasmaco.ops.ui.common.DetailHeader
import rw.plasmaco.ops.ui.common.Fmt
import rw.plasmaco.ops.ui.common.IssueList
import rw.plasmaco.ops.ui.common.KeyValueRow
import rw.plasmaco.ops.ui.common.ScreenForm
import rw.plasmaco.ops.ui.common.messageFor
import rw.plasmaco.ops.ui.common.tone
import rw.plasmaco.ops.ui.components.AppTextField
import rw.plasmaco.ops.ui.components.ConfirmDialog
import rw.plasmaco.ops.ui.components.DateField
import rw.plasmaco.ops.ui.components.DropdownField
import rw.plasmaco.ops.ui.components.EmptyState
import rw.plasmaco.ops.ui.components.FieldHint
import rw.plasmaco.ops.ui.components.InsetCard
import rw.plasmaco.ops.ui.components.LoadingState
import rw.plasmaco.ops.ui.components.NoticeBanner
import rw.plasmaco.ops.ui.components.PlasmacoCard
import rw.plasmaco.ops.ui.components.PrimaryButton
import rw.plasmaco.ops.ui.components.SecondaryButton
import rw.plasmaco.ops.ui.components.SectionCard
import rw.plasmaco.ops.ui.components.StatusBadge
import rw.plasmaco.ops.ui.components.TextLink
import rw.plasmaco.ops.ui.components.TimeField
import rw.plasmaco.ops.ui.components.Tone
import rw.plasmaco.ops.ui.navigation.Navigator
import rw.plasmaco.ops.ui.navigation.Routes
import rw.plasmaco.ops.ui.theme.Plasmaco
import java.time.LocalDateTime

@Composable
fun ReportEditorScreen(nav: Navigator, vm: ReportEditorViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var confirmLeave by remember { mutableStateOf(false) }
    val report = ui.report

    BackHandler(enabled = ui.dirty) { confirmLeave = true }

    when {
        ui.loading -> { LoadingState(label = "Opening report"); return }
        ui.notFound || report == null -> { EmptyState("Report not found", "It may have been deleted.", actionText = "Back", onAction = nav::back); return }
        !ui.canEdit -> {
            EmptyState(
                "${report.reference} is ${report.status.label.lowercase()}",
                "Only drafts and reports returned or reopened for correction can be edited, by their author or a supervisor.",
                icon = Icons.Outlined.Lock, actionText = "Open report", onAction = { nav.open(Routes.detail(report.id)) },
            )
            return
        }
    }
    report!!

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = Plasmaco.dimens.gutter)) {
            DetailHeader(
                title = report.reference,
                subtitle = "${ui.master.line(report.lineId)?.name ?: "—"} · ${ui.master.shift(report.shiftId)?.name ?: "—"} · ${Fmt.date(report.date)}",
                onBack = { if (ui.dirty) confirmLeave = true else nav.back() },
            ) { StatusBadge(if (ui.dirty) "Unsaved" else report.status.label, if (ui.dirty) Tone.WARNING else report.status.tone()) }
            StepBar(ui, vm::goTo)
        }
        Box(Modifier.weight(1f)) {
            ScreenForm {
                val stepIssues = ui.issuesFor(ui.step)
                if (ui.step != EditorStep.REVIEW && stepIssues.isNotEmpty()) IssueList(stepIssues)
                when (ui.step) {
                    EditorStep.IDENTIFICATION -> IdentificationStep(ui, vm)
                    EditorStep.PRODUCTION -> ProductionStep(ui, vm)
                    EditorStep.MATERIALS -> MaterialsStep(ui, vm)
                    EditorStep.DOWNTIME -> DowntimeStep(ui, vm)
                    EditorStep.QUALITY -> QualityStep(ui, vm)
                    EditorStep.HANDOVER -> HandoverStep(ui, vm)
                    EditorStep.REVIEW -> ReviewStep(ui, vm)
                }
            }
        }
        ActionBar {
            if (ui.step == EditorStep.REVIEW) {
                SecondaryButton("Preview", { vm.save(onSaved = { nav.open(Routes.preview(report.id)) }) }, Modifier.weight(1f), icon = Icons.Outlined.Visibility, fillWidth = false)
                PrimaryButton(
                    "Submit", { vm.submit { id -> nav.back(); nav.open(Routes.detail(id)) } }, Modifier.weight(1f), icon = Icons.Outlined.Send,
                    loading = busy, enabled = ui.submitIssues?.hasErrors() == false, fillWidth = false,
                )
            } else {
                SecondaryButton("Save draft", { vm.save() }, Modifier.weight(1f), loading = busy, fillWidth = false)
                PrimaryButton("Save and continue", { vm.saveAndContinue() }, Modifier.weight(1f), loading = busy, fillWidth = false)
            }
        }
    }

    if (confirmLeave) {
        ConfirmDialog(
            "Leave without saving?", "Your changes to this report are only on this screen. Save them as a draft first, or discard them.",
            confirmText = "Discard changes", destructive = true,
            onConfirm = { confirmLeave = false; nav.back() }, onDismiss = { confirmLeave = false },
        )
    }
}

@Composable
private fun StepBar(ui: EditorUi, onStep: (EditorStep) -> Unit) {
    val c = Plasmaco.colors
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        EditorStep.entries.forEach { s ->
            val selected = s == ui.step
            val errors = ui.issuesFor(s).count { it.severity == Severity.ERROR }
            val bg = if (selected) c.primary else c.surface
            val fg = if (selected) c.onPrimary else c.textPrimary
            Row(
                Modifier.heightIn(min = 40.dp).background(bg, Plasmaco.shapes.chip).border(1.dp, if (selected) c.primary else c.border, Plasmaco.shapes.chip)
                    .clickable(role = Role.Tab) { onStep(s) }.padding(horizontal = 12.dp, vertical = 8.dp)
                    .semantics { this.selected = selected; contentDescription = "Step ${s.ordinal + 1}, ${s.title}${if (errors > 0) ", $errors to fix" else ""}" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${s.ordinal + 1}", style = Plasmaco.type.labelStrong, color = if (selected) c.onPrimary else c.textSecondary)
                Spacer(Modifier.width(6.dp))
                Text(s.title, style = Plasmaco.type.chip, color = fg)
                if (errors > 0) {
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.size(18.dp).background(c.danger, Plasmaco.shapes.circle), contentAlignment = Alignment.Center) {
                        Text("$errors", style = Plasmaco.type.caption, color = c.onPrimary)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ steps

@Composable
private fun IdentificationStep(ui: EditorUi, vm: ReportEditorViewModel) {
    val r = ui.report ?: return
    val m = ui.master
    val actor = ui.actor
    val issues = ui.issues
    PlasmacoCard {
        DateField("Production date", r.date, vm::setDate, maxDate = ui.today, required = true, error = issues.messageFor("date"))
        Spacer(Modifier.height(12.dp))
        DropdownField("Production line", m.lines.filter { it.active || it.id == r.lineId }, m.line(r.lineId), { vm.setLine(it.id) }, { it.name }, required = true, error = issues.messageFor("lineId"))
        Spacer(Modifier.height(12.dp))
        DropdownField(
            "Shift", m.shifts.filter { it.active || it.id == r.shiftId }, m.shift(r.shiftId), { vm.setShift(it.id) }, { it.name }, required = true,
            error = issues.messageFor("shiftId"), itemSupporting = { "${Fmt.time(it.start)} – ${Fmt.time(it.end)}" },
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TimeField("Start", r.startTime, vm::setStart, Modifier.weight(1f), required = true, error = issues.messageFor("startTime"))
            TimeField("End", r.endTime, vm::setEnd, Modifier.weight(1f), required = true, error = issues.messageFor("endTime"))
        }
        val st = r.startTime
        val et = r.endTime
        if (st != null && et != null && !et.isAfter(st)) FieldHint("Ends the next day (crosses midnight).")
    }
    PlasmacoCard {
        val operators = ui.users.filter { it.active && (UserRole.OPERATOR in it.roles || UserRole.SUPERVISOR in it.roles || it.id == r.operatorId) }
        if (actor?.can(Permission.REPORT_EDIT_ANY_DRAFT) == true) {
            DropdownField("Operator", operators, operators.firstOrNull { it.id == r.operatorId }, { vm.setOperator(it.id) }, { it.displayName }, required = true, error = issues.messageFor("operatorId"))
        } else {
            KeyValueRow("Operator", ui.users.firstOrNull { it.id == r.operatorId }?.displayName ?: actor?.displayName.orEmpty())
        }
        Spacer(Modifier.height(12.dp))
        val supervisors = ui.users.filter { it.active && UserRole.SUPERVISOR in it.roles }
        DropdownField("Supervisor on duty", supervisors, supervisors.firstOrNull { it.id == r.supervisorId }, { vm.setSupervisor(it.id) }, { it.displayName }, placeholder = "Not specified")
        Spacer(Modifier.height(12.dp))
        AppTextField(r.remarks, vm::setRemarks, "General remarks", singleLine = false, minLines = 3)
    }
}

@Composable
private fun ItemCard(title: String, lines: List<String>, issues: List<Issue>, onEdit: () -> Unit, onRemove: () -> Unit) {
    PlasmacoCard(onClick = onEdit) {
        Text(title, style = Plasmaco.type.cardTitle, color = Plasmaco.colors.textPrimary)
        lines.forEach { Text(it, style = Plasmaco.type.bodySmall, color = Plasmaco.colors.textSecondary) }
        if (issues.isNotEmpty()) { Spacer(Modifier.height(8.dp)); IssueList(issues) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextLink("Remove", onRemove)
            TextLink("Edit", onEdit)
        }
    }
}

@Composable
private fun AddButton(text: String, onClick: () -> Unit) = SecondaryButton(text, onClick, icon = Icons.Outlined.Add)

@Composable
private fun ProductionStep(ui: EditorUi, vm: ReportEditorViewModel) {
    val r = ui.report ?: return
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<String?>(null) }
    r.entries.forEachIndexed { i, e ->
        val p = ui.master.product(e.productId)
        val ach = ProductionMetrics.entryAchievement(e.good, e.target)
        ItemCard(
            p?.name ?: "Unknown product",
            listOfNotNull(
                "Good ${Fmt.qty(e.good, e.unit)} · rejected ${Fmt.qty(e.rejected, e.unit)} · gross ${Fmt.qty(e.gross, e.unit)}",
                e.target?.let { "Target ${Fmt.qty(it, e.unit)}${ach?.let { a -> " · ${Fmt.number(a, 1)}% achieved" } ?: ""}" } ?: "No target",
                e.machineId?.let { ui.master.machine(it)?.name },
                e.batch?.let { "Batch $it" },
            ),
            ui.issues.filter { it.field.startsWith("entries[$i]") },
            onEdit = { editing = e.id }, onRemove = { removing = e.id },
        )
    }
    if (r.entries.isEmpty()) NoticeBanner("Add each product made on this shift. A report needs at least one product before it can be submitted.", Tone.INFO)
    else {
        val metres = r.entries.filter { it.unit == UnitOfMeasure.METRE }
        InsetCard {
            KeyValueRow("Good output", Fmt.qty(metres.map { it.good }.sumDecimal(), UnitOfMeasure.METRE), emphasise = true)
            KeyValueRow("Rejected output", Fmt.qty(metres.map { it.rejected }.sumDecimal(), UnitOfMeasure.METRE))
            val gross = metres.map { it.gross }.sumDecimal()
            KeyValueRow("Rejection rate", Fmt.number(Decimals.percent(metres.map { it.rejected }.sumDecimal(), gross), 1) + "%")
            if (metres.size < r.entries.size) FieldHint("Totals include metre entries only; other units are listed per product.")
        }
    }
    AddButton("Add product") { editing = "new" }

    editing?.let { id ->
        val idx = r.entries.indexOfFirst { it.id == id }
        EntrySheet(r, ui.master, r.entries.getOrNull(idx), if (idx < 0) r.entries.size else idx, ui.issues, { vm.upsertEntry(it); editing = null }, { editing = null })
    }
    removing?.let { id ->
        ConfirmDialog("Remove this product?", "Its output and any link from inspections will be removed from the report.", "Remove",
            { vm.removeEntry(id); removing = null }, { removing = null }, destructive = true)
    }
}

@Composable
private fun MaterialsStep(ui: EditorUi, vm: ReportEditorViewModel) {
    val r = ui.report ?: return
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<String?>(null) }
    r.materials.forEachIndexed { i, mc ->
        val mat = ui.master.material(mc.materialId)
        ItemCard(
            mat?.name ?: "Unknown material",
            listOfNotNull(
                "Issued ${Fmt.qty(mc.issued, mc.unit)}${if (mc.returned.signum() != 0) " · returned ${Fmt.qty(mc.returned, mc.unit)}" else ""}",
                if (mc.lineOpening != null || mc.lineClosing != null) "Line-side ${Fmt.qty(mc.lineOpening, mc.unit)} → ${Fmt.qty(mc.lineClosing, mc.unit)}" else null,
                "Consumed ${Fmt.qty(mc.consumed, mc.unit)}${if (mc.scrap.signum() != 0) " (scrap ${Fmt.qty(mc.scrap, mc.unit)})" else ""}",
            ),
            ui.issues.filter { it.field.startsWith("materials[$i]") },
            onEdit = { editing = mc.id }, onRemove = { removing = mc.id },
        )
    }
    val theoretical = ReportValidator.theoreticalResinKg(r, ui.master)
    val consumed = resinConsumedKg(r, ui.master)
    SectionCard("Resin check", subtitle = "Theoretical use from product weights (ISO 4427-2 kg/m) against resin consumed") {
        KeyValueRow("Theoretical resin", theoretical?.let { Fmt.qty(it, UnitOfMeasure.KILOGRAM) } ?: "Not available (a product has no kg/m)")
        KeyValueRow("Resin consumed", consumed?.let { Fmt.qty(it, UnitOfMeasure.KILOGRAM) } ?: "—")
        if (theoretical != null && consumed != null && theoretical.signum() > 0) {
            val variance = Decimals.percent(consumed - theoretical, theoretical)
            KeyValueRow("Variance", Fmt.number(variance, 1) + "%", emphasise = true)
        }
    }
    if (r.materials.isEmpty()) NoticeBanner("Record the resin and other materials drawn for this shift. Consumption is calculated, never typed.", Tone.INFO)
    AddButton("Add material") { editing = "new" }

    editing?.let { id ->
        val idx = r.materials.indexOfFirst { it.id == id }
        MaterialSheet(ui.master, r.materials.getOrNull(idx), if (idx < 0) r.materials.size else idx, ui.issues, { vm.upsertMaterial(it); editing = null }, { editing = null })
    }
    removing?.let { id ->
        ConfirmDialog("Remove this material line?", "The quantities on it will be removed from the report.", "Remove", { vm.removeMaterial(id); removing = null }, { removing = null }, destructive = true)
    }
}

@Composable
private fun DowntimeStep(ui: EditorUi, vm: ReportEditorViewModel) {
    val r = ui.report ?: return
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<String?>(null) }
    r.downtime.forEachIndexed { i, d ->
        val cat = ui.master.downtimeCategory(d.categoryId ?: "")
        ItemCard(
            "${Fmt.time(d.start.toLocalTime())} – ${d.end?.let { Fmt.time(it.toLocalTime()) } ?: "still stopped"} · ${Fmt.minutes(d.minutes)}",
            listOfNotNull(
                listOfNotNull(d.machineId?.let { ui.master.machine(it)?.name } ?: "Whole line", cat?.let { "${it.name}${if (it.planned) " (planned)" else ""}" }).joinToString(" · "),
                d.reason.ifBlank { null },
                d.correctiveAction.ifBlank { null }?.let { "Action: $it" },
            ),
            ui.issues.filter { it.field.startsWith("downtime[$i]") },
            onEdit = { editing = d.id }, onRemove = { removing = d.id },
        )
    }
    if (r.downtime.isEmpty()) NoticeBanner("No stoppages recorded. Add every stop, planned or not, so availability is correct.", Tone.INFO)
    else InsetCard { KeyValueRow("Total downtime (overlaps counted once)", Fmt.minutes(DowntimeMath.mergedMinutes(r.downtime)), emphasise = true) }
    AddButton("Add stoppage") { editing = "new" }

    editing?.let { id ->
        val idx = r.downtime.indexOfFirst { it.id == id }
        DowntimeSheet(r, ui.master, r.downtime.getOrNull(idx), if (idx < 0) r.downtime.size else idx, ui.issues, { vm.upsertDowntime(it); editing = null }, { editing = null })
    }
    removing?.let { id ->
        ConfirmDialog("Remove this stoppage?", "It will no longer count towards downtime.", "Remove", { vm.removeDowntime(id); removing = null }, { removing = null }, destructive = true)
    }
}

@Composable
private fun QualityStep(ui: EditorUi, vm: ReportEditorViewModel) {
    val r = ui.report ?: return
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<String?>(null) }
    r.inspections.forEachIndexed { i, q ->
        ItemCard(
            "${Analytics.shortName(ui.master.product(q.productId)?.name ?: "Unknown product")} · ${q.result.label}",
            listOfNotNull(
                "${Fmt.time(q.time.toLocalTime())} · sample ${Fmt.qty(q.sampleQty, q.unit)} · accepted ${Fmt.qty(q.accepted, q.unit)} · rejected ${Fmt.qty(q.rejected, q.unit)}",
                q.defectCategoryId?.let { id -> ui.master.defectCategories.firstOrNull { it.id == id }?.name?.let { "Defect: $it" } },
                q.correctiveAction.ifBlank { null }?.let { "Action: $it" },
            ),
            ui.issues.filter { it.field.startsWith("inspections[$i]") },
            onEdit = { editing = q.id }, onRemove = { removing = q.id },
        )
    }
    if (r.inspections.isEmpty()) NoticeBanner("Record quality checks made during the shift (dimensions, appearance, pressure tests).", Tone.INFO)
    AddButton("Add inspection") { editing = "new" }

    editing?.let { id ->
        val idx = r.inspections.indexOfFirst { it.id == id }
        InspectionSheet(
            r, ui.master, r.inspections.getOrNull(idx), if (idx < 0) r.inspections.size else idx, ui.issues, ui.actor?.userId.orEmpty(),
            LocalDateTime.now().toLocalTime(), { vm.upsertInspection(it); editing = null }, { editing = null },
        )
    }
    removing?.let { id ->
        ConfirmDialog("Remove this inspection?", "The inspection record will be removed from the report.", "Remove", { vm.removeInspection(id); removing = null }, { removing = null }, destructive = true)
    }
}

@Composable
private fun HandoverStep(ui: EditorUi, vm: ReportEditorViewModel) {
    val r = ui.report ?: return
    val h = r.handover ?: ShiftHandover()
    PlasmacoCard {
        Text("For the next shift", style = Plasmaco.type.sectionTitle, color = Plasmaco.colors.textPrimary)
        Text("Everything the incoming team must know. Leave a field empty if there is nothing to report.", style = Plasmaco.type.label, color = Plasmaco.colors.textSecondary)
        listOf<Triple<String, String, (String) -> ShiftHandover>>(
            Triple("Outstanding production", h.outstandingProduction) { v -> h.copy(outstandingProduction = v) },
            Triple("Machine condition", h.machineCondition) { v -> h.copy(machineCondition = v) },
            Triple("Pending maintenance", h.pendingMaintenance) { v -> h.copy(pendingMaintenance = v) },
            Triple("Material shortages", h.materialShortages) { v -> h.copy(materialShortages = v) },
            Triple("Quality issues", h.qualityIssues) { v -> h.copy(qualityIssues = v) },
            Triple("Safety incidents", h.safetyIncidents) { v -> h.copy(safetyIncidents = v) },
            Triple("Instructions for the next shift", h.instructions) { v -> h.copy(instructions = v) },
        ).forEach { (label, value, update) ->
            Spacer(Modifier.height(12.dp))
            AppTextField(value, { vm.setHandover(update(it)) }, label, singleLine = false, minLines = 2)
        }
    }
}

@Composable
private fun ReviewStep(ui: EditorUi, vm: ReportEditorViewModel) {
    val r = ui.report ?: return
    val submit = ui.submitIssues
    ReviewSummary(r, ui.master)
    when {
        submit == null -> LoadingState(Modifier.heightIn(max = 120.dp), label = "Checking the report")
        submit.isEmpty() -> NoticeBanner("Everything required is filled in. Preview the report or submit it for approval.", Tone.SUCCESS, icon = Icons.Outlined.Check)
        else -> {
            val errors = submit.count { it.severity == Severity.ERROR }
            NoticeBanner(
                if (errors > 0) "$errors item(s) must be fixed before submitting. Tap one to go to it." else "Warnings only: you can submit, but add a remark where asked.",
                if (errors > 0) Tone.DANGER else Tone.WARNING,
            )
            IssueList(submit, onOpen = { vm.goTo(EditorStep.of(it.field)) })
        }
    }
    FieldHint("Submitting stores a version of the report that cannot be changed; corrections need it to be returned or reopened.")
}

@Composable
fun ReviewSummary(r: ProductionReport, m: MasterData) {
    val s = ProductionMetrics.summarize(listOf(r), m)
    SectionCard("Summary") {
        KeyValueRow("Products", r.entries.size.toString())
        KeyValueRow("Good output", Fmt.qty(s.good, UnitOfMeasure.METRE), emphasise = true)
        KeyValueRow("Rejected output", Fmt.qty(s.rejected, UnitOfMeasure.METRE))
        KeyValueRow("Target achievement", if (s.target.signum() > 0) Fmt.number(Decimals.percent(s.targetedGood, s.target), 1) + "%" else "No target")
        KeyValueRow("Material consumed", Fmt.qty(s.materialConsumedKg, UnitOfMeasure.KILOGRAM))
        KeyValueRow("Downtime", Fmt.minutes(s.downtimeMinutes))
        KeyValueRow("Inspections", r.inspections.size.toString())
        KeyValueRow("Handover", if (r.handover == null) "Nothing recorded" else "Recorded")
    }
}
