package rw.plasmaco.ops.ui.history

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Factory
import androidx.compose.material.icons.outlined.FilterAltOff
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import rw.plasmaco.ops.data.repository.MasterDataRepository
import rw.plasmaco.ops.data.repository.ReportRepository
import rw.plasmaco.ops.data.repository.SessionManager
import rw.plasmaco.ops.data.repository.UserRepository
import rw.plasmaco.ops.domain.analytics.Analytics
import rw.plasmaco.ops.domain.common.DateRange
import rw.plasmaco.ops.domain.master.MasterData
import rw.plasmaco.ops.domain.production.ProductionReport
import rw.plasmaco.ops.domain.production.ReportStatus
import rw.plasmaco.ops.domain.security.Actor
import rw.plasmaco.ops.domain.workflow.ReportWorkflow
import rw.plasmaco.ops.ui.common.Fmt
import rw.plasmaco.ops.ui.common.ListCard
import rw.plasmaco.ops.ui.common.MenuChip
import rw.plasmaco.ops.ui.common.ScreenList
import rw.plasmaco.ops.ui.common.SectionTitle
import rw.plasmaco.ops.ui.components.AppFilterChip
import rw.plasmaco.ops.ui.components.DateRangeDialog
import rw.plasmaco.ops.ui.components.DropdownChip
import rw.plasmaco.ops.ui.components.EmptyState
import rw.plasmaco.ops.ui.components.FilterChipRow
import rw.plasmaco.ops.ui.components.LoadingState
import rw.plasmaco.ops.ui.components.PageHeaderCard
import rw.plasmaco.ops.ui.components.SearchField
import rw.plasmaco.ops.ui.navigation.Navigator
import rw.plasmaco.ops.ui.navigation.Routes
import rw.plasmaco.ops.ui.production.ReportRow
import rw.plasmaco.ops.ui.theme.Plasmaco
import java.time.LocalDate
import javax.inject.Inject

data class HistoryQuery(
    val text: String = "",
    val range: DateRange? = null,
    val lineId: String? = null,
    val shiftId: String? = null,
    val productId: String? = null,
    val statuses: Set<ReportStatus> = emptySet(),
) {
    val isFiltered: Boolean get() = text.isNotBlank() || range != null || lineId != null || shiftId != null || productId != null || statuses.isNotEmpty()

    fun matches(r: ProductionReport, m: MasterData): Boolean {
        if (range != null && r.date !in range) return false
        if (lineId != null && r.lineId != lineId) return false
        if (shiftId != null && r.shiftId != shiftId) return false
        if (productId != null && r.entries.none { it.productId == productId }) return false
        if (statuses.isNotEmpty() && r.status !in statuses) return false
        val q = text.trim().lowercase().replace(" ", "")
        if (q.isEmpty()) return true
        val haystack = buildString {
            append(r.reference); append(m.line(r.lineId)?.name); append(m.shift(r.shiftId)?.name); append(r.remarks)
            r.entries.forEach { e -> append(m.product(e.productId)?.name); append(e.batch) }
        }.lowercase().replace(" ", "")
        return q in haystack
    }
}

data class HistoryUi(
    val loading: Boolean = true,
    val actor: Actor? = null,
    val master: MasterData = MasterData(),
    val query: HistoryQuery = HistoryQuery(),
    val results: List<ProductionReport> = emptyList(),
    val userNames: Map<String, String> = emptyMap(),
    val today: LocalDate = LocalDate.now(),
)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    handle: SavedStateHandle,
    reports: ReportRepository,
    master: MasterDataRepository,
    users: UserRepository,
    session: SessionManager,
) : ViewModel() {
    private val query = MutableStateFlow(
        HistoryQuery(
            range = handle.get<String>("date")?.takeIf { it.isNotBlank() }?.let { runCatching { DateRange.single(LocalDate.parse(it)) }.getOrNull() },
            productId = handle.get<String>("product")?.takeIf { it.isNotBlank() },
            lineId = handle.get<String>("line")?.takeIf { it.isNotBlank() },
            shiftId = handle.get<String>("shift")?.takeIf { it.isNotBlank() },
        ),
    )

    val state: StateFlow<HistoryUi> = combine(reports.reports, master.master, users.users, session.user, query) { all, m, us, u, q ->
        val actor = u?.toActor() ?: return@combine HistoryUi(loading = false)
        HistoryUi(
            loading = false, actor = actor, master = m, query = q,
            results = all.filter { ReportWorkflow.canView(it, actor) && q.matches(it, m) }
                .sortedWith(compareByDescending<ProductionReport> { it.date }.thenBy { m.line(it.lineId)?.name }.thenBy { m.shift(it.shiftId)?.start }),
            userNames = us.associate { it.id to it.displayName },
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUi())

    fun update(transform: (HistoryQuery) -> HistoryQuery) = query.update(transform)
    fun clear() { query.value = HistoryQuery() }
}

@Composable
fun HistoryScreen(nav: Navigator, vm: HistoryViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    var pickRange by remember { mutableStateOf(false) }
    if (ui.loading) {
        LoadingState(label = "Loading history")
        return
    }
    val q = ui.query
    val m = ui.master
    val groups = ui.results.groupBy { it.date }
    ScreenList {
        item { PageHeaderCard("Production History", "${ui.results.size} report(s)${if (q.isFiltered) " match the filters" else ""}") }
        item { SearchField(q.text, { t -> vm.update { it.copy(text = t) } }, "Search reference, product, batch, remarks") }
        item {
            FilterChipRow {
                DropdownChip(q.range?.let { "${Fmt.short(it.start)} – ${Fmt.short(it.end)}" } ?: "Any date", { pickRange = true }, icon = Icons.Outlined.CalendarMonth)
                MenuChip("Line", "All lines", m.lines, m.lines.firstOrNull { it.id == q.lineId }, { it.name }, { l -> vm.update { it.copy(lineId = l?.id) } }, Icons.Outlined.Factory)
                MenuChip("Shift", "All shifts", m.shifts, m.shifts.firstOrNull { it.id == q.shiftId }, { it.name }, { s -> vm.update { it.copy(shiftId = s?.id) } }, Icons.Outlined.Schedule)
                MenuChip("Product", "All products", m.products, m.products.firstOrNull { it.id == q.productId }, { Analytics.shortName(it.name) }, { p -> vm.update { it.copy(productId = p?.id) } }, Icons.Outlined.Category)
                if (q.isFiltered) AppFilterChip("Clear", false, vm::clear, icon = Icons.Outlined.FilterAltOff)
            }
        }
        item {
            FilterChipRow {
                ReportStatus.entries.forEach { s ->
                    AppFilterChip(s.label, s in q.statuses, { vm.update { it.copy(statuses = if (s in it.statuses) it.statuses - s else it.statuses + s) } })
                }
            }
        }
        if (ui.results.isEmpty()) {
            item { EmptyState("No reports found", if (q.isFiltered) "Try a wider date range or clear the filters." else "Reports appear here once they are created.", icon = Icons.Outlined.Search) }
        }
        groups.forEach { (date, list) ->
            item(key = "d-$date") { SectionTitle(Fmt.date(date)) }
            item(key = "l-$date") { ListCard(list) { r -> ReportRow(r, m, { nav.open(Routes.detail(r.id)) }, operatorName = ui.userNames[r.operatorId]) } }
        }
        if (ui.results.isNotEmpty()) item { Text("Tap a report to see its details, approvals and versions.", style = Plasmaco.type.label, color = Plasmaco.colors.textSecondary) }
    }
    if (pickRange) {
        val r = q.range ?: DateRange(ui.today.minusDays(30), ui.today)
        DateRangeDialog(r.start, r.end, { pickRange = false }) { s, e -> pickRange = false; vm.update { it.copy(range = DateRange(s, e)) } }
    }
}
