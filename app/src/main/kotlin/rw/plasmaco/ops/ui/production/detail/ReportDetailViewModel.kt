package rw.plasmaco.ops.ui.production.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import rw.plasmaco.ops.data.local.AuditEntity
import rw.plasmaco.ops.data.repository.AuditRepository
import rw.plasmaco.ops.data.repository.EntityTypes
import rw.plasmaco.ops.data.repository.ExportRepository
import rw.plasmaco.ops.data.repository.InventoryRepository
import rw.plasmaco.ops.data.repository.MasterDataRepository
import rw.plasmaco.ops.data.repository.ReportRepository
import rw.plasmaco.ops.data.repository.SessionManager
import rw.plasmaco.ops.data.repository.UserRepository
import rw.plasmaco.ops.data.repository.VersionSnapshot
import rw.plasmaco.ops.domain.analytics.Filters
import rw.plasmaco.ops.domain.common.DateRange
import rw.plasmaco.ops.domain.common.TimeSource
import rw.plasmaco.ops.domain.export.ExportFormat
import rw.plasmaco.ops.domain.inventory.Ledger
import rw.plasmaco.ops.domain.master.MasterData
import rw.plasmaco.ops.domain.production.ProductionReport
import rw.plasmaco.ops.domain.production.ReportStatus
import rw.plasmaco.ops.domain.reports.ReportType
import rw.plasmaco.ops.domain.security.Actor
import rw.plasmaco.ops.domain.security.Permission
import rw.plasmaco.ops.domain.sync.SyncState
import rw.plasmaco.ops.domain.workflow.ApprovalRecord
import rw.plasmaco.ops.domain.workflow.ReportAction
import rw.plasmaco.ops.domain.workflow.ReportWorkflow
import rw.plasmaco.ops.ui.common.ExportingViewModel
import rw.plasmaco.ops.ui.common.UserMessages
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class DetailUi(
    val loading: Boolean = true,
    val actor: Actor? = null,
    val report: ProductionReport? = null,
    val master: MasterData = MasterData(),
    val userNames: Map<String, String> = emptyMap(),
    val approvals: List<ApprovalRecord> = emptyList(),
    val versions: List<VersionSnapshot> = emptyList(),
    val audit: List<AuditEntity> = emptyList(),
    val actions: List<ReportAction> = emptyList(),
    val canEdit: Boolean = false,
    val canView: Boolean = false,
    val syncState: SyncState? = null,
    /** Quantity already received into finished-goods stock per entry id (approved reports). */
    val receivedByEntry: Map<String, BigDecimal> = emptyMap(),
    val today: LocalDate = LocalDate.now(),
    val zone: ZoneId = ZoneId.of("Africa/Kigali"),
) {
    fun name(id: String?): String = id?.let { userNames[it] } ?: "—"
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ReportDetailViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val reports: ReportRepository,
    master: MasterDataRepository,
    users: UserRepository,
    inventory: InventoryRepository,
    audit: AuditRepository,
    private val session: SessionManager,
    private val time: TimeSource,
    exports: ExportRepository,
    messages: UserMessages,
) : ExportingViewModel(messages, exports) {
    private val id: String = checkNotNull(handle["id"])

    private val auditFlow = if (session.actor?.can(Permission.AUDIT_VIEW) == true) audit.forEntity(EntityTypes.REPORT, id) else flowOf(emptyList())

    private data class Parts(val report: ProductionReport?, val approvals: List<ApprovalRecord>, val versions: List<VersionSnapshot>, val audit: List<AuditEntity>, val sync: String?)

    private val parts = combine(reports.report(id), reports.approvals(id), reports.versions(id), auditFlow, reports.syncState(id)) { r, a, v, au, s -> Parts(r, a, v, au, s) }

    val state: StateFlow<DetailUi> = combine(parts, master.master, users.users, inventory.transactions, session.user) { p, m, us, txns, u ->
        val actor = u?.toActor()
        val r = p.report
        val ledger = Ledger(txns)
        DetailUi(
            loading = false,
            actor = actor,
            report = r,
            master = m,
            userNames = us.associate { it.id to it.displayName },
            approvals = p.approvals.sortedBy { it.at },
            versions = p.versions.sortedByDescending { it.version },
            audit = p.audit.sortedByDescending { it.at },
            actions = if (r != null && actor != null) ReportWorkflow.availableActions(r, actor) else emptyList(),
            canEdit = r != null && actor != null && ReportWorkflow.canEdit(r, actor),
            canView = r != null && actor != null && ReportWorkflow.canView(r, actor),
            syncState = p.sync?.let { s -> runCatching { SyncState.valueOf(s) }.getOrNull() },
            receivedByEntry = if (r?.status == ReportStatus.APPROVED) r.entries.associate { it.id to ledger.receivedForEntry(it.id) } else emptyMap(),
            today = time.today(),
            zone = time.zone(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailUi())

    fun act(which: ReportAction, reason: String?, onDone: () -> Unit = {}) = action {
        reports.applyAction(id, which, reason).handle(
            when (which) {
                ReportAction.SUBMIT -> "Submitted for approval"
                ReportAction.APPROVE -> "Report approved"
                ReportAction.REJECT -> "Report rejected"
                ReportAction.RETURN -> "Returned to the author for correction"
                ReportAction.REOPEN -> "Report reopened for correction"
            },
        )?.let { onDone() }
    }

    fun duplicate(date: LocalDate, onCreated: (String) -> Unit) = action {
        reports.duplicate(id, date).handle("Draft created (quantities are not copied)")?.let { onCreated(it.id) }
    }

    fun deleteDraft(onDeleted: () -> Unit) = action {
        if (reports.deleteDraft(id)) {
            messages.show("Draft deleted")
            onDeleted()
        } else {
            messages.show("Only an unsubmitted draft can be deleted")
        }
    }

    fun exportPdf(share: Boolean) {
        val r = state.value.report ?: return
        export(ExportFormat.PDF, share) { exports.build(ReportType.DAILY_PRODUCTION, Filters(DateRange.single(r.date)), r.id) }
    }
}
