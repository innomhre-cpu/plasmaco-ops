package rw.plasmaco.ops.ui.inventory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Grain
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
import rw.plasmaco.ops.data.settings.SettingsRepository
import rw.plasmaco.ops.domain.common.sumDecimal
import rw.plasmaco.ops.domain.inventory.Ledger
import rw.plasmaco.ops.domain.inventory.StockStatus
import rw.plasmaco.ops.domain.inventory.StockStatusRules
import rw.plasmaco.ops.domain.inventory.TxnStatus
import rw.plasmaco.ops.domain.master.ItemKind
import rw.plasmaco.ops.domain.security.Actor
import rw.plasmaco.ops.domain.security.Permission
import rw.plasmaco.ops.domain.units.UnitOfMeasure
import rw.plasmaco.ops.ui.common.Fmt
import rw.plasmaco.ops.ui.common.ListCard
import rw.plasmaco.ops.ui.common.ListRow
import rw.plasmaco.ops.ui.common.ScreenList
import rw.plasmaco.ops.ui.common.tone
import rw.plasmaco.ops.ui.components.AppFilterChip
import rw.plasmaco.ops.ui.components.EmptyState
import rw.plasmaco.ops.ui.components.FilterChipRow
import rw.plasmaco.ops.ui.components.KpiCard
import rw.plasmaco.ops.ui.components.LoadingState
import rw.plasmaco.ops.ui.components.NoticeBanner
import rw.plasmaco.ops.ui.components.PageHeaderCard
import rw.plasmaco.ops.ui.components.SearchField
import rw.plasmaco.ops.ui.components.StatusBadge
import rw.plasmaco.ops.ui.components.TextLink
import rw.plasmaco.ops.ui.components.Tone
import rw.plasmaco.ops.ui.navigation.Navigator
import rw.plasmaco.ops.ui.navigation.Routes
import rw.plasmaco.ops.ui.theme.Plasmaco
import java.math.BigDecimal
import java.time.LocalDate
import javax.inject.Inject

data class StockRow(
    val kind: ItemKind,
    val id: String,
    val code: String,
    val name: String,
    val unit: UnitOfMeasure,
    val balance: BigDecimal,
    val minimum: BigDecimal?,
    val reorderPoint: BigDecimal?,
    val status: StockStatus,
    val lastMovement: LocalDate?,
    val pending: Int,
    /** Finished goods only: balance × kg/m, for the stock sheet's weight column. */
    val weightKg: BigDecimal?,
)

data class InventoryUi(
    val loading: Boolean = true,
    val actor: Actor? = null,
    val kind: ItemKind = ItemKind.FINISHED_GOOD,
    val query: String = "",
    val status: StockStatus? = null,
    val rows: List<StockRow> = emptyList(),
    val counts: Map<StockStatus, Int> = emptyMap(),
    val totalBalance: BigDecimal = BigDecimal.ZERO,
    val totalUnit: UnitOfMeasure = UnitOfMeasure.METRE,
    val totalWeightKg: BigDecimal? = null,
    val cutoff: LocalDate? = null,
    val hasLiveMovements: Boolean = false,
    val hasHistory: Boolean = false,
)

@HiltViewModel
class InventoryViewModel @Inject constructor(
    handle: SavedStateHandle,
    inventory: InventoryRepository,
    master: MasterDataRepository,
    settings: SettingsRepository,
    session: SessionManager,
) : ViewModel() {
    private data class Filter(val kind: ItemKind, val query: String = "", val status: StockStatus? = null)

    private val filter = MutableStateFlow(
        Filter(handle.get<String>("tab")?.let { t -> ItemKind.entries.firstOrNull { it.name == t } } ?: ItemKind.FINISHED_GOOD),
    )

    val state: StateFlow<InventoryUi> = combine(inventory.transactions, master.master, settings.settings, session.user, filter) { txns, m, s, u, f ->
        val live = Ledger(txns)
        val items: List<Triple<String, String, String>> = when (f.kind) {
            ItemKind.FINISHED_GOOD -> m.products.filter { it.active || live.balance(f.kind, it.id).signum() != 0 }.map { Triple(it.id, it.code, it.name) }
            ItemKind.RAW_MATERIAL -> m.materials.filter { it.active || live.balance(f.kind, it.id).signum() != 0 }.map { Triple(it.id, it.code, it.name) }
        }
        val all = items.map { (id, code, name) ->
            val th = m.threshold(f.kind, id)
            val bal = live.balance(f.kind, id)
            val unit = m.itemUnit(f.kind, id) ?: UnitOfMeasure.METRE
            StockRow(
                f.kind, id, code, name, unit, bal, th?.minimum, th?.reorderPoint, StockStatusRules.classify(bal, th?.minimum, th?.reorderPoint),
                live.lastMovement(f.kind, id), txns.count { it.itemKind == f.kind && it.itemId == id && it.status == TxnStatus.PENDING_APPROVAL },
                if (f.kind == ItemKind.FINISHED_GOOD) m.product(id)?.kgPerMetre?.let { bal * it } else null,
            )
        }
        val q = f.query.trim().lowercase().replace(" ", "")
        val shown = all.filter { r -> (f.status == null || r.status == f.status) && (q.isEmpty() || (r.name + r.code).lowercase().replace(" ", "").contains(q)) }
            .sortedWith(compareByDescending<StockRow> { it.status.severity }.thenBy { it.name })
        InventoryUi(
            loading = false, actor = u?.toActor(), kind = f.kind, query = f.query, status = f.status, rows = shown,
            counts = all.groupingBy { it.status }.eachCount(),
            totalBalance = all.map { it.balance }.sumDecimal(),
            totalUnit = if (f.kind == ItemKind.FINISHED_GOOD) UnitOfMeasure.METRE else UnitOfMeasure.KILOGRAM,
            totalWeightKg = if (f.kind == ItemKind.FINISHED_GOOD && all.all { it.weightKg != null || it.balance.signum() == 0 }) all.mapNotNull { it.weightKg }.sumDecimal() else null,
            cutoff = s.inventoryCutoff,
            hasLiveMovements = txns.any { !it.historical },
            hasHistory = txns.any { it.historical },
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InventoryUi())

    fun setKind(k: ItemKind) = filter.update { it.copy(kind = k, status = null) }
    fun setQuery(q: String) = filter.update { it.copy(query = q) }
    fun setStatus(s: StockStatus?) = filter.update { it.copy(status = s) }
}

@Composable
fun InventoryScreen(nav: Navigator, vm: InventoryViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val actor = ui.actor
    if (ui.loading || actor == null) {
        LoadingState(label = "Loading stock")
        return
    }
    val canMove = listOf(Permission.INVENTORY_RECEIVE, Permission.INVENTORY_ISSUE, Permission.INVENTORY_DISPATCH, Permission.INVENTORY_TRANSFER, Permission.ADJUSTMENT_REQUEST).any { actor.can(it) }
    ScreenList {
        item {
            PageHeaderCard(
                "Inventory", "Live balances from posted stock movements" + (ui.cutoff?.let { " since go-live on ${Fmt.date(it)}" } ?: ""),
                actionText = if (canMove) "Record movement" else null, actionIcon = Icons.Outlined.AddCircleOutline,
                onAction = if (canMove) ({ nav.open(Routes.movement(ui.kind)) }) else null,
            )
        }
        item {
            FilterChipRow {
                AppFilterChip("Finished goods", ui.kind == ItemKind.FINISHED_GOOD, { vm.setKind(ItemKind.FINISHED_GOOD) }, icon = Icons.Outlined.Category)
                AppFilterChip("Raw materials", ui.kind == ItemKind.RAW_MATERIAL, { vm.setKind(ItemKind.RAW_MATERIAL) }, icon = Icons.Outlined.Grain)
                if (actor.can(Permission.STOCK_COUNT) || actor.can(Permission.ADJUSTMENT_APPROVE)) {
                    AppFilterChip("Stock counts", false, { nav.open(Routes.COUNTS) }, icon = Icons.Outlined.Checklist)
                }
            }
        }
        if (!ui.hasLiveMovements && ui.hasHistory) {
            item {
                Column {
                    NoticeBanner(
                        "Only imported workbook history is on file. It is shown on each item's ledger but does not set live balances; " +
                            "an administrator sets opening balances at go-live.",
                        Tone.INFO,
                    )
                    if (actor.can(Permission.OPENING_BALANCE_MIGRATE)) TextLink("Set opening balances", { nav.top(Routes.MIGRATION) })
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(Plasmaco.dimens.cardGap)) {
                KpiCard(
                    if (ui.kind == ItemKind.FINISHED_GOOD) "Finished goods in stock" else "Raw material in stock",
                    Fmt.number(ui.totalBalance, if (ui.kind == ItemKind.FINISHED_GOOD) 0 else 1), ui.totalUnit.code, Modifier.weight(1f),
                    icon = Icons.Outlined.Inventory2,
                )
                val alerts = (ui.counts[StockStatus.OUT_OF_STOCK] ?: 0) + (ui.counts[StockStatus.LOW_STOCK] ?: 0) + (ui.counts[StockStatus.REORDER_REQUIRED] ?: 0)
                KpiCard("Out of stock, low or at reorder point", alerts.toString(), "items", Modifier.weight(1f))
            }
        }
        ui.totalWeightKg?.let { w -> item { Text("Total weight ${Fmt.qty(w, UnitOfMeasure.KILOGRAM)} (metres × kg/m)", style = Plasmaco.type.label, color = Plasmaco.colors.textSecondary) } }
        item { SearchField(ui.query, vm::setQuery, if (ui.kind == ItemKind.FINISHED_GOOD) "Search product, e.g. DN110 PN10" else "Search material") }
        item {
            FilterChipRow {
                AppFilterChip("All", ui.status == null, { vm.setStatus(null) })
                StockStatus.entries.forEach { s -> AppFilterChip("${s.label} (${ui.counts[s] ?: 0})", ui.status == s, { vm.setStatus(if (ui.status == s) null else s) }) }
            }
        }
        if (ui.rows.isEmpty()) item { EmptyState("No items", if (ui.query.isNotBlank() || ui.status != null) "Nothing matches the search or filter." else "Add products and materials in master data.") }
        else item {
            ListCard(ui.rows) { r ->
                ListRow(
                    r.name,
                    subtitle = buildString {
                        append(Fmt.qty(r.balance, r.unit))
                        r.weightKg?.let { append(" · ${Fmt.qty(it, UnitOfMeasure.KILOGRAM)}") }
                        r.minimum?.let { append(" · min ${Fmt.qty(it, r.unit)}") }
                        r.lastMovement?.let { append(" · last ${Fmt.short(it)}") }
                        if (r.pending > 0) append(" · ${r.pending} awaiting approval")
                    },
                    onClick = { nav.open(Routes.item(r.kind, r.id)) },
                ) { StatusBadge(r.status.label, r.status.tone()) }
            }
        }
    }
}
