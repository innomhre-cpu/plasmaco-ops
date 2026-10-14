package rw.plasmaco.ops.server.core

import java.time.Instant

/**
 * One versioned record on the server. Every synced entity is stored the same way: its wire DTO as JSON plus a
 * server version that increases by one on each accepted change. The server copy is the shared source of truth.
 */
data class StoredRecord(
    val type: String,
    val id: String,
    val version: Long,
    /** Wire DTO JSON; null for a deletion. */
    val payload: String?,
    val deleted: Boolean,
    val updatedAt: Instant,
    val updatedBy: String,
    /** Device that wrote this version; used to tell a device's own queued edits from someone else's change. */
    val updatedByDevice: String,
)

/** A row of the change feed devices pull from; [seq] is strictly increasing across all records. */
data class StoredChange(val seq: Long, val record: StoredRecord)

/** A push operation that was applied, kept so a retried request with the same idempotency key is applied once. */
data class AppliedOp(
    val idempotencyKey: String,
    val deviceId: String,
    val entityType: String,
    val entityId: String,
    val version: Long?,
    val appliedAt: Instant,
    val actorId: String,
)

/** Sign-in secret for a user; never part of any synced payload. */
data class Credentials(
    val userId: String,
    val username: String,
    /** PBKDF2 hash in the domain PinHasher format. */
    val passwordHash: String,
    val failedAttempts: Int = 0,
    val lockedUntil: Instant? = null,
)

data class AuditEntry(
    val id: String,
    val at: Instant,
    val actorId: String,
    val actorName: String,
    val deviceId: String,
    val entityType: String,
    val entityId: String,
    val action: String,
    val summary: String,
)

/**
 * Persistence used by the sync and auth services. The production implementation is JDBC on PostgreSQL
 * (web/JdbcServerStore); tests use an in-memory one with the same semantics.
 */
interface ServerStore {
    /** Runs [block] in one transaction; a thrown exception rolls everything in it back. */
    fun <T> transaction(block: () -> T): T

    /** Reads a record; with [forUpdate] the row stays locked until the transaction ends (concurrent pushes queue). */
    fun record(type: String, id: String, forUpdate: Boolean = false): StoredRecord?

    /** All live (not deleted) records of a type. */
    fun records(type: String): List<StoredRecord>

    /** Writes the next version of a record (1 for a new one) and appends it to the change feed. */
    fun write(type: String, id: String, payload: String?, deleted: Boolean, actorId: String, deviceId: String, at: Instant): StoredRecord

    /** Changes after [afterSeq], oldest first, at most [limit]. */
    fun changesSince(afterSeq: Long, limit: Int): List<StoredChange>

    fun appliedOp(idempotencyKey: String): AppliedOp?
    fun recordApplied(op: AppliedOp)

    fun credentials(username: String): Credentials?
    fun credentialsById(userId: String): Credentials?
    fun saveCredentials(c: Credentials)

    fun audit(entry: AuditEntry)
}
