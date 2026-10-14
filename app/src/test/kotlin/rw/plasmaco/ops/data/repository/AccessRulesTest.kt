package rw.plasmaco.ops.data.repository

import rw.plasmaco.ops.domain.TestData
import rw.plasmaco.ops.domain.analytics.DataSet
import rw.plasmaco.ops.domain.inventory.InventoryTransaction
import rw.plasmaco.ops.domain.inventory.TxnType
import rw.plasmaco.ops.domain.master.ItemKind
import rw.plasmaco.ops.domain.reports.ReportType
import rw.plasmaco.ops.domain.units.UnitOfMeasure
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccessRulesTest {
    private fun can(actor: rw.plasmaco.ops.domain.security.Actor, type: ReportType, reportId: String? = null) = ExportRepository.canExport(actor, type, reportId)

    @Test
    fun `operators can export only a single production report they may view`() {
        assertTrue(can(TestData.operator, ReportType.DAILY_PRODUCTION, reportId = "r1"))
        assertFalse(can(TestData.operator, ReportType.DAILY_PRODUCTION))
        assertFalse(can(TestData.operator, ReportType.FINISHED_GOODS_STOCK))
    }

    @Test
    fun `stock reports need both stock visibility and the export permission`() {
        // Current role matrix: storekeepers see stock but have no export permission (listed for confirmation in docs).
        assertFalse(can(TestData.storekeeper, ReportType.RAW_MATERIAL_STOCK))
        assertTrue(can(TestData.supervisor, ReportType.RAW_MATERIAL_STOCK))
        assertTrue(can(TestData.manager, ReportType.INVENTORY_MOVEMENT))
        assertTrue(ReportType.entries.all { can(TestData.admin, it) })
    }

    @Test
    fun `an operator's analytics contain only their own reports and no stock ledger`() {
        val own = TestData.report(id = "own")
        val other = TestData.report(id = "other", createdBy = "u-someone-else")
        val txn = InventoryTransaction(
            "t1", ItemKind.RAW_MATERIAL, TestData.RESIN.id, TxnType.OPENING_BALANCE, BigDecimal(100), UnitOfMeasure.KILOGRAM, TestData.TODAY,
            createdBy = TestData.admin.userId, createdAt = TestData.NOW, idempotencyKey = "t1",
        )
        val all = DataSet(TestData.masterFromCatalogue(), listOf(own, other), listOf(txn))
        val forOperator = all.visibleTo(TestData.operator)
        assertEquals(listOf("own"), forOperator.reports.map { it.id })
        assertEquals(emptyList(), forOperator.transactions)
        val forSupervisor = all.visibleTo(TestData.supervisor)
        assertEquals(2, forSupervisor.reports.size)
        assertEquals(1, forSupervisor.transactions.size)
    }
}
