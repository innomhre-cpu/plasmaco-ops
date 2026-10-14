package rw.plasmaco.ops.ui.production.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import rw.plasmaco.ops.domain.analytics.Analytics
import rw.plasmaco.ops.domain.inventory.TxnType
import rw.plasmaco.ops.domain.master.ItemKind
import rw.plasmaco.ops.domain.production.InspectionResult
import rw.plasmaco.ops.domain.production.ProductionReport
import rw.plasmaco.ops.domain.production.ReportDiff
import rw.plasmaco.ops.domain.production.ReportStatus
import rw.plasmaco.ops.domain.security.Permission
import rw.plasmaco.ops.domain.workflow.ReportAction
import rw.plasmaco.ops.ui.common.ActionBar
import rw.plasmaco.ops.ui.common.DetailHeader
import rw.plasmaco.ops.ui.common.ExportEffects
import rw.plasmaco.ops.ui.common.Fmt
import rw.plasmaco.ops.ui.common.KeyValueRow
import rw.plasmaco.ops.ui.common.ListRow
import rw.plasmaco.ops.ui.common.RowDivider
import rw.plasmaco.ops.ui.common.ScreenList
import rw.plasmaco.ops.ui.common.tone
import rw.plasmaco.ops.ui.components.AppBottomSheet
import rw.plasmaco.ops.ui.components.CircleIconButton
import rw.plasmaco.ops.ui.components.ConfirmDialog
import rw.plasmaco.ops.ui.components.DateField
import rw.plasmaco.ops.ui.components.EmptyState
import rw.plasmaco.ops.ui.components.FieldHint
import rw.plasmaco.ops.ui.components.LoadingState
import rw.plasmaco.ops.ui.components.NoticeBanner
import rw.plasmaco.ops.ui.components.PlasmacoCard
import rw.plasmaco.ops.ui.components.PrimaryButton
import rw.plasmaco.ops.ui.components.ReasonDialog
import rw.plasmaco.ops.ui.components.SecondaryButton
import rw.plasmaco.ops.ui.components.SectionCard
import rw.plasmaco.ops.ui.components.StatusBadge
import rw.plasmaco.ops.ui.components.SyncStatusPill
import rw.plasmaco.ops.ui.components.TextLink
import rw.plasmaco.ops.ui.components.Tone
import rw.plasmaco.ops.ui.navigation.Navigator
import rw.plasmaco.ops.ui.navigation.Routes
import rw.plasmaco.ops.ui.production.editor.ReviewSummary
import rw.plasmaco.ops.ui.theme.Plasmaco

@Composable
fun ReportDetailScreen(nav: Navigator, preview: Boolean, vm: ReportDetailViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var reasonFor by remember { mutableStateOf<ReportAction?>(null) }
    var confirmApprove by remember { mutableStateOf(false) }
    var duplicating by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var versionShown by rememberSaveable { mutableStateOf<Int?>(null) }
    ExportEffects(vm)

    val r = ui.report
    val actor = ui.actor
    when {
        ui.loading -> { LoadingState(label = "Opening report"); return }
        r == null || actor == null -> { EmptyState("Report not found", "It may have been deleted, or it has not reached this device yet.", actionText = "Back", onAction = nav::back); return }
        !ui.canView -> { EmptyState("No access", "You can only open reports you created or operated.", actionText = "Back", onAction = nav::back); return }
    }
    r!!
    actor!!
    val line = ui.master.line(r.lineId)?.name ?: "—"
    val shift = ui.master.shift(r.shiftId)?.name ?: "—"

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            ScreenList {
                item {
                    DetailHeader(r.reference, nav::back, subtitle = "$line · $shift · ${Fmt.date(r.date)}") {
                        CircleIconButton(Icons.Outlined.PictureAsPdf, "Save as PDF", { vm.exportPdf(share = false) })
                        CircleIconButton(Icons.Outlined.Share, "Share PDF", { vm.exportPdf(share = true) })
                    }
                }
                if (preview) item { NoticeBanner("Preview: this is the report as it will be submitted and printed.", Tone.INFO, icon = Icons.Outlined.Visibility) }
                item { StatusCard(ui, r) }
                item { ReviewSummary(r, ui.master) }
                item { IdentificationCard(ui, r, line, shift) }
                item { EntriesCard(ui, r, nav) }
                if (r.materials.isNotEmpty()) item { MaterialsCard(ui, r) }
                if (r.downtime.isNotEmpty()) item { DowntimeCard(ui, r) }
                if (r.inspections.isNotEmpty()) item { QualityCard(ui, r) }
                r.handover?.let { h ->
                    item {
                        SectionCard("Shift handover") {
                            listOf(
                                "Outstanding production" to h.outstandingProduction, "Machine condition" to h.machineCondition,
                                "Pending maintenance" to h.pendingMaintenance, "Material shortages" to h.materialShortages,
                                "Quality issues" to h.qualityIssues, "Safety incidents" to h.safetyIncidents, "Instructions" to h.instructions,
                            ).filter { it.second.isNotBlank() }.forEach { (k, v) -> KeyValueRow(k, v) }
                        }
                    }
                }
                if (r.remarks.isNotBlank()) item { SectionCard("Remarks") { Text(r.remarks, style = Plasmaco.type.body, color = Plasmaco.colors.textPrimary) } }
                item { ApprovalsCard(ui) }
                if (ui.versions.isNotEmpty()) {
                    item {
                        SectionCard("Submitted versions", subtitle = "Each submission is stored unchanged") {
                            ui.versions.forEachIndexed { i, v ->
                                if (i > 0) RowDivider()
                                ListRow("Version ${v.version}", subtitle = "${Fmt.epoch(v.createdAt, ui.zone)} · ${ui.name(v.createdBy)}", onClick = { versionShown = v.version })
                            }
                        }
                    }
                }
                if (ui.audit.isNotEmpty()) {
                    item {
                        SectionCard("Audit trail") {
                            ui.audit.take(30).forEachIndexed { i, a ->
                                if (i > 0) RowDivider()
                                ListRow(a.summary, subtitle = "${Fmt.epoch(a.at, ui.zone)} · ${a.actorName} · ${a.action}")
                            }
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (actor.can(Permission.REPORT_DUPLICATE)) TextLink("Duplicate as new draft", { duplicating = true })
                        if (ui.canEdit && r.status == ReportStatus.DRAFT && r.version == 0) TextLink("Delete draft", { deleting = true })
                    }
                }
            }
        }
        val actions = ui.actions
        if (actions.isNotEmpty() || ui.canEdit) {
            ActionBar {
                if (ui.canEdit) SecondaryButton("Edit", { nav.open(Routes.editor(r.id)) }, Modifier.weight(1f), icon = Icons.Outlined.Edit, fillWidth = false)
                actions.filter { it != ReportAction.APPROVE && it != ReportAction.SUBMIT }.forEach { a ->
                    SecondaryButton(
                        if (a == ReportAction.RETURN) "Return" else a.label, { reasonFor = a }, Modifier.weight(1f),
                        destructive = a == ReportAction.REJECT, fillWidth = false,
                    )
                }
                if (ReportAction.SUBMIT in actions) {
                    PrimaryButton("Submit", { vm.act(ReportAction.SUBMIT, null) }, Modifier.weight(1f), loading = busy, fillWidth = false)
                }
                if (ReportAction.APPROVE in actions) {
                    PrimaryButton("Approve", { confirmApprove = true }, Modifier.weight(1f), loading = busy, fillWidth = false)
                }
            }
        }
    }

    reasonFor?.let { a ->
        ReasonDialog(
            title = when (a) {
                ReportAction.REJECT -> "Reject ${r.reference}?"
                ReportAction.RETURN -> "Return ${r.reference} for correction?"
                else -> "Reopen ${r.reference}?"
            },
            message = when (a) {
                ReportAction.REJECT -> "A rejected report is closed. The author will see your reason; a new report can then be created for this line and shift."
                ReportAction.RETURN -> "The author can correct and resubmit it. Explain what needs to change."
                else -> "Reopening an approved report lets it be corrected and approved again. The approved version stays in the history."
            },
            confirmText = a.label,
            destructive = a == ReportAction.REJECT,
            onConfirm = { reason -> reasonFor = null; vm.act(a, reason) },
            onDismiss = { reasonFor = null },
        )
    }
    if (confirmApprove) {
        ConfirmDialog(
            "Approve ${r.reference}?", "Approved figures count in analytics and can be received into stock. Changes afterwards need the report to be reopened.",
            "Approve", { confirmApprove = false; vm.act(ReportAction.APPROVE, null) }, { confirmApprove = false },
        )
    }
    if (deleting) {
        ConfirmDialog("Delete this draft?", "It has never been submitted. This cannot be undone.", "Delete",
            { deleting = false; vm.deleteDraft { nav.back() } }, { deleting = false }, destructive = true)
    }
    if (duplicating) {
        var date by rememberSaveable { mutableStateOf(ui.today) }
        AppBottomSheet("Duplicate as a new draft", { duplicating = false }) {
            DateField("Production date", date, { date = it }, maxDate = ui.today, required = true)
            FieldHint("Same line, shift and product list. Quantities, times, materials, downtime and inspections are not copied.")
            Spacer(Modifier.height(16.dp))
            PrimaryButton("Create draft", { vm.duplicate(date) { id -> duplicating = false; nav.open(Routes.editor(id)) } }, icon = Icons.Outlined.ContentCopy, loading = busy)
        }
    }
    versionShown?.let { v ->
        val snap = ui.versions.firstOrNull { it.version == v }
        if (snap != null) {
            val changes = ReportDiff.between(snap.report, r)
            AppBottomSheet("Version $v", { versionShown = null }) {
                Text("Submitted ${Fmt.epoch(snap.createdAt, ui.zone)} by ${ui.name(snap.createdBy)}", style = Plasmaco.type.bodySmall, color = Plasmaco.colors.textSecondary)
                Spacer(Modifier.height(12.dp))
                if (changes.isEmpty()) Text("The current report is identical to this version.", style = Plasmaco.type.body, color = Plasmaco.colors.textPrimary)
                else {
                    Text("${changes.size} difference(s) between this version and the current report", style = Plasmaco.type.bodyStrong, color = Plasmaco.colors.textPrimary)
                    changes.take(100).forEach { c -> KeyValueRow(c.path, "${c.before ?: "—"} → ${c.after ?: "—"}") }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(ui: DetailUi, r: ProductionReport) {
    PlasmacoCard {
        Row {
            StatusBadge(r.status.label, r.status.tone())
            Spacer(Modifier.weight(1f))
            ui.syncState?.let { SyncStatusPill(it) }
        }
        Spacer(Modifier.height(8.dp))
        KeyValueRow("Version", if (r.version == 0) "Not yet submitted" else r.version.toString())
        r.submittedAt?.let { KeyValueRow("Submitted", Fmt.instant(it, ui.zone)) }
        r.decidedAt?.let { KeyValueRow("Decided", "${Fmt.instant(it, ui.zone)} · ${ui.name(r.decidedBy)}") }
        ui.approvals.lastOrNull { it.reason != null && it.toStatus == r.status }?.let {
            Spacer(Modifier.height(8.dp))
            NoticeBanner("${it.action.label} by ${it.actorName}: ${it.reason}", if (r.status == ReportStatus.REJECTED) Tone.DANGER else Tone.WARNING)
        }
        r.duplicatedFrom?.let { KeyValueRow("Started from", "an earlier report (no quantities copied)") }
    }
}

@Composable
private fun IdentificationCard(ui: DetailUi, r: ProductionReport, line: String, shift: String) {
    SectionCard("Identification") {
        KeyValueRow("Date", Fmt.date(r.date))
        KeyValueRow("Line", line)
        KeyValueRow("Shift", shift)
        KeyValueRow("Time", "${Fmt.time(r.startTime)} – ${Fmt.time(r.endTime)}")
        KeyValueRow("Operator", ui.name(r.operatorId))
        KeyValueRow("Supervisor", ui.name(r.supervisorId))
        KeyValueRow("Created by", ui.name(r.createdBy))
    }
}

@Composable
private fun EntriesCard(ui: DetailUi, r: ProductionReport, nav: Navigator) {
    val canReceive = ui.actor?.can(Permission.INVENTORY_RECEIVE) == true && r.status == ReportStatus.APPROVED
    SectionCard("Production", subtitle = if (r.status == ReportStatus.APPROVED) "Stock moves only when the store records a receipt" else null) {
        if (r.entries.isEmpty()) Text("No products recorded.", style = Plasmaco.type.bodySmall, color = Plasmaco.colors.textSecondary)
        r.entries.forEachIndexed { i, e ->
            if (i > 0) RowDivider()
            val p = ui.master.product(e.productId)
            val received = ui.receivedByEntry[e.id]
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(p?.name ?: "Unknown product", style = Plasmaco.type.bodyStrong, color = Plasmaco.colors.textPrimary)
                Text(
                    "Good ${Fmt.qty(e.good, e.unit)} · rejected ${Fmt.qty(e.rejected, e.unit)} · gross ${Fmt.qty(e.gross, e.unit)}" +
                        (e.target?.let { " · target ${Fmt.qty(it, e.unit)}" } ?: ""),
                    style = Plasmaco.type.bodySmall, color = Plasmaco.colors.textSecondary,
                )
                listOfNotNull(e.machineId?.let { ui.master.machine(it)?.name }, e.batch?.let { "Batch $it" }, e.remarks.ifBlank { null })
                    .takeIf { it.isNotEmpty() }?.let { Text(it.joinToString(" · "), style = Plasmaco.type.label, color = Plasmaco.colors.textSecondary) }
                if (received != null) {
                    val outstanding = e.good - received
                    Text(
                        "Received into stock ${Fmt.qty(received, e.unit)}" + if (outstanding.signum() > 0) " · ${Fmt.qty(outstanding, e.unit)} not yet received" else "",
                        style = Plasmaco.type.label, color = if (outstanding.signum() > 0) Plasmaco.colors.warning else Plasmaco.colors.success,
                    )
                    if (canReceive && outstanding.signum() > 0) {
                        TextLink("Record production receipt", {
                            nav.open(Routes.movement(ItemKind.FINISHED_GOOD, e.productId, TxnType.PRODUCTION_RECEIPT.name, r.id, e.id))
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun MaterialsCard(ui: DetailUi, r: ProductionReport) {
    SectionCard("Materials") {
        r.materials.forEachIndexed { i, m ->
            if (i > 0) RowDivider()
            ListRow(
                ui.master.material(m.materialId)?.name ?: "Unknown material",
                subtitle = "Issued ${Fmt.qty(m.issued, m.unit)} · returned ${Fmt.qty(m.returned, m.unit)} · consumed ${Fmt.qty(m.consumed, m.unit)}" +
                    (if (m.scrap.signum() != 0) " · scrap ${Fmt.qty(m.scrap, m.unit)}" else "") + (m.batch?.let { " · batch $it" } ?: ""),
            )
        }
    }
}

@Composable
private fun DowntimeCard(ui: DetailUi, r: ProductionReport) {
    SectionCard("Downtime") {
        r.downtime.forEachIndexed { i, d ->
            if (i > 0) RowDivider()
            ListRow(
                "${Fmt.time(d.start.toLocalTime())} – ${d.end?.let { Fmt.time(it.toLocalTime()) } ?: "open"} · ${Fmt.minutes(d.minutes)}",
                subtitle = listOfNotNull(
                    d.machineId?.let { ui.master.machine(it)?.name } ?: "Whole line",
                    d.categoryId?.let { ui.master.downtimeCategory(it)?.name },
                    d.reason.ifBlank { null },
                    d.correctiveAction.ifBlank { null }?.let { "Action: $it" },
                ).joinToString(" · "),
            )
        }
    }
}

@Composable
private fun QualityCard(ui: DetailUi, r: ProductionReport) {
    SectionCard("Quality") {
        r.inspections.forEachIndexed { i, q ->
            if (i > 0) RowDivider()
            ListRow(
                "${Analytics.shortName(ui.master.product(q.productId)?.name ?: "Unknown")} · ${q.result.label}",
                subtitle = "${Fmt.time(q.time.toLocalTime())} · sample ${Fmt.qty(q.sampleQty, q.unit)} · accepted ${Fmt.qty(q.accepted, q.unit)} · rejected ${Fmt.qty(q.rejected, q.unit)}" +
                    (q.defectCategoryId?.let { id -> ui.master.defectCategories.firstOrNull { it.id == id }?.name?.let { " · $it" } } ?: "") +
                    " · inspector ${ui.name(q.inspectorId)}",
            ) { StatusBadge(q.result.label, when (q.result) { InspectionResult.PASS -> Tone.SUCCESS; InspectionResult.FAIL -> Tone.DANGER; InspectionResult.CONDITIONAL -> Tone.WARNING }) }
        }
    }
}

@Composable
private fun ApprovalsCard(ui: DetailUi) {
    SectionCard("Approval history") {
        if (ui.approvals.isEmpty()) Text("Not submitted yet.", style = Plasmaco.type.bodySmall, color = Plasmaco.colors.textSecondary)
        ui.approvals.forEachIndexed { i, a ->
            if (i > 0) RowDivider()
            ListRow(
                "${a.action.label} · ${a.actorName}",
                subtitle = "${Fmt.instant(a.at, ui.zone)} · ${a.fromStatus.label} → ${a.toStatus.label}${a.reason?.let { "\n$it" } ?: ""}",
            ) { StatusBadge("v${a.version}", Tone.NEUTRAL) }
        }
    }
}
