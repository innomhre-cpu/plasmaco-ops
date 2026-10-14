package rw.plasmaco.ops.domain.inventory

import rw.plasmaco.ops.domain.common.DateRange
import rw.plasmaco.ops.domain.common.Id
import rw.plasmaco.ops.domain.master.ItemKind
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Balances are always derived from transactions: closing = opening + Σ signed movements. Nothing in the
 * system stores a balance that a person can overwrite.
 */
class Ledger(transactions: List<InventoryTransaction>, private val includeHistorical: Boolean = false) {
    private val posted: List<InventoryTransaction> = transactions
        .filter { it.affectsBalance && (includeHistorical || !it.historical) }
        .sortedWith(compareBy<InventoryTransaction> { it.date }.thenBy { it.createdAt }.thenBy { it.id })

    private val byItem: Map<Pair<ItemKind, Id>, List<InventoryTransaction>> = posted.groupBy { it.itemKind to it.itemId }

    fun items(kind: ItemKind? = null): Set<Pair<ItemKind, Id>> = byItem.keys.filter { kind == null || it.first == kind }.toSet()

    fun transactions(kind: ItemKind, itemId: Id): List<InventoryTransaction> = byItem[kind to itemId].orEmpty()

    /** Balance at the end of [asOf] (inclusive); the whole ledger when null. */
    fun balance(kind: ItemKind, itemId: Id, asOf: LocalDate? = null): BigDecimal =
        transactions(kind, itemId).filter { asOf == null || !it.date.isAfter(asOf) }.fold(BigDecimal.ZERO) { a, t -> a + t.quantity }

    /**
     * The lowest end-of-day balance from [from] onward if [extra] were added. Used to refuse a movement that would
     * make stock negative on any later day, not just on its own date (a back-dated dispatch can break later days).
     */
    fun lowestBalanceFrom(kind: ItemKind, itemId: Id, from: LocalDate, extra: BigDecimal): BigDecimal {
        val txns = transactions(kind, itemId)
        var running = txns.filter { it.date.isBefore(from) }.fold(BigDecimal.ZERO) { a, t -> a + t.quantity } + extra
        var lowest = running
        txns.filter { !it.date.isBefore(from) }.groupBy { it.date }.toSortedMap().forEach { (_, day) ->
            running += day.fold(BigDecimal.ZERO) { a, t -> a + t.quantity }
            if (running < lowest) lowest = running
        }
        return lowest
    }

    data class StatementLine(val txn: InventoryTransaction, val runningBalance: BigDecimal)

    data class Statement(
        val kind: ItemKind,
        val itemId: Id,
        val range: DateRange,
        val opening: BigDecimal,
        val lines: List<StatementLine>,
        val totals: Map<MovementGroup, BigDecimal>,
        val closing: BigDecimal,
    ) {
        val receipts: BigDecimal get() = totals[MovementGroup.RECEIPT] ?: BigDecimal.ZERO
        /** Issues and dispatches as a positive figure, the way the legacy "Outward" column showed them. */
        val issues: BigDecimal get() = (totals[MovementGroup.ISSUE] ?: BigDecimal.ZERO).negate()
        val returns: BigDecimal get() = totals[MovementGroup.RETURN] ?: BigDecimal.ZERO
        val transfers: BigDecimal get() = totals[MovementGroup.TRANSFER] ?: BigDecimal.ZERO
        val adjustments: BigDecimal get() = (totals[MovementGroup.ADJUSTMENT] ?: BigDecimal.ZERO) + (totals[MovementGroup.OPENING] ?: BigDecimal.ZERO)
        val lastMovementDate: LocalDate? get() = lines.lastOrNull()?.txn?.date

        /** Must always be true: closing = opening + receipts − issues + returns + transfers + adjustments. */
        val reconciles: Boolean get() = closing.compareTo(opening + receipts - issues + returns + transfers + adjustments) == 0
    }

    fun statement(kind: ItemKind, itemId: Id, range: DateRange): Statement {
        val txns = transactions(kind, itemId)
        val opening = txns.filter { it.date.isBefore(range.start) }.fold(BigDecimal.ZERO) { a, t -> a + t.quantity }
        var running = opening
        val lines = txns.filter { it.date in range }.map { t ->
            running += t.quantity
            StatementLine(t, running)
        }
        val totals = lines.groupBy { it.txn.type.group }.mapValues { (_, l) -> l.fold(BigDecimal.ZERO) { a, x -> a + x.txn.quantity } }
        return Statement(kind, itemId, range, opening, lines, totals, running)
    }

    /** Last movement date overall (not limited to a range). */
    fun lastMovement(kind: ItemKind, itemId: Id): LocalDate? = transactions(kind, itemId).lastOrNull()?.date

    /** Total quantity already received from a production entry, to stop the same output being received twice. */
    fun receivedForEntry(entryId: Id): BigDecimal =
        posted.filter { it.type == TxnType.PRODUCTION_RECEIPT && it.entryId == entryId }.fold(BigDecimal.ZERO) { a, t -> a + t.quantity }
}
