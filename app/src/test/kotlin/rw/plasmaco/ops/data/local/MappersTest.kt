package rw.plasmaco.ops.data.local

import rw.plasmaco.ops.domain.TestData
import rw.plasmaco.ops.domain.inventory.InventoryTransaction
import rw.plasmaco.ops.domain.inventory.TxnOrigin
import rw.plasmaco.ops.domain.inventory.TxnStatus
import rw.plasmaco.ops.domain.inventory.TxnType
import rw.plasmaco.ops.domain.master.ItemKind
import rw.plasmaco.ops.domain.production.DowntimeEvent
import rw.plasmaco.ops.domain.production.InspectionResult
import rw.plasmaco.ops.domain.production.QualityInspection
import rw.plasmaco.ops.domain.production.ReportStatus
import rw.plasmaco.ops.domain.production.ShiftHandover
import rw.plasmaco.ops.domain.units.UnitOfMeasure
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

/** Everything a report or a stock movement holds survives the trip to the local database rows and back. */
class MappersTest {
    @Test
    fun `a full production report maps to rows and back unchanged`() {
        val day = TestData.TODAY.minusDays(1)
        val report = TestData.report(
            status = ReportStatus.SUBMITTED,
            downtime = listOf(DowntimeEvent("d1", TestData.MACHINE.id, day.atTime(9, 10), day.atTime(9, 55), TestData.BREAKDOWN.id, "Heater band failed", "Replaced band")),
        ).copy(
            version = 2,
            supervisorId = "u-sup",
            submittedAt = TestData.NOW,
            remarks = "Line restarted after heater repair",
            inspections = listOf(
                QualityInspection("q1", TestData.product("PE100-DN110-PN10").id, "e1", "B-17", day.atTime(10, 0), BigDecimal("6.0"), BigDecimal("5.50"), BigDecimal("0.50"),
                    defectCategoryId = "def-test", result = InspectionResult.CONDITIONAL, correctiveAction = "Adjust haul-off", inspectorId = "u-sup"),
            ),
            handover = ShiftHandover(machineCondition = "Extruder warm", instructions = "Check ovality at start"),
        )
        assertEquals(report, report.toEntity(version = 7, syncState = "PENDING").toDomain())
    }

    @Test
    fun `an empty handover is stored as no handover`() {
        val report = TestData.report().copy(handover = ShiftHandover())
        assertEquals(null, report.toEntity(0, "PENDING").toDomain().handover)
    }

    @Test
    fun `a stock movement maps to a row and back unchanged`() {
        val txn = InventoryTransaction(
            "t1", ItemKind.RAW_MATERIAL, TestData.RESIN.id, TxnType.OPENING_BALANCE, BigDecimal("1250.500"), UnitOfMeasure.KILOGRAM, TestData.TODAY,
            enteredQuantity = BigDecimal("50.02"), enteredUnit = UnitOfMeasure.BAG, reference = "GRN-1", counterparty = "Supplier (test)", location = "Store A",
            note = "Opening count", status = TxnStatus.PENDING_APPROVAL, origin = TxnOrigin.LEGACY_IMPORT, sourceRef = "workbook:Oct:12",
            negativeOverride = false, createdBy = TestData.admin.userId, createdAt = TestData.NOW, approvedBy = null, idempotencyKey = "opening:RAW_MATERIAL:mat-resin",
        )
        assertEquals(txn, txn.toEntity(now = 0).toDomain())
    }
}
