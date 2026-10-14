package rw.plasmaco.ops.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import rw.plasmaco.ops.data.repository.AnalyticsRepository
import rw.plasmaco.ops.data.repository.SessionManager
import rw.plasmaco.ops.data.settings.SettingsRepository
import rw.plasmaco.ops.domain.analytics.Analytics
import rw.plasmaco.ops.domain.analytics.DashboardSnapshot
import rw.plasmaco.ops.domain.analytics.DataSet
import rw.plasmaco.ops.domain.common.TimeSource
import rw.plasmaco.ops.domain.master.MasterData
import rw.plasmaco.ops.domain.master.Shift
import rw.plasmaco.ops.domain.metrics.ProductionMetrics
import rw.plasmaco.ops.domain.production.ReportStatus
import rw.plasmaco.ops.domain.security.Actor
import rw.plasmaco.ops.ui.common.FilterSelection
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject

/** The shift running now and how its reporting is going (the template's dark "live" card). */
data class CurrentShift(
    val shift: Shift,
    val reportDate: LocalDate,
    val activeLines: Int,
    val linesWithReport: Int,
    val drafts: Int,
    val submitted: Int,
    val good: BigDecimal,
    /** Good ÷ target over this shift's reports that have targets; null when no target is set. */
    val achievementPct: BigDecimal?,
)

data class DashboardUi(
    val loading: Boolean = true,
    val actor: Actor? = null,
    val selection: FilterSelection = FilterSelection(),
    val today: LocalDate = LocalDate.now(),
    val master: MasterData = MasterData(),
    val snapshot: DashboardSnapshot? = null,
    val current: CurrentShift? = null,
    val setupGaps: List<String> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DashboardViewModel @Inject constructor(
    analytics: AnalyticsRepository,
    settings: SettingsRepository,
    session: SessionManager,
    private val time: TimeSource,
) : ViewModel() {
    private val selection = MutableStateFlow(FilterSelection())

    private val data = session.user.filterNotNull().flatMapLatest { u -> analytics.dataSetFor(u.toActor()).map { u.toActor() to it } }

    val state: StateFlow<DashboardUi> = combine(data, selection, settings.settings) { (actor, d), sel, s ->
        val today = time.today()
        val snapshot = Analytics.dashboard(d, sel.toFilters(today), today, s.alertThresholds)
        DashboardUi(
            loading = false, actor = actor, selection = sel, today = today, master = d.master, snapshot = snapshot,
            current = currentShift(d, LocalDateTime.ofInstant(time.now(), time.zone())),
            setupGaps = d.master.setupGaps(),
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUi())

    fun select(s: FilterSelection) {
        selection.value = s
    }

    private fun currentShift(d: DataSet, now: LocalDateTime): CurrentShift? {
        val t = now.toLocalTime()
        val shift = d.master.shifts.filter { it.active }.firstOrNull { s ->
            if (s.crossesMidnight) !t.isBefore(s.start) || t.isBefore(s.end) else !t.isBefore(s.start) && t.isBefore(s.end)
        } ?: return null
        // A night shift that started yesterday is reported under yesterday's date.
        val date = if (shift.crossesMidnight && t.isBefore(shift.end)) now.toLocalDate().minusDays(1) else now.toLocalDate()
        val lines = d.master.lines.filter { it.active }
        val reports = d.reports.filter { it.date == date && it.shiftId == shift.id && it.status != ReportStatus.REJECTED }
        val summary = ProductionMetrics.summarize(reports, d.master)
        val achievement = if (summary.target.signum() > 0) summary.targetedGood.multiply(BigDecimal(100)).divide(summary.target, 1, java.math.RoundingMode.HALF_UP) else null
        return CurrentShift(
            shift, date, lines.size, reports.map { it.lineId }.distinct().count { id -> lines.any { it.id == id } },
            reports.count { it.status.editable }, reports.count { it.status == ReportStatus.SUBMITTED || it.status == ReportStatus.APPROVED },
            summary.good, achievement,
        )
    }
}
