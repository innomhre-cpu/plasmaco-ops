package rw.plasmaco.ops.server

import rw.plasmaco.ops.api.LineDto
import rw.plasmaco.ops.api.PushOperation
import rw.plasmaco.ops.api.PushRequest
import rw.plasmaco.ops.api.PushStatus
import rw.plasmaco.ops.api.WireEntity
import rw.plasmaco.ops.api.WireJson
import rw.plasmaco.ops.api.toDto
import rw.plasmaco.ops.domain.TestData
import rw.plasmaco.ops.domain.common.FixedTimeSource
import rw.plasmaco.ops.server.core.AppliedOp
import rw.plasmaco.ops.server.core.AuditEntry
import rw.plasmaco.ops.server.core.Credentials
import rw.plasmaco.ops.server.core.RequestPrincipal
import rw.plasmaco.ops.server.core.ServerStore
import rw.plasmaco.ops.server.core.StoredRecord
import rw.plasmaco.ops.server.core.SyncService
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behaviour every [ServerStore] must have. It runs against the in-memory store ([InMemoryStoreTest]) and against
 * PostgreSQL ([rw.plasmaco.ops.server.web.JdbcServerStoreTest]), so the sync tests that use the in-memory store stay
 * representative of production. Each test uses its own ids and a cursor taken at its start, so the tests also pass on
 * a database shared by the whole class.
 */
abstract class ServerStoreContract {
    protected abstract fun newStore(): ServerStore

    private val store: ServerStore by lazy { newStore() }
    private val tag = UUID.randomUUID().toString().take(8)
    private val type = "T$tag"
    private val t0 = Instant.parse("2026-10-13T06:00:00Z")

    private fun cursor(): Long = store.changesSince(0, Int.MAX_VALUE).lastOrNull()?.seq ?: 0
    private fun write(id: String, payload: String?, at: Instant = t0, device: String = "dev-1", deleted: Boolean = payload == null): StoredRecord =
        store.transaction { store.write(type, id, payload, deleted, "u-1", device, at) }

    @Test
    fun `versions start at 1 and increase by one per write`() {
        assertEquals(1, write("a", """{"v":1}""").version)
        val second = write("a", """{"v":2}""", t0.plusSeconds(300), device = "dev-2")
        assertEquals(2, second.version)
        assertEquals(second, store.record(type, "a"))
        assertEquals(second, store.transaction { store.record(type, "a", forUpdate = true) })
        assertNull(store.record(type, "missing"))
        assertEquals(42, store.transaction { 42 })
    }

    @Test
    fun `the change feed lists each record once, at its latest version, in write order`() {
        val start = cursor()
        write("a", """{"v":1}""")
        write("b", """{"v":1}""")
        write("a", """{"v":2}""")
        val feed = store.changesSince(start, 100).filter { it.record.type == type }
        assertEquals(listOf("b" to 1L, "a" to 2L), feed.map { it.record.id to it.record.version })
        assertTrue(feed[0].seq < feed[1].seq)
        assertEquals(1, store.changesSince(start, 1).size)
        assertEquals(listOf("a"), store.changesSince(feed[0].seq, 100).filter { it.record.type == type }.map { it.record.id })
    }

    @Test
    fun `deleted records leave the live list but stay in the feed`() {
        val start = cursor()
        write("a", """{"v":1}""")
        write("b", """{"v":1}""")
        write("a", null)
        assertEquals(listOf("b"), store.records(type).map { it.id })
        val gone = store.record(type, "a")!!
        assertTrue(gone.deleted)
        assertNull(gone.payload)
        assertEquals(2, gone.version)
        assertEquals(setOf("a", "b"), store.changesSince(start, 100).filter { it.record.type == type }.map { it.record.id }.toSet())
    }

    @Test
    fun `a failed transaction leaves nothing behind`() {
        val start = cursor()
        assertFailsWith<IllegalStateException> {
            store.transaction {
                store.write(type, "a", "{}", false, "u-1", "dev-1", t0)
                store.recordApplied(AppliedOp("key-$tag", "dev-1", type, "a", 1, t0, "u-1"))
                store.saveCredentials(Credentials("user-$tag", "user-$tag", "hash"))
                error("rolled back")
            }
        }
        assertNull(store.record(type, "a"))
        assertNull(store.appliedOp("key-$tag"))
        assertNull(store.credentialsById("user-$tag"))
        assertTrue(store.changesSince(start, 100).none { it.record.type == type })
    }

    @Test
    fun `an idempotency key is recorded once`() {
        val first = AppliedOp("key-$tag", "dev-1", type, "a", 2, t0, "u-1")
        store.transaction { store.recordApplied(first) }
        store.transaction { store.recordApplied(first.copy(deviceId = "dev-2", version = 9, appliedAt = t0.plusSeconds(60))) }
        assertEquals(first, store.appliedOp("key-$tag"))
        val noVersion = AppliedOp("key2-$tag", "dev-1", type, "a", null, t0, "u-1")
        store.transaction { store.recordApplied(noVersion) }
        assertEquals(noVersion, store.appliedOp("key2-$tag"))
        assertNull(store.appliedOp("missing-$tag"))
    }

    @Test
    fun `credentials are found by username in any case and updated in place`() {
        val c = Credentials("user-$tag", "Alain-$tag", "pbkdf2-a")
        store.transaction { store.saveCredentials(c) }
        val locked = c.copy(passwordHash = "pbkdf2-b", failedAttempts = 5, lockedUntil = t0.plus(Duration.ofMinutes(15)))
        store.transaction { store.saveCredentials(locked) }
        assertEquals(locked, store.credentials("ALAIN-$tag".uppercase()))
        assertEquals(locked, store.credentialsById("user-$tag"))
        assertNull(store.credentials("nobody-$tag"))
    }

    @Test
    fun `audit entries are accepted`() {
        store.transaction { store.audit(AuditEntry("audit-$tag", t0, "u-1", "Test Admin", "dev-1", type, "a", "UPSERT", "Created")) }
    }

    @Test
    fun `pushed changes come back through the change feed`() {
        val sync = SyncService(store, FixedTimeSource(TestData.NOW))
        val start = cursor()
        val line = TestData.LINE.copy(id = "line-$tag", code = "L$tag")
        val payload = WireJson.encodeToJsonElement(LineDto.serializer(), line.toDto())
        val pushed = sync.push(RequestPrincipal(TestData.admin, "dev-1"), PushRequest("dev-1", listOf(PushOperation("op-$tag", "key-$tag", WireEntity.LINE, line.id, "UPSERT", null, payload))))
        assertEquals(PushStatus.APPLIED, pushed.results.single().status)
        val pulled = sync.pull(TestData.operator, start.toString(), 100).changes.single { it.entityId == line.id }
        assertEquals(line.toDto(1), WireJson.decodeFromJsonElement(LineDto.serializer(), pulled.payload!!))
    }
}
