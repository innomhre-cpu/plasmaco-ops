package rw.plasmaco.ops.domain.inventory

import rw.plasmaco.ops.domain.common.Id
import rw.plasmaco.ops.domain.common.Issue
import rw.plasmaco.ops.domain.common.Outcome
import rw.plasmaco.ops.domain.common.Severity
import rw.plasmaco.ops.domain.common.newId
import rw.plasmaco.ops.domain.master.ItemKind
import rw.plasmaco.ops.domain.master.MasterData
import rw.plasmaco.ops.domain.production.ProductionReport
import rw.plasmaco.ops.domain.production.ReportStatus
import rw.plasmaco.ops.domain.security.Actor
import rw.plasmaco.ops.domain.security.Permission
import rw.plasmaco.ops.domain.units.Quantity
import rw.plasmaco.ops.domain.units.UnitConverter
import rw.plasmaco.ops.domain.units.UnitOfMeasure
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** What a storekeeper (or the system) asks to post. Quantity is always entered as a positive magnitude. */
data class MovementRequest(
    val itemKind: ItemKind,
    val itemId: Id,
    val type: TxnType,
    val quantity: BigDecimal,
    val unit: UnitOfMeasure,
    val date: LocalDate,
    /** For ADJUSTMENT and COUNT_VARIANCE only: +1 gain or −1 loss. */
    val adjustmentSign: Int = 0,
    val reference: String? = null,
    val counterparty: String? = null,
    val reportId: Id? = null,
    val entryId: Id? = null,
    val location: String? = null,
    val note: String? = null,
    val overrideNegative: Boolean = false,
    val idempotencyKey: String = newId(),
)

data class InventorySettings(
    /** Live balances start here; nothing live may be dated earlier. Null = no migration cut-off yet. */
    val cutoffDate: LocalDate? = null,
    /**
     * Negative stock is always blocked unless an administrator explicitly enables this exception; even then it needs
     * a person with [Permission.NEGATIVE_STOCK_OVERRIDE] and a note, and the transaction is flagged for audit.
     */
    val allowNegativeWithOverride: Boolean = false,
)

object MovementService {
    fun prepare(
        request: MovementRequest,
        actor: Actor,
        master: MasterData,
        existing: List<InventoryTransaction>,
        reports: (Id) -> ProductionReport?,
        today: LocalDate,
        now: Instant,
        settings: InventorySettings = InventorySettings(),
    ): Outcome<InventoryTransaction> {
        val issues = mutableListOf<Issue>()
        fun err(field: String, code: String, msg: String) = issues.add(Issue(field, code, msg))

        // Idempotency: the same request replayed returns the transaction already posted.
        existing.firstOrNull { it.idempotencyKey == request.idempotencyKey }?.let { return Outcome.Ok(it) }

        if (!request.type.allows(request.itemKind)) err("type", "txn.kind", "${request.type.label} does not apply to ${request.itemKind.name.lowercase().replace('_', ' ')}s")
        if (!actor.can(request.type.permission)) err("type", "txn.forbidden", "Your role cannot record ${request.type.label.lowercase()}")
        val baseUnit = master.itemUnit(request.itemKind, request.itemId)
        if (baseUnit == null) err("itemId", "item.unknown", "Choose an item")
        if (request.quantity.signum() <= 0) err("quantity", "qty.not_positive", "Quantity must be greater than zero")
        if (request.date.isAfter(today)) err("date", "date.future", "Movements cannot be dated in the future")
        settings.cutoffDate?.let { cutoff ->
            if (request.type != TxnType.OPENING_BALANCE && request.date.isBefore(cutoff)) err("date", "date.before_cutoff", "Live stock starts on $cutoff; earlier movements belong to imported history")
        }
        val signed = when (request.type.direction) {
            0 -> if (request.adjustmentSign == 1 || request.adjustmentSign == -1) request.adjustmentSign else {
                err("adjustmentSign", "adjustment.sign", "Choose whether this is a gain or a loss"); 1
            }
            else -> request.type.direction
        }
        if (issues.isNotEmpty() || baseUnit == null) return Outcome.Refused(issues)

        val converted = UnitConverter.convert(Quantity(request.quantity, request.unit), baseUnit, master.itemConversions(request.itemKind, request.itemId))
            ?: return Outcome.Refused("unit", "unit.incompatible", "${request.unit.label} cannot be converted to ${baseUnit.label} for this item")
        val qty = converted.value.setScale(3, java.math.RoundingMode.HALF_UP) * BigDecimal(signed)

        // Production receipts must come from an approved report and never exceed the entry's good output.
        if (request.type == TxnType.PRODUCTION_RECEIPT) {
            val report = request.reportId?.let(reports)
            val entry = report?.entries?.firstOrNull { it.id == request.entryId }
            when {
                report == null || entry == null -> err("reportId", "receipt.no_report", "Choose the approved production report and entry being received")
                report.status != ReportStatus.APPROVED -> err("reportId", "receipt.not_approved", "Report ${report.reference} is not approved yet")
                entry.productId != request.itemId -> err("itemId", "receipt.wrong_product", "The entry is for a different product")
                else -> {
                    val already = Ledger(existing).receivedForEntry(entry.id)
                    val remaining = entry.good - already
                    if (qty > remaining) err("quantity", "receipt.exceeds_output", "Only ${remaining.stripTrailingZeros().toPlainString()} m of good output remain to receive for this entry")
                }
            }
        }
        if (issues.isNotEmpty()) return Outcome.Refused(issues)

        val status = if (request.type.requiresApproval && !actor.can(Permission.ADJUSTMENT_APPROVE)) TxnStatus.PENDING_APPROVAL else TxnStatus.POSTED
        var negativeOverride = false
        val warnings = mutableListOf<Issue>()
        if (qty.signum() < 0 && status == TxnStatus.POSTED && request.type != TxnType.LEGACY_RECONCILIATION) {
            val lowest = Ledger(existing).lowestBalanceFrom(request.itemKind, request.itemId, request.date, qty)
            if (lowest.signum() < 0) {
                val canOverride = settings.allowNegativeWithOverride && request.overrideNegative &&
                    actor.can(Permission.NEGATIVE_STOCK_OVERRIDE) && !request.note.isNullOrBlank()
                if (!canOverride) {
                    return Outcome.Refused(
                        "quantity", "stock.negative",
                        "This would take stock to ${lowest.stripTrailingZeros().toPlainString()} ${baseUnit.code}. Negative stock is blocked; record the missing receipt first or ask a manager for an override",
                    )
                }
                negativeOverride = true
                warnings += Issue("quantity", "stock.negative_override", "Stock goes negative under your override; it is flagged in the audit log", Severity.WARNING)
            }
        }

        val txn = InventoryTransaction(
            id = newId(),
            itemKind = request.itemKind,
            itemId = request.itemId,
            type = request.type,
            quantity = qty,
            unit = baseUnit,
            date = request.date,
            enteredQuantity = request.quantity,
            enteredUnit = request.unit,
            reference = request.reference?.trim()?.ifEmpty { null },
            counterparty = request.counterparty?.trim()?.ifEmpty { null },
            reportId = request.reportId,
            entryId = request.entryId,
            location = request.location,
            note = request.note?.trim()?.ifEmpty { null },
            status = status,
            negativeOverride = negativeOverride,
            createdBy = actor.userId,
            createdAt = now,
            approvedBy = if (status == TxnStatus.POSTED && request.type.requiresApproval) actor.userId else null,
            approvedAt = if (status == TxnStatus.POSTED && request.type.requiresApproval) now else null,
            idempotencyKey = request.idempotencyKey,
        )
        return Outcome.Ok(txn, warnings)
    }

    /** Approves or rejects a pending adjustment. Approval re-checks negative stock at approval time. */
    fun decide(
        txn: InventoryTransaction,
        approve: Boolean,
        actor: Actor,
        existing: List<InventoryTransaction>,
        now: Instant,
        note: String? = null,
        settings: InventorySettings = InventorySettings(),
    ): Outcome<InventoryTransaction> {
        if (txn.status != TxnStatus.PENDING_APPROVAL) return Outcome.Refused("status", "txn.not_pending", "This movement is not waiting for approval")
        if (!actor.can(Permission.ADJUSTMENT_APPROVE)) return Outcome.Refused("actor", "txn.forbidden", "Your role cannot approve adjustments")
        if (txn.createdBy == actor.userId && !actor.hasRole(rw.plasmaco.ops.domain.security.Role.ADMIN)) {
            return Outcome.Refused("actor", "txn.self_approval", "Another person must approve your own adjustment")
        }
        if (!approve) return Outcome.Ok(txn.copy(status = TxnStatus.REJECTED, approvedBy = actor.userId, approvedAt = now, note = listOfNotNull(txn.note, note).joinToString(" — ")))
        var override = false
        if (txn.quantity.signum() < 0) {
            val lowest = Ledger(existing).lowestBalanceFrom(txn.itemKind, txn.itemId, txn.date, txn.quantity)
            if (lowest.signum() < 0) {
                val canOverride = settings.allowNegativeWithOverride && actor.can(Permission.NEGATIVE_STOCK_OVERRIDE) && !note.isNullOrBlank()
                if (!canOverride) return Outcome.Refused("quantity", "stock.negative", "Approving would make stock negative")
                override = true
            }
        }
        return Outcome.Ok(
            txn.copy(
                status = TxnStatus.POSTED, approvedBy = actor.userId, approvedAt = now, negativeOverride = override,
                note = if (override) listOfNotNull(txn.note, note).joinToString(" — ") else txn.note,
            ),
        )
    }
}

/** A physical count. Approving it posts one COUNT_VARIANCE per item whose counted quantity differs from the system. */
data class StockCount(
    val id: Id,
    val date: LocalDate,
    val itemKind: ItemKind,
    val lines: List<StockCountLine>,
    val status: StockCountStatus = StockCountStatus.DRAFT,
    val createdBy: Id,
    val createdAt: Instant,
    val note: String? = null,
)

data class StockCountLine(val itemId: Id, val systemQty: BigDecimal, val countedQty: BigDecimal?, val unit: UnitOfMeasure) {
    val variance: BigDecimal? get() = countedQty?.minus(systemQty)
}

enum class StockCountStatus { DRAFT, SUBMITTED, APPROVED, REJECTED }

object StockCounts {
    fun start(id: Id, date: LocalDate, kind: ItemKind, master: MasterData, ledger: Ledger, actorId: Id, now: Instant): StockCount {
        val items = when (kind) {
            ItemKind.FINISHED_GOOD -> master.products.filter { it.active }.map { it.id to it.baseUnit }
            ItemKind.RAW_MATERIAL -> master.materials.filter { it.active }.map { it.id to it.baseUnit }
        }
        return StockCount(id, date, kind, items.map { (itemId, unit) -> StockCountLine(itemId, ledger.balance(kind, itemId, date), null, unit) }, createdBy = actorId, createdAt = now)
    }

    fun varianceTransactions(count: StockCount, approver: Actor, now: Instant): List<InventoryTransaction> =
        count.lines.mapNotNull { line ->
            val v = line.variance ?: return@mapNotNull null
            if (v.signum() == 0) return@mapNotNull null
            InventoryTransaction(
                id = newId(), itemKind = count.itemKind, itemId = line.itemId, type = TxnType.COUNT_VARIANCE,
                quantity = v, unit = line.unit, date = count.date, reference = "COUNT-${count.id.take(8)}",
                note = "Counted ${line.countedQty?.toPlainString()} vs system ${line.systemQty.toPlainString()}",
                status = TxnStatus.POSTED, createdBy = count.createdBy, createdAt = now, approvedBy = approver.userId, approvedAt = now,
                idempotencyKey = "count:${count.id}:${line.itemId}",
            )
        }
}
