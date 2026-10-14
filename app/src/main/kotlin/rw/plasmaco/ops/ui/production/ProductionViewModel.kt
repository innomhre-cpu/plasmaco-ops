package rw.plasmaco.ops.ui.production

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import rw.plasmaco.ops.data.repository.MasterDataRepository
import rw.plasmaco.ops.data.repository.ReportRepository
import rw.plasmaco.ops.data.repository.SessionManager
import rw.plasmaco.ops.data.repository.UserRepository
import rw.plasmaco.ops.domain.common.TimeSource
import rw.plasmaco.ops.domain.master.MasterData
import rw.plasmaco.ops.domain.master.Shift
import rw.plasmaco.ops.domain.production.ProductionReport
import rw.plasmaco.ops.domain.production.ReportStatus
import rw.plasmaco.ops.domain.security.Actor
import rw.plasmaco.ops.domain.security.Permission
import rw.plasmaco.ops.domain.security.Role
import rw.plasmaco.ops.domain.security.User
import rw.plasmaco.ops.domain.workflow.ReportWorkflow
import rw.plasmaco.ops.ui.common.PlasmacoViewModel
import rw.plasmaco.ops.ui.common.UserMessages
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject

data class ProductionUi(
    val loading: Boolean = true,
    val actor: Actor? = null,
    val master: MasterData = MasterData(),
    val today: LocalDate = LocalDate.now(),
    val needsAttention: List<ProductionReport> = emptyList(),
    val drafts: List<ProductionReport> = emptyList(),
    val todays: List<ProductionReport> = emptyList(),
    val recent: List<ProductionReport> = emptyList(),
    val operators: List<User> = emptyList(),
    /** Shift running now, to preselect in the new-report form. */
    val currentShift: Shift? = null,
    /** Reports that can be used as a starting point (same line and shift, most recent first). */
    val duplicable: List<ProductionReport> = emptyList(),
)

@HiltViewModel
class ProductionViewModel @Inject constructor(
    private val handle: SavedStateHandle,
    private val reports: ReportRepository,
    master: MasterDataRepository,
    users: UserRepository,
    session: SessionManager,
    private val time: TimeSource,
    messages: UserMessages,
) : PlasmacoViewModel(messages) {

    val state: StateFlow<ProductionUi> = combine(reports.reports, master.master, users.users, session.user) { all, m, us, u ->
        val actor = u?.toActor() ?: return@combine ProductionUi(loading = false)
        val visible = all.filter { ReportWorkflow.canView(it, actor) }
        val mine = visible.filter { it.createdBy == actor.userId || it.operatorId == actor.userId }
        val today = time.today()
        val now = LocalDateTime.ofInstant(time.now(), time.zone()).toLocalTime()
        ProductionUi(
            loading = false,
            actor = actor,
            master = m,
            today = today,
            needsAttention = mine.filter { it.status == ReportStatus.RETURNED || it.status == ReportStatus.REOPENED }.sortedByDescending { it.updatedAt },
            drafts = visible.filter { it.status == ReportStatus.DRAFT && ReportWorkflow.canEdit(it, actor) }.sortedByDescending { it.updatedAt },
            todays = visible.filter { it.date == today }.sortedWith(compareBy({ m.line(it.lineId)?.name }, { m.shift(it.shiftId)?.start })),
            recent = mine.filter { it.status == ReportStatus.SUBMITTED || it.status == ReportStatus.APPROVED || it.status == ReportStatus.REJECTED }
                .sortedByDescending { it.submittedAt ?: it.updatedAt }.take(10),
            operators = us.filter { it.active && (Role.OPERATOR in it.roles || Role.SUPERVISOR in it.roles) }.sortedBy { it.displayName },
            currentShift = m.shifts.filter { it.active }.firstOrNull { s ->
                if (s.crossesMidnight) !now.isBefore(s.start) || now.isBefore(s.end) else !now.isBefore(s.start) && now.isBefore(s.end)
            },
            duplicable = visible.filter { it.entries.isNotEmpty() }.sortedByDescending { it.date }.take(30),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProductionUi())

    fun consumeOpenNew(): Boolean {
        val open = handle.get<Boolean>("new") == true
        handle["new"] = false
        return open
    }

    /** Creates an empty draft (or a copy of [fromReportId] without quantities) and opens it in the editor. */
    fun create(date: LocalDate, lineId: String?, shiftId: String?, operatorId: String?, fromReportId: String?, onCreated: (String) -> Unit) = action {
        if (lineId == null || shiftId == null) {
            messages.show("Choose a production line and a shift")
            return@action
        }
        val actor = state.value.actor ?: return@action
        val operator = if (actor.can(Permission.REPORT_EDIT_ANY_DRAFT)) operatorId else actor.userId
        val outcome = if (fromReportId != null) reports.duplicate(fromReportId, date) else reports.createDraft(date, shiftId, lineId, operator)
        outcome.handle(if (fromReportId != null) "Draft created from the earlier report (quantities are not copied)" else "Draft created")?.let { onCreated(it.id) }
    }

    fun deleteDraft(id: String) = action {
        if (reports.deleteDraft(id)) messages.show("Draft deleted") else messages.show("Only an unsubmitted draft you can edit can be deleted")
    }
}
