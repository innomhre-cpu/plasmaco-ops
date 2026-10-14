package rw.plasmaco.ops.domain.workflow

import org.junit.Test
import rw.plasmaco.ops.domain.TestData
import rw.plasmaco.ops.domain.common.Issue
import rw.plasmaco.ops.domain.common.Outcome
import rw.plasmaco.ops.domain.common.valueOrThrow
import rw.plasmaco.ops.domain.production.ReportDiff
import rw.plasmaco.ops.domain.production.ReportDuplicator
import rw.plasmaco.ops.domain.production.ReportStatus
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WorkflowTest {
    private val now = Instant.parse("2026-10-08T09:00:00Z")
    private fun refusedCode(o: Outcome<*>) = (o as Outcome.Refused).issues.first().code

    @Test
    fun `draft to approved, with a version snapshot on each submission`() {
        val draft = TestData.report()
        val submitted = ReportWorkflow.apply(draft, ReportAction.SUBMIT, TestData.operator, null, now).valueOrThrow()
        assertEquals(ReportStatus.SUBMITTED, submitted.report.status)
        assertEquals(1, submitted.report.version)
        assertTrue(submitted.snapshotRequired)
        val approved = ReportWorkflow.apply(submitted.report, ReportAction.APPROVE, TestData.supervisor, null, now).valueOrThrow()
        assertEquals(ReportStatus.APPROVED, approved.report.status)
        assertEquals(TestData.supervisor.userId, approved.report.decidedBy)
        assertEquals(TestData.supervisor.userId, approved.report.supervisorId)
    }

    @Test
    fun `return and reject need a reason`() {
        val submitted = TestData.report(status = ReportStatus.SUBMITTED)
        assertEquals("workflow.reason_required", refusedCode(ReportWorkflow.apply(submitted, ReportAction.RETURN, TestData.supervisor, " ", now)))
        val returned = ReportWorkflow.apply(submitted, ReportAction.RETURN, TestData.supervisor, "Rejected metres missing", now).valueOrThrow()
        assertEquals(ReportStatus.RETURNED, returned.report.status)
        assertEquals("Rejected metres missing", returned.record.reason)
        // Author corrects and resubmits; the fixture was never submitted before, so this is version 1.
        val again = ReportWorkflow.apply(returned.report, ReportAction.SUBMIT, TestData.operator, null, now).valueOrThrow()
        assertEquals(1, again.report.version)
    }

    @Test
    fun `operators cannot approve and supervisors cannot approve their own report`() {
        val submitted = TestData.report(status = ReportStatus.SUBMITTED)
        assertEquals("workflow.forbidden", refusedCode(ReportWorkflow.apply(submitted, ReportAction.APPROVE, TestData.operator, null, now)))
        val own = submitted.copy(createdBy = TestData.supervisor.userId)
        assertEquals("workflow.self_approval", refusedCode(ReportWorkflow.apply(own, ReportAction.APPROVE, TestData.supervisor, null, now)))
    }

    @Test
    fun `submitting with validation errors is refused`() {
        val draft = TestData.report()
        val errors = listOf(Issue("entries", "entries.empty", "Add at least one production entry"))
        assertEquals("entries.empty", refusedCode(ReportWorkflow.apply(draft, ReportAction.SUBMIT, TestData.operator, null, now, errors)))
    }

    @Test
    fun `approved reports are locked until a manager reopens them with a reason`() {
        val approved = TestData.report(status = ReportStatus.APPROVED)
        assertTrue(!ReportWorkflow.canEdit(approved, TestData.operator))
        assertTrue(!ReportWorkflow.canEdit(approved, TestData.supervisor))
        assertEquals("workflow.forbidden", refusedCode(ReportWorkflow.apply(approved, ReportAction.REOPEN, TestData.supervisor, "Wrong product", now)))
        val reopened = ReportWorkflow.apply(approved, ReportAction.REOPEN, TestData.manager, "Wrong product on entry 1", now).valueOrThrow()
        assertEquals(ReportStatus.REOPENED, reopened.report.status)
        assertTrue(ReportWorkflow.canEdit(reopened.report, TestData.supervisor))
    }

    @Test
    fun `available actions follow role and state`() {
        val submitted = TestData.report(status = ReportStatus.SUBMITTED)
        assertEquals(setOf(ReportAction.APPROVE, ReportAction.REJECT, ReportAction.RETURN), ReportWorkflow.availableActions(submitted, TestData.supervisor).toSet())
        assertEquals(emptyList(), ReportWorkflow.availableActions(submitted, TestData.storekeeper))
    }

    @Test
    fun `operators see only their own reports`() {
        val mine = TestData.report()
        val theirs = mine.copy(createdBy = "someone", operatorId = "someone")
        assertTrue(ReportWorkflow.canView(mine, TestData.operator))
        assertTrue(!ReportWorkflow.canView(theirs, TestData.operator))
        assertTrue(ReportWorkflow.canView(theirs, TestData.manager))
    }

    @Test
    fun `duplicating copies products but never quantities, approvals or stock`() {
        val src = TestData.report(status = ReportStatus.APPROVED)
        val copy = ReportDuplicator.duplicateAsDraft(src, TestData.TODAY, "PR-NEW", TestData.operator.userId, now)
        assertEquals(ReportStatus.DRAFT, copy.status)
        assertEquals(0, copy.version)
        assertEquals(src.entries.map { it.productId }, copy.entries.map { it.productId })
        assertTrue(copy.entries.all { it.gross.signum() == 0 && it.good.signum() == 0 })
        assertTrue(copy.materials.all { it.issued.signum() == 0 })
        assertTrue(copy.downtime.isEmpty() && copy.inspections.isEmpty())
        assertEquals(src.id, copy.duplicatedFrom)
    }

    @Test
    fun `the diff names every changed field`() {
        val a = TestData.report()
        val b = a.copy(remarks = "Die change at 10:00", entries = a.entries.map { it.copy(good = BigDecimal(390), rejected = BigDecimal(30)) })
        val paths = ReportDiff.between(a, b).map { it.path }
        assertTrue("remarks" in paths)
        assertTrue(paths.any { it.endsWith(".good") } && paths.any { it.endsWith(".rejected") })
        assertIs<List<ReportDiff.Change>>(ReportDiff.between(a, a)).let { assertTrue(it.isEmpty()) }
    }
}
