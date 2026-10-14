package rw.plasmaco.ops.domain.workflow

import rw.plasmaco.ops.domain.common.Id
import rw.plasmaco.ops.domain.common.Issue
import rw.plasmaco.ops.domain.common.Outcome
import rw.plasmaco.ops.domain.common.hasErrors
import rw.plasmaco.ops.domain.common.newId
import rw.plasmaco.ops.domain.production.ProductionReport
import rw.plasmaco.ops.domain.production.ReportStatus
import rw.plasmaco.ops.domain.security.Actor
import rw.plasmaco.ops.domain.security.Permission
import rw.plasmaco.ops.domain.security.Role
import java.time.Instant

enum class ReportAction(val label: String, val needsReason: Boolean) {
    SUBMIT("Submit for approval", false),
    APPROVE("Approve", false),
    REJECT("Reject", true),
    RETURN("Return for correction", true),
    REOPEN("Reopen", true),
}

/** One row of a report's approval history. */
data class ApprovalRecord(
    val id: Id,
    val reportId: Id,
    val action: ReportAction,
    val fromStatus: ReportStatus,
    val toStatus: ReportStatus,
    val actorId: Id,
    val actorName: String,
    val reason: String?,
    val at: Instant,
    val version: Int,
)

data class Transition(
    val report: ProductionReport,
    val record: ApprovalRecord,
    /** True when the caller must persist an immutable snapshot of [report] as version [ProductionReport.version]. */
    val snapshotRequired: Boolean,
)

/**
 * The report state machine (discovery report, section 8). Pure: given a report, an actor and an action it
 * returns the next report state plus the approval record, or the reasons it is refused.
 */
object ReportWorkflow {
    data class Policy(
        /** Segregation of duties: a supervisor may not approve a report they created or operated. Admins are exempt. */
        val forbidSelfApproval: Boolean = true,
        val minReasonLength: Int = 5,
    )

    private val allowedFrom: Map<ReportAction, Set<ReportStatus>> = mapOf(
        ReportAction.SUBMIT to setOf(ReportStatus.DRAFT, ReportStatus.RETURNED, ReportStatus.REOPENED),
        ReportAction.APPROVE to setOf(ReportStatus.SUBMITTED),
        ReportAction.REJECT to setOf(ReportStatus.SUBMITTED),
        ReportAction.RETURN to setOf(ReportStatus.SUBMITTED),
        ReportAction.REOPEN to setOf(ReportStatus.APPROVED),
    )

    private val target: Map<ReportAction, ReportStatus> = mapOf(
        ReportAction.SUBMIT to ReportStatus.SUBMITTED,
        ReportAction.APPROVE to ReportStatus.APPROVED,
        ReportAction.REJECT to ReportStatus.REJECTED,
        ReportAction.RETURN to ReportStatus.RETURNED,
        ReportAction.REOPEN to ReportStatus.REOPENED,
    )

    private val permission: Map<ReportAction, Permission> = mapOf(
        ReportAction.SUBMIT to Permission.REPORT_SUBMIT,
        ReportAction.APPROVE to Permission.REPORT_APPROVE,
        ReportAction.REJECT to Permission.REPORT_REJECT,
        ReportAction.RETURN to Permission.REPORT_RETURN,
        ReportAction.REOPEN to Permission.REPORT_REOPEN,
    )

    fun availableActions(report: ProductionReport, actor: Actor, policy: Policy = Policy()): List<ReportAction> =
        ReportAction.entries.filter { refusal(report, actor, it, policy) == null }

    /** Why [actor] may not take [action] now (ignoring the reason text), or null when allowed. */
    fun refusal(report: ProductionReport, actor: Actor, action: ReportAction, policy: Policy = Policy()): Issue? {
        if (report.status !in allowedFrom.getValue(action)) {
            return Issue("status", "workflow.invalid_state", "${action.label} is not possible while the report is ${report.status.label.lowercase()}")
        }
        if (!actor.can(permission.getValue(action))) {
            return Issue("actor", "workflow.forbidden", "Your role cannot ${action.label.lowercase()} reports")
        }
        if (action == ReportAction.SUBMIT && !canEdit(report, actor)) {
            return Issue("actor", "workflow.not_author", "Only the report's author or a supervisor can submit it")
        }
        val decision = action == ReportAction.APPROVE || action == ReportAction.REJECT || action == ReportAction.RETURN
        if (decision && policy.forbidSelfApproval && !actor.hasRole(Role.ADMIN) &&
            (report.createdBy == actor.userId || report.operatorId == actor.userId)
        ) {
            return Issue("actor", "workflow.self_approval", "You cannot decide on a report you created or operated")
        }
        return null
    }

    /**
     * Applies [action]. [validationIssues] are the report's SUBMIT-stage validation results; submitting with
     * errors is refused, so an incomplete report can never reach a supervisor.
     */
    fun apply(
        report: ProductionReport,
        action: ReportAction,
        actor: Actor,
        reason: String?,
        now: Instant,
        validationIssues: List<Issue> = emptyList(),
        policy: Policy = Policy(),
    ): Outcome<Transition> {
        refusal(report, actor, action, policy)?.let { return Outcome.Refused(listOf(it)) }
        val cleanReason = reason?.trim()?.takeIf { it.isNotEmpty() }
        if (action.needsReason && (cleanReason == null || cleanReason.length < policy.minReasonLength)) {
            return Outcome.Refused("reason", "workflow.reason_required", "Give a reason of at least ${policy.minReasonLength} characters")
        }
        if (action == ReportAction.SUBMIT && validationIssues.hasErrors()) {
            return Outcome.Refused(validationIssues)
        }
        val to = target.getValue(action)
        val version = if (action == ReportAction.SUBMIT) report.version + 1 else report.version
        val updated = when (action) {
            ReportAction.SUBMIT -> report.copy(status = to, version = version, submittedAt = now, updatedAt = now, decidedAt = null, decidedBy = null)
            ReportAction.APPROVE, ReportAction.REJECT, ReportAction.RETURN ->
                report.copy(status = to, decidedAt = now, decidedBy = actor.userId, updatedAt = now, supervisorId = report.supervisorId ?: actor.userId)
            ReportAction.REOPEN -> report.copy(status = to, updatedAt = now)
        }
        val record = ApprovalRecord(newId(), report.id, action, report.status, to, actor.userId, actor.displayName, cleanReason, now, version)
        return Outcome.Ok(Transition(updated, record, snapshotRequired = action == ReportAction.SUBMIT))
    }

    fun canEdit(report: ProductionReport, actor: Actor): Boolean =
        report.status.editable && (
            (report.createdBy == actor.userId && actor.can(Permission.REPORT_EDIT_OWN_DRAFT)) ||
                actor.can(Permission.REPORT_EDIT_ANY_DRAFT)
            )

    fun canView(report: ProductionReport, actor: Actor): Boolean =
        actor.can(Permission.REPORT_VIEW_ALL) ||
            (actor.can(Permission.REPORT_VIEW_OWN) && (report.createdBy == actor.userId || report.operatorId == actor.userId))
}
