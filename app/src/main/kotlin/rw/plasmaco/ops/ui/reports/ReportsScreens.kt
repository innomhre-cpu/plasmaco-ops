package rw.plasmaco.ops.ui.reports

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import rw.plasmaco.ops.data.repository.ExportRepository
import rw.plasmaco.ops.data.repository.MasterDataRepository
import rw.plasmaco.ops.data.repository.NotAllowedException
import rw.plasmaco.ops.data.repository.SessionManager
import rw.plasmaco.ops.domain.common.TimeSource
import rw.plasmaco.ops.domain.export.ExportFormat
import rw.plasmaco.ops.domain.master.MasterData
import rw.plasmaco.ops.domain.reports.CellValue
import rw.plasmaco.ops.domain.reports.Column
import rw.plasmaco.ops.domain.reports.ColumnKind
import rw.plasmaco.ops.domain.reports.ReportDocument
import rw.plasmaco.ops.domain.reports.ReportType
import rw.plasmaco.ops.domain.reports.Section
import rw.plasmaco.ops.domain.security.Actor
import rw.plasmaco.ops.domain.units.Formatting
import rw.plasmaco.ops.ui.common.ActionBar
import rw.plasmaco.ops.ui.common.DetailHeader
import rw.plasmaco.ops.ui.common.ExportEffects
import rw.plasmaco.ops.ui.common.ExportingViewModel
import rw.plasmaco.ops.ui.common.FilterSelection
import rw.plasmaco.ops.ui.common.FiltersBar
import rw.plasmaco.ops.ui.common.Fmt
import rw.plasmaco.ops.ui.common.KeyValueRow
import rw.plasmaco.ops.ui.common.ListCard
import rw.plasmaco.ops.ui.common.ListRow
import rw.plasmaco.ops.ui.common.Period
import rw.plasmaco.ops.ui.common.ScreenList
import rw.plasmaco.ops.ui.common.UserMessages
import rw.plasmaco.ops.ui.components.CircleIconButton
import rw.plasmaco.ops.ui.components.EmptyState
import rw.plasmaco.ops.ui.components.KpiCard
import rw.plasmaco.ops.ui.components.LoadingState
import rw.plasmaco.ops.ui.components.PageHeaderCard
import rw.plasmaco.ops.ui.components.PlasmacoCard
import rw.plasmaco.ops.ui.components.PrimaryButton
import rw.plasmaco.ops.ui.components.SecondaryButton
import rw.plasmaco.ops.ui.components.SectionCard
import rw.plasmaco.ops.ui.components.charts.ChartView
import rw.plasmaco.ops.ui.navigation.Navigator
import rw.plasmaco.ops.ui.navigation.Routes
import rw.plasmaco.ops.ui.theme.Plasmaco
import java.time.LocalDate
import javax.inject.Inject

// ------------------------------------------------------------------------------------------------ catalogue

@HiltViewModel
class ReportsViewModel @Inject constructor(session: SessionManager, private val exports: ExportRepository) : ViewModel() {
    val actor: Actor? = session.actor
    fun available(actor: Actor): List<ReportType> = ReportType.entries.filter { exports.canExport(actor, it, null) }
}

@Composable
fun ReportsScreen(nav: Navigator, vm: ReportsViewModel = hiltViewModel()) {
    val actor = vm.actor ?: return
    val types = vm.available(actor)
    ScreenList {
        item { PageHeaderCard("Reports", "Preview any report on screen, then save it as PDF or Excel or share it") }
        if (types.isEmpty()) item { EmptyState("No reports for your role", "Your role can export single production reports from their detail screen.") }
        else item {
            ListCard(types) { t ->
                ListRow(t.title, subtitle = t.description + if (t.landscape) " · landscape" else "", icon = Icons.Outlined.Description, onClick = { nav.open(Routes.builder(t)) })
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ builder

data class BuilderUi(
    val actor: Actor? = null,
    val type: ReportType = ReportType.MANAGEMENT_SUMMARY,
    val reportId: String? = null,
    val selection: FilterSelection = FilterSelection(),
    val master: MasterData = MasterData(),
    val today: LocalDate = LocalDate.now(),
    val document: ReportDocument? = null,
    val building: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class ReportBuilderViewModel @Inject constructor(
    handle: SavedStateHandle,
    master: MasterDataRepository,
    session: SessionManager,
    private val time: TimeSource,
    exports: ExportRepository,
    messages: UserMessages,
) : ExportingViewModel(messages, exports) {
    private val type = ReportType.valueOf(checkNotNull(handle.get<String>("type")))
    private val reportId = handle.get<String>("reportId")?.takeIf { it.isNotBlank() }
    private val local = MutableStateFlow(
        BuilderUi(
            type = type, reportId = reportId, today = time.today(),
            selection = FilterSelection(period = if (type == ReportType.DAILY_PRODUCTION) Period.TODAY else Period.THIS_MONTH),
        ),
    )

    val state: StateFlow<BuilderUi> = combine(local, master.master, session.user) { l, m, u -> l.copy(master = m, actor = u?.toActor()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    fun select(s: FilterSelection) = local.update { it.copy(selection = s, document = null) }

    private var buildJob: Job? = null

    /** Rebuilds the preview; a newer request (e.g. a filter change) cancels the one in progress. */
    fun build() {
        buildJob?.cancel()
        buildJob = viewModelScope.launch {
            local.update { it.copy(building = true, error = null) }
            try {
                val doc = exports.build(type, local.value.selection.toFilters(time.today()), reportId)
                local.update { it.copy(document = doc, building = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: NotAllowedException) {
                local.update { it.copy(building = false, error = e.message) }
            } catch (e: Exception) {
                local.update { it.copy(building = false, error = "The report could not be built (${e.javaClass.simpleName})") }
            }
        }
    }

    fun exportAs(format: ExportFormat, share: Boolean) = export(format, share) {
        local.value.document ?: exports.build(type, local.value.selection.toFilters(time.today()), reportId)
    }
}

@Composable
fun ReportBuilderScreen(nav: Navigator, vm: ReportBuilderViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    ExportEffects(vm)
    LaunchedEffect(ui.selection) { if (ui.document == null) vm.build() }
    val stock = ui.type in setOf(ReportType.RAW_MATERIAL_STOCK, ReportType.FINISHED_GOODS_STOCK, ReportType.INVENTORY_MOVEMENT)
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            ScreenList {
                item {
                    DetailHeader(ui.type.title, nav::back, subtitle = ui.type.description) {
                        CircleIconButton(Icons.Outlined.Refresh, "Rebuild preview", { vm.build() })
                    }
                }
                if (ui.reportId == null) item { FiltersBar(ui.selection, ui.master, ui.today, vm::select, showProduction = !stock) }
                val doc = ui.document
                when {
                    ui.building || (doc == null && ui.error == null) -> item { LoadingState(Modifier.height(160.dp), label = "Building report") }
                    ui.error != null -> item { EmptyState("Could not build the report", ui.error.orEmpty()) }
                    doc != null -> documentPreview(doc)
                }
            }
        }
        ActionBar {
            SecondaryButton("Excel", { vm.exportAs(ExportFormat.XLSX, share = false) }, Modifier.weight(1f), icon = Icons.Outlined.GridOn, loading = busy, fillWidth = false)
            SecondaryButton("Share", { vm.exportAs(ExportFormat.PDF, share = true) }, Modifier.weight(1f), icon = Icons.Outlined.Share, fillWidth = false)
            PrimaryButton("PDF", { vm.exportAs(ExportFormat.PDF, share = false) }, Modifier.weight(1f), icon = Icons.Outlined.PictureAsPdf, loading = busy, fillWidth = false)
        }
    }
}

/** On-screen preview of the same [ReportDocument] the PDF and Excel files are rendered from, so the numbers always match. */
private fun androidx.compose.foundation.lazy.LazyListScope.documentPreview(doc: ReportDocument) {
    item {
        PlasmacoCard {
            Text(doc.title, style = Plasmaco.type.sectionTitle, color = Plasmaco.colors.textPrimary, modifier = Modifier.semantics { heading() })
            doc.subtitle?.let { Text(it, style = Plasmaco.type.bodySmall, color = Plasmaco.colors.textSecondary) }
            Text("${Fmt.date(doc.period.start)} – ${Fmt.date(doc.period.end)} · generated by ${doc.generatedBy}", style = Plasmaco.type.label, color = Plasmaco.colors.textSecondary)
            if (doc.filters.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                doc.filters.forEach { (k, v) -> KeyValueRow(k, v) }
            }
        }
    }
    if (doc.kpis.isNotEmpty()) {
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Plasmaco.dimens.cardGap)) {
                doc.kpis.forEach { k -> KpiCard(k.label + (k.note?.let { " ($it)" } ?: ""), k.value, null) }
            }
        }
    }
    doc.sections.forEachIndexed { i, s ->
        item(key = "s$i") {
            when (s) {
                is Section.Table -> TablePreview(s)
                is Section.Chart -> SectionCard(s.title) { ChartView(s.spec) }
                is Section.Text -> SectionCard(s.title) { s.paragraphs.forEach { p -> Text(p, style = Plasmaco.type.body, color = Plasmaco.colors.textPrimary, modifier = Modifier.padding(bottom = 6.dp)) } }
                is Section.KeyValues -> SectionCard(s.title) { s.pairs.forEach { (k, v) -> KeyValueRow(k, v) } }
            }
        }
    }
    if (doc.approvals.isNotEmpty()) {
        item { SectionCard("Approvals") { doc.approvals.forEach { a -> KeyValueRow(a.action, "${a.by}${a.reason?.let { " — $it" } ?: ""}") } } }
    }
    if (doc.definitions.isNotEmpty()) {
        item { SectionCard("Definitions") { doc.definitions.forEach { (k, v) -> KeyValueRow(k, v) } } }
    }
}

private const val PREVIEW_ROWS = 60

private fun cellText(cell: CellValue, col: Column): String = when (cell) {
    is CellValue.Empty -> ""
    is CellValue.Text -> cell.text
    is CellValue.Day -> Fmt.short(cell.date)
    is CellValue.Num -> cell.value?.let { v -> Formatting.number(v, col.decimals) + if (col.kind == ColumnKind.PERCENT) "%" else "" } ?: ""
}

@Composable
private fun TablePreview(t: Section.Table) {
    val c = Plasmaco.colors
    SectionCard(t.title, subtitle = if (t.rows.size > PREVIEW_ROWS) "First $PREVIEW_ROWS of ${t.rows.size} rows; the exported file has all of them" else "${t.rows.size} row(s)") {
        if (t.rows.isEmpty()) {
            Text(t.note ?: "No rows for this period.", style = Plasmaco.type.bodySmall, color = c.textSecondary)
            return@SectionCard
        }
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            Row(Modifier.background(c.surfaceInset, Plasmaco.shapes.badge).padding(vertical = 6.dp)) {
                t.columns.forEach { col ->
                    Text(
                        col.headerWithUnit, style = Plasmaco.type.tableHeader, color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        textAlign = if (col.kind == ColumnKind.TEXT) TextAlign.Start else TextAlign.End,
                        modifier = Modifier.width((col.weight * 96).dp).padding(horizontal = 6.dp),
                    )
                }
            }
            t.rows.take(PREVIEW_ROWS).forEach { row ->
                Row(Modifier.padding(vertical = 5.dp)) {
                    row.forEachIndexed { i, cell ->
                        val col = t.columns[i]
                        Text(
                            cellText(cell, col), style = Plasmaco.type.tableCell, color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            textAlign = if (col.kind == ColumnKind.TEXT) TextAlign.Start else TextAlign.End,
                            modifier = Modifier.width((col.weight * 96).dp).padding(horizontal = 6.dp),
                        )
                    }
                }
            }
            if (t.showTotals) {
                Row(Modifier.background(c.primaryTint, Plasmaco.shapes.badge).padding(vertical = 6.dp)) {
                    t.columns.forEachIndexed { i, col ->
                        val total = t.totals[i]
                        Text(
                            if (i == 0) "Total" else total?.let { Formatting.number(it, col.decimals) } ?: "",
                            style = Plasmaco.type.tableHeader, color = c.textPrimary,
                            textAlign = if (col.kind == ColumnKind.TEXT) TextAlign.Start else TextAlign.End,
                            modifier = Modifier.width((col.weight * 96).dp).padding(horizontal = 6.dp),
                        )
                    }
                }
            }
        }
        t.note?.let { Spacer(Modifier.height(6.dp)); Text(it, style = Plasmaco.type.label, color = c.textSecondary) }
    }
}
