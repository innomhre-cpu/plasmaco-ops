package rw.plasmaco.ops.server

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import rw.plasmaco.ops.api.ApprovalDto
import rw.plasmaco.ops.api.CategoryDto
import rw.plasmaco.ops.api.LineDto
import rw.plasmaco.ops.api.MachineDto
import rw.plasmaco.ops.api.MaterialDto
import rw.plasmaco.ops.api.ProductDto
import rw.plasmaco.ops.api.PushOperation
import rw.plasmaco.ops.api.PushRequest
import rw.plasmaco.ops.api.PushResult
import rw.plasmaco.ops.api.PushStatus
import rw.plasmaco.ops.api.ReportActionDto
import rw.plasmaco.ops.api.ReportDto
import rw.plasmaco.ops.api.ShiftDto
import rw.plasmaco.ops.api.TxnDto
import rw.plasmaco.ops.api.WireEntity
import rw.plasmaco.ops.api.WireJson
import rw.plasmaco.ops.api.toDomain
import rw.plasmaco.ops.api.toDto
import rw.plasmaco.ops.domain.TestData
import rw.plasmaco.ops.domain.common.FixedTimeSource
import rw.plasmaco.ops.domain.common.valueOrThrow
import rw.plasmaco.ops.domain.inventory.InventorySettings
import rw.plasmaco.ops.domain.inventory.InventoryTransaction
import rw.plasmaco.ops.domain.inventory.MovementRequest
import rw.plasmaco.ops.domain.inventory.MovementService
import rw.plasmaco.ops.domain.inventory.TxnOrigin
import rw.plasmaco.ops.domain.inventory.TxnStatus
import rw.plasmaco.ops.domain.inventory.TxnType
import rw.plasmaco.ops.domain.master.ItemKind
import rw.plasmaco.ops.domain.production.ProductionReport
import rw.plasmaco.ops.domain.production.ReportStatus
import rw.plasmaco.ops.domain.security.Actor
import rw.plasmaco.ops.domain.security.Role
import rw.plasmaco.ops.domain.units.UnitOfMeasure
import rw.plasmaco.ops.domain.workflow.ReportAction
import rw.plasmaco.ops.domain.workflow.ReportWorkflow
import rw.plasmaco.ops.server.core.RequestPrincipal
import rw.plasmaco.ops.server.core.SyncService
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SyncServiceTest {
    private val time = FixedTimeSource(TestData.NOW)
    private var store = InMemoryStore()
    private var sync = SyncService(store, time)
    private var n = 0

    private val m = TestData.masterFromCatalogue()
    private val dn110 = TestData.product("PE100-DN110-PN10")
    private val op = TestData.operator
    private val sup = TestData.supervisor
    private val store1 = TestData.storekeeper
    private val admin = TestData.admin

    private fun who(a: Actor, device: String = "dev-${a.userId}") = RequestPrincipal(a, device)
    private fun <T> json(s: KSerializer<T>, v: T): JsonElement = WireJson.encodeToJsonElement(s, v)
    private fun operation(type: String, id: String, kind: String, payload: JsonElement, base: Long? = null, key: String? = null): PushOperation {
        n++
        return PushOperation("op$n", key ?: "key$n", type, id, kind, base, payload)
    }
    private fun push(p: RequestPrincipal, vararg ops: PushOperation): List<PushResult> = sync.push(p, PushRequest(p.deviceId, ops.toList())).results
    private fun one(p: RequestPrincipal, o: PushOperation): PushResult = push(p, o).single()

    private fun seedMaster() {
        val ops = listOf(
            operation(WireEntity.LINE, TestData.LINE.id, "UPSERT", json(LineDto.serializer(), TestData.LINE.toDto())),
            operation(WireEntity.MACHINE, TestData.MACHINE.id, "UPSERT", json(MachineDto.serializer(), TestData.MACHINE.toDto())),
            operation(WireEntity.SHIFT, TestData.DAY.id, "UPSERT", json(ShiftDto.serializer(), TestData.DAY.toDto())),
            operation(WireEntity.SHIFT, TestData.NIGHT.id, "UPSERT", json(ShiftDto.serializer(), TestData.NIGHT.toDto())),
            operation(WireEntity.DOWNTIME_CATEGORY, TestData.BREAKDOWN.id, "UPSERT", json(CategoryDto.serializer(), TestData.BREAKDOWN.toDto())),
            operation(WireEntity.MATERIAL, TestData.RESIN.id, "UPSERT", json(MaterialDto.serializer(), TestData.RESIN.toDto())),
            operation(WireEntity.PRODUCT, dn110.id, "UPSERT", json(ProductDto.serializer(), dn110.toDto())),
        )
        val results = push(who(admin), *ops.toTypedArray())
        assertTrue(results.all { it.status == PushStatus.APPLIED }, results.toString())
    }

    private fun reportJson(r: ProductionReport) = json(ReportDto.serializer(), r.toDto())
    private fun serverReport(id: String): ProductionReport = WireJson.decodeFromString(ReportDto.serializer(), store.record(WireEntity.REPORT, id)!!.payload!!).toDomain()
    private fun serverTxn(id: String): InventoryTransaction = WireJson.decodeFromString(TxnDto.serializer(), store.record(WireEntity.TXN, id)!!.payload!!).toDomain()

    /** What the app sends when someone takes a workflow action (applied locally first, then pushed). */
    private fun actionOp(r: ProductionReport, action: ReportAction, actor: Actor, reason: String? = null, base: Long? = store.record(WireEntity.REPORT, r.id)?.version): PushOperation {
        val t = ReportWorkflow.apply(r, action, actor, reason, TestData.NOW).valueOrThrow()
        val dto = ReportActionDto(r.id, action.name, reason, actor.userId, TestData.NOW.toEpochMilli(), t.record.id, if (t.snapshotRequired) t.report.toDto(base ?: 0) else null)
        return operation(WireEntity.REPORT_ACTION, r.id, "ACTION", json(ReportActionDto.serializer(), dto), base)
    }

    /** A report created by the operator and submitted, as the app would push it. */
    private fun submittedReport(id: String = "r1", createdBy: Actor = op): ProductionReport {
        val r = TestData.report(id = id, createdBy = createdBy.userId)
        assertEquals(PushStatus.APPLIED, one(who(createdBy), operation(WireEntity.REPORT, r.id, "UPSERT", reportJson(r))).status)
        assertEquals(PushStatus.APPLIED, one(who(createdBy), actionOp(r, ReportAction.SUBMIT, createdBy)).status)
        return serverReport(id)
    }

    private fun txnOp(t: InventoryTransaction, base: Long? = null) = operation(WireEntity.TXN, t.id, "UPSERT", json(TxnDto.serializer(), t.toDto()), base, key = t.idempotencyKey)

    /** A device whose local ledger is out of date and believes there is plenty of resin. */
    private val optimisticLedger by lazy { listOf(opening("100000").copy(id = "device-only", idempotencyKey = "device-only")) }

    private fun movement(actor: Actor, type: TxnType, qty: String, item: String = TestData.RESIN.id, kind: ItemKind = ItemKind.RAW_MATERIAL,
                         unit: UnitOfMeasure = UnitOfMeasure.KILOGRAM, sign: Int = 0, note: String? = null, override: Boolean = false,
                         reportId: String? = null, entryId: String? = null, deviceLedger: List<InventoryTransaction> = optimisticLedger): InventoryTransaction =
        // Prepared as the device would against its own (possibly stale) ledger; the server's checks then decide.
        MovementService.prepare(
            MovementRequest(kind, item, type, BigDecimal(qty), unit, TestData.TODAY, sign, note = note, overrideNegative = override, reportId = reportId, entryId = entryId),
            actor, m, deviceLedger, { id -> runCatching { serverReport(id) }.getOrNull() }, TestData.TODAY, TestData.NOW, InventorySettings(allowNegativeWithOverride = true),
        ).let { o -> (o as? rw.plasmaco.ops.domain.common.Outcome.Ok)?.value ?: error("device refused: $o") }

    private fun opening(qty: String) = InventoryTransaction(
        "open-resin", ItemKind.RAW_MATERIAL, TestData.RESIN.id, TxnType.OPENING_BALANCE, BigDecimal(qty), UnitOfMeasure.KILOGRAM, TestData.TODAY.minusDays(10),
        createdBy = admin.userId, createdAt = TestData.NOW, approvedBy = admin.userId, approvedAt = TestData.NOW, idempotencyKey = "opening:RAW_MATERIAL:${TestData.RESIN.id}",
    )

    // ---------------------------------------------------------------------------------------------- tests

    @Test
    fun `only administrators change master data`() {
        val r = one(who(op), operation(WireEntity.PRODUCT, dn110.id, "UPSERT", json(ProductDto.serializer(), dn110.toDto())))
        assertEquals(PushStatus.REJECTED, r.status)
        seedMaster()
        assertNotNull(store.record(WireEntity.PRODUCT, dn110.id))
    }

    @Test
    fun `offline edits from one device chain, an edit from another device conflicts`() {
        seedMaster()
        val r = TestData.report()
        val a = who(op, "tablet-A")
        assertEquals(PushStatus.APPLIED, one(a, operation(WireEntity.REPORT, r.id, "UPSERT", reportJson(r))).status)
        // The device saved again before hearing back: its op still carries no base version.
        val edited = r.copy(remarks = "Second save offline")
        val second = one(a, operation(WireEntity.REPORT, r.id, "UPSERT", reportJson(edited)))
        assertEquals(PushStatus.APPLIED, second.status)
        assertEquals(2L, second.version)
        // A supervisor on another device edits from version 1: the server copy changed since, so it is a conflict.
        val other = one(who(sup, "tablet-B"), operation(WireEntity.REPORT, r.id, "UPSERT", reportJson(r.copy(remarks = "Supervisor note")), base = 1))
        assertEquals(PushStatus.CONFLICT, other.status)
        assertEquals("Second save offline", serverReport(r.id).remarks)
        assertNotNull(other.serverCopy)
    }

    @Test
    fun `a device cannot change status or authorship through an edit`() {
        seedMaster()
        val r = TestData.report()
        one(who(op), operation(WireEntity.REPORT, r.id, "UPSERT", reportJson(r)))
        one(who(op), operation(WireEntity.REPORT, r.id, "UPSERT", reportJson(r.copy(status = ReportStatus.APPROVED, createdBy = "someone-else", remarks = "x")), base = 1))
        val s = serverReport(r.id)
        assertEquals(ReportStatus.DRAFT, s.status)
        assertEquals(op.userId, s.createdBy)
        assertEquals("x", s.remarks)
    }

    @Test
    fun `submission is re-checked and locks the report`() {
        seedMaster()
        val s = submittedReport()
        assertEquals(ReportStatus.SUBMITTED, s.status)
        assertEquals(1, s.version)
        assertNotNull(store.record(WireEntity.REPORT_VERSION, "r1:1"))
        assertEquals(1, store.records(WireEntity.APPROVAL).size)
        val late = one(who(op), operation(WireEntity.REPORT, "r1", "UPSERT", reportJson(TestData.report().copy(remarks = "after submit")), base = store.record(WireEntity.REPORT, "r1")!!.version))
        assertEquals(PushStatus.CONFLICT, late.status)
    }

    @Test
    fun `an incomplete report is refused at submission by the server too`() {
        seedMaster()
        val r = TestData.report(entries = emptyList())
        one(who(op), operation(WireEntity.REPORT, r.id, "UPSERT", reportJson(r)))
        // A device that skipped validation still cannot get it to a supervisor.
        val t = r.copy(status = ReportStatus.SUBMITTED, version = 1)
        val dto = ReportActionDto(r.id, "SUBMIT", null, op.userId, TestData.NOW.toEpochMilli(), "appr-x", t.toDto(1))
        val res = one(who(op), operation(WireEntity.REPORT_ACTION, r.id, "ACTION", json(ReportActionDto.serializer(), dto), base = 1))
        assertEquals(PushStatus.REJECTED, res.status)
        assertEquals(ReportStatus.DRAFT, serverReport(r.id).status)
    }

    @Test
    fun `approval needs another person, and only one decision wins`() {
        seedMaster()
        val s = submittedReport()
        // The operator cannot approve (role), the supervisor can.
        val byOperator = one(who(op), operation(WireEntity.REPORT_ACTION, s.id, "ACTION",
            json(ReportActionDto.serializer(), ReportActionDto(s.id, "APPROVE", null, op.userId, 0, "a1", null)), base = store.record(WireEntity.REPORT, s.id)!!.version))
        assertEquals(PushStatus.REJECTED, byOperator.status)
        assertEquals(PushStatus.APPLIED, one(who(sup), actionOp(s, ReportAction.APPROVE, sup)).status)
        val approved = serverReport(s.id)
        assertEquals(ReportStatus.APPROVED, approved.status)
        assertEquals(sup.userId, approved.decidedBy)
        // A second supervisor acting on the old copy: the report is no longer submitted.
        val second = Actor("u-sup2", "Second Supervisor", setOf(Role.SUPERVISOR))
        val late = one(who(second), actionOp(s, ReportAction.REJECT, second, "Wrong product", base = 3))
        assertEquals(PushStatus.CONFLICT, late.status)
    }

    @Test
    fun `a supervisor cannot approve their own report`() {
        seedMaster()
        val s = submittedReport(id = "r-own", createdBy = sup)
        val dto = ReportActionDto(s.id, "APPROVE", null, sup.userId, 0, "a-own", null)
        val res = one(who(sup), operation(WireEntity.REPORT_ACTION, s.id, "ACTION", json(ReportActionDto.serializer(), dto), base = store.record(WireEntity.REPORT, s.id)!!.version))
        assertEquals(PushStatus.REJECTED, res.status)
        assertTrue(res.message!!.contains("cannot decide on a report you created"), res.message)
    }

    @Test
    fun `a retried operation is applied once`() {
        seedMaster()
        val r = TestData.report()
        val o = operation(WireEntity.REPORT, r.id, "UPSERT", reportJson(r))
        assertEquals(PushStatus.APPLIED, one(who(op), o).status)
        val again = one(who(op), o)
        assertEquals(PushStatus.DUPLICATE, again.status)
        assertEquals(1L, store.record(WireEntity.REPORT, r.id)!!.version)
    }

    @Test
    fun `negative stock is refused unless the server allows the audited override`() {
        seedMaster()
        assertEquals(PushStatus.APPLIED, one(who(admin), txnOp(opening("100"))).status)
        val issue = movement(store1, TxnType.ISSUE_TO_PRODUCTION, "150")
        val refused = one(who(store1), txnOp(issue))
        assertEquals(PushStatus.REJECTED, refused.status)
        assertTrue(refused.message!!.contains("Negative stock is blocked"), refused.message)

        // Same request with the exception enabled on the server, by a role allowed to override, with a reason.
        sync = SyncService(store, time, SyncService.Rules(allowNegativeOverride = true))
        val withOverride = movement(admin, TxnType.ISSUE_TO_PRODUCTION, "150", note = "Physical count confirmed; receipt GRN-12 not yet entered", override = true,
            deviceLedger = listOf(opening("100")))
        assertEquals(PushStatus.APPLIED, one(who(admin), txnOp(withOverride)).status)
        assertTrue(serverTxn(withOverride.id).negativeOverride)
    }

    @Test
    fun `production receipts are limited to approved good output`() {
        seedMaster()
        val s = submittedReport()
        // Not approved yet: refused by the server whatever the device thought.
        val early = InventoryTransaction(
            "early", ItemKind.FINISHED_GOOD, dn110.id, TxnType.PRODUCTION_RECEIPT, BigDecimal("100.000"), UnitOfMeasure.METRE, TestData.TODAY,
            enteredQuantity = BigDecimal("100"), enteredUnit = UnitOfMeasure.METRE, reportId = s.id, entryId = "e1",
            createdBy = store1.userId, createdAt = TestData.NOW, idempotencyKey = "early",
        )
        val earlyResult = one(who(store1), txnOp(early))
        assertEquals(PushStatus.REJECTED, earlyResult.status)
        assertTrue(earlyResult.message!!.contains("not approved"), earlyResult.message)
        one(who(sup), actionOp(s, ReportAction.APPROVE, sup))
        val first = movement(store1, TxnType.PRODUCTION_RECEIPT, "300", dn110.id, ItemKind.FINISHED_GOOD, UnitOfMeasure.METRE, reportId = s.id, entryId = "e1")
        assertEquals(PushStatus.APPLIED, one(who(store1), txnOp(first)).status)
        val tooMuch = movement(store1, TxnType.PRODUCTION_RECEIPT, "150", dn110.id, ItemKind.FINISHED_GOOD, UnitOfMeasure.METRE, reportId = s.id, entryId = "e1")
        val res = one(who(store1), txnOp(tooMuch))
        assertEquals(PushStatus.REJECTED, res.status)
        assertTrue(res.message!!.contains("remain to receive"), res.message)
    }

    @Test
    fun `adjustments wait for someone else's approval`() {
        seedMaster()
        one(who(admin), txnOp(opening("100")))
        val adj = movement(store1, TxnType.ADJUSTMENT, "10", sign = -1, note = "Spillage at mixer")
        assertEquals(TxnStatus.PENDING_APPROVAL, adj.status)
        assertEquals(PushStatus.APPLIED, one(who(store1), txnOp(adj)).status)
        // The requester cannot approve their own adjustment, even by editing the record.
        val selfApproved = adj.copy(status = TxnStatus.POSTED, approvedBy = store1.userId, approvedAt = TestData.NOW)
        assertEquals(PushStatus.REJECTED, one(who(store1), operation(WireEntity.TXN, adj.id, "UPSERT", json(TxnDto.serializer(), selfApproved.toDto()), base = 1)).status)
        assertEquals(TxnStatus.PENDING_APPROVAL, serverTxn(adj.id).status)
        val decided = MovementService.decide(serverTxn(adj.id), true, sup, listOf(serverTxn("open-resin")), TestData.NOW).valueOrThrow()
        val res = one(who(sup), operation(WireEntity.TXN, adj.id, "UPSERT", json(TxnDto.serializer(), decided.toDto()), base = 1))
        assertEquals(PushStatus.APPLIED, res.status, res.message)
        assertEquals(TxnStatus.POSTED, serverTxn(adj.id).status)
        assertEquals(sup.userId, serverTxn(adj.id).approvedBy)
    }

    @Test
    fun `the same workbook imported on two devices is stored once`() {
        seedMaster()
        fun legacy(id: String) = InventoryTransaction(
            id, ItemKind.RAW_MATERIAL, TestData.RESIN.id, TxnType.GOODS_RECEIPT, BigDecimal("500"), UnitOfMeasure.KILOGRAM, TestData.TODAY.minusDays(200),
            historical = true, origin = TxnOrigin.LEGACY_IMPORT, createdBy = admin.userId, createdAt = TestData.NOW, idempotencyKey = "legacy:rm:2026-03-02",
        )
        assertEquals(PushStatus.APPLIED, one(who(admin, "pc-1"), txnOp(legacy("from-pc-1"))).status)
        assertEquals(PushStatus.DUPLICATE, one(who(admin, "pc-2"), txnOp(legacy("from-pc-2"))).status)
        assertEquals(1, store.records(WireEntity.TXN).size)
    }

    @Test
    fun `each role pulls only what it may see`() {
        seedMaster()
        submittedReport()
        val other = Actor("u-op2", "Second Operator", setOf(Role.OPERATOR))
        one(who(admin), txnOp(opening("100")))
        fun types(a: Actor) = sync.pull(a, null, 500).changes.groupingBy { it.entityType }.eachCount()
        val mine = types(op)
        assertEquals(1, mine[WireEntity.REPORT])
        assertEquals(1, mine[WireEntity.APPROVAL])
        assertEquals(null, mine[WireEntity.TXN])
        assertEquals(null, mine[WireEntity.REPORT_VERSION])
        val theirs = types(other)
        assertEquals(null, theirs[WireEntity.REPORT])
        assertEquals(null, theirs[WireEntity.APPROVAL])
        assertEquals(1, types(store1)[WireEntity.TXN])
        // Paging: the cursor moves forward and hasMore is reported.
        val page = sync.pull(admin, null, 2)
        assertEquals(2, page.changes.size)
        assertTrue(page.hasMore)
        val rest = sync.pull(admin, page.cursor, 500)
        assertFalse(rest.hasMore)
        assertTrue(rest.changes.none { c -> page.changes.any { it.entityId == c.entityId && it.entityType == c.entityType } })
    }

    @Test
    fun `only an unsubmitted draft can be deleted`() {
        seedMaster()
        val r = TestData.report(id = "d1")
        one(who(op), operation(WireEntity.REPORT, r.id, "UPSERT", reportJson(r)))
        assertEquals(PushStatus.APPLIED, one(who(op), operation(WireEntity.REPORT, r.id, "DELETE", json(ReportDto.serializer(), r.toDto()))).status)
        assertTrue(store.record(WireEntity.REPORT, r.id)!!.deleted)
        val s = submittedReport(id = "s1")
        assertEquals(PushStatus.CONFLICT, one(who(op), operation(WireEntity.REPORT, s.id, "DELETE", json(ReportDto.serializer(), s.toDto()))).status)
    }

    @Test
    fun `malformed payloads are rejected without affecting the rest of the batch`() {
        seedMaster()
        val bad = operation(WireEntity.REPORT, "x", "UPSERT", json(ApprovalDto.serializer(), ApprovalDto("a", "x", "APPROVE", "DRAFT", "APPROVED", "u", "U", null, 0, 0)))
        val good = operation(WireEntity.REPORT, "r1", "UPSERT", reportJson(TestData.report()))
        val results = push(who(op), bad, good)
        assertEquals(listOf(PushStatus.REJECTED, PushStatus.APPLIED), results.map { it.status })
    }
}
