package rw.plasmaco.ops.ui.inventory

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import rw.plasmaco.ops.data.repository.InventoryRepository
import rw.plasmaco.ops.data.repository.MasterDataRepository
import rw.plasmaco.ops.data.repository.ReportRepository
import rw.plasmaco.ops.data.repository.SessionManager
import rw.plasmaco.ops.data.settings.SettingsRepository
import rw.plasmaco.ops.domain.analytics.Analytics
import rw.plasmaco.ops.domain.common.Decimals
import rw.plasmaco.ops.domain.common.Issue
import rw.plasmaco.ops.domain.common.Outcome
import rw.plasmaco.ops.domain.common.TimeSource
import rw.plasmaco.ops.domain.common.newId
import rw.plasmaco.ops.domain.inventory.Ledger
import rw.plasmaco.ops.domain.inventory.MovementRequest
import rw.plasmaco.ops.domain.inventory.TxnStatus
import rw.plasmaco.ops.domain.inventory.TxnType
import rw.plasmaco.ops.domain.master.ItemKind
import rw.plasmaco.ops.domain.master.MasterData
import rw.plasmaco.ops.domain.production.ProductionReport
import rw.plasmaco.ops.domain.security.Actor
import rw.plasmaco.ops.domain.security.Permission
import rw.plasmaco.ops.domain.units.Quantity
import rw.plasmaco.ops.domain.units.UnitConverter
import rw.plasmaco.ops.domain.units.UnitOfMeasure
import rw.plasmaco.ops.ui.common.DetailHeader
import rw.plasmaco.ops.ui.common.Fmt
import rw.plasmaco.ops.ui.common.IssueList
import rw.plasmaco.ops.ui.common.KeyValueRow
import rw.plasmaco.ops.ui.common.PlasmacoViewModel
import rw.plasmaco.ops.ui.common.ScreenForm
import rw.plasmaco.ops.ui.common.UserMessages
import rw.plasmaco.ops.ui.common.messageFor
import rw.plasmaco.ops.ui.components.AppFilterChip
import rw.plasmaco.ops.ui.components.AppTextField
import rw.plasmaco.ops.ui.components.DateField
import rw.plasmaco.ops.ui.components.DropdownField
import rw.plasmaco.ops.ui.components.FieldHint
import rw.plasmaco.ops.ui.components.FieldLabel
import rw.plasmaco.ops.ui.components.FilterChipRow
import rw.plasmaco.ops.ui.components.InsetCard
import rw.plasmaco.ops.ui.components.LoadingState
import rw.plasmaco.ops.ui.components.NumericField
import rw.plasmaco.ops.ui.components.PlasmacoCard
import rw.plasmaco.ops.ui.components.PrimaryButton
import rw.plasmaco.ops.ui.components.SearchablePickerField
import rw.plasmaco.ops.ui.navigation.Navigator
import rw.plasmaco.ops.ui.production.editor.unitsFor
import rw.plasmaco.ops.ui.theme.Plasmaco
import java.math.BigDecimal
import java.time.LocalDate
import javax.inject.Inject

data class MovementForm(
    val kind: ItemKind = ItemKind.FINISHED_GOOD,
    val itemId: String? = null,
    val type: TxnType? = null,
    val quantity: String = "",
    val unit: UnitOfMeasure? = null,
    val date: LocalDate = LocalDate.now(),
    val sign: Int = 0,
    val reference: String = "",
    val counterparty: String = "",
    val location: String = "",
    val note: String = "",
    val overrideNegative: Boolean = false,
    val reportId: String? = null,
    val entryId: String? = null,
    /** One key per form so a double tap or a retry after a timeout cannot post twice. */
    val idempotencyKey: String = newId(),
)

data class MovementUi(
    val loading: Boolean = true,
    val actor: Actor? = null,
    val master: MasterData = MasterData(),
    val form: MovementForm = MovementForm(),
    val balance: BigDecimal? = null,
    val linkedReport: ProductionReport? = null,
    val allowNegativeOverride: Boolean = false,
    val issues: List<Issue> = emptyList(),
    val today: LocalDate = LocalDate.now(),
)

/** Types the person may record for an item kind (by role). Opening balances and legacy reconciliations are not entered here. */
fun allowedTypes(kind: ItemKind, actor: Actor): List<TxnType> = TxnType.entries.filter {
    it.allows(kind) && actor.can(it.permission) && it != TxnType.OPENING_BALANCE && it != TxnType.LEGACY_RECONCILIATION && it != TxnType.COUNT_VARIANCE
}

@HiltViewModel
class MovementViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val inventory: InventoryRepository,
    reports: ReportRepository,
    master: MasterDataRepository,
    settings: SettingsRepository,
    session: SessionManager,
    time: TimeSource,
    messages: UserMessages,
) : PlasmacoViewModel(messages) {
    private val form = MutableStateFlow(
        MovementForm(
            kind = handle.get<String>("kind")?.takeIf { it.isNotBlank() }?.let { k -> ItemKind.entries.firstOrNull { it.name == k } } ?: ItemKind.FINISHED_GOOD,
            itemId = handle.get<String>("item")?.takeIf { it.isNotBlank() },
            type = handle.get<String>("type")?.takeIf { it.isNotBlank() }?.let { t -> TxnType.entries.firstOrNull { it.name == t } },
            reportId = handle.get<String>("report")?.takeIf { it.isNotBlank() },
            entryId = handle.get<String>("entry")?.takeIf { it.isNotBlank() },
            date = time.today(),
        ),
    )
    private val issues = MutableStateFlow<List<Issue>>(emptyList())
    private val today = time.today()

    val state: StateFlow<MovementUi> = combine(form, inventory.transactions, master.master, combine(reports.reports, settings.settings) { r, s -> r to s }, combine(session.user, issues) { u, i -> u to i }) { f, txns, m, (rs, s), (u, iss) ->
        val linked = f.reportId?.let { id -> rs.firstOrNull { it.id == id } }
        MovementUi(
            loading = false, actor = u?.toActor(), master = m, form = f,
            balance = f.itemId?.let { Ledger(txns).balance(f.kind, it) },
            linkedReport = linked, allowNegativeOverride = s.allowNegativeOverride, issues = iss, today = today,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MovementUi())

    fun edit(transform: (MovementForm) -> MovementForm) {
        form.update(transform)
        issues.value = emptyList()
    }

    fun post(onDone: () -> Unit) = action {
        val f = form.value
        val actor = state.value.actor ?: return@action
        val qty = Decimals.parseInput(f.quantity)
        val local = buildList {
            if (f.itemId == null) add(Issue("itemId", "item.required", "Choose the item"))
            if (f.type == null) add(Issue("type", "type.required", "Choose the type of movement"))
            if (qty == null) add(Issue("quantity", "qty.required", "Enter the quantity"))
        }
        if (local.isNotEmpty()) { issues.value = local; return@action }
        val unit = f.unit ?: state.value.master.itemUnit(f.kind, f.itemId!!) ?: UnitOfMeasure.METRE
        val request = MovementRequest(
            itemKind = f.kind, itemId = f.itemId!!, type = f.type!!, quantity = qty!!, unit = unit, date = f.date, adjustmentSign = f.sign,
            reference = f.reference, counterparty = f.counterparty, reportId = f.reportId, entryId = f.entryId,
            location = f.location.trim().ifEmpty { null }, note = f.note, overrideNegative = f.overrideNegative && actor.can(Permission.NEGATIVE_STOCK_OVERRIDE),
            idempotencyKey = f.idempotencyKey,
        )
        when (val o = inventory.post(request)) {
            is Outcome.Ok -> {
                messages.show(if (o.value.status == TxnStatus.PENDING_APPROVAL) "${f.type.label} recorded; it changes stock once approved" else "${f.type.label} posted")
                onDone()
            }
            is Outcome.Refused -> issues.value = o.issues
        }
    }
}

@Composable
fun MovementScreen(nav: Navigator, vm: MovementViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val actor = ui.actor
    if (ui.loading || actor == null) {
        LoadingState(label = "Loading")
        return
    }
    val f = ui.form
    val m = ui.master
    val types = allowedTypes(f.kind, actor)
    val baseUnit = f.itemId?.let { m.itemUnit(f.kind, it) }
    val units = f.itemId?.let { id -> baseUnit?.let { unitsFor(it, m.itemConversions(f.kind, id)) } } ?: listOfNotNull(baseUnit)
    val unit = f.unit ?: baseUnit
    val entry = ui.linkedReport?.entries?.firstOrNull { it.id == f.entryId }
    val qty = Decimals.parseInput(f.quantity)
    val signed = f.type?.let { t -> if (t.direction == 0) f.sign else t.direction } ?: 0
    val qtyInBase = if (qty != null && unit != null && baseUnit != null && f.itemId != null) UnitConverter.convert(Quantity(qty, unit), baseUnit, m.itemConversions(f.kind, f.itemId))?.value else null
    val balance = ui.balance
    val after = if (balance != null && qtyInBase != null && signed != 0) balance + qtyInBase * BigDecimal(signed) else null

    ScreenForm {
        DetailHeader("Record stock movement", nav::back, subtitle = if (f.kind == ItemKind.FINISHED_GOOD) "Finished goods" else "Raw materials")
        if (ui.issues.isNotEmpty()) IssueList(ui.issues)
        PlasmacoCard {
            if (f.reportId == null) {
                FilterChipRow {
                    AppFilterChip("Finished goods", f.kind == ItemKind.FINISHED_GOOD, { vm.edit { it.copy(kind = ItemKind.FINISHED_GOOD, itemId = null, type = null, unit = null) } })
                    AppFilterChip("Raw materials", f.kind == ItemKind.RAW_MATERIAL, { vm.edit { it.copy(kind = ItemKind.RAW_MATERIAL, itemId = null, type = null, unit = null) } })
                }
                Spacer(Modifier.height(12.dp))
            }
            if (f.kind == ItemKind.FINISHED_GOOD) {
                SearchablePickerField(
                    "Product", m.products.filter { it.active || it.id == f.itemId }, f.itemId?.let { m.product(it) }, { p -> vm.edit { it.copy(itemId = p.id, unit = null) } },
                    { it.name }, required = true, enabled = f.reportId == null, error = ui.issues.messageFor("itemId"), itemSupporting = { it.code },
                )
            } else {
                DropdownField(
                    "Material", m.materials.filter { it.active || it.id == f.itemId }, f.itemId?.let { m.material(it) }, { mat -> vm.edit { it.copy(itemId = mat.id, unit = null) } },
                    { it.name }, required = true, error = ui.issues.messageFor("itemId"),
                )
            }
            Spacer(Modifier.height(12.dp))
            DropdownField(
                "Movement", types, f.type, { t -> vm.edit { it.copy(type = t, sign = if (t.direction == 0) it.sign else 0) } }, { it.label }, required = true,
                enabled = f.reportId == null, error = ui.issues.messageFor("type"),
                itemSupporting = { t -> if (t.requiresApproval && !actor.can(Permission.ADJUSTMENT_APPROVE)) "Needs approval before it changes stock" else null },
            )
            if (f.type?.direction == 0) {
                Spacer(Modifier.height(12.dp))
                FieldLabel("Direction", required = true)
                FilterChipRow {
                    AppFilterChip("Gain (+)", f.sign == 1, { vm.edit { it.copy(sign = 1) } })
                    AppFilterChip("Loss (−)", f.sign == -1, { vm.edit { it.copy(sign = -1) } })
                }
                ui.issues.messageFor("adjustmentSign")?.let { FieldHint(it) }
            }
        }
        ui.linkedReport?.let { linked ->
            InsetCard {
                KeyValueRow("Production report", linked.reference)
                entry?.let { e ->
                    KeyValueRow("Product", Analytics.shortName(m.product(e.productId)?.name.orEmpty()))
                    KeyValueRow("Good output", Fmt.qty(e.good, e.unit))
                }
                FieldHint("Only approved good output can be received, and never more than what remains for this entry.")
            }
        }
        PlasmacoCard {
            Row {
                NumericField(f.quantity, { q -> vm.edit { it.copy(quantity = q) } }, "Quantity", unit?.code, Modifier.weight(1f), required = true, error = ui.issues.messageFor("quantity"))
            }
            if (units.size > 1) {
                Spacer(Modifier.height(12.dp))
                DropdownField("Unit", units, unit, { u -> vm.edit { it.copy(unit = u) } }, { "${it.label} (${it.code})" }, error = ui.issues.messageFor("unit"))
            }
            Spacer(Modifier.height(12.dp))
            DateField("Date", f.date, { d -> vm.edit { it.copy(date = d) } }, maxDate = ui.today, required = true, error = ui.issues.messageFor("date"))
            Spacer(Modifier.height(12.dp))
            AppTextField(f.reference, { v -> vm.edit { it.copy(reference = v) } }, "Reference", placeholder = "Delivery note, GRN or invoice number")
            Spacer(Modifier.height(12.dp))
            AppTextField(
                f.counterparty, { v -> vm.edit { it.copy(counterparty = v) } },
                if (f.kind == ItemKind.FINISHED_GOOD) "Customer or destination" else "Supplier or source",
            )
            if (f.type == TxnType.TRANSFER_IN || f.type == TxnType.TRANSFER_OUT) {
                Spacer(Modifier.height(12.dp))
                AppTextField(f.location, { v -> vm.edit { it.copy(location = v) } }, "Other location")
            }
            Spacer(Modifier.height(12.dp))
            AppTextField(f.note, { v -> vm.edit { it.copy(note = v) } }, "Note", singleLine = false, minLines = 2,
                hint = if (f.type?.requiresApproval == true) "Explain the reason; the approver sees it" else null)
        }
        InsetCard {
            KeyValueRow("Current balance", balance?.let { Fmt.qty(it, baseUnit) } ?: "—")
            KeyValueRow("After this movement", after?.let { Fmt.qty(it, baseUnit) } ?: "—", emphasise = true)
            if (after != null && after.signum() < 0) {
                Text("Negative stock is blocked.", style = Plasmaco.type.label, color = Plasmaco.colors.danger)
            }
        }
        if (after != null && after.signum() < 0 && ui.allowNegativeOverride && actor.can(Permission.NEGATIVE_STOCK_OVERRIDE)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(f.overrideNegative, { v -> vm.edit { it.copy(overrideNegative = v) } }, colors = CheckboxDefaults.colors(checkedColor = Plasmaco.colors.danger))
                Text("Override: allow negative stock (needs a note; flagged in the audit log)", style = Plasmaco.type.bodySmall, color = Plasmaco.colors.textPrimary)
            }
        }
        PrimaryButton("Post movement", { vm.post { nav.back() } }, icon = Icons.Outlined.Check, loading = busy)
    }
}
