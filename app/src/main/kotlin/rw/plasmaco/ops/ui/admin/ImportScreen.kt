package rw.plasmaco.ops.ui.admin

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.TableView
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import rw.plasmaco.ops.data.local.ImportBatchEntity
import rw.plasmaco.ops.data.repository.AuditRepository
import rw.plasmaco.ops.data.repository.ImportRepository
import rw.plasmaco.ops.data.repository.MasterDataRepository
import rw.plasmaco.ops.domain.common.Outcome
import rw.plasmaco.ops.domain.common.Severity
import rw.plasmaco.ops.domain.common.TimeSource
import rw.plasmaco.ops.domain.master.MasterData
import rw.plasmaco.ops.domain.master.MaterialCategory
import rw.plasmaco.ops.domain.units.UnitOfMeasure
import rw.plasmaco.ops.ui.common.Fmt
import rw.plasmaco.ops.ui.common.KeyValueRow
import rw.plasmaco.ops.ui.common.ListRow
import rw.plasmaco.ops.ui.common.PlasmacoViewModel
import rw.plasmaco.ops.ui.common.RowDivider
import rw.plasmaco.ops.ui.common.ScreenList
import rw.plasmaco.ops.ui.common.UserMessages
import rw.plasmaco.ops.ui.common.tone
import rw.plasmaco.ops.ui.components.AppFilterChip
import rw.plasmaco.ops.ui.components.ConfirmDialog
import rw.plasmaco.ops.ui.components.DropdownField
import rw.plasmaco.ops.ui.components.FieldHint
import rw.plasmaco.ops.ui.components.FieldLabel
import rw.plasmaco.ops.ui.components.FilterChipRow
import rw.plasmaco.ops.ui.components.NoticeBanner
import rw.plasmaco.ops.ui.components.PageHeaderCard
import rw.plasmaco.ops.ui.components.PlasmacoCard
import rw.plasmaco.ops.ui.components.PrimaryButton
import rw.plasmaco.ops.ui.components.PriorityText
import rw.plasmaco.ops.ui.components.SecondaryButton
import rw.plasmaco.ops.ui.components.SectionCard
import rw.plasmaco.ops.ui.components.Tone
import rw.plasmaco.ops.ui.navigation.Navigator
import rw.plasmaco.ops.ui.navigation.Routes
import rw.plasmaco.ops.ui.theme.Plasmaco
import java.time.ZoneId
import javax.inject.Inject

data class ImportUi(
    val master: MasterData = MasterData(),
    val uri: Uri? = null,
    val resinMaterialId: String? = null,
    val sheets: Set<String>? = null,
    val preview: ImportRepository.Preview? = null,
    val reading: Boolean = false,
    val refusal: String? = null,
    val imported: Int? = null,
    val history: List<ImportBatchEntity> = emptyList(),
    val zone: ZoneId = ZoneId.of("Africa/Kigali"),
)

/**
 * Historical workbook import. The file is treated as untrusted input: it is size-limited, parsed without running any
 * formula or macro, previewed with row-level findings, and committed only on explicit confirmation as historical
 * movements that never change live balances.
 */
@HiltViewModel
class ImportViewModel @Inject constructor(
    private val imports: ImportRepository,
    master: MasterDataRepository,
    audit: AuditRepository,
    private val time: TimeSource,
    messages: UserMessages,
) : PlasmacoViewModel(messages) {
    private val local = MutableStateFlow(ImportUi())

    val state: StateFlow<ImportUi> = combine(local, master.master, audit.imports) { l, m, h ->
        l.copy(
            master = m, history = h.sortedByDescending { it.importedAt }, zone = time.zone(),
            resinMaterialId = l.resinMaterialId ?: m.materials.singleOrNull { it.active && it.category == MaterialCategory.RESIN }?.id,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ImportUi())

    fun choose(uri: Uri?) {
        if (uri == null) return
        local.update { ImportUi(uri = uri, resinMaterialId = it.resinMaterialId) }
        preview()
    }

    fun setResin(id: String) {
        local.update { it.copy(resinMaterialId = id) }
        preview()
    }

    fun toggleSheet(name: String) {
        val all = local.value.preview?.sheetNames.orEmpty()
        local.update { s ->
            val current = s.sheets ?: all.toSet()
            s.copy(sheets = if (name in current) current - name else current + name)
        }
        preview()
    }

    private fun preview() = action {
        val uri = local.value.uri ?: return@action
        local.update { it.copy(reading = true, refusal = null, imported = null) }
        val resin = local.value.resinMaterialId ?: state.value.resinMaterialId
        when (val o = imports.preview(uri, resin, local.value.sheets)) {
            is Outcome.Ok -> local.update { it.copy(preview = o.value, reading = false) }
            is Outcome.Refused -> local.update { it.copy(preview = null, reading = false, refusal = o.issues.first().message) }
        }
    }

    fun commit() = action {
        val p = local.value.preview ?: return@action
        when (val o = imports.commit(p)) {
            is Outcome.Ok -> {
                local.update { ImportUi(imported = o.value) }
                messages.show("${o.value} historical movements imported")
            }
            is Outcome.Refused -> messages.show(o.issues.first().message)
        }
    }
}

@Composable
fun ImportScreen(nav: Navigator, vm: ImportViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { vm.choose(it) }
    val p = ui.preview
    ScreenList {
        item {
            PageHeaderCard(
                "Excel import", "Bring in the history from the \"Finished Goods and Raw Material Stock\" workbooks",
                actionText = "Choose workbook", actionIcon = Icons.Outlined.FileOpen,
                onAction = { picker.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.ms-excel.sheet.macroEnabled.12", "application/octet-stream")) },
            )
        }
        item {
            NoticeBanner(
                "Imported figures are history: they appear on item ledgers and in reports but never change live balances. " +
                    "Live stock starts from opening balances set at go-live. Macros and formulas in the file are not run.",
                Tone.INFO,
            )
        }
        ui.imported?.let { n -> item { NoticeBanner("Import complete: $n historical movements saved.", Tone.SUCCESS) } }
        ui.refusal?.let { r -> item { NoticeBanner(r, Tone.DANGER) } }
        if (ui.reading) item { Text("Reading the workbook…", style = Plasmaco.type.bodySmall, color = Plasmaco.colors.textSecondary) }
        if (p != null) {
            val st = p.plan.stats
            item {
                SectionCard(p.fileName, subtitle = "SHA-256 ${p.plan.sha256.take(12)}…") {
                    if (p.alreadyImported) NoticeBanner("This exact file was imported before. Importing it again is blocked.", Tone.WARNING)
                    KeyValueRow("Period", "${Fmt.date(st.firstDate)} – ${Fmt.date(st.lastDate)}")
                    KeyValueRow("Days found", st.days.toString())
                    KeyValueRow("Product rows", st.productRows.toString())
                    KeyValueRow("Finished-goods movements", st.finishedGoodsTransactions.toString())
                    KeyValueRow("Raw-material movements", st.rawMaterialTransactions.toString())
                    KeyValueRow("Reconciliation entries", "${st.reconciliations} (where a day's opening differs from the previous closing)")
                    KeyValueRow("Already imported (skipped)", p.plan.skippedAlreadyImported.toString())
                    KeyValueRow("New products to create", p.plan.newProducts.size.toString())
                    KeyValueRow("Resin balance in workbook", Fmt.qty(st.workbookResinBalance, UnitOfMeasure.KILOGRAM))
                    KeyValueRow("Resin balance after import", Fmt.qty(st.importedResinBalance, UnitOfMeasure.KILOGRAM), emphasise = true)
                }
            }
            item {
                PlasmacoCard {
                    DropdownField(
                        "Raw material for the workbook's resin column", ui.master.materials.filter { it.active }, ui.master.materials.firstOrNull { it.id == ui.resinMaterialId },
                        { vm.setResin(it.id) }, { it.name }, required = true, itemSupporting = { Fmt.enumLabel(it.category.name) },
                    )
                    FieldHint("The workbook keeps one resin balance; choose the material it should be booked to.")
                    if (p.sheetNames.size > 1) {
                        Spacer(Modifier.height(12.dp))
                        FieldLabel("Worksheets")
                        FilterChipRow {
                            p.sheetNames.forEach { name -> AppFilterChip(name, ui.sheets?.contains(name) ?: true, { vm.toggleSheet(name) }, icon = Icons.Outlined.TableView) }
                        }
                    }
                }
            }
            if (p.plan.conflicts.isNotEmpty()) {
                item {
                    SectionCard("Changed since the last import", subtitle = "Kept as first imported; review the workbook") {
                        p.plan.conflicts.take(30).forEachIndexed { i, c ->
                            if (i > 0) RowDivider()
                            ListRow(c.message, subtitle = "${c.sheet}${c.cell?.let { " · $it" } ?: ""}")
                        }
                    }
                }
            }
            if (p.plan.issues.isNotEmpty()) {
                item {
                    val errors = p.plan.issues.count { it.severity == Severity.ERROR }
                    SectionCard("Findings", subtitle = "${p.plan.issues.size} finding(s), $errors blocking") {
                        p.plan.issues.sortedBy { it.severity.ordinal }.take(60).forEachIndexed { i, f ->
                            if (i > 0) RowDivider()
                            ListRow(f.message, subtitle = "${f.sheet}${f.cell?.let { " · cell $it" } ?: ""} · ${f.code}") { PriorityText(Fmt.enumLabel(f.severity.name), f.severity.tone()) }
                        }
                        if (p.plan.issues.size > 60) Text("All findings are stored with the import record.", style = Plasmaco.type.label, color = Plasmaco.colors.textSecondary)
                    }
                }
            }
            item {
                PrimaryButton(
                    "Import ${p.plan.transactions.size} movements", { confirm = true }, icon = Icons.Outlined.UploadFile, loading = busy,
                    enabled = !p.alreadyImported && p.plan.blocking.isEmpty() && p.plan.transactions.isNotEmpty(),
                )
            }
            if (p.plan.proposedOpenings.isNotEmpty()) {
                item { SecondaryButton("Go-live opening balances", { nav.top(Routes.MIGRATION) }) }
            }
        }
        if (ui.history.isNotEmpty()) {
            item {
                SectionCard("Previous imports") {
                    ui.history.forEachIndexed { i, h ->
                        if (i > 0) RowDivider()
                        ListRow(h.fileName, subtitle = "${Fmt.epoch(h.importedAt, ui.zone)} · ${h.transactions} movements · ${Fmt.date(h.firstDate)} – ${Fmt.date(h.lastDate)}")
                    }
                }
            }
        }
    }
    if (confirm && p != null) {
        ConfirmDialog(
            "Import ${p.plan.transactions.size} historical movements?",
            "They are saved as history from ${p.fileName} and recorded in the audit log. Live balances do not change.",
            "Import", { confirm = false; vm.commit() }, { confirm = false },
        )
    }
}
