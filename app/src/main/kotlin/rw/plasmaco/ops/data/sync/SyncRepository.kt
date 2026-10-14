package rw.plasmaco.ops.data.sync

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import retrofit2.HttpException
import rw.plasmaco.ops.data.local.ConflictEntity
import rw.plasmaco.ops.data.local.OutboxEntity
import rw.plasmaco.ops.data.local.PlasmacoDatabase
import rw.plasmaco.ops.data.local.toEntity
import rw.plasmaco.ops.data.remote.ApiProvider
import rw.plasmaco.ops.api.WireEntity
import rw.plasmaco.ops.api.ApprovalDto
import rw.plasmaco.ops.api.CategoryDto
import rw.plasmaco.ops.api.Change
import rw.plasmaco.ops.api.LineDto
import rw.plasmaco.ops.api.MachineDto
import rw.plasmaco.ops.api.MaterialDto
import rw.plasmaco.ops.api.ProductDto
import rw.plasmaco.ops.api.PushOperation
import rw.plasmaco.ops.api.PushRequest
import rw.plasmaco.ops.api.ReportDto
import rw.plasmaco.ops.api.RunRateDto
import rw.plasmaco.ops.api.ShiftDto
import rw.plasmaco.ops.api.TargetDto
import rw.plasmaco.ops.api.ThresholdDto
import rw.plasmaco.ops.api.TxnDto
import rw.plasmaco.ops.api.UserDto
import rw.plasmaco.ops.api.toDefectCategory
import rw.plasmaco.ops.api.toDomain
import rw.plasmaco.ops.api.toDowntimeCategory
import rw.plasmaco.ops.data.repository.AppJson
import rw.plasmaco.ops.data.repository.EntityTypes
import rw.plasmaco.ops.data.settings.SettingsRepository
import rw.plasmaco.ops.domain.common.TimeSource
import rw.plasmaco.ops.domain.common.newId
import rw.plasmaco.ops.domain.production.ReportStatus
import rw.plasmaco.ops.domain.sync.OpKind
import rw.plasmaco.ops.domain.sync.OpState
import rw.plasmaco.ops.domain.sync.OutboxOperation
import rw.plasmaco.ops.domain.sync.Resolution
import rw.plasmaco.ops.domain.sync.SyncRules
import rw.plasmaco.ops.domain.sync.VersionInfo
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

data class SyncReport(val pushed: Int, val pulled: Int, val conflicts: Int, val failed: Int, val error: String?)

/**
 * Push queued operations, then pull server changes. Safe to run repeatedly: operations carry idempotency keys,
 * applied operations are marked DONE in the same transaction that updates the record's version, and approved or newer
 * server records are never overwritten by a device copy.
 */
@Singleton
class SyncRepository @Inject constructor(
    private val db: PlasmacoDatabase,
    private val apiProvider: ApiProvider,
    private val settings: SettingsRepository,
    private val time: TimeSource,
) {
    val pendingCount: Flow<Int> = db.outbox().observePendingCount()
    val problemCount: Flow<Int> = db.outbox().observeProblemCount()
    val problems: Flow<List<OutboxEntity>> = db.outbox().observeProblems()
    val conflicts: Flow<List<ConflictEntity>> = db.conflicts().observeOpen()

    private val tableFor = mapOf(
        EntityTypes.REPORT to "production_reports", EntityTypes.TXN to "inventory_transactions", EntityTypes.PRODUCT to "products",
        EntityTypes.MATERIAL to "materials", EntityTypes.LINE to "lines", EntityTypes.MACHINE to "machines", EntityTypes.SHIFT to "shifts",
        EntityTypes.DOWNTIME_CATEGORY to "downtime_categories", EntityTypes.DEFECT_CATEGORY to "defect_categories", EntityTypes.TARGET to "production_targets",
        EntityTypes.USER to "users", EntityTypes.STOCK_COUNT to "stock_counts",
    )

    suspend fun run(): SyncReport {
        val api = apiProvider.api() ?: return SyncReport(0, 0, 0, 0, "No server configured (standalone mode)")
        var pushed = 0
        var failed = 0
        var conflicts = 0
        var pulled = 0
        try {
            // ---- push
            val now = time.now()
            val ops = db.outbox().pending().map { it.toDomain() }
            val ready = SyncRules.pushOrder(ops, now)
            for (batch in ready.chunked(50)) {
                val request = PushRequest(settings.deviceId(), batch.map { PushOperation(it.id, it.idempotencyKey, it.entityType, it.entityId, it.kind.name, it.baseVersion, Json.parseToJsonElement(it.payload)) })
                val response = try {
                    api.push(request)
                } catch (e: HttpException) {
                    batch.forEach { op -> db.outbox().upsert(SyncRules.afterFailure(op, e.code(), "HTTP ${e.code()}", time.now()).toEntity()) }
                    failed += batch.size
                    if (e.code() == 401) return SyncReport(pushed, pulled, conflicts, failed, "Sign in again to sync")
                    continue
                }
                val byId = batch.associateBy { it.id }
                for (result in response.results) {
                    val op = byId[result.id] ?: continue
                    db.withTransaction {
                        when (result.status) {
                            "APPLIED", "DUPLICATE" -> {
                                db.outbox().upsert(op.copy(state = OpState.DONE, lastError = null).toEntity())
                                markSynced(op.entityType, op.entityId, result.version)
                                pushed++
                            }
                            "CONFLICT" -> {
                                db.outbox().upsert(op.copy(state = OpState.CONFLICT, lastError = result.message).toEntity())
                                db.conflicts().insert(ConflictEntity(newId(), op.entityType, op.entityId, result.version ?: 0, result.serverCopy?.toString() ?: "{}", op.payload,
                                    serverApplied = false, reason = result.message ?: "Changed on another device", detectedAt = time.now().toEpochMilli(), resolved = false))
                                setState(op.entityType, op.entityId, "CONFLICT")
                                conflicts++
                            }
                            else -> {
                                db.outbox().upsert(SyncRules.afterFailure(op, 422, result.message ?: "Rejected by the server", time.now()).toEntity())
                                setState(op.entityType, op.entityId, "FAILED")
                                failed++
                            }
                        }
                    }
                }
            }
            // ---- pull
            var cursor = settings.current().syncCursor
            do {
                val page = api.pull(cursor)
                db.withTransaction { page.changes.forEach { if (apply(it)) conflicts++ else pulled++ } }
                cursor = page.cursor
                settings.setSyncState(time.now().toEpochMilli(), cursor)
            } while (page.hasMore)
            db.outbox().pruneDone(time.now().toEpochMilli() - 7L * 86_400_000)
            return SyncReport(pushed, pulled, conflicts, failed, null)
        } catch (e: IOException) {
            return SyncReport(pushed, pulled, conflicts, failed, "Server not reachable; will retry")
        } catch (e: HttpException) {
            return SyncReport(pushed, pulled, conflicts, failed, "Server error ${e.code()}; will retry")
        }
    }

    /** Applies one server change. Returns true when it became a conflict instead. */
    private suspend fun apply(change: Change): Boolean {
        if (change.deleted) {
            // Only never-submitted drafts are ever deleted on the server; anything else stays for the audit trail.
            if (change.entityType == EntityTypes.REPORT) db.reports().deleteDraft(change.entityId)
            return false
        }
        val payload = change.payload ?: return false
        val local = localVersionInfo(change.entityType, change.entityId)
        val serverLocked = change.entityType == EntityTypes.REPORT &&
            runCatching { AppJson.decodeFromJsonElement<ReportDto>(payload).status }.getOrNull() == ReportStatus.APPROVED.name
        val resolution = if (local == null) Resolution.AcceptServer
        else SyncRules.resolve(VersionInfo(change.version, Instant.ofEpochMilli(0), serverLocked), local.first, local.second)
        return when (resolution) {
            Resolution.AcceptServer, Resolution.PushLocal -> { if (resolution == Resolution.AcceptServer) write(change.entityType, payload, change.version); false }
            is Resolution.Conflict -> {
                if (resolution.keepServerCopy) write(change.entityType, payload, change.version)
                else setState(change.entityType, change.entityId, "CONFLICT")
                db.conflicts().insert(ConflictEntity(newId(), change.entityType, change.entityId, change.version, payload.toString(), null,
                    serverApplied = resolution.keepServerCopy, reason = resolution.reason, detectedAt = time.now().toEpochMilli(), resolved = false))
                true
            }
        }
    }

    /** (local base version, has unsynced local changes) or null when the record does not exist locally. */
    private suspend fun localVersionInfo(entityType: String, entityId: String): Pair<Long, Boolean>? {
        val table = tableFor[entityType] ?: return null
        db.query("SELECT version, syncState FROM $table WHERE id = ?", arrayOf(entityId)).use { c ->
            if (!c.moveToFirst()) return null
            val dirty = c.getString(1) in setOf("PENDING", "LOCAL_ONLY", "CONFLICT", "FAILED") &&
                db.outbox().pending().any { it.entityType == entityType && it.entityId == entityId }
            return c.getLong(0) to dirty
        }
    }

    private suspend fun write(entityType: String, payload: JsonElement, version: Long) {
        val now = time.now().toEpochMilli()
        val m = db.master()
        when (entityType) {
            EntityTypes.REPORT -> db.reports().replace(AppJson.decodeFromJsonElement<ReportDto>(payload).toDomain().toEntity(version, "SYNCED"))
            EntityTypes.TXN -> {
                val txn = AppJson.decodeFromJsonElement<TxnDto>(payload).toDomain()
                db.inventory().deleteDuplicateKey(txn.idempotencyKey, txn.id)
                db.inventory().upsert(txn.toEntity(now, version, "SYNCED"))
            }
            EntityTypes.PRODUCT -> m.upsertProducts(listOf(AppJson.decodeFromJsonElement<ProductDto>(payload).toDomain().toEntity(now, version, "SYNCED")))
            EntityTypes.MATERIAL -> m.upsertMaterials(listOf(AppJson.decodeFromJsonElement<MaterialDto>(payload).toDomain().toEntity(now, version, "SYNCED")))
            EntityTypes.LINE -> m.upsertLines(listOf(AppJson.decodeFromJsonElement<LineDto>(payload).toDomain().toEntity(now, version, "SYNCED")))
            EntityTypes.MACHINE -> m.upsertMachines(listOf(AppJson.decodeFromJsonElement<MachineDto>(payload).toDomain().toEntity(now, version, "SYNCED")))
            EntityTypes.SHIFT -> m.upsertShifts(listOf(AppJson.decodeFromJsonElement<ShiftDto>(payload).toDomain().toEntity(now, version, "SYNCED")))
            EntityTypes.DOWNTIME_CATEGORY -> m.upsertDowntimeCategories(listOf(AppJson.decodeFromJsonElement<CategoryDto>(payload).toDowntimeCategory().toEntity(now).copy(version = version, syncState = "SYNCED")))
            EntityTypes.DEFECT_CATEGORY -> m.upsertDefectCategories(listOf(AppJson.decodeFromJsonElement<CategoryDto>(payload).toDefectCategory().toEntity(now).copy(version = version, syncState = "SYNCED")))
            EntityTypes.THRESHOLD -> m.upsertThresholds(listOf(AppJson.decodeFromJsonElement<ThresholdDto>(payload).toDomain().toEntity(now).copy(version = version, syncState = "SYNCED")))
            EntityTypes.TARGET -> m.upsertTargets(listOf(AppJson.decodeFromJsonElement<TargetDto>(payload).toDomain().toEntity(now).copy(version = version, syncState = "SYNCED")))
            EntityTypes.RUN_RATE -> m.upsertRunRates(listOf(AppJson.decodeFromJsonElement<RunRateDto>(payload).toDomain().toEntity(now).copy(version = version, syncState = "SYNCED")))
            EntityTypes.USER -> {
                val dto = AppJson.decodeFromJsonElement<UserDto>(payload)
                val existing = db.users().byId(dto.id)
                db.users().upsert(dto.toDomain().copy(pinHash = existing?.pinHash).toEntity(now, existing?.lastServerVerificationAt).copy(version = version, syncState = "SYNCED"))
            }
            WireEntity.APPROVAL -> {
                val a = AppJson.decodeFromJsonElement<ApprovalDto>(payload).toDomain()
                runCatching { db.reports().insertApproval(a.toEntity().copy(syncState = "SYNCED")) }
            }
        }
    }

    private fun markSynced(entityType: String, entityId: String, version: Long?) {
        val table = tableFor[entityType] ?: return
        if (version != null) db.openHelper.writableDatabase.execSQL("UPDATE $table SET version = ?, syncState = 'SYNCED' WHERE id = ?", arrayOf(version, entityId))
        else db.openHelper.writableDatabase.execSQL("UPDATE $table SET syncState = 'SYNCED' WHERE id = ?", arrayOf(entityId))
    }

    private fun setState(entityType: String, entityId: String, state: String) {
        val table = tableFor[entityType] ?: return
        db.openHelper.writableDatabase.execSQL("UPDATE $table SET syncState = ? WHERE id = ?", arrayOf(state, entityId))
    }

    /** "Use server copy": applies the stored server version and drops the device's queued change. */
    suspend fun resolveUseServer(conflictId: String) {
        val c = db.conflicts().get(conflictId) ?: return
        db.withTransaction {
            if (!c.serverApplied) write(c.entityType, Json.parseToJsonElement(c.serverPayload), c.serverVersion)
            db.outbox().pending().filter { it.entityType == c.entityType && it.entityId == c.entityId }.forEach { db.outbox().upsert(it.copy(state = OpState.DONE.name)) }
            db.conflicts().resolve(conflictId)
        }
    }

    /** "Keep mine": re-queues the device's change on top of the server version (allowed only for unlocked records). */
    suspend fun resolveKeepMine(conflictId: String) {
        val c = db.conflicts().get(conflictId) ?: return
        if (c.serverApplied) return
        db.withTransaction {
            db.outbox().pending().filter { it.entityType == c.entityType && it.entityId == c.entityId && it.state == OpState.CONFLICT.name }
                .forEach { db.outbox().upsert(it.copy(state = OpState.QUEUED.name, baseVersion = c.serverVersion, attempts = 0, lastError = null)) }
            setState(c.entityType, c.entityId, "PENDING")
            db.conflicts().resolve(conflictId)
        }
    }

    /** Puts failed operations back in the queue after the user fixed the cause. */
    suspend fun retryFailed() {
        db.outbox().pending().filter { it.state == OpState.FAILED.name }.forEach { db.outbox().upsert(it.copy(state = OpState.QUEUED.name, attempts = 0, nextAttemptAt = null)) }
    }

    private fun OutboxEntity.toDomain() = OutboxOperation(id, entityType, entityId, OpKind.valueOf(kind), payload, baseVersion, Instant.ofEpochMilli(createdAt), attempts,
        OpState.valueOf(state), lastError, nextAttemptAt?.let(Instant::ofEpochMilli), idempotencyKey)

    private fun OutboxOperation.toEntity() = OutboxEntity(id, entityType, entityId, kind.name, payload, baseVersion, createdAt.toEpochMilli(), attempts, state.name, lastError, nextAttemptAt?.toEpochMilli(), idempotencyKey)
}
