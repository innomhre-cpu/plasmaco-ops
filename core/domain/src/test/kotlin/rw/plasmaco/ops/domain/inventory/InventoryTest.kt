package rw.plasmaco.ops.domain.inventory

import org.junit.Test
import rw.plasmaco.ops.domain.TestData
import rw.plasmaco.ops.domain.common.DateRange
import rw.plasmaco.ops.domain.common.Outcome
import rw.plasmaco.ops.domain.common.valueOrThrow
import rw.plasmaco.ops.domain.master.ItemKind
import rw.plasmaco.ops.domain.production.ReportStatus
import rw.plasmaco.ops.domain.units.UnitOfMeasure
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InventoryTest {
    private val master = TestData.masterFromCatalogue()
    private val pipe = TestData.product("PE100-DN110-PN10")
    private val today = TestData.TODAY
    private val approvedReport = TestData.report(status = ReportStatus.APPROVED)

    private fun post(existing: List<InventoryTransaction>, req: MovementRequest, actor: rw.plasmaco.ops.domain.security.Actor = TestData.storekeeper, settings: InventorySettings = InventorySettings()) =
        MovementService.prepare(req, actor, master, existing, { id -> approvedReport.takeIf { it.id == id } }, today, TestData.NOW, settings)

    private fun req(type: TxnType, qty: String, date: LocalDate = today, kind: ItemKind = ItemKind.FINISHED_GOOD, item: String = pipe.id, unit: UnitOfMeasure = if (kind == ItemKind.FINISHED_GOOD) UnitOfMeasure.METRE else UnitOfMeasure.KILOGRAM, sign: Int = 0) =
        MovementRequest(kind, item, type, BigDecimal(qty), unit, date, adjustmentSign = sign, reportId = approvedReport.id, entryId = approvedReport.entries.first().id)

    private fun opening(qty: String, date: LocalDate = today.minusDays(10), kind: ItemKind = ItemKind.FINISHED_GOOD, item: String = pipe.id) =
        post(emptyList(), req(TxnType.OPENING_BALANCE, qty, date, kind, item), TestData.admin).valueOrThrow()

    @Test
    fun `closing equals opening plus receipts minus issues plus adjustments`() {
        val txns = mutableListOf(opening("1000"))
        txns += post(txns, req(TxnType.PRODUCTION_RECEIPT, "400", today.minusDays(2))).valueOrThrow()
        txns += post(txns, req(TxnType.DISPATCH, "650", today.minusDays(1))).valueOrThrow()
        txns += post(txns, req(TxnType.CUSTOMER_RETURN, "20", today)).valueOrThrow()
        val ledger = Ledger(txns)
        assertEquals(0, BigDecimal(770).compareTo(ledger.balance(ItemKind.FINISHED_GOOD, pipe.id)))
        val st = ledger.statement(ItemKind.FINISHED_GOOD, pipe.id, DateRange(today.minusDays(2), today))
        assertEquals(0, BigDecimal(1000).compareTo(st.opening))
        assertEquals(0, BigDecimal(400).compareTo(st.receipts))
        assertEquals(0, BigDecimal(650).compareTo(st.issues))
        assertTrue(st.reconciles)
        assertEquals(listOf("1400.000", "750.000", "770.000"), st.lines.map { it.runningBalance.toPlainString() })
    }

    @Test
    fun `negative stock is blocked, including by a back-dated dispatch`() {
        val txns = mutableListOf(opening("100"))
        txns += post(txns, req(TxnType.DISPATCH, "80", today)).valueOrThrow()
        // 50 m dated yesterday fits yesterday (100) but leaves today at -30.
        val back = post(txns, req(TxnType.DISPATCH, "50", today.minusDays(1)))
        assertEquals("stock.negative", (back as Outcome.Refused).issues.first().code)
    }

    @Test
    fun `a manager can override negative stock only with a note, and only when the exception is enabled`() {
        val txns = listOf(opening("10"))
        val r = req(TxnType.DISPATCH, "15").copy(overrideNegative = true)
        val noted = r.copy(note = "Physical stock confirmed; receipt delayed")
        // Default settings: blocked for everyone, even an administrator with a note.
        assertEquals("stock.negative", (post(txns, noted, TestData.admin) as Outcome.Refused).issues.first().code)
        val enabled = InventorySettings(allowNegativeWithOverride = true)
        assertTrue(post(txns, r, TestData.storekeeper, enabled) is Outcome.Refused)
        assertTrue(post(txns, r, TestData.manager.copy(roles = TestData.manager.roles + rw.plasmaco.ops.domain.security.Role.STOREKEEPER), enabled) is Outcome.Refused)
        val ok = post(txns, noted, TestData.admin, enabled)
        assertTrue((ok as Outcome.Ok).value.negativeOverride)
    }

    @Test
    fun `production receipts need an approved report and cannot exceed its good output`() {
        val first = post(emptyList(), req(TxnType.PRODUCTION_RECEIPT, "300")).valueOrThrow()
        val second = post(listOf(first), req(TxnType.PRODUCTION_RECEIPT, "150"))
        assertEquals("receipt.exceeds_output", (second as Outcome.Refused).issues.first().code)
        val draftReport = TestData.report(id = "draft", status = ReportStatus.DRAFT)
        val notApproved = MovementService.prepare(req(TxnType.PRODUCTION_RECEIPT, "10").copy(reportId = draftReport.id, entryId = draftReport.entries.first().id),
            TestData.storekeeper, master, emptyList(), { draftReport }, today, TestData.NOW)
        assertEquals("receipt.not_approved", (notApproved as Outcome.Refused).issues.first().code)
    }

    @Test
    fun `replaying the same request does not post twice`() {
        val r = req(TxnType.PRODUCTION_RECEIPT, "100")
        val a = post(emptyList(), r).valueOrThrow()
        val b = post(listOf(a), r).valueOrThrow()
        assertEquals(a.id, b.id)
    }

    @Test
    fun `bags convert to kilograms through the material's bag weight, and incompatible units are refused`() {
        val resin = TestData.RESIN
        val r = req(TxnType.GOODS_RECEIPT, "990", kind = ItemKind.RAW_MATERIAL, item = resin.id, unit = UnitOfMeasure.BAG)
        val t = post(emptyList(), r).valueOrThrow()
        assertEquals(0, BigDecimal(24_750).compareTo(t.quantity))
        assertEquals(UnitOfMeasure.KILOGRAM, t.unit)
        assertEquals(0, BigDecimal(990).compareTo(t.enteredQuantity))
        val wrong = post(emptyList(), req(TxnType.GOODS_RECEIPT, "5", kind = ItemKind.RAW_MATERIAL, item = resin.id, unit = UnitOfMeasure.METRE))
        assertEquals("unit.incompatible", (wrong as Outcome.Refused).issues.first().code)
    }

    @Test
    fun `adjustments wait for approval by someone else`() {
        val txns = listOf(opening("500"))
        val pending = post(txns, req(TxnType.ADJUSTMENT, "25", sign = -1)).valueOrThrow()
        assertEquals(TxnStatus.PENDING_APPROVAL, pending.status)
        assertEquals(0, BigDecimal(500).compareTo(Ledger(txns + pending).balance(ItemKind.FINISHED_GOOD, pipe.id)))
        val approved = MovementService.decide(pending, true, TestData.supervisor, txns, TestData.NOW).valueOrThrow()
        assertEquals(0, BigDecimal(475).compareTo(Ledger(txns + approved).balance(ItemKind.FINISHED_GOOD, pipe.id)))
        val selfApproval = MovementService.decide(pending.copy(createdBy = TestData.supervisor.userId), true, TestData.supervisor, txns, TestData.NOW)
        assertEquals("txn.self_approval", (selfApproval as Outcome.Refused).issues.first().code)
    }

    @Test
    fun `wrong item kind and missing permission are refused`() {
        assertTrue(post(emptyList(), req(TxnType.GOODS_RECEIPT, "10")) is Outcome.Refused)
        assertEquals("txn.forbidden", (post(emptyList(), req(TxnType.DISPATCH, "1"), TestData.operator) as Outcome.Refused).issues.first().code)
    }

    @Test
    fun `nothing live may be dated before the migration cut-off`() {
        val r = post(listOf(opening("100")), req(TxnType.DISPATCH, "1", today.minusDays(5)), settings = InventorySettings(cutoffDate = today.minusDays(3)))
        assertEquals("date.before_cutoff", (r as Outcome.Refused).issues.first().code)
    }

    @Test
    fun `stock count approval posts only the variances`() {
        val txns = listOf(opening("100"))
        val count = StockCounts.start("c1", today, ItemKind.FINISHED_GOOD, master, Ledger(txns), TestData.storekeeper.userId, TestData.NOW)
        val counted = count.copy(lines = count.lines.map { if (it.itemId == pipe.id) it.copy(countedQty = BigDecimal(96)) else it.copy(countedQty = it.systemQty) })
        val variances = StockCounts.varianceTransactions(counted, TestData.supervisor, TestData.NOW)
        assertEquals(1, variances.size)
        assertEquals(0, BigDecimal(-4).compareTo(variances.single().quantity))
        assertEquals(0, BigDecimal(96).compareTo(Ledger(txns + variances).balance(ItemKind.FINISHED_GOOD, pipe.id)))
    }

    @Test
    fun `stock status follows the legacy minimum rule`() {
        assertEquals(StockStatus.OUT_OF_STOCK, StockStatusRules.classify(BigDecimal.ZERO, BigDecimal(5000), null))
        assertEquals(StockStatus.LOW_STOCK, StockStatusRules.classify(BigDecimal(1000), BigDecimal(5000), null))
        assertEquals(StockStatus.REORDER_REQUIRED, StockStatusRules.classify(BigDecimal(6000), BigDecimal(5000), BigDecimal(8000)))
        assertEquals(StockStatus.IN_STOCK, StockStatusRules.classify(BigDecimal(9000), BigDecimal(5000), BigDecimal(8000)))
        assertEquals(StockStatus.IN_STOCK, StockStatusRules.classify(BigDecimal(1), null, null))
    }

    @Test
    fun `historical imports never change live balances`() {
        val hist = opening("1000").copy(historical = true, origin = TxnOrigin.LEGACY_IMPORT)
        assertEquals(0, BigDecimal.ZERO.compareTo(Ledger(listOf(hist)).balance(ItemKind.FINISHED_GOOD, pipe.id)))
        assertEquals(0, BigDecimal(1000).compareTo(Ledger(listOf(hist), includeHistorical = true).balance(ItemKind.FINISHED_GOOD, pipe.id)))
    }
}
