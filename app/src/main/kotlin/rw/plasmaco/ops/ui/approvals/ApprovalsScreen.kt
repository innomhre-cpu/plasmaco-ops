package rw.plasmaco.ops.ui.approvals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Inventory
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import rw.plasmaco.ops.data.repository.InventoryRepository
import rw.plasmaco.ops.data.repository.MasterDataRepository
import rw.plasmaco.ops.data.repository.ReportRepository
import rw.plasmaco.ops.data.repository.SessionManager
import rw.plasmaco.ops.data.repository.UserRepository
import rw.plasmaco.ops.domain.common.TimeSource
import rw.plasmaco.ops.domain.inventory.InventoryTransaction
import rw.plasmaco.ops.domain.inventory.StockCount
import rw.plasmaco.ops.domain.inventory.StockCountStatus
import rw.plasmaco.ops.domain.master.ItemKind
import rw.plasmaco.ops.domain.master.MasterData
import rw.plasmaco.ops.domain.production.ProductionReport
import rw.plasmaco.ops.domain.production.ReportStatus
import rw.plasmaco.ops.domain.security.Actor
import rw.plasmaco.ops.domain.security.Permission
import rw.plasmaco.ops.domain.security.Role
import rw.plasmaco.ops.domain.workflow.ReportWorkflow
import rw.plasmaco.ops.ui.common.Fmt
import rw.plasmaco.ops.ui.common.ListCard
import rw.plasmaco.ops.ui.common.ListRow
import rw.plasmaco.ops.ui.common.PlasmacoViewModel
import rw.plasmaco.ops.ui.common.ScreenList
import rw.plasmaco.ops.ui.common.SectionTitle
import rw.plasmaco.ops.ui.common.UserMessages
import rw.plasmaco.ops.ui.components.EmptyState
import rw.plasmaco.ops.ui.components.LoadingState
import rw.plasmaco.ops.ui.components.PageHeaderCard
import rw.plasmaco.ops.ui.components.PlasmacoCard
import rw.plasmaco.ops.ui.components.ReasonDialog
import rw.plasmaco.ops.ui.components.SmallButton
import rw.plasmaco.ops.ui.components.StatusBadge
import rw.plasmaco.ops.ui.components.TextLink
import rw.plasmaco.ops.ui.components.Tone
import rw.plasmaco.ops.ui.navigation.Navigator
import rw.plasmaco.ops.ui.navigation.Routes
import rw.plasmaco.ops.ui.production.ReportRow
import rw.plasmaco.ops.ui.theme.Plasmaco
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

data class ApprovalsUi(
    val loading: Boolean = true,
    val actor: Actor? = null,
    val master: MasterData = MasterData(),
    val reports: List<ProductionReport> = emptyList(),
    val movements: List<InventoryTransaction> = emptyList(),
    val counts: List<StockCount> = emptyList(),
    val userNames: Map<String, String> = emptyMap(),
    val now: Instant = Instant.now(),
)

@HiltViewModel
class ApprovalsViewModel @Inject constructor(
    reports: ReportRepository,
    private val inventory: InventoryRepository,
    master: MasterDataRepository,
    users: UserRepository,
    session: SessionManager,
    private val time: TimeSource,
    messages: UserMessages,
) : PlasmacoViewModel(messages) {
    private val stock = combine(inventory.pending, inventory.counts) { p, c -> p to c }

    val state: StateFlow<ApprovalsUi> = combine(reports.reports, stock, master.master, users.users, session.user) { rs, (pending, counts), m, us, u ->
        val actor = u?.toActor() ?: return@combine ApprovalsUi(loading = false)
        ApprovalsUi(
            loading = false, actor = actor, master = m,
            reports = if (actor.can(Permission.REPORT_APPROVE)) rs.filter { it.status == ReportStatus.SUBMITTED && ReportWorkflow.canView(it, actor) }.sortedBy { it.submittedAt } else emptyList(),
            movements = if (actor.can(Permission.ADJUSTMENT_APPROVE)) pending.sortedBy { it.createdAt } else emptyList(),
            counts = if (actor.can(Permission.ADJUSTMENT_APPROVE)) counts.filter { it.status == StockCountStatus.SUBMITTED } else emptyList(),
            userNames = us.associate { it.id to it.displayName },
            now = time.now(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ApprovalsUi())

    fun decide(txn: InventoryTransaction, approve: Boolean, note: String?) = action {
        inventory.decide(txn.id, approve, note).handle(if (approve) "Movement approved and posted" else "Movement rejected")
    }
}

private fun waited(from: Instant?, now: Instant): String {
    if (from == null) return ""
    val h = Duration.between(from, now).toHours()
    return if (h < 1) "waiting under an hour" else if (h < 48) "waiting $h h" else "waiting ${h / 24} days"
}

@Composable
fun ApprovalsScreen(nav: Navigator, vm: ApprovalsViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    var rejecting by remember { mutableStateOf<InventoryTransaction?>(null) }
    val actor = ui.actor
    if (ui.loading || actor == null) {
        LoadingState(label = "Loading approvals")
        return
    }
    val total = ui.reports.size + ui.movements.size + ui.counts.size
    ScreenList {
        item { PageHeaderCard("Approvals", if (total == 0) "Nothing is waiting for you" else "$total item(s) waiting") }
        if (total == 0) item { EmptyState("All caught up", "Submitted reports, stock adjustments and stock counts that need a decision appear here.", icon = Icons.Outlined.DoneAll) }
        if (ui.reports.isNotEmpty()) {
            item { SectionTitle("Production reports") }
            item {
                ListCard(ui.reports) { r ->
                    ReportRow(r, ui.master, { nav.open(Routes.detail(r.id)) }, operatorName = "${ui.userNames[r.operatorId] ?: "—"} · ${waited(r.submittedAt, ui.now)}")
                }
            }
        }
        if (ui.movements.isNotEmpty()) {
            item { SectionTitle("Stock adjustments") }
            ui.movements.forEach { t ->
                item(key = t.id) {
                    PlasmacoCard {
                        ListRow(
                            "${t.type.label}: ${ui.master.itemName(t.itemKind, t.itemId)}",
                            subtitle = "${if (t.quantity.signum() > 0) "+" else ""}${Fmt.qty(t.quantity, t.unit)} · ${Fmt.date(t.date)} · requested by ${ui.userNames[t.createdBy] ?: "—"} · ${waited(t.createdAt, ui.now)}" +
                                (t.note?.let { "\n$it" } ?: ""),
                            icon = Icons.Outlined.SwapVert, iconTone = Tone.INFO,
                        ) { StatusBadge("Pending", Tone.INFO) }
                        if (t.createdBy == actor.userId && !actor.hasRole(Role.ADMIN)) {
                            Text("You requested this; another person must approve it.", style = Plasmaco.type.label, color = Plasmaco.colors.textSecondary)
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextLink("Reject", { rejecting = t })
                                SmallButton("Approve", { vm.decide(t, true, null) })
                            }
                        }
                    }
                }
            }
        }
        if (ui.counts.isNotEmpty()) {
            item { SectionTitle("Stock counts") }
            item {
                ListCard(ui.counts) { c ->
                    val variances = c.lines.count { (it.variance?.signum() ?: 0) != 0 }
                    ListRow(
                        "${if (c.itemKind == ItemKind.FINISHED_GOOD) "Finished goods" else "Raw materials"} count · ${Fmt.date(c.date)}",
                        subtitle = "$variances variance(s) · counted by ${ui.userNames[c.createdBy] ?: "—"}",
                        icon = Icons.Outlined.Inventory, onClick = { nav.open(Routes.count(c.id)) },
                    )
                }
            }
        }
    }
    rejecting?.let { t ->
        ReasonDialog(
            "Reject this ${t.type.label.lowercase()}?", "The movement will not change stock. The requester sees your reason.", "Reject",
            onConfirm = { reason -> rejecting = null; vm.decide(t, false, reason) }, onDismiss = { rejecting = null }, destructive = true,
        )
    }
}
