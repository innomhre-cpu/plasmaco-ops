package rw.plasmaco.ops.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.encodeToJsonElement
import rw.plasmaco.ops.data.local.PlasmacoDatabase
import rw.plasmaco.ops.data.local.toDomain
import rw.plasmaco.ops.data.local.toEntity
import rw.plasmaco.ops.api.toDto
import rw.plasmaco.ops.data.settings.SettingsRepository
import rw.plasmaco.ops.domain.common.Outcome
import rw.plasmaco.ops.domain.common.TimeSource
import rw.plasmaco.ops.domain.common.newId
import rw.plasmaco.ops.domain.inventory.InventoryTransaction
import rw.plasmaco.ops.domain.inventory.Ledger
import rw.plasmaco.ops.domain.inventory.MovementRequest
import rw.plasmaco.ops.domain.inventory.MovementService
import rw.plasmaco.ops.domain.inventory.StockCount
import rw.plasmaco.ops.domain.inventory.StockCountStatus
import rw.plasmaco.ops.domain.inventory.StockCounts
import rw.plasmaco.ops.domain.inventory.TxnStatus
import rw.plasmaco.ops.domain.inventory.TxnType
import rw.plasmaco.ops.domain.legacy.ProposedOpening
import rw.plasmaco.ops.domain.master.ItemKind
import rw.plasmaco.ops.domain.security.Permission
import rw.plasmaco.ops.domain.sync.OpKind
import java.math.BigDecimal
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InventoryRepository @Inject constructor(
    private val db: PlasmacoDatabase,
    private val session: SessionManager,
    private val master: MasterDataRepository,
    private val settings: SettingsRepository,
    private val recorder: ChangeRecorder,
    private val time: TimeSource,
) {
    private val dao get() = db.inventory()

    val transactions: Flow<List<InventoryTransaction>> = dao.observeAll().map { l -> l.map { it.toDomain() } }
    val pending: Flow<List<InventoryTransaction>> = dao.observePending().map { l -> l.map { it.toDomain() } }
    val counts: Flow<List<StockCount>> = dao.observeCounts().map { l -> l.map { it.toDomain() } }

    fun item(kind: ItemKind, itemId: String): Flow<List<InventoryTransaction>> = dao.observeItem(kind.name, itemId).map { l -> l.map { it.toDomain() } }
    fun count(id: String): Flow<StockCount?> = dao.observeCount(id).map { it?.toDomain() }

    /** Validates with the domain rules (permissions, units, negative stock, receipts vs approved output) and posts. */
    suspend fun post(request: MovementRequest): Outcome<InventoryTransaction> {
        val actor = session.requireActor()
        val existing = dao.all().map { it.toDomain() }
        // The referenced report is loaded up front so the pure domain check needs no database access.
        val linked = request.reportId?.let { db.reports().get(it)?.toDomain() }
        val outcome = MovementService.prepare(
            request, actor, master.snapshot(), existing,
            { id -> linked?.takeIf { it.id == id } },
            time.today(), time.now(), settings.current().inventorySettings,
        )
        val txn = (outcome as? Outcome.Ok)?.value ?: return outcome
        if (existing.any { it.id == txn.id }) return outcome // idempotent replay
        db.withTransaction {
            dao.insert(txn.toEntity(time.now().toEpochMilli()))
            recorder.audit(actor, EntityTypes.TXN, txn.id, request.type.name,
                "${request.type.label}: ${txn.quantity.stripTrailingZeros().toPlainString()} ${txn.unit.code}${if (txn.negativeOverride) " (negative stock override)" else ""}${if (txn.status == TxnStatus.PENDING_APPROVAL) " — awaiting approval" else ""}")
            recorder.enqueue(EntityTypes.TXN, txn.id, OpKind.UPSERT, AppJson.encodeToJsonElement(txn.toDto()), null, txn.idempotencyKey)
        }
        return outcome
    }

    suspend fun decide(txnId: String, approve: Boolean, note: String?): Outcome<InventoryTransaction> {
        val actor = session.requireActor()
        val all = dao.all().map { it.toDomain() }
        val txn = all.firstOrNull { it.id == txnId } ?: return Outcome.Refused("id", "txn.missing", "Movement not found")
        val outcome = MovementService.decide(txn, approve, actor, all, time.now(), note, settings.current().inventorySettings)
        val decided = (outcome as? Outcome.Ok)?.value ?: return outcome
        db.withTransaction {
            dao.upsert(decided.toEntity(time.now().toEpochMilli()))
            recorder.audit(actor, EntityTypes.TXN, decided.id, if (approve) "APPROVE" else "REJECT", "${if (approve) "Approved" else "Rejected"} ${txn.type.label.lowercase()}${note?.let { " — $it" } ?: ""}")
            recorder.enqueue(EntityTypes.TXN, decided.id, OpKind.UPSERT, AppJson.encodeToJsonElement(decided.toDto()), null)
        }
        return outcome
    }

    suspend fun balance(kind: ItemKind, itemId: String): BigDecimal = Ledger(dao.all().map { it.toDomain() }).balance(kind, itemId)

    // ---------------------------------------------------------------- stock counts

    suspend fun startCount(kind: ItemKind, date: LocalDate): Outcome<StockCount> {
        val actor = session.requireActor()
        if (!actor.can(Permission.STOCK_COUNT)) return Outcome.Refused("actor", "txn.forbidden", "Your role cannot run stock counts")
        val count = StockCounts.start(newId(), date, kind, master.snapshot(), Ledger(dao.all().map { it.toDomain() }), actor.userId, time.now())
        saveCount(count, "Started ${kind.name.lowercase().replace('_', ' ')} count")
        return Outcome.Ok(count)
    }

    suspend fun updateCount(count: StockCount): Outcome<StockCount> {
        if (count.status != StockCountStatus.DRAFT) return Outcome.Refused("status", "count.locked", "This count is already submitted")
        saveCount(count, null)
        return Outcome.Ok(count)
    }

    suspend fun submitCount(count: StockCount): Outcome<StockCount> {
        if (count.lines.any { it.countedQty == null }) return Outcome.Refused("lines", "count.incomplete", "Enter a counted quantity for every item (0 when none is found)")
        val submitted = count.copy(status = StockCountStatus.SUBMITTED)
        saveCount(submitted, "Submitted stock count with ${count.lines.count { (it.variance?.signum() ?: 0) != 0 }} variance(s)")
        return Outcome.Ok(submitted)
    }

    /** Approval posts one COUNT_VARIANCE per differing item; the count itself is never edited afterwards. */
    suspend fun decideCount(count: StockCount, approve: Boolean): Outcome<StockCount> {
        val actor = session.requireActor()
        if (!actor.can(Permission.ADJUSTMENT_APPROVE)) return Outcome.Refused("actor", "txn.forbidden", "Your role cannot approve stock counts")
        if (count.status != StockCountStatus.SUBMITTED) return Outcome.Refused("status", "count.state", "Only a submitted count can be decided")
        if (count.createdBy == actor.userId && !actor.hasRole(rw.plasmaco.ops.domain.security.Role.ADMIN)) return Outcome.Refused("actor", "txn.self_approval", "Another person must approve your count")
        val decided = count.copy(status = if (approve) StockCountStatus.APPROVED else StockCountStatus.REJECTED)
        val txns = if (approve) StockCounts.varianceTransactions(count, actor, time.now()) else emptyList()
        db.withTransaction {
            val e = decided.toEntity(time.now().toEpochMilli())
            dao.upsertCount(e.count); dao.upsertCountLines(e.lines)
            txns.forEach { t ->
                dao.insert(t.toEntity(time.now().toEpochMilli()))
                recorder.enqueue(EntityTypes.TXN, t.id, OpKind.UPSERT, AppJson.encodeToJsonElement(t.toDto()), null, t.idempotencyKey)
            }
            recorder.audit(actor, EntityTypes.STOCK_COUNT, count.id, if (approve) "APPROVE" else "REJECT", "${if (approve) "Approved" else "Rejected"} stock count; ${txns.size} variance posting(s)")
        }
        return Outcome.Ok(decided)
    }

    private suspend fun saveCount(count: StockCount, auditSummary: String?) {
        val actor = session.requireActor()
        db.withTransaction {
            val e = count.toEntity(time.now().toEpochMilli())
            dao.upsertCount(e.count); dao.upsertCountLines(e.lines)
            if (auditSummary != null) recorder.audit(actor, EntityTypes.STOCK_COUNT, count.id, "SAVE", auditSummary)
        }
    }

    // ---------------------------------------------------------------- go-live migration

    /**
     * Authorised opening-balance migration: posts live OPENING_BALANCE transactions at [cutoff] (from a physical count
     * or from the imported history's last closing) and sets the live-ledger cut-off. Requires a reason; audited.
     */
    suspend fun migrateOpeningBalances(openings: List<ProposedOpening>, cutoff: LocalDate, reason: String): Outcome<Int> {
        val actor = session.requireActor()
        if (!actor.can(Permission.OPENING_BALANCE_MIGRATE)) return Outcome.Refused("actor", "txn.forbidden", "Only an administrator can set opening balances")
        if (reason.trim().length < 10) return Outcome.Refused("reason", "migration.reason", "Explain the source of these balances (at least 10 characters)")
        val existing = dao.all().map { it.toDomain() }
        if (existing.any { !it.historical && it.type == TxnType.OPENING_BALANCE }) return Outcome.Refused("cutoff", "migration.done", "Opening balances have already been set; use adjustments instead")
        // The imported history can end below zero (the workbooks did); stock that is physically there never is.
        if (openings.any { it.quantity.signum() < 0 }) return Outcome.Refused("quantity", "migration.negative", "Opening balances cannot be negative; enter the physically counted quantity")
        val txns = openings.filter { it.quantity.signum() != 0 }.map { o ->
            InventoryTransaction(
                newId(), o.kind, o.itemId, TxnType.OPENING_BALANCE, o.quantity, master.snapshot().itemUnit(o.kind, o.itemId) ?: rw.plasmaco.ops.domain.units.UnitOfMeasure.METRE,
                cutoff, note = "Go-live opening balance — ${reason.trim()}", createdBy = actor.userId, createdAt = time.now(),
                approvedBy = actor.userId, approvedAt = time.now(), idempotencyKey = "opening:${o.kind}:${o.itemId}",
            )
        }
        db.withTransaction {
            txns.forEach { t ->
                dao.insert(t.toEntity(time.now().toEpochMilli()))
                recorder.enqueue(EntityTypes.TXN, t.id, OpKind.UPSERT, AppJson.encodeToJsonElement(t.toDto()), null, t.idempotencyKey)
            }
            recorder.audit(actor, EntityTypes.TXN, "opening-balances", "MIGRATE", "Set ${txns.size} opening balances at $cutoff — ${reason.trim()}")
        }
        settings.setInventoryCutoff(cutoff)
        return Outcome.Ok(txns.size)
    }
}
