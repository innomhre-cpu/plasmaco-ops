package rw.plasmaco.ops.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import rw.plasmaco.ops.domain.TestData
import rw.plasmaco.ops.domain.inventory.InventoryTransaction
import rw.plasmaco.ops.domain.inventory.TxnType
import rw.plasmaco.ops.domain.master.ItemKind
import rw.plasmaco.ops.domain.production.ReportStatus
import rw.plasmaco.ops.domain.units.UnitOfMeasure
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The Room schema and queries on a real SQLite (in memory), on a device or emulator. */
@RunWith(AndroidJUnit4::class)
class DatabaseTest {
    private lateinit var db: PlasmacoDatabase

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PlasmacoDatabase::class.java).build()
    }

    @After
    fun close() = db.close()

    private fun txn(id: String, key: String) = InventoryTransaction(
        id, ItemKind.RAW_MATERIAL, TestData.RESIN.id, TxnType.GOODS_RECEIPT, BigDecimal(500), UnitOfMeasure.KILOGRAM, TestData.TODAY,
        createdBy = TestData.storekeeper.userId, createdAt = TestData.NOW, idempotencyKey = key,
    )

    @Test
    fun reportIsStoredWithItsChildrenAndReplacedAsAWhole() = runBlocking {
        val report = TestData.report()
        db.reports().replace(report.toEntity(0, "PENDING"))
        assertEquals(report, db.reports().get(report.id)?.toDomain())
        val edited = report.copy(entries = report.entries.map { it.copy(good = BigDecimal(390)) }, materials = emptyList())
        db.reports().replace(edited.toEntity(0, "PENDING"))
        assertEquals(edited, db.reports().get(report.id)?.toDomain())
    }

    @Test
    fun onlyNeverSubmittedDraftsCanBeDeleted() = runBlocking {
        val submitted = TestData.report(id = "submitted", status = ReportStatus.SUBMITTED).copy(version = 1)
        val draft = TestData.report(id = "draft").let { r ->
            r.copy(entries = r.entries.map { it.copy(id = "draft-${it.id}") }, materials = r.materials.map { it.copy(id = "draft-${it.id}") })
        }
        db.reports().replace(submitted.toEntity(1, "SYNCED"))
        db.reports().replace(draft.toEntity(0, "PENDING"))
        assertEquals(0, db.reports().deleteDraft("submitted"))
        assertEquals(1, db.reports().deleteDraft("draft"))
        assertNotNull(db.reports().get("submitted"))
        assertNull(db.reports().get("draft"))
    }

    @Test
    fun aMovementIsPostedOncePerIdempotencyKey() = runBlocking {
        val now = TestData.NOW.toEpochMilli()
        assertEquals(true, db.inventory().insert(txn("t1", "k1").toEntity(now)) > 0)
        assertEquals(-1L, db.inventory().insert(txn("t2", "k1").toEntity(now)))
        assertEquals(1, db.inventory().all().size)
        // The server's copy of the same movement (other id) replaces the local one.
        assertEquals(1, db.inventory().deleteDuplicateKey("k1", "t-server"))
        db.inventory().upsert(txn("t-server", "k1").toEntity(now))
        assertEquals(listOf("t-server"), db.inventory().all().map { it.id })
    }
}
