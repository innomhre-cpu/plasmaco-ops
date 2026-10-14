package rw.plasmaco.ops.domain.inventory

import rw.plasmaco.ops.domain.common.Id
import rw.plasmaco.ops.domain.master.ItemKind
import rw.plasmaco.ops.domain.security.Permission
import rw.plasmaco.ops.domain.units.UnitOfMeasure
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** How a movement affects stock. [direction] +1 adds, −1 removes, 0 = sign carried by the quantity (adjustments). */
enum class TxnType(
    val label: String,
    val direction: Int,
    val kinds: Set<ItemKind>,
    val permission: Permission,
    val requiresApproval: Boolean,
    val group: MovementGroup,
) {
    OPENING_BALANCE("Opening balance", +1, ItemKind.entries.toSet(), Permission.OPENING_BALANCE_MIGRATE, false, MovementGroup.OPENING),
    PRODUCTION_RECEIPT("Production receipt", +1, setOf(ItemKind.FINISHED_GOOD), Permission.INVENTORY_RECEIVE, false, MovementGroup.RECEIPT),
    GOODS_RECEIPT("Goods received", +1, setOf(ItemKind.RAW_MATERIAL), Permission.INVENTORY_RECEIVE, false, MovementGroup.RECEIPT),
    CUSTOMER_RETURN("Customer return", +1, setOf(ItemKind.FINISHED_GOOD), Permission.INVENTORY_RECEIVE, false, MovementGroup.RETURN),
    RETURN_FROM_PRODUCTION("Return from production", +1, setOf(ItemKind.RAW_MATERIAL), Permission.INVENTORY_ISSUE, false, MovementGroup.RETURN),
    DISPATCH("Dispatch", -1, setOf(ItemKind.FINISHED_GOOD), Permission.INVENTORY_DISPATCH, false, MovementGroup.ISSUE),
    ISSUE_TO_PRODUCTION("Issue to production", -1, setOf(ItemKind.RAW_MATERIAL), Permission.INVENTORY_ISSUE, false, MovementGroup.ISSUE),
    TRANSFER_IN("Transfer in", +1, ItemKind.entries.toSet(), Permission.INVENTORY_TRANSFER, false, MovementGroup.TRANSFER),
    TRANSFER_OUT("Transfer out", -1, ItemKind.entries.toSet(), Permission.INVENTORY_TRANSFER, false, MovementGroup.TRANSFER),
    SCRAP_WRITE_OFF("Scrap write-off", -1, ItemKind.entries.toSet(), Permission.ADJUSTMENT_REQUEST, true, MovementGroup.ADJUSTMENT),
    ADJUSTMENT("Adjustment", 0, ItemKind.entries.toSet(), Permission.ADJUSTMENT_REQUEST, true, MovementGroup.ADJUSTMENT),
    COUNT_VARIANCE("Stock-count variance", 0, ItemKind.entries.toSet(), Permission.STOCK_COUNT, true, MovementGroup.ADJUSTMENT),

    /** Imported history only: explains a jump between a legacy closing and the next legacy opening. */
    LEGACY_RECONCILIATION("Legacy reconciliation", 0, ItemKind.entries.toSet(), Permission.IMPORT_RUN, false, MovementGroup.ADJUSTMENT),
    ;

    fun allows(kind: ItemKind) = kind in kinds
}

/** Column groups used by the stock reports (opening, receipts, issues, returns, transfers, adjustments, closing). */
enum class MovementGroup { OPENING, RECEIPT, ISSUE, RETURN, TRANSFER, ADJUSTMENT }

enum class TxnStatus { POSTED, PENDING_APPROVAL, REJECTED, VOID }

enum class TxnOrigin { APP, LEGACY_IMPORT, SERVER }

data class InventoryTransaction(
    val id: Id,
    val itemKind: ItemKind,
    val itemId: Id,
    val type: TxnType,
    /** Signed quantity in the item's base unit. */
    val quantity: BigDecimal,
    val unit: UnitOfMeasure,
    val date: LocalDate,
    /** What the user typed (e.g. 40 bags) before conversion to the base unit. */
    val enteredQuantity: BigDecimal? = null,
    val enteredUnit: UnitOfMeasure? = null,
    val reference: String? = null,
    val counterparty: String? = null,
    val reportId: Id? = null,
    val entryId: Id? = null,
    val location: String? = null,
    val note: String? = null,
    val status: TxnStatus = TxnStatus.POSTED,
    /** True for imported workbook history: shown in history and analytics, excluded from live balances. */
    val historical: Boolean = false,
    val origin: TxnOrigin = TxnOrigin.APP,
    val sourceRef: String? = null,
    val negativeOverride: Boolean = false,
    val createdBy: Id,
    val createdAt: Instant,
    val approvedBy: Id? = null,
    val approvedAt: Instant? = null,
    /** Client-generated key so a retried sync or double tap never posts twice. */
    val idempotencyKey: String = id,
) {
    val affectsBalance: Boolean get() = status == TxnStatus.POSTED
}

enum class StockStatus(val label: String, val severity: Int) {
    IN_STOCK("In stock", 0),
    REORDER_REQUIRED("Reorder required", 1),
    LOW_STOCK("Low stock", 2),
    OUT_OF_STOCK("Out of stock", 3),
}

object StockStatusRules {
    /**
     * Legacy rule: red alert when closing ≤ "Min Qty Required". Here: Out of stock at ≤ 0, Low stock at or below the
     * minimum, Reorder required at or below the reorder point. Thresholds are optional per item.
     */
    fun classify(balance: BigDecimal, minimum: BigDecimal?, reorderPoint: BigDecimal?): StockStatus = when {
        balance.signum() <= 0 -> StockStatus.OUT_OF_STOCK
        minimum != null && minimum.signum() > 0 && balance <= minimum -> StockStatus.LOW_STOCK
        reorderPoint != null && reorderPoint.signum() > 0 && balance <= reorderPoint -> StockStatus.REORDER_REQUIRED
        else -> StockStatus.IN_STOCK
    }
}
