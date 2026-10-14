package rw.plasmaco.ops.ui.inventory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import rw.plasmaco.ops.data.repository.InventoryRepository
import rw.plasmaco.ops.data.repository.MasterDataRepository
import rw.plasmaco.ops.data.repository.SessionManager
import rw.plasmaco.ops.data.repository.UserRepository
import rw.plasmaco.ops.domain.common.Decimals
import rw.plasmaco.ops.domain.common.TimeSource
import rw.plasmaco.ops.domain.inventory.StockCount
import rw.plasmaco.ops.domain.inventory.StockCountStatus
import rw.plasmaco.ops.domain.master.ItemKind
import rw.plasmaco.ops.domain.master.MasterData
import rw.plasmaco.ops.domain.security.Actor
import rw.plasmaco.ops.domain.security.Permission
import rw.plasmaco.ops.domain.security.Role
import rw.plasmaco.ops.ui.common.ActionBar
import rw.plasmaco.ops.ui.common.DetailHeader
import rw.plasmaco.ops.ui.common.Fmt
import rw.plasmaco.ops.ui.common.ListCard
import rw.plasmaco.ops.ui.common.ListRow
import rw.plasmaco.ops.ui.common.PlasmacoViewModel
import rw.plasmaco.ops.ui.common.ScreenList
import rw.plasmaco.ops.ui.common.UserMessages
import rw.plasmaco.ops.ui.common.tone
import rw.plasmaco.ops.ui.components.AppBottomSheet
import rw.plasmaco.ops.ui.components.AppFilterChip
import rw.plasmaco.ops.ui.components.ConfirmDialog
import rw.plasmaco.ops.ui.components.DateField
import rw.plasmaco.ops.ui.components.EmptyState
import rw.plasmaco.ops.ui.components.FieldHint
import rw.plasmaco.ops.ui.components.FilterChipRow
import rw.plasmaco.ops.ui.components.LoadingState
import rw.plasmaco.ops.ui.components.NoticeBanner
import rw.plasmaco.ops.ui.components.NumericField
import rw.plasmaco.ops.ui.components.PageHeaderCard
import rw.plasmaco.ops.ui.components.PlasmacoCard
import rw.plasmaco.ops.ui.components.PrimaryButton
import rw.plasmaco.ops.ui.components.SearchField
import rw.plasmaco.ops.ui.components.SecondaryButton
import rw.plasmaco.ops.ui.components.StatusBadge
import rw.plasmaco.ops.ui.components.Tone
import rw.plasmaco.ops.ui.navigation.Navigator
import rw.plasmaco.ops.ui.navigation.Routes
import rw.plasmaco.ops.ui.theme.Plasmaco
import java.time.LocalDate
import javax.inject.Inject

data class CountsUi(
    val loading: Boolean = true,
    val actor: Actor? = null,
    val master: MasterData = MasterData(),
    val counts: List<StockCount> = emptyList(),
    val count: StockCount? = null,
    val userNames: Map<String, String> = emptyMap(),
    val today: LocalDate = LocalDate.now(),
)

/** Physical stock counts: a storekeeper counts, someone else approves, approval posts the variances. */
@HiltViewModel
class CountsViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val inventory: InventoryRepository,
    master: MasterDataRepository,
    users: UserRepository,
    session: SessionManager,
    time: TimeSource,
    messages: UserMessages,
) : PlasmacoViewModel(messages) {
    private val countId: String? = handle["id"]
    private val today = time.today()

    val state: StateFlow<CountsUi> = combine(
        inventory.counts, countId?.let { inventory.count(it) } ?: flowOf(null), master.master, users.users, session.user,
    ) { all, one, m, us, u ->
        CountsUi(false, u?.toActor(), m, all.sortedByDescending { it.date }, one, us.associate { it.id to it.displayName }, today)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CountsUi())

    fun start(kind: ItemKind, date: LocalDate, onStarted: (String) -> Unit) = action {
        inventory.startCount(kind, date).handle("Count started with today's system quantities")?.let { onStarted(it.id) }
    }

    /** Saves the counted quantities typed so far (the count stays a draft). */
    fun saveCounts(entered: Map<String, String>, submit: Boolean, onDone: () -> Unit = {}) = action {
        val c = state.value.count ?: return@action
        val bad = entered.filter { (_, v) -> v.isNotBlank() && Decimals.parseInput(v) == null }.keys
        if (bad.isNotEmpty()) {
            messages.show("${bad.size} quantity(ies) are not numbers")
            return@action
        }
        val updated = c.copy(lines = c.lines.map { l -> entered[l.itemId]?.let { v -> l.copy(countedQty = Decimals.parseInput(v)) } ?: l })
        val saved = inventory.updateCount(updated).handle(if (submit) null else "Count saved") ?: return@action
        if (submit) inventory.submitCount(saved).handle("Count submitted for approval")?.let { onDone() } else onDone()
    }

    fun decide(approve: Boolean, onDone: () -> Unit) = action {
        val c = state.value.count ?: return@action
        inventory.decideCount(c, approve).handle(if (approve) "Count approved; variances posted" else "Count rejected")?.let { onDone() }
    }
}

@Composable
fun CountsScreen(nav: Navigator, vm: CountsViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var starting by rememberSaveable { mutableStateOf(false) }
    val actor = ui.actor
    if (ui.loading || actor == null) { LoadingState(label = "Loading counts"); return }
    ScreenList {
        item {
            PageHeaderCard(
                "Stock counts", "Count what is physically in the store; approved counts correct the balances",
                actionText = if (actor.can(Permission.STOCK_COUNT)) "New count" else null, onAction = if (actor.can(Permission.STOCK_COUNT)) ({ starting = true }) else null,
            )
        }
        if (ui.counts.isEmpty()) item { EmptyState("No counts yet", "Start a count to compare the shelves with the system.", icon = Icons.Outlined.Checklist) }
        else item {
            ListCard(ui.counts) { c ->
                val variances = c.lines.count { (it.variance?.signum() ?: 0) != 0 }
                val counted = c.lines.count { it.countedQty != null }
                ListRow(
                    "${if (c.itemKind == ItemKind.FINISHED_GOOD) "Finished goods" else "Raw materials"} · ${Fmt.date(c.date)}",
                    subtitle = "$counted of ${c.lines.size} counted · $variances variance(s) · ${ui.userNames[c.createdBy] ?: "—"}",
                    onClick = { nav.open(Routes.count(c.id)) },
                ) { StatusBadge(Fmt.enumLabel(c.status.name), c.status.tone()) }
            }
        }
    }
    if (starting) {
        var kind by rememberSaveable { mutableStateOf(ItemKind.FINISHED_GOOD) }
        var date by rememberSaveable { mutableStateOf(ui.today) }
        AppBottomSheet("New stock count", { starting = false }) {
            FilterChipRow {
                AppFilterChip("Finished goods", kind == ItemKind.FINISHED_GOOD, { kind = ItemKind.FINISHED_GOOD })
                AppFilterChip("Raw materials", kind == ItemKind.RAW_MATERIAL, { kind = ItemKind.RAW_MATERIAL })
            }
            Spacer(Modifier.height(12.dp))
            DateField("Count date", date, { date = it }, maxDate = ui.today, required = true)
            FieldHint("The system quantity of every active item is captured now; count without looking at it.")
            Spacer(Modifier.height(16.dp))
            PrimaryButton("Start count", { vm.start(kind, date) { id -> starting = false; nav.open(Routes.count(id)) } }, loading = busy)
        }
    }
}

@Composable
fun CountScreen(nav: Navigator, vm: CountsViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val entered = remember { mutableStateMapOf<String, String>() }
    var query by rememberSaveable { mutableStateOf("") }
    var confirm by remember { mutableStateOf<Boolean?>(null) }
    val actor = ui.actor
    val c = ui.count
    if (ui.loading || actor == null) { LoadingState(label = "Loading count"); return }
    if (c == null) { EmptyState("Count not found", "It may not have reached this device yet.", actionText = "Back", onAction = nav::back); return }
    val editable = c.status == StockCountStatus.DRAFT && actor.can(Permission.STOCK_COUNT)
    val canDecide = c.status == StockCountStatus.SUBMITTED && actor.can(Permission.ADJUSTMENT_APPROVE) && (c.createdBy != actor.userId || actor.hasRole(Role.ADMIN))
    val m = ui.master
    val q = query.trim().lowercase().replace(" ", "")
    val lines = c.lines.filter { l -> q.isEmpty() || m.itemName(c.itemKind, l.itemId).lowercase().replace(" ", "").contains(q) }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            ScreenList {
                item {
                    DetailHeader(
                        "${if (c.itemKind == ItemKind.FINISHED_GOOD) "Finished goods" else "Raw materials"} count", nav::back,
                        subtitle = "${Fmt.date(c.date)} · ${ui.userNames[c.createdBy] ?: "—"}",
                    ) { StatusBadge(Fmt.enumLabel(c.status.name), c.status.tone()) }
                }
                if (c.status == StockCountStatus.SUBMITTED && !canDecide) item { NoticeBanner("Waiting for approval by someone other than the counter.", Tone.INFO) }
                item { SearchField(query, { query = it }, "Find an item") }
                lines.forEach { l ->
                    item(key = l.itemId) {
                        PlasmacoCard(padding = 12.dp) {
                            Text(m.itemName(c.itemKind, l.itemId), style = Plasmaco.type.bodyStrong, color = Plasmaco.colors.textPrimary)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text("System ${Fmt.qty(l.systemQty, l.unit)}", style = Plasmaco.type.label, color = Plasmaco.colors.textSecondary)
                                    val v = (entered[l.itemId]?.let { Decimals.parseInput(it) } ?: l.countedQty)?.minus(l.systemQty)
                                    if (v != null) Text(
                                        "Variance ${if (v.signum() > 0) "+" else ""}${Fmt.qty(v, l.unit)}", style = Plasmaco.type.labelStrong,
                                        color = if (v.signum() == 0) Plasmaco.colors.success else Plasmaco.colors.warning,
                                    )
                                }
                                if (editable) {
                                    NumericField(entered[l.itemId] ?: Fmt.input(l.countedQty), { entered[l.itemId] = it }, "Counted", l.unit.code, Modifier.weight(1f))
                                } else {
                                    Text(l.countedQty?.let { Fmt.qty(it, l.unit) } ?: "Not counted", style = Plasmaco.type.number, color = Plasmaco.colors.textPrimary, modifier = Modifier.padding(end = 4.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
        if (editable) {
            ActionBar {
                SecondaryButton("Save", { vm.saveCounts(entered.toMap(), submit = false) { entered.clear() } }, Modifier.weight(1f), loading = busy, fillWidth = false)
                PrimaryButton("Submit", { vm.saveCounts(entered.toMap(), submit = true) { nav.back() } }, Modifier.weight(1f), loading = busy, fillWidth = false)
            }
        } else if (canDecide) {
            ActionBar {
                SecondaryButton("Reject", { confirm = false }, Modifier.weight(1f), destructive = true, fillWidth = false)
                PrimaryButton("Approve", { confirm = true }, Modifier.weight(1f), loading = busy, fillWidth = false)
            }
        }
    }
    confirm?.let { approve ->
        val variances = c.lines.count { (it.variance?.signum() ?: 0) != 0 }
        ConfirmDialog(
            if (approve) "Approve this count?" else "Reject this count?",
            if (approve) "$variances variance posting(s) will correct the live balances. This cannot be undone; later corrections need a new count or an adjustment."
            else "No balances change. The counter can start a new count.",
            if (approve) "Approve" else "Reject",
            { confirm = null; vm.decide(approve) { nav.back() } }, { confirm = null }, destructive = !approve,
        )
    }
}
