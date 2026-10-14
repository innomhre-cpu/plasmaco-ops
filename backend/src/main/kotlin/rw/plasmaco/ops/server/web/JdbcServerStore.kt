package rw.plasmaco.ops.server.web

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.transaction.support.TransactionTemplate
import rw.plasmaco.ops.server.core.AppliedOp
import rw.plasmaco.ops.server.core.AuditEntry
import rw.plasmaco.ops.server.core.Credentials
import rw.plasmaco.ops.server.core.ServerStore
import rw.plasmaco.ops.server.core.StoredChange
import rw.plasmaco.ops.server.core.StoredRecord
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Every statement the store runs. Kept together so tools/verify-server-sql.sh can prepare and exercise each one
 * against a real PostgreSQL with the Flyway schema.
 */
object StoreSql {
    /** Serialises writers for the rest of the transaction, so change-feed sequence order equals commit order. */
    const val WRITE_LOCK = "SELECT pg_advisory_xact_lock(7311)"
    const val RECORD = "SELECT type, id, version, payload, deleted, updated_at, updated_by, updated_by_device, seq FROM records WHERE type = ? AND id = ?"
    const val RECORD_FOR_UPDATE = "$RECORD FOR UPDATE"
    const val RECORDS = "SELECT type, id, version, payload, deleted, updated_at, updated_by, updated_by_device, seq FROM records WHERE type = ? AND NOT deleted ORDER BY seq"
    const val UPSERT = """INSERT INTO records (type, id, version, payload, deleted, updated_at, updated_by, updated_by_device, seq)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, nextval('record_seq'))
        ON CONFLICT (type, id) DO UPDATE SET version = EXCLUDED.version, payload = EXCLUDED.payload, deleted = EXCLUDED.deleted,
            updated_at = EXCLUDED.updated_at, updated_by = EXCLUDED.updated_by, updated_by_device = EXCLUDED.updated_by_device, seq = EXCLUDED.seq"""
    const val HISTORY = """INSERT INTO record_history (type, id, version, payload, deleted, updated_at, updated_by, updated_by_device)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)"""
    const val CHANGES = "SELECT type, id, version, payload, deleted, updated_at, updated_by, updated_by_device, seq FROM records WHERE seq > ? ORDER BY seq LIMIT ?"
    const val APPLIED = "SELECT idempotency_key, device_id, entity_type, entity_id, version, applied_at, actor_id FROM applied_ops WHERE idempotency_key = ?"
    const val RECORD_APPLIED = """INSERT INTO applied_ops (idempotency_key, device_id, entity_type, entity_id, version, applied_at, actor_id)
        VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (idempotency_key) DO NOTHING"""
    const val CREDENTIALS = "SELECT user_id, username, password_hash, failed_attempts, locked_until FROM credentials WHERE lower(username) = lower(?)"
    const val CREDENTIALS_BY_ID = "SELECT user_id, username, password_hash, failed_attempts, locked_until FROM credentials WHERE user_id = ?"
    const val SAVE_CREDENTIALS = """INSERT INTO credentials (user_id, username, password_hash, failed_attempts, locked_until) VALUES (?, ?, ?, ?, ?)
        ON CONFLICT (user_id) DO UPDATE SET username = EXCLUDED.username, password_hash = EXCLUDED.password_hash,
            failed_attempts = EXCLUDED.failed_attempts, locked_until = EXCLUDED.locked_until"""
    const val AUDIT = """INSERT INTO audit_log (id, at, actor_id, actor_name, device_id, entity_type, entity_id, action, summary)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"""
}

/** PostgreSQL implementation of [ServerStore] (schema: db/migration/V1__init.sql). */
class JdbcServerStore(private val jdbc: JdbcTemplate, private val tx: TransactionTemplate) : ServerStore {
    private val recordMapper = RowMapper { rs: ResultSet, _: Int -> rs.toRecord() }

    private fun ResultSet.toRecord() = StoredRecord(
        type = getString("type"), id = getString("id"), version = getLong("version"), payload = getString("payload"), deleted = getBoolean("deleted"),
        updatedAt = instant("updated_at")!!, updatedBy = getString("updated_by"), updatedByDevice = getString("updated_by_device"),
    )

    /** Instants are bound and read as OffsetDateTime (UTC) so TIMESTAMPTZ values never depend on the JVM or session time zone. */
    private fun ts(i: Instant?): OffsetDateTime? = i?.atOffset(ZoneOffset.UTC)

    private fun ResultSet.instant(column: String): Instant? = getObject(column, OffsetDateTime::class.java)?.toInstant()

    override fun <T> transaction(block: () -> T): T {
        @Suppress("UNCHECKED_CAST")
        return tx.execute { block() } as T
    }

    override fun record(type: String, id: String, forUpdate: Boolean): StoredRecord? =
        jdbc.query(if (forUpdate) StoreSql.RECORD_FOR_UPDATE else StoreSql.RECORD, recordMapper, type, id).firstOrNull()

    override fun records(type: String): List<StoredRecord> = jdbc.query(StoreSql.RECORDS, recordMapper, type)

    override fun write(type: String, id: String, payload: String?, deleted: Boolean, actorId: String, deviceId: String, at: Instant): StoredRecord {
        jdbc.query(StoreSql.WRITE_LOCK, RowMapper { _: ResultSet, _: Int -> Unit })
        val version = (jdbc.query(StoreSql.RECORD_FOR_UPDATE, recordMapper, type, id).firstOrNull()?.version ?: 0) + 1
        jdbc.update(StoreSql.UPSERT, type, id, version, payload, deleted, ts(at), actorId, deviceId)
        jdbc.update(StoreSql.HISTORY, type, id, version, payload, deleted, ts(at), actorId, deviceId)
        return StoredRecord(type, id, version, payload, deleted, at, actorId, deviceId)
    }

    override fun changesSince(afterSeq: Long, limit: Int): List<StoredChange> =
        jdbc.query(StoreSql.CHANGES, RowMapper { rs: ResultSet, _: Int -> StoredChange(rs.getLong("seq"), rs.toRecord()) }, afterSeq, limit)

    override fun appliedOp(idempotencyKey: String): AppliedOp? = jdbc.query(StoreSql.APPLIED, RowMapper { rs: ResultSet, _: Int ->
        AppliedOp(
            rs.getString("idempotency_key"), rs.getString("device_id"), rs.getString("entity_type"), rs.getString("entity_id"),
            rs.getLong("version").takeIf { !rs.wasNull() }, rs.instant("applied_at")!!, rs.getString("actor_id"),
        )
    }, idempotencyKey).firstOrNull()

    override fun recordApplied(op: AppliedOp) {
        jdbc.update(StoreSql.RECORD_APPLIED, op.idempotencyKey, op.deviceId, op.entityType, op.entityId, op.version, ts(op.appliedAt), op.actorId)
    }

    private val credentialsMapper = RowMapper { rs: ResultSet, _: Int ->
        Credentials(rs.getString("user_id"), rs.getString("username"), rs.getString("password_hash"), rs.getInt("failed_attempts"), rs.instant("locked_until"))
    }

    override fun credentials(username: String): Credentials? = jdbc.query(StoreSql.CREDENTIALS, credentialsMapper, username).firstOrNull()
    override fun credentialsById(userId: String): Credentials? = jdbc.query(StoreSql.CREDENTIALS_BY_ID, credentialsMapper, userId).firstOrNull()

    override fun saveCredentials(c: Credentials) {
        jdbc.update(StoreSql.SAVE_CREDENTIALS, c.userId, c.username, c.passwordHash, c.failedAttempts, ts(c.lockedUntil))
    }

    override fun audit(entry: AuditEntry) {
        jdbc.update(
            StoreSql.AUDIT, entry.id, ts(entry.at), entry.actorId, entry.actorName.take(200), entry.deviceId.take(64), entry.entityType, entry.entityId.take(200),
            entry.action.take(40), entry.summary,
        )
    }
}
