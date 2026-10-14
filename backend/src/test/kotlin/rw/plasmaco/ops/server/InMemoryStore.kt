package rw.plasmaco.ops.server

import rw.plasmaco.ops.server.core.AppliedOp
import rw.plasmaco.ops.server.core.AuditEntry
import rw.plasmaco.ops.server.core.Credentials
import rw.plasmaco.ops.server.core.ServerStore
import rw.plasmaco.ops.server.core.StoredChange
import rw.plasmaco.ops.server.core.StoredRecord
import java.time.Instant

/**
 * In-memory [ServerStore] with the same semantics as the JDBC store: one row per record holding its latest version,
 * a global change sequence, and all-or-nothing transactions (state is copied and restored on failure).
 */
class InMemoryStore : ServerStore {
    private data class Row(val record: StoredRecord, val seq: Long)
    private data class State(
        val rows: MutableMap<Pair<String, String>, Row> = LinkedHashMap(),
        val applied: MutableMap<String, AppliedOp> = HashMap(),
        val creds: MutableMap<String, Credentials> = HashMap(),
        val audit: MutableList<AuditEntry> = ArrayList(),
        var seq: Long = 0,
    ) {
        fun copy() = State(LinkedHashMap(rows), HashMap(applied), HashMap(creds), ArrayList(audit), seq)
    }

    private var state = State()
    private val lock = Any()
    val auditLog: List<AuditEntry> get() = state.audit

    override fun <T> transaction(block: () -> T): T = synchronized(lock) {
        val saved = state.copy()
        try {
            block()
        } catch (e: Throwable) {
            state = saved
            throw e
        }
    }

    override fun record(type: String, id: String, forUpdate: Boolean): StoredRecord? = state.rows[type to id]?.record

    override fun records(type: String): List<StoredRecord> = state.rows.values.map { it.record }.filter { it.type == type && !it.deleted }

    override fun write(type: String, id: String, payload: String?, deleted: Boolean, actorId: String, deviceId: String, at: Instant): StoredRecord {
        val version = (state.rows[type to id]?.record?.version ?: 0) + 1
        val r = StoredRecord(type, id, version, payload, deleted, at, actorId, deviceId)
        state.seq += 1
        state.rows[type to id] = Row(r, state.seq)
        return r
    }

    override fun changesSince(afterSeq: Long, limit: Int): List<StoredChange> =
        state.rows.values.filter { it.seq > afterSeq }.sortedBy { it.seq }.take(limit).map { StoredChange(it.seq, it.record) }

    override fun appliedOp(idempotencyKey: String): AppliedOp? = state.applied[idempotencyKey]
    override fun recordApplied(op: AppliedOp) { state.applied.putIfAbsent(op.idempotencyKey, op) }
    override fun credentials(username: String): Credentials? = state.creds.values.firstOrNull { it.username.equals(username, ignoreCase = true) }
    override fun credentialsById(userId: String): Credentials? = state.creds[userId]
    override fun saveCredentials(c: Credentials) { state.creds[c.userId] = c }
    override fun audit(entry: AuditEntry) { state.audit += entry }
}
