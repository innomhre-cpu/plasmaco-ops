package rw.plasmaco.ops.ui.production.editor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import rw.plasmaco.ops.data.repository.MasterDataRepository
import rw.plasmaco.ops.data.repository.ReportRepository
import rw.plasmaco.ops.data.repository.SessionManager
import rw.plasmaco.ops.data.repository.UserRepository
import rw.plasmaco.ops.domain.common.Issue
import rw.plasmaco.ops.domain.common.Outcome
import rw.plasmaco.ops.domain.common.TimeSource
import rw.plasmaco.ops.domain.common.hasErrors
import rw.plasmaco.ops.domain.master.MasterData
import rw.plasmaco.ops.domain.production.DowntimeEvent
import rw.plasmaco.ops.domain.production.MaterialConsumption
import rw.plasmaco.ops.domain.production.ProductionEntry
import rw.plasmaco.ops.domain.production.ProductionReport
import rw.plasmaco.ops.domain.production.QualityInspection
import rw.plasmaco.ops.domain.production.ShiftHandover
import rw.plasmaco.ops.domain.security.Actor
import rw.plasmaco.ops.domain.security.User
import rw.plasmaco.ops.domain.workflow.ReportAction
import rw.plasmaco.ops.domain.workflow.ReportWorkflow
import rw.plasmaco.ops.ui.common.PlasmacoViewModel
import rw.plasmaco.ops.ui.common.UserMessages
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import javax.inject.Inject

/** The seven editor steps (requirements: identification, production, materials, downtime, quality, handover, review). */
enum class EditorStep(val title: String) {
    IDENTIFICATION("Identification"),
    PRODUCTION("Production"),
    MATERIALS("Materials"),
    DOWNTIME("Downtime"),
    QUALITY("Quality"),
    HANDOVER("Handover"),
    REVIEW("Review");

    companion object {
        /** Which step holds the input a validation issue points at (issue fields are paths like "entries[2].good"). */
        fun of(field: String): EditorStep = when {
            field.startsWith("entries") -> PRODUCTION
            field.startsWith("materials") -> MATERIALS
            field.startsWith("downtime") -> DOWNTIME
            field.startsWith("inspections") -> QUALITY
            field.startsWith("handover") -> HANDOVER
            else -> IDENTIFICATION
        }
    }
}

data class EditorUi(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    val actor: Actor? = null,
    val master: MasterData = MasterData(),
    val users: List<User> = emptyList(),
    val today: LocalDate = LocalDate.now(),
    val report: ProductionReport? = null,
    val step: EditorStep = EditorStep.IDENTIFICATION,
    /** Unsaved local changes. */
    val dirty: Boolean = false,
    val canEdit: Boolean = false,
    /** Findings from the last save (draft rules). */
    val draftIssues: List<Issue> = emptyList(),
    /** Findings from the submission check (review step); null until checked. */
    val submitIssues: List<Issue>? = null,
) {
    val issues: List<Issue> get() = (submitIssues ?: emptyList()) + draftIssues.filter { d -> submitIssues?.none { it.field == d.field && it.code == d.code } ?: true }
    fun issuesFor(step: EditorStep): List<Issue> = issues.filter { EditorStep.of(it.field) == step }
}

/**
 * Holds the report being edited in memory. Nothing reaches the database until "Save draft" or "Save and continue";
 * every save runs the draft rules, and the review step runs the full submission rules before "Submit" is offered.
 */
@HiltViewModel
class ReportEditorViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val reports: ReportRepository,
    master: MasterDataRepository,
    users: UserRepository,
    private val session: SessionManager,
    private val time: TimeSource,
    messages: UserMessages,
) : PlasmacoViewModel(messages) {
    private val reportId: String = checkNotNull(handle["id"])
    private val _ui = MutableStateFlow(EditorUi(step = EditorStep.entries.getOrElse(handle.get<Int>("step") ?: 0) { EditorStep.IDENTIFICATION }))
    val ui: StateFlow<EditorUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            val r = reports.report(reportId).first()
            val actor = session.actor
            _ui.update {
                it.copy(
                    loading = false, notFound = r == null, report = r, actor = actor, today = time.today(),
                    canEdit = r != null && actor != null && ReportWorkflow.canEdit(r, actor),
                )
            }
            if (_ui.value.step == EditorStep.REVIEW) checkSubmit()
        }
        viewModelScope.launch { master.master.collect { m -> _ui.update { it.copy(master = m) } } }
        viewModelScope.launch { users.users.collect { u -> _ui.update { it.copy(users = u) } } }
    }

    // ------------------------------------------------------------------------------------------- navigation

    fun goTo(step: EditorStep) {
        _ui.update { it.copy(step = step) }
        if (step == EditorStep.REVIEW) checkSubmit()
    }

    /** "Save and continue": saves the draft, then moves to the next step. */
    fun saveAndContinue() = save(next = EditorStep.entries.getOrNull(_ui.value.step.ordinal + 1))

    fun save(next: EditorStep? = null, onSaved: (() -> Unit)? = null) = action {
        val r = _ui.value.report ?: return@action
        if (!_ui.value.canEdit) return@action
        when (val o = reports.saveDraft(r)) {
            is Outcome.Ok -> {
                _ui.update { it.copy(report = o.value, dirty = false, draftIssues = o.warnings, step = next ?: it.step) }
                if (next == null && onSaved == null) messages.show("Draft saved")
                if (next == EditorStep.REVIEW) checkSubmitNow()
                onSaved?.invoke()
            }
            is Outcome.Refused -> {
                _ui.update { it.copy(draftIssues = o.issues) }
                messages.show("Not saved: ${o.issues.first().message}")
            }
        }
    }

    fun checkSubmit() = viewModelScope.launch { checkSubmitNow() }

    private suspend fun checkSubmitNow() {
        val r = _ui.value.report ?: return
        _ui.update { it.copy(submitIssues = reports.validateForSubmit(r)) }
    }

    /** Saves (if needed) and submits; the repository re-runs the submission rules and stores the version snapshot. */
    fun submit(onSubmitted: (String) -> Unit) = action {
        val r = _ui.value.report ?: return@action
        if (_ui.value.dirty) {
            val saved = reports.saveDraft(r)
            if (saved is Outcome.Refused) {
                _ui.update { it.copy(draftIssues = saved.issues) }
                messages.show("Not saved: ${saved.issues.first().message}")
                return@action
            }
            _ui.update { it.copy(report = (saved as Outcome.Ok).value, dirty = false) }
        }
        val issues = reports.validateForSubmit(_ui.value.report ?: return@action)
        _ui.update { it.copy(submitIssues = issues) }
        if (issues.hasErrors()) {
            messages.show("Fix the items marked in red before submitting")
            return@action
        }
        when (val o = reports.applyAction(r.id, ReportAction.SUBMIT, null)) {
            is Outcome.Ok -> {
                _ui.update { it.copy(report = o.value, canEdit = false) }
                messages.show("${o.value.reference} submitted for approval")
                onSubmitted(o.value.id)
            }
            is Outcome.Refused -> {
                _ui.update { it.copy(submitIssues = o.issues) }
                messages.show(o.issues.first().message)
            }
        }
    }

    // ------------------------------------------------------------------------------------------- edits

    private fun edit(transform: (ProductionReport) -> ProductionReport) {
        if (!_ui.value.canEdit) return
        _ui.update { s -> s.report?.let { s.copy(report = transform(it), dirty = true, submitIssues = null) } ?: s }
    }

    /** Changing the date moves every time stamp in the report by the same number of days. */
    fun setDate(date: LocalDate) = edit { r ->
        val days = ChronoUnit.DAYS.between(r.date, date)
        fun LocalDateTime.shift() = plusDays(days)
        r.copy(
            date = date,
            entries = r.entries.map { e -> e.copy(start = e.start?.shift(), end = e.end?.shift()) },
            downtime = r.downtime.map { d -> d.copy(start = d.start.shift(), end = d.end?.shift()) },
            inspections = r.inspections.map { i -> i.copy(time = i.time.shift()) },
        )
    }

    fun setLine(lineId: String) = edit { it.copy(lineId = lineId) }

    /** A new shift brings its own default times when the report still had the previous shift's times. */
    fun setShift(shiftId: String) = edit { r ->
        val m = _ui.value.master
        val old = m.shift(r.shiftId)
        val new = m.shift(shiftId) ?: return@edit r
        val keepTimes = old != null && (r.startTime != old.start || r.endTime != old.end)
        r.copy(shiftId = shiftId, startTime = if (keepTimes) r.startTime else new.start, endTime = if (keepTimes) r.endTime else new.end)
    }

    fun setOperator(id: String) = edit { it.copy(operatorId = id) }
    fun setSupervisor(id: String?) = edit { it.copy(supervisorId = id) }
    fun setStart(t: LocalTime) = edit { it.copy(startTime = t) }
    fun setEnd(t: LocalTime) = edit { it.copy(endTime = t) }
    fun setRemarks(text: String) = edit { it.copy(remarks = text) }

    fun upsertEntry(e: ProductionEntry) = edit { r -> r.copy(entries = r.entries.upsert(e) { it.id }) }
    fun removeEntry(id: String) = edit { r ->
        r.copy(entries = r.entries.filterNot { it.id == id }, inspections = r.inspections.map { if (it.entryId == id) it.copy(entryId = null) else it })
    }
    fun upsertMaterial(m: MaterialConsumption) = edit { r -> r.copy(materials = r.materials.upsert(m) { it.id }) }
    fun removeMaterial(id: String) = edit { r -> r.copy(materials = r.materials.filterNot { it.id == id }) }
    fun upsertDowntime(d: DowntimeEvent) = edit { r -> r.copy(downtime = r.downtime.upsert(d) { it.id }.sortedBy { it.start }) }
    fun removeDowntime(id: String) = edit { r -> r.copy(downtime = r.downtime.filterNot { it.id == id }) }
    fun upsertInspection(i: QualityInspection) = edit { r -> r.copy(inspections = r.inspections.upsert(i) { it.id }.sortedBy { it.time }) }
    fun removeInspection(id: String) = edit { r -> r.copy(inspections = r.inspections.filterNot { it.id == id }) }
    fun setHandover(h: ShiftHandover) = edit { it.copy(handover = h.takeUnless { h.isEmpty }) }

    private fun <T> List<T>.upsert(item: T, key: (T) -> String): List<T> {
        val i = indexOfFirst { key(it) == key(item) }
        return if (i < 0) this + item else toMutableList().also { it[i] = item }
    }

    companion object {
        /**
         * A clock time entered on the form, placed in the report window: on a shift that crosses midnight, times
         * before the report's start belong to the next calendar day.
         */
        fun resolve(report: ProductionReport, t: LocalTime): LocalDateTime {
            val start = report.startTime
            val end = report.endTime
            val crosses = start != null && end != null && !end.isAfter(start)
            return if (crosses && t.isBefore(start)) report.date.plusDays(1).atTime(t) else report.date.atTime(t)
        }
    }
}
