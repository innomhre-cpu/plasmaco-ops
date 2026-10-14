package rw.plasmaco.ops.domain.sync

import rw.plasmaco.ops.domain.common.Id
import java.time.Instant
import kotlin.math.min

/** What the user sees on every record. */
enum class SyncState(val label: String) {
    LOCAL_ONLY("Saved on this device"),
    PENDING("Waiting to sync"),
    SYNCED("Synced"),
    CONFLICT("Conflict — needs review"),
    FAILED("Sync failed"),
}

enum class OpKind { UPSERT, DELETE, ACTION }

enum class OpState { QUEUED, IN_FLIGHT, DONE, FAILED, CONFLICT }

/**
 * One queued change. Written in the same database transaction as the change itself, so a crash can never
 * leave a saved record without its sync operation (or the reverse).
 */
data class OutboxOperation(
    val id: Id,
    val entityType: String,
    val entityId: Id,
    val kind: OpKind,
    /** Serialised entity or action body; opaque to this layer. */
    val payload: String,
    /** Server version the change was based on; null for a record created on this device. */
    val baseVersion: Long?,
    val createdAt: Instant,
    val attempts: Int = 0,
    val state: OpState = OpState.QUEUED,
    val lastError: String? = null,
    val nextAttemptAt: Instant? = null,
    /** Sent with the request; the server stores it so a retried request is applied once. */
    val idempotencyKey: String = id,
)

/** Version facts for one record on each side. */
data class VersionInfo(val version: Long, val updatedAt: Instant, val locked: Boolean)

sealed interface Resolution {
    /** Server copy is newer and the device has no unsynced edits: take the server copy. */
    data object AcceptServer : Resolution

    /** Device edit is based on the server's current version: push it. */
    data object PushLocal : Resolution

    /** Both changed. [keepServerCopy] true when the server copy must win (approved/locked records). */
    data class Conflict(val keepServerCopy: Boolean, val reason: String) : Resolution
}

object SyncRules {
    private const val BASE_DELAY_MS = 15_000L
    private const val MAX_DELAY_MS = 30 * 60_000L
    const val MAX_ATTEMPTS = 12

    /** Exponential back-off: 15 s, 30 s, 1 min … capped at 30 min. */
    fun backoffMillis(attempt: Int): Long = min(MAX_DELAY_MS, BASE_DELAY_MS * (1L shl min(attempt, 16)))

    /** Timeouts, rate limits and server errors are retried; validation and permission errors are not. */
    fun isTransient(httpStatus: Int?): Boolean = httpStatus == null || httpStatus == 408 || httpStatus == 429 || httpStatus in 500..599

    fun afterFailure(op: OutboxOperation, httpStatus: Int?, message: String, now: Instant): OutboxOperation {
        val attempts = op.attempts + 1
        return if (httpStatus == 409) op.copy(state = OpState.CONFLICT, attempts = attempts, lastError = message)
        else if (isTransient(httpStatus) && attempts < MAX_ATTEMPTS) op.copy(state = OpState.QUEUED, attempts = attempts, lastError = message, nextAttemptAt = now.plusMillis(backoffMillis(attempts)))
        else op.copy(state = OpState.FAILED, attempts = attempts, lastError = message)
    }

    /**
     * Decides what to do with a record that exists on both sides.
     *  - never silently overwrite an approved (locked) or newer server record;
     *  - a device edit based on the current server version is pushed;
     *  - otherwise both copies are kept and the user resolves.
     */
    fun resolve(server: VersionInfo, deviceBaseVersion: Long?, deviceHasUnsyncedChanges: Boolean): Resolution = when {
        !deviceHasUnsyncedChanges -> Resolution.AcceptServer
        deviceBaseVersion != null && deviceBaseVersion == server.version && !server.locked -> Resolution.PushLocal
        server.locked -> Resolution.Conflict(keepServerCopy = true, reason = "The server copy is approved and locked; your edit is kept as a separate draft for review")
        else -> Resolution.Conflict(keepServerCopy = false, reason = "Changed on another device since you started editing")
    }

    /**
     * Operations to push now, oldest first. For each record only the unbroken prefix of due operations is
     * returned: if an earlier change to the same record is failed, in conflict or backing off, later changes wait,
     * so a create always reaches the server before the edits and actions that depend on it.
     *
     * Operations are chained by [OutboxOperation.entityId] alone (ids are globally unique), so a workflow action on a
     * report (its own entity type) still waits for the report's pending content changes.
     */
    fun pushOrder(ops: List<OutboxOperation>, now: Instant): List<OutboxOperation> {
        fun due(o: OutboxOperation) = o.state == OpState.QUEUED && (o.nextAttemptAt == null || !o.nextAttemptAt.isAfter(now))
        return ops.filter { it.state != OpState.DONE }
            .groupBy { it.entityId }
            .values
            .flatMap { chain -> chain.sortedWith(compareBy<OutboxOperation> { it.createdAt }.thenBy { it.id }).takeWhile(::due) }
            .sortedWith(compareBy<OutboxOperation> { it.createdAt }.thenBy { it.id })
    }

    fun stateFor(ops: List<OutboxOperation>): SyncState = when {
        ops.isEmpty() || ops.all { it.state == OpState.DONE } -> SyncState.SYNCED
        ops.any { it.state == OpState.CONFLICT } -> SyncState.CONFLICT
        ops.any { it.state == OpState.FAILED } -> SyncState.FAILED
        else -> SyncState.PENDING
    }
}
