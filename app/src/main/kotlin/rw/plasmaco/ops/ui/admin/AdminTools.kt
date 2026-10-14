package rw.plasmaco.ops.ui.admin

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Restore
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
import rw.plasmaco.ops.data.local.AuditEntity
import rw.plasmaco.ops.data.repository.AuditRepository
import rw.plasmaco.ops.data.repository.BackupRepository
import rw.plasmaco.ops.data.repository.InventoryRepository
import rw.plasmaco.ops.data.repository.MasterDataRepository
import rw.plasmaco.ops.data.settings.SettingsRepository
import rw.plasmaco.ops.domain.common.Decimals
import rw.plasmaco.ops.domain.common.TimeSource
import rw.plasmaco.ops.domain.inventory.Ledger
import rw.plasmaco.ops.domain.inventory.TxnType
import rw.plasmaco.ops.domain.legacy.ProposedOpening
import rw.plasmaco.ops.domain.master.ItemKind
import rw.plasmaco.ops.domain.master.MasterData
import rw.plasmaco.ops.ui.common.Fmt
import rw.plasmaco.ops.ui.common.ListCard
import rw.plasmaco.ops.ui.common.ListRow
import rw.plasmaco.ops.ui.common.PlasmacoViewModel
import rw.plasmaco.ops.ui.common.ScreenList
import rw.plasmaco.ops.ui.common.UserMessages
import rw.plasmaco.ops.ui.components.AppFilterChip
import rw.plasmaco.ops.ui.components.AppTextField
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
import rw.plasmaco.ops.ui.components.SectionCard
import rw.plasmaco.ops.ui.components.Tone
import rw.plasmaco.ops.ui.navigation.Navigator
import rw.plasmaco.ops.ui.theme.Plasmaco
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

// ------------------------------------------------------------------------------------------------ audit log

@HiltViewModel
class AuditViewModel @Inject constructor(audit: AuditRepository, time: TimeSource) : ViewModel() {
    val zone: ZoneId = time.zone()
    val entries: StateFlow<List<AuditEntity>?> = audit.recent(1000).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
fun AuditScreen(nav: Navigator, vm: AuditViewModel = hiltViewModel()) {
    val entries by vm.entries.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf<String?>(null) }
    val all = entries ?: run { LoadingState(label = "Loading audit log"); return }
    val q = query.trim().lowercase()
    val shown = all.filter { e -> (type == null || e.entityType == type) && (q.isEmpty() || "${e.summary} ${e.actorName} ${e.action}".lowercase().contains(q)) }
    ScreenList {
        item { PageHeaderCard("Audit log", "Who changed what and when; entries cannot be edited or deleted. Latest ${all.size} shown.") }
        item { SearchField(query, { query = it }, "Search person, action or record") }
        item {
            FilterChipRow {
                AppFilterChip("All", type == null, { type = null })
                all.map { it.entityType }.distinct().sorted().forEach { t -> AppFilterChip(Fmt.enumLabel(t), type == t, { type = if (type == t) null else t }) }
            }
        }
        if (shown.isEmpty()) item { EmptyState("No entries", "Nothing matches the search.") }
        else item {
            ListCard(shown.take(300)) { e ->
                ListRow(e.summary, subtitle = "${Fmt.epoch(e.at, vm.zone)} · ${e.actorName} · ${Fmt.enumLabel(e.entityType)} · ${e.action} · device ${e.deviceId.take(8)}")
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ backup and restore

@HiltViewModel
class BackupViewModel @Inject constructor(private val backups: BackupRepository, private val time: TimeSource, messages: UserMessages) : PlasmacoViewModel(messages) {
    val restoreStaged = MutableStateFlow(false)

    fun backupName(): String = "plasmaco-backup-" + time.now().atZone(time.zone()).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".db"

    fun backupTo(uri: Uri?) = action {
        if (uri == null) return@action
        val size = backups.backupTo(uri)
        messages.show("Backup saved (${size / 1024} KB)")
    }

    fun restoreFrom(uri: Uri?) = action {
        if (uri == null) return@action
        if (backups.stageRestore(uri)) {
            restoreStaged.value = true
            messages.show("Backup checked. Close and reopen the app to finish restoring.")
        } else {
            messages.show("That file is not a Plasmaco backup")
        }
    }
}

@Composable
fun BackupScreen(nav: Navigator, vm: BackupViewModel = hiltViewModel()) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    val staged by vm.restoreStaged.collectAsStateWithLifecycle()
    var confirmRestore by remember { mutableStateOf(false) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { vm.backupTo(it) }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { vm.restoreFrom(it) }
    ScreenList {
        item { PageHeaderCard("Backup and restore", "A copy of everything stored on this device") }
        item {
            SectionCard("Back up") {
                Text(
                    "Saves the device database to a location you choose (for example a USB drive or a shared folder). With a server, the server copy remains the main record.",
                    style = Plasmaco.type.bodySmall, color = Plasmaco.colors.textSecondary,
                )
                Spacer(Modifier.height(12.dp))
                PrimaryButton("Create backup", { save.launch(vm.backupName()) }, icon = Icons.Outlined.Backup, loading = busy)
            }
        }
        item {
            SectionCard("Restore") {
                Text(
                    "Replaces everything on this device with a backup. Changes made after the backup are lost unless they were already synced to the server.",
                    style = Plasmaco.type.bodySmall, color = Plasmaco.colors.textSecondary,
                )
                Spacer(Modifier.height(12.dp))
                if (staged) NoticeBanner("A backup is ready. Close and reopen the app to finish restoring.", Tone.WARNING)
                else SecondaryButton("Restore from backup", { confirmRestore = true }, icon = Icons.Outlined.Restore, destructive = true)
            }
        }
    }
    if (confirmRestore) {
        ConfirmDialog(
            "Replace this device's data?", "Choose a backup file next. The restore completes the next time the app starts, and is recorded in the audit log.",
            "Choose backup", { confirmRestore = false; open.launch(arrayOf("*/*")) }, { confirmRestore = false }, destructive = true,
        )
    }
}

// ------------------------------------------------------------------------------------------------ go-live opening balances

data class MigrationUi(
    val loading: Boolean = true,
    val master: MasterData = MasterData(),
    val cutoff: LocalDate = LocalDate.now(),
    val alreadyMigrated: Boolean = false,
    val currentCutoff: LocalDate? = null,
    /** Closing balance per item from imported history, on the day before the cut-off. */
    val fromHistory: Map<Pair<ItemKind, String>, BigDecimal> = emptyMap(),
    val hasHistory: Boolean = false,
    val today: LocalDate = LocalDate.now(),
)

/**
 * Authorised go-live migration: live balances start from opening balances an administrator confirms, either the
 * imported history's closing figures or a physical count. Nothing here runs automatically.
 */
@HiltViewModel
class MigrationViewModel @Inject constructor(
    private val inventory: InventoryRepository,
    master: MasterDataRepository,
    settings: SettingsRepository,
    time: TimeSource,
    messages: UserMessages,
) : PlasmacoViewModel(messages) {
    private val today = time.today()
    private val cutoff = MutableStateFlow(today)

    val state: StateFlow<MigrationUi> = combine(inventory.transactions, master.master, settings.settings, cutoff) { txns, m, s, c ->
        val history = Ledger(txns.filter { it.historical }, includeHistorical = true)
        val fromHistory = buildMap {
            m.products.forEach { p -> put(ItemKind.FINISHED_GOOD to p.id, history.balance(ItemKind.FINISHED_GOOD, p.id, c.minusDays(1))) }
            m.materials.forEach { x -> put(ItemKind.RAW_MATERIAL to x.id, history.balance(ItemKind.RAW_MATERIAL, x.id, c.minusDays(1))) }
        }
        MigrationUi(
            false, m, c, txns.any { !it.historical && it.type == TxnType.OPENING_BALANCE }, s.inventoryCutoff, fromHistory,
            txns.any { it.historical }, today,
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MigrationUi())

    fun setCutoff(d: LocalDate) {
        cutoff.value = d
    }

    fun migrate(entered: Map<String, String>, reason: String, done: () -> Unit) = action {
        val s = state.value
        val openings = mutableListOf<ProposedOpening>()
        val keys = s.master.products.filter { it.active }.map { ItemKind.FINISHED_GOOD to it.id } + s.master.materials.filter { it.active }.map { ItemKind.RAW_MATERIAL to it.id }
        for (k in keys) {
            val text = entered["${k.first}:${k.second}"]
            val qty = if (text == null) s.fromHistory[k] ?: BigDecimal.ZERO else Decimals.parseInput(text)
            if (qty == null) { messages.show("${s.master.itemName(k.first, k.second)}: not a number"); return@action }
            if (qty.signum() < 0) { messages.show("${s.master.itemName(k.first, k.second)}: an opening balance cannot be negative"); return@action }
            openings += ProposedOpening(k.first, k.second, qty, s.cutoff)
        }
        inventory.migrateOpeningBalances(openings, s.cutoff, reason).handle()?.let { n ->
            messages.show("$n opening balance(s) posted; live stock starts on ${Fmt.date(s.cutoff)}")
            done()
        }
    }
}

@Composable
fun MigrationScreen(nav: Navigator, vm: MigrationViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val entered = remember { mutableStateMapOf<String, String>() }
    var reason by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(ItemKind.FINISHED_GOOD) }
    var confirm by remember { mutableStateOf(false) }
    if (ui.loading) { LoadingState(label = "Loading"); return }
    val m = ui.master
    ScreenList {
        item { PageHeaderCard("Go-live opening balances", "Where live stock starts. Done once, by an administrator, with a reason.") }
        if (ui.alreadyMigrated) {
            item { NoticeBanner("Opening balances were set${ui.currentCutoff?.let { " on ${Fmt.date(it)}" } ?: ""}. Correct stock now with adjustments or a stock count.", Tone.SUCCESS) }
            return@ScreenList
        }
        item {
            PlasmacoCard {
                DateField("Live stock starts on", ui.cutoff, vm::setCutoff, maxDate = ui.today, required = true)
                FieldHint(
                    if (ui.hasHistory) "Proposed figures are each item's closing balance in the imported workbooks on the day before. Replace any figure with a physical count where you have one."
                    else "No workbook history is imported; enter the physically counted quantity of each item.",
                )
            }
        }
        item {
            FilterChipRow {
                AppFilterChip("Finished goods", kind == ItemKind.FINISHED_GOOD, { kind = ItemKind.FINISHED_GOOD })
                AppFilterChip("Raw materials", kind == ItemKind.RAW_MATERIAL, { kind = ItemKind.RAW_MATERIAL })
            }
        }
        val items = if (kind == ItemKind.FINISHED_GOOD) m.products.filter { it.active }.map { it.id to it.name } else m.materials.filter { it.active }.map { it.id to it.name }
        items.forEach { (id, name) ->
            item(key = "$kind:$id") {
                val k = "$kind:$id"
                val proposed = ui.fromHistory[kind to id] ?: BigDecimal.ZERO
                val unit = m.itemUnit(kind, id)
                PlasmacoCard(padding = 12.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(name, style = Plasmaco.type.bodyStrong, color = Plasmaco.colors.textPrimary)
                            if (ui.hasHistory) Text("History: ${Fmt.qty(proposed, unit)}", style = Plasmaco.type.label, color = Plasmaco.colors.textSecondary)
                        }
                        NumericField(entered[k] ?: Fmt.input(proposed), { entered[k] = it }, "Opening", unit?.code, Modifier.weight(1f))
                    }
                }
            }
        }
        item {
            PlasmacoCard {
                AppTextField(reason, { reason = it }, "Source of these balances", required = true, singleLine = false, minLines = 2,
                    hint = "e.g. \"Physical count on 30 Sep 2026 signed by the storekeeper; DN110 counted twice\" (at least 10 characters)")
                Spacer(Modifier.height(12.dp))
                PrimaryButton("Set opening balances", { confirm = true }, loading = busy, enabled = reason.trim().length >= 10)
            }
        }
    }
    if (confirm) {
        val count = m.products.count { it.active } + m.materials.count { it.active }
        ConfirmDialog(
            "Start live stock on ${Fmt.date(ui.cutoff)}?",
            "Opening balances for $count item(s) will be posted and movements dated earlier will be refused. This is recorded in the audit log and cannot be repeated.",
            "Set balances", { confirm = false; vm.migrate(entered.toMap(), reason) { nav.back() } }, { confirm = false }, destructive = true,
        )
    }
}
