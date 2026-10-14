package rw.plasmaco.ops.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
interface UserDao {
    @Query("SELECT * FROM users ORDER BY displayName") fun observeAll(): Flow<List<UserEntity>>
    @Query("SELECT * FROM users ORDER BY displayName") suspend fun all(): List<UserEntity>
    @Query("SELECT * FROM users WHERE username = :username COLLATE NOCASE LIMIT 1") suspend fun byUsername(username: String): UserEntity?
    @Query("SELECT * FROM users WHERE id = :id") suspend fun byId(id: String): UserEntity?
    @Query("SELECT COUNT(*) FROM users") suspend fun count(): Int
    @Upsert suspend fun upsert(user: UserEntity)
}

@Dao
interface MasterDao {
    @Query("SELECT * FROM products ORDER BY dn, pn") fun observeProducts(): Flow<List<ProductEntity>>
    @Query("SELECT * FROM materials ORDER BY name") fun observeMaterials(): Flow<List<MaterialEntity>>
    @Query("SELECT * FROM lines ORDER BY code") fun observeLines(): Flow<List<LineEntity>>
    @Query("SELECT * FROM machines ORDER BY code") fun observeMachines(): Flow<List<MachineEntity>>
    @Query("SELECT * FROM shifts ORDER BY start") fun observeShifts(): Flow<List<ShiftEntity>>
    @Query("SELECT * FROM downtime_categories ORDER BY name") fun observeDowntimeCategories(): Flow<List<DowntimeCategoryEntity>>
    @Query("SELECT * FROM defect_categories ORDER BY name") fun observeDefectCategories(): Flow<List<DefectCategoryEntity>>
    @Query("SELECT * FROM stock_thresholds") fun observeThresholds(): Flow<List<ThresholdEntity>>
    @Query("SELECT * FROM production_targets ORDER BY validFrom DESC") fun observeTargets(): Flow<List<TargetEntity>>
    @Query("SELECT * FROM run_rates") fun observeRunRates(): Flow<List<RunRateEntity>>

    @Query("SELECT COUNT(*) FROM products") suspend fun productCount(): Int
    @Query("SELECT * FROM products") suspend fun products(): List<ProductEntity>

    @Upsert suspend fun upsertProducts(items: List<ProductEntity>)
    @Upsert suspend fun upsertMaterials(items: List<MaterialEntity>)
    @Upsert suspend fun upsertLines(items: List<LineEntity>)
    @Upsert suspend fun upsertMachines(items: List<MachineEntity>)
    @Upsert suspend fun upsertShifts(items: List<ShiftEntity>)
    @Upsert suspend fun upsertDowntimeCategories(items: List<DowntimeCategoryEntity>)
    @Upsert suspend fun upsertDefectCategories(items: List<DefectCategoryEntity>)
    @Upsert suspend fun upsertThresholds(items: List<ThresholdEntity>)
    @Upsert suspend fun upsertTargets(items: List<TargetEntity>)
    @Upsert suspend fun upsertRunRates(items: List<RunRateEntity>)
}

@Dao
abstract class ReportDao {
    @Transaction @Query("SELECT * FROM production_reports ORDER BY date DESC, reference DESC") abstract fun observeAll(): Flow<List<ReportWithChildren>>
    @Transaction @Query("SELECT * FROM production_reports WHERE date BETWEEN :from AND :to ORDER BY date DESC, reference DESC")
    abstract fun observeBetween(from: LocalDate, to: LocalDate): Flow<List<ReportWithChildren>>
    @Transaction @Query("SELECT * FROM production_reports WHERE id = :id") abstract fun observe(id: String): Flow<ReportWithChildren?>
    @Transaction @Query("SELECT * FROM production_reports WHERE id = :id") abstract suspend fun get(id: String): ReportWithChildren?
    @Transaction @Query("SELECT * FROM production_reports ORDER BY date DESC") abstract suspend fun all(): List<ReportWithChildren>
    @Query("SELECT reference FROM production_reports WHERE reference LIKE :prefix || '%'") abstract suspend fun referencesLike(prefix: String): List<String>
    @Query("SELECT COUNT(*) FROM production_reports WHERE status = 'SUBMITTED'") abstract fun observePendingCount(): Flow<Int>

    @Upsert abstract suspend fun upsertReport(report: ReportEntity)
    @Query("DELETE FROM production_entries WHERE reportId = :reportId") abstract suspend fun deleteEntries(reportId: String)
    @Query("DELETE FROM material_lines WHERE reportId = :reportId") abstract suspend fun deleteMaterials(reportId: String)
    @Query("DELETE FROM downtime_events WHERE reportId = :reportId") abstract suspend fun deleteDowntime(reportId: String)
    @Query("DELETE FROM quality_inspections WHERE reportId = :reportId") abstract suspend fun deleteInspections(reportId: String)
    @Insert abstract suspend fun insertEntries(items: List<EntryEntity>)
    @Insert abstract suspend fun insertMaterials(items: List<MaterialLineEntity>)
    @Insert abstract suspend fun insertDowntime(items: List<DowntimeEntity>)
    @Insert abstract suspend fun insertInspections(items: List<InspectionEntity>)
    @Query("DELETE FROM production_reports WHERE id = :id AND status = 'DRAFT' AND reportVersion = 0") abstract suspend fun deleteDraft(id: String): Int

    /** Replaces a report and all its children atomically. */
    @Transaction
    open suspend fun replace(r: ReportWithChildren) {
        upsertReport(r.report)
        deleteEntries(r.report.id); deleteMaterials(r.report.id); deleteDowntime(r.report.id); deleteInspections(r.report.id)
        insertEntries(r.entries); insertMaterials(r.materials); insertDowntime(r.downtime); insertInspections(r.inspections)
    }

    @Insert(onConflict = OnConflictStrategy.ABORT) abstract suspend fun insertVersion(v: ReportVersionEntity)
    @Query("SELECT * FROM report_versions WHERE reportId = :reportId ORDER BY reportVersion") abstract fun observeVersions(reportId: String): Flow<List<ReportVersionEntity>>

    @Insert abstract suspend fun insertApproval(a: ApprovalEntity)
    @Query("SELECT * FROM approval_actions WHERE reportId = :reportId ORDER BY at") abstract fun observeApprovals(reportId: String): Flow<List<ApprovalEntity>>
    @Query("SELECT * FROM approval_actions ORDER BY at") abstract fun observeAllApprovals(): Flow<List<ApprovalEntity>>
    @Query("SELECT * FROM approval_actions ORDER BY at") abstract suspend fun allApprovals(): List<ApprovalEntity>
}

@Dao
interface InventoryDao {
    @Query("SELECT * FROM inventory_transactions ORDER BY date, createdAt") fun observeAll(): Flow<List<TxnEntity>>
    @Query("SELECT * FROM inventory_transactions ORDER BY date, createdAt") suspend fun all(): List<TxnEntity>
    @Query("SELECT * FROM inventory_transactions WHERE itemKind = :kind AND itemId = :itemId ORDER BY date, createdAt")
    fun observeItem(kind: String, itemId: String): Flow<List<TxnEntity>>
    @Query("SELECT * FROM inventory_transactions WHERE status = 'PENDING_APPROVAL' ORDER BY createdAt") fun observePending(): Flow<List<TxnEntity>>
    @Query("SELECT idempotencyKey, quantity FROM inventory_transactions WHERE origin = 'LEGACY_IMPORT'") suspend fun legacyKeys(): List<KeyQuantity>
    @Query("SELECT MAX(date) FROM inventory_transactions WHERE historical = 1") suspend fun lastHistoricalDate(): LocalDate?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(txn: TxnEntity): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAll(txns: List<TxnEntity>): List<Long>
    @Upsert suspend fun upsert(txn: TxnEntity)
    /** Another device posted the same movement first (same idempotency key): its server copy replaces ours. */
    @Query("DELETE FROM inventory_transactions WHERE idempotencyKey = :key AND id != :id") suspend fun deleteDuplicateKey(key: String, id: String): Int

    @Upsert suspend fun upsertCount(c: StockCountEntity)
    @Upsert suspend fun upsertCountLines(lines: List<StockCountLineEntity>)
    @Transaction @Query("SELECT * FROM stock_counts ORDER BY date DESC") fun observeCounts(): Flow<List<StockCountWithLines>>
    @Transaction @Query("SELECT * FROM stock_counts WHERE id = :id") fun observeCount(id: String): Flow<StockCountWithLines?>
}

data class KeyQuantity(val idempotencyKey: String, val quantity: java.math.BigDecimal)

@Dao
interface AuditDao {
    @Insert suspend fun insert(e: AuditEntity)
    @Query("SELECT * FROM audit_log ORDER BY at DESC LIMIT :limit") fun observeRecent(limit: Int): Flow<List<AuditEntity>>
    @Query("SELECT * FROM audit_log WHERE entityType = :type AND entityId = :id ORDER BY at") fun observeFor(type: String, id: String): Flow<List<AuditEntity>>
    /** Retention: audit rows older than the policy are removed only after they have reached the server. */
    @Query("DELETE FROM audit_log WHERE at < :before AND syncState = 'SYNCED'") suspend fun pruneSynced(before: Long): Int
}

@Dao
interface NotificationDao {
    @Query("SELECT * FROM notifications WHERE resolved = 0 ORDER BY createdAt DESC") fun observeOpen(): Flow<List<NotificationEntity>>
    @Query("SELECT COUNT(*) FROM notifications WHERE resolved = 0 AND read = 0") fun observeUnread(): Flow<Int>
    @Query("SELECT `key` FROM notifications WHERE resolved = 0") suspend fun openKeys(): List<String>
    /** A condition that cleared and came back is notified again (its old row is replaced). */
    @Upsert suspend fun upsertAll(items: List<NotificationEntity>)
    @Query("UPDATE notifications SET read = 1 WHERE `key` = :key") suspend fun markRead(key: String)
    @Query("UPDATE notifications SET read = 1") suspend fun markAllRead()
    @Query("UPDATE notifications SET resolved = 1 WHERE `key` NOT IN (:openKeys) AND resolved = 0") suspend fun resolveAllExcept(openKeys: List<String>)
}

@Dao
interface OutboxDao {
    @Insert suspend fun insert(op: OutboxEntity)
    @Query("SELECT * FROM sync_outbox WHERE state != 'DONE' ORDER BY createdAt") suspend fun pending(): List<OutboxEntity>
    @Query("SELECT COUNT(*) FROM sync_outbox WHERE state != 'DONE'") fun observePendingCount(): Flow<Int>
    @Query("SELECT COUNT(*) FROM sync_outbox WHERE state IN ('FAILED','CONFLICT')") fun observeProblemCount(): Flow<Int>
    @Query("SELECT COUNT(*) FROM sync_outbox WHERE state IN ('FAILED','CONFLICT')") suspend fun problemCount(): Int
    @Query("SELECT * FROM sync_outbox WHERE state IN ('FAILED','CONFLICT') ORDER BY createdAt") fun observeProblems(): Flow<List<OutboxEntity>>
    @Upsert suspend fun upsert(op: OutboxEntity)
    @Query("DELETE FROM sync_outbox WHERE state = 'DONE' AND createdAt < :before") suspend fun pruneDone(before: Long)
}

@Dao
interface ImportDao {
    @Query("SELECT * FROM import_batches ORDER BY importedAt DESC") fun observeAll(): Flow<List<ImportBatchEntity>>
    @Query("SELECT * FROM import_batches WHERE sha256 = :sha LIMIT 1") suspend fun bySha(sha: String): ImportBatchEntity?
    @Insert suspend fun insert(b: ImportBatchEntity)
}

@Dao
interface ConflictDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(c: ConflictEntity)
    @Query("SELECT * FROM sync_conflicts WHERE resolved = 0 ORDER BY detectedAt DESC") fun observeOpen(): Flow<List<ConflictEntity>>
    @Query("SELECT * FROM sync_conflicts WHERE id = :id") suspend fun get(id: String): ConflictEntity?
    @Query("UPDATE sync_conflicts SET resolved = 1 WHERE id = :id") suspend fun resolve(id: String)
}
