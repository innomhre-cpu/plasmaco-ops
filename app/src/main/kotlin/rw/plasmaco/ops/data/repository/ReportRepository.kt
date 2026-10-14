package rw.plasmaco.ops.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import rw.plasmaco.ops.data.local.PlasmacoDatabase
import rw.plasmaco.ops.data.local.ReportVersionEntity
import rw.plasmaco.ops.data.local.toDomain
import rw.plasmaco.ops.data.local.toEntity
import rw.plasmaco.ops.api.ReportActionDto
import rw.plasmaco.ops.api.ReportDto
import rw.plasmaco.ops.api.toDomain
import rw.plasmaco.ops.api.toDto
import rw.plasmaco.ops.domain.common.Issue
import rw.plasmaco.ops.domain.common.Outcome
import rw.plasmaco.ops.domain.common.TimeSource
import rw.plasmaco.ops.domain.common.hasErrors
import rw.plasmaco.ops.domain.common.newId
import rw.plasmaco.ops.domain.production.ProductionReport
import rw.plasmaco.ops.domain.production.ReportDiff
import rw.plasmaco.ops.domain.production.ReportDuplicator
import rw.plasmaco.ops.domain.production.ReportReferences
import rw.plasmaco.ops.domain.production.ReportStatus
import rw.plasmaco.ops.domain.production.ReportValidator
import rw.plasmaco.ops.domain.security.Permission
import rw.plasmaco.ops.domain.sync.OpKind
import rw.plasmaco.ops.domain.workflow.ApprovalRecord
import rw.plasmaco.ops.domain.workflow.ReportAction
import rw.plasmaco.ops.domain.workflow.ReportWorkflow
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

data class VersionSnapshot(val version: Int, val createdAt: Long, val createdBy: String, val report: ProductionReport)

@Singleton
class ReportRepository @Inject constructor(
    private val db: PlasmacoDatabase,
    private val session: SessionManager,
    private val master: MasterDataRepository,
    private val recorder: ChangeRecorder,
    private val time: TimeSource,
) {
    private val dao get() = db.reports()

    val reports: Flow<List<ProductionReport>> = dao.observeAll().map { list -> list.map { it.toDomain() } }
    val approvals: Flow<List<ApprovalRecord>> = dao.observeAllApprovals().map { list -> list.map { it.toDomain() } }
    val pendingApprovalCount: Flow<Int> = dao.observePendingCount()

    fun report(id: String): Flow<ProductionReport?> = dao.observe(id).map { it?.toDomain() }
    fun approvals(reportId: String): Flow<List<ApprovalRecord>> = dao.observeApprovals(reportId).map { l -> l.map { it.toDomain() } }
    fun versions(reportId: String): Flow<List<VersionSnapshot>> = dao.observeVersions(reportId).map { l ->
        l.map { VersionSnapshot(it.reportVersion, it.createdAt, it.createdBy, AppJson.decodeFromString(ReportDto.serializer(), it.snapshotJson).toDomain()) }
    }
    fun syncState(id: String): Flow<String?> = dao.observe(id).map { it?.report?.syncState }

    /** Creates an empty draft for a line and shift; refuses a second live report for the same date, shift and line. */
    suspend fun createDraft(date: LocalDate, shiftId: String, lineId: String, operatorId: String?): Outcome<ProductionReport> {
        val actor = session.requireActor()
        if (!actor.can(Permission.REPORT_CREATE)) return Outcome.Refused("actor", "workflow.forbidden", "Your role cannot create production reports")
        val m = master.snapshot()
        val line = m.line(lineId) ?: return Outcome.Refused("lineId", "line.unknown", "Choose a production line")
        val shift = m.shift(shiftId) ?: return Outcome.Refused("shiftId", "shift.unknown", "Choose a shift")
        val existing = dao.all().map { it.toDomain() }.firstOrNull { it.date == date && it.shiftId == shiftId && it.lineId == lineId && it.status != ReportStatus.REJECTED }
        if (existing != null) return Outcome.Refused("date", "report.duplicate", "Report ${existing.reference} already exists for ${line.name}, ${shift.name} on $date")
        val now = time.now()
        val reference = ReportReferences.next(date, line.code, shift.code, dao.referencesLike("PR-%04d%02d%02d".format(date.year, date.monthValue, date.dayOfMonth)))
        val report = ProductionReport(
            id = newId(), reference = reference, date = date, shiftId = shiftId, lineId = lineId, operatorId = operatorId ?: actor.userId,
            startTime = shift.start, endTime = shift.end, createdBy = actor.userId, createdAt = now, updatedAt = now,
        )
        db.withTransaction {
            dao.replace(report.toEntity(0, "PENDING"))
            recorder.audit(actor, EntityTypes.REPORT, report.id, "CREATE", "Created draft $reference")
            recorder.enqueue(EntityTypes.REPORT, report.id, OpKind.UPSERT, AppJson.encodeToJsonElement(report.toDto()), null)
        }
        return Outcome.Ok(report)
    }

    /** Saves a draft (or a returned/reopened report). Draft-level validation only: incomplete work can always be saved. */
    suspend fun saveDraft(report: ProductionReport): Outcome<ProductionReport> {
        val actor = session.requireActor()
        val stored = dao.get(report.id)
        val before = stored?.toDomain()
        if (before != null && !ReportWorkflow.canEdit(before, actor)) return Outcome.Refused("status", "report.locked", "This report is ${before.status.label.lowercase()} and cannot be edited")
        if (before == null && !actor.can(Permission.REPORT_CREATE)) return Outcome.Refused("actor", "workflow.forbidden", "Your role cannot create production reports")
        val issues = ReportValidator.validate(report, master.snapshot(), time.today(), dao.all().map { it.toDomain() }, ReportValidator.Stage.DRAFT)
        if (issues.hasErrors()) return Outcome.Refused(issues)
        val updated = report.copy(updatedAt = time.now(), status = before?.status ?: report.status, version = before?.version ?: report.version)
        val serverVersion = stored?.report?.version ?: 0
        db.withTransaction {
            dao.replace(updated.toEntity(serverVersion, "PENDING"))
            val diff = before?.let { ReportDiff.between(it, updated) }.orEmpty()
            if (before == null || diff.isNotEmpty()) {
                recorder.audit(actor, EntityTypes.REPORT, updated.id, if (before == null) "CREATE" else "EDIT",
                    if (before == null) "Created ${updated.reference}" else "Edited ${updated.reference}: ${diff.size} change(s)",
                    JsonArray(diff.take(200).map { c -> JsonObject(mapOf("path" to JsonPrimitive(c.path), "before" to JsonPrimitive(c.before), "after" to JsonPrimitive(c.after))) }))
                recorder.enqueue(EntityTypes.REPORT, updated.id, OpKind.UPSERT, AppJson.encodeToJsonElement(updated.toDto(serverVersion)), serverVersion.takeIf { it > 0 })
            }
        }
        return Outcome.Ok(updated, issues)
    }

    /** Submission-level validation, for the editor's review step. */
    suspend fun validateForSubmit(report: ProductionReport): List<Issue> =
        ReportValidator.validate(report, master.snapshot(), time.today(), dao.all().map { it.toDomain() }, ReportValidator.Stage.SUBMIT)

    /**
     * Applies a workflow action. Submission stores an immutable snapshot; every action writes the approval history,
     * the audit log and a sync operation that the server re-checks against its own copy.
     */
    suspend fun applyAction(reportId: String, action: ReportAction, reason: String?): Outcome<ProductionReport> {
        val actor = session.requireActor()
        val stored = dao.get(reportId) ?: return Outcome.Refused("id", "report.missing", "Report not found")
        val report = stored.toDomain()
        val issues = if (action == ReportAction.SUBMIT) validateForSubmit(report) else emptyList()
        val outcome = ReportWorkflow.apply(report, action, actor, reason, time.now(), issues)
        val t = when (outcome) {
            is Outcome.Refused -> return outcome
            is Outcome.Ok -> outcome.value
        }
        val serverVersion = stored.report.version
        db.withTransaction {
            dao.replace(t.report.toEntity(serverVersion, "PENDING"))
            dao.insertApproval(t.record.toEntity())
            val snapshot = if (t.snapshotRequired) t.report.toDto(serverVersion) else null
            if (snapshot != null) {
                dao.insertVersion(ReportVersionEntity(newId(), t.report.id, t.report.version, AppJson.encodeToString(snapshot), time.now().toEpochMilli(), actor.userId))
            }
            recorder.audit(actor, EntityTypes.REPORT, t.report.id, action.name, "${action.label}: ${t.report.reference}${t.record.reason?.let { " — $it" } ?: ""}")
            recorder.enqueue(
                EntityTypes.REPORT_ACTION, t.report.id, OpKind.ACTION,
                AppJson.encodeToJsonElement(ReportActionDto(t.report.id, action.name, t.record.reason, actor.userId, t.record.at.toEpochMilli(), t.record.id, snapshot)),
                serverVersion.takeIf { it > 0 },
            )
        }
        return Outcome.Ok(t.report)
    }

    suspend fun duplicate(sourceId: String, date: LocalDate): Outcome<ProductionReport> {
        val actor = session.requireActor()
        if (!actor.can(Permission.REPORT_DUPLICATE)) return Outcome.Refused("actor", "workflow.forbidden", "Your role cannot duplicate reports")
        val source = dao.get(sourceId)?.toDomain() ?: return Outcome.Refused("id", "report.missing", "Report not found")
        val m = master.snapshot()
        val line = m.line(source.lineId)
        val shift = m.shift(source.shiftId)
        if (line == null || shift == null) return Outcome.Refused("lineId", "master.missing", "The line or shift of the source report no longer exists")
        val clash = dao.all().map { it.toDomain() }.firstOrNull { it.date == date && it.shiftId == source.shiftId && it.lineId == source.lineId && it.status != ReportStatus.REJECTED }
        if (clash != null) return Outcome.Refused("date", "report.duplicate", "Report ${clash.reference} already exists for that date, shift and line")
        val reference = ReportReferences.next(date, line.code, shift.code, dao.referencesLike("PR-%04d%02d%02d".format(date.year, date.monthValue, date.dayOfMonth)))
        val copy = ReportDuplicator.duplicateAsDraft(source, date, reference, actor.userId, time.now()).copy(startTime = shift.start, endTime = shift.end)
        db.withTransaction {
            dao.replace(copy.toEntity(0, "PENDING"))
            recorder.audit(actor, EntityTypes.REPORT, copy.id, "DUPLICATE", "Duplicated ${source.reference} as $reference (no quantities copied)")
            recorder.enqueue(EntityTypes.REPORT, copy.id, OpKind.UPSERT, AppJson.encodeToJsonElement(copy.toDto()), null)
        }
        return Outcome.Ok(copy)
    }

    /** Only never-submitted drafts can be deleted; anything submitted stays for the audit trail. */
    suspend fun deleteDraft(id: String): Boolean {
        val actor = session.requireActor()
        val r = dao.get(id)?.toDomain() ?: return false
        if (!ReportWorkflow.canEdit(r, actor) || r.status != ReportStatus.DRAFT || r.version > 0) return false
        var deleted = false
        db.withTransaction {
            deleted = dao.deleteDraft(id) > 0
            if (deleted) {
                recorder.audit(actor, EntityTypes.REPORT, id, "DELETE", "Deleted unsubmitted draft ${r.reference}")
                recorder.enqueue(EntityTypes.REPORT, id, OpKind.DELETE, JsonPrimitive(id), null)
            }
        }
        return deleted
    }
}
