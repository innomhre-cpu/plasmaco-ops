package rw.plasmaco.ops.data.repository

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import rw.plasmaco.ops.api.WireEntity
import rw.plasmaco.ops.api.WireJson
import rw.plasmaco.ops.data.local.AuditEntity
import rw.plasmaco.ops.data.local.OutboxEntity
import rw.plasmaco.ops.data.local.PlasmacoDatabase
import rw.plasmaco.ops.data.settings.SettingsRepository
import rw.plasmaco.ops.domain.common.TimeSource
import rw.plasmaco.ops.domain.common.newId
import rw.plasmaco.ops.domain.security.Actor
import rw.plasmaco.ops.domain.sync.OpKind
import rw.plasmaco.ops.domain.sync.OpState
import javax.inject.Inject
import javax.inject.Singleton

/** The app's JSON settings are the wire settings (core/api), so outbox payloads and snapshots match the server. */
val AppJson: Json = WireJson

/** Entity type names used in the outbox, the audit log and the sync API. */
object EntityTypes {
    const val REPORT = WireEntity.REPORT
    const val REPORT_ACTION = WireEntity.REPORT_ACTION
    const val TXN = WireEntity.TXN
    const val STOCK_COUNT = WireEntity.STOCK_COUNT
    const val PRODUCT = WireEntity.PRODUCT
    const val MATERIAL = WireEntity.MATERIAL
    const val LINE = WireEntity.LINE
    const val MACHINE = WireEntity.MACHINE
    const val SHIFT = WireEntity.SHIFT
    const val DOWNTIME_CATEGORY = WireEntity.DOWNTIME_CATEGORY
    const val DEFECT_CATEGORY = WireEntity.DEFECT_CATEGORY
    const val THRESHOLD = WireEntity.THRESHOLD
    const val TARGET = WireEntity.TARGET
    const val RUN_RATE = WireEntity.RUN_RATE
    const val USER = WireEntity.USER
    const val IMPORT = WireEntity.IMPORT
    const val AUDIT = WireEntity.AUDIT
}

/**
 * Writes the audit row and the outbox operation for a change. Callers invoke it INSIDE the same Room transaction as
 * the change, so the record, its audit trail and its sync operation are saved together or not at all.
 */
@Singleton
class ChangeRecorder @Inject constructor(
    private val db: PlasmacoDatabase,
    private val time: TimeSource,
    private val settings: SettingsRepository,
) {
    suspend fun audit(actor: Actor, entityType: String, entityId: String, action: String, summary: String, detail: JsonElement? = null) {
        db.audit().insert(
            AuditEntity(
                id = newId(), entityType = entityType, entityId = entityId, action = action, actorId = actor.userId, actorName = actor.displayName,
                at = time.now().toEpochMilli(), summary = summary.take(500), detailJson = detail?.toString(), deviceId = settings.deviceId(),
            ),
        )
    }

    suspend fun enqueue(entityType: String, entityId: String, kind: OpKind, payload: JsonElement, baseVersion: Long?, idempotencyKey: String = newId()) {
        db.outbox().insert(
            OutboxEntity(
                id = newId(), entityType = entityType, entityId = entityId, kind = kind.name, payload = payload.toString(),
                baseVersion = baseVersion, createdAt = time.now().toEpochMilli(), attempts = 0, state = OpState.QUEUED.name,
                lastError = null, nextAttemptAt = null, idempotencyKey = idempotencyKey,
            ),
        )
    }
}

/** Thrown when the signed-in user may not perform an operation; screens show [message]. */
class NotAllowedException(message: String) : Exception(message)
