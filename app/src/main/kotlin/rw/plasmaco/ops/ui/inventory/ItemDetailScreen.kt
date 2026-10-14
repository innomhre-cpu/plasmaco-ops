package rw.plasmaco.ops.ui.inventory

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import rw.plasmaco.ops.data.repository.InventoryRepository
import rw.plasmaco.ops.data.repository.MasterDataRepository
import rw.plasmaco.ops.data.repository.SessionManager
import rw.plasmaco.ops.data.repository.UserRepository
import rw.plasmaco.ops.domain.common.DateRange
import rw.plasmaco.ops.domain.common.TimeSource
import rw.plasmaco.ops.domain.inventory.InventoryTransaction
import rw.plasmaco.ops.domain.inventory.Ledger
import rw.plasmaco.ops.domain.inventory.StockStatus
import rw.plasmaco.ops.domain.inventory.StockStatusRules
import rw.plasmaco.ops.domain.inventory.TxnOrigin
import rw.plasmaco.ops.domain.inventory.TxnStatus
import rw.plasmaco.ops.domain.master.ItemKind
import rw.plasmaco.ops.domain.master.MasterData
import rw.plasmaco.ops.domain.master.StockThreshold
import rw.plasmaco.ops.domain.security.Actor
import rw.plasmaco.ops.domain.security.Permission
import rw.plasmaco.ops.domain.units.UnitOfMeasure
import rw.plasmaco.ops.ui.common.DetailHeader
import rw.plasmaco.ops.ui.common.Fmt
import rw.plasmaco.ops.ui.common.KeyValueRow
import rw.plasmaco.ops.ui.common.ListRow
import rw.plasmaco.ops.ui.common.Period
import rw.plasmaco.ops.ui.common.RowDivider
import rw.plasmaco.ops.ui.common.ScreenList
import rw.plasmaco.ops.ui.common.label
import rw.plasmaco.ops.ui.common.tone
import rw.plasmaco.ops.ui.components.AppFilterChip
import rw.plasmaco.ops.ui.components.DateRangeDialog
import rw.plasmaco.ops.ui.components.DropdownChip
import rw.plasmaco.ops.ui.components.EmptyState
import rw.plasmaco.ops.ui.components.FilterChipRow
import rw.plasmaco.ops.ui.components.LoadingState
import rw.plasmaco.ops.ui.components.PlasmacoCard
import rw.plasmaco.ops.ui.components.PrimaryButton
import rw.plasmaco.ops.ui.components.SectionCard
import rw.plasmaco.ops.ui.components.StatusBadge
import rw.plasmaco.ops.ui.components.Tone
import rw.plasmaco.ops.ui.navigation.Navigator
import rw.plasmaco.ops.ui.navigation.Routes
import rw.plasmaco.ops.ui.theme.Plasmaco
import java.math.BigDecimal
import java.time.LocalDate
import javax.inject.Inject

data class ItemUi(
    val loading: Boolean = true,
    val actor: Actor? = null,
    val kind: ItemKind = ItemKind.FINISHED_GOOD,
    val itemId: String = "",
    val master: MasterData = MasterData(),
    val name: String = "",
    val unit: UnitOfMeasure = UnitOfMeasure.METRE,
    val balance: BigDecimal = BigDecimal.ZERO,
    val status: StockStatus = StockStatus.IN_STOCK,
    val threshold: StockThreshold? = null,
    val range: DateRange = DateRange(LocalDate.now().withDayOfMonth(1), LocalDate.now()),
    val includeHistory: Boolean = false,
    val statement: Ledger.Statement? = null,
    val pending: List<InventoryTransaction> = emptyList(),
    val userNames: Map<String, String> = emptyMap(),
    val today: LocalDate = LocalDate.now(),
)

/** One item's stock card: live balance, thresholds and a dated ledger with running balance. */
@HiltViewModel
class ItemDetailViewModel @Inject constructor(
    handle: SavedStateHandle,
    inventory: InventoryRepository,
    master: MasterDataRepository,
    users: UserRepository,
    session: SessionManager,
    time: TimeSource,
) : ViewModel() {
    private val kind = ItemKind.valueOf(checkNotNull(handle.get<String>("kind")))
    private val itemId: String = checkNotNull(handle["id"])
    private data class Options(val range: DateRange, val history: Boolean)
    private val options = MutableStateFlow(Options(Period.THIS_MONTH.range(time.today(), null), false))
    private val today = time.today()

    val state: StateFlow<ItemUi> = combine(inventory.item(kind, itemId), master.master, users.users, session.user, options) { txns, m, us, u, o ->
        val live = Ledger(txns)
        val th = m.threshold(kind, itemId)
        val bal = live.balance(kind, itemId)
        ItemUi(
            loading = false, actor = u?.toActor(), kind = kind, itemId = itemId, master = m, name = m.itemName(kind, itemId),
            unit = m.itemUnit(kind, itemId) ?: UnitOfMeasure.METRE, balance = bal,
            status = StockStatusRules.classify(bal, th?.minimum, th?.reorderPoint), threshold = th, range = o.range, includeHistory = o.history,
            statement = Ledger(txns, includeHistorical = o.history).statement(kind, itemId, o.range),
            pending = txns.filter { it.status == TxnStatus.PENDING_APPROVAL },
            userNames = us.associate { it.id to it.displayName } + ("importer" to "Workbook import"),
            today = today,
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ItemUi())

    fun setRange(r: DateRange) = options.update { it.copy(range = r) }
    fun setHistory(on: Boolean) = options.update { it.copy(history = on) }
}

@Composable
fun ItemDetailScreen(nav: Navigator, vm: ItemDetailViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    var pickRange by remember { mutableStateOf(false) }
    val actor = ui.actor
    if (ui.loading || actor == null) {
        LoadingState(label = "Loading item")
        return
    }
    val st = ui.statement
    ScreenList {
        item { DetailHeader(ui.name, nav::back, subtitle = if (ui.kind == ItemKind.FINISHED_GOOD) "Finished good" else "Raw material") }
        item {
            PlasmacoCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(Fmt.number(ui.balance, ui.unit.displayDecimals), style = Plasmaco.type.kpiValue, color = Plasmaco.colors.textPrimary)
                    Spacer(Modifier.width(6.dp))
                    Text(ui.unit.code, style = Plasmaco.type.kpiUnit, color = Plasmaco.colors.textSecondary)
                    Spacer(Modifier.weight(1f))
                    StatusBadge(ui.status.label, ui.status.tone())
                }
                Text("Live balance", style = Plasmaco.type.label, color = Plasmaco.colors.textSecondary)
                Spacer(Modifier.height(8.dp))
                KeyValueRow("Minimum stock", ui.threshold?.minimum?.let { Fmt.qty(it, ui.unit) } ?: "Not set")
                KeyValueRow("Reorder point", ui.threshold?.reorderPoint?.let { Fmt.qty(it, ui.unit) } ?: "Not set")
                if (ui.kind == ItemKind.FINISHED_GOOD) {
                    ui.master.product(ui.itemId)?.kgPerMetre?.let { kg -> KeyValueRow("Weight", "${Fmt.qty(ui.balance * kg, UnitOfMeasure.KILOGRAM)} at ${Fmt.number(kg, 3)} kg/m") }
                }
                if (ui.pending.isNotEmpty()) KeyValueRow("Awaiting approval", "${ui.pending.size} movement(s), not in the balance")
                val canMove = listOf(Permission.INVENTORY_RECEIVE, Permission.INVENTORY_ISSUE, Permission.INVENTORY_DISPATCH, Permission.INVENTORY_TRANSFER, Permission.ADJUSTMENT_REQUEST).any { actor.can(it) }
                if (canMove) {
                    Spacer(Modifier.height(12.dp))
                    PrimaryButton("Record movement", { nav.open(Routes.movement(ui.kind, ui.itemId)) }, icon = Icons.Outlined.AddCircleOutline)
                }
            }
        }
        item {
            FilterChipRow {
                DropdownChip("${Fmt.short(ui.range.start)} – ${Fmt.short(ui.range.end)}", { pickRange = true })
                AppFilterChip("This month", ui.range == Period.THIS_MONTH.range(ui.today, null), { vm.setRange(Period.THIS_MONTH.range(ui.today, null)) })
                AppFilterChip("Last month", ui.range == Period.LAST_MONTH.range(ui.today, null), { vm.setRange(Period.LAST_MONTH.range(ui.today, null)) })
                AppFilterChip("Imported history", ui.includeHistory, { vm.setHistory(!ui.includeHistory) }, icon = Icons.Outlined.History)
            }
        }
        if (st != null) {
            item {
                SectionCard("Movement summary", subtitle = if (ui.includeHistory) "Including imported workbook history" else "Live movements only") {
                    KeyValueRow("Opening", Fmt.qty(st.opening, ui.unit))
                    KeyValueRow(if (ui.kind == ItemKind.FINISHED_GOOD) "Receipts" else "Goods received", Fmt.qty(st.receipts, ui.unit))
                    KeyValueRow(if (ui.kind == ItemKind.FINISHED_GOOD) "Outward (dispatches)" else "Issued to production", Fmt.qty(st.issues, ui.unit))
                    KeyValueRow("Returns", Fmt.qty(st.returns, ui.unit))
                    KeyValueRow("Transfers", Fmt.qty(st.transfers, ui.unit))
                    KeyValueRow("Adjustments", Fmt.qty(st.adjustments, ui.unit))
                    KeyValueRow("Closing", Fmt.qty(st.closing, ui.unit), emphasise = true)
                    if (!st.reconciles) Text("The movement totals do not reconcile; report this to the administrator.", style = Plasmaco.type.label, color = Plasmaco.colors.danger)
                }
            }
            if (st.lines.isEmpty()) item { EmptyState("No movements in this period", "Choose a wider period or include the imported history.") }
            else item {
                SectionCard("Ledger", subtitle = "${st.lines.size} movement(s), newest first") {
                    st.lines.reversed().take(300).forEachIndexed { i, l ->
                        if (i > 0) RowDivider()
                        val t = l.txn
                        ListRow(
                            "${t.type.label} · ${if (t.quantity.signum() > 0) "+" else ""}${Fmt.qty(t.quantity, t.unit)}",
                            subtitle = listOfNotNull(
                                Fmt.date(t.date), "balance ${Fmt.qty(l.runningBalance, t.unit)}",
                                t.reference, t.counterparty, t.note,
                                if (t.origin == TxnOrigin.LEGACY_IMPORT) "from ${t.sourceRef ?: "workbook"}" else ui.userNames[t.createdBy],
                                if (t.negativeOverride) "negative-stock override" else null,
                            ).joinToString(" · "),
                            onClick = t.reportId?.let { id -> { nav.open(Routes.detail(id)) } },
                        ) {
                            if (t.historical) StatusBadge("History", Tone.NEUTRAL)
                        }
                    }
                    if (st.lines.size > 300) Text("Showing the latest 300; export the Inventory Movement Report for all.", style = Plasmaco.type.label, color = Plasmaco.colors.textSecondary)
                }
            }
        }
        if (ui.pending.isNotEmpty()) {
            item {
                SectionCard("Awaiting approval") {
                    ui.pending.forEachIndexed { i, t ->
                        if (i > 0) RowDivider()
                        ListRow("${t.type.label} · ${Fmt.qty(t.quantity, t.unit)}", subtitle = "${Fmt.date(t.date)} · ${ui.userNames[t.createdBy] ?: "—"}${t.note?.let { " · $it" } ?: ""}") {
                            StatusBadge(t.status.label(), t.status.tone())
                        }
                    }
                }
            }
        }
    }
    if (pickRange) {
        DateRangeDialog(ui.range.start, ui.range.end, { pickRange = false }) { s, e -> pickRange = false; vm.setRange(DateRange(s, e)) }
    }
}
