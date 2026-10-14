package rw.plasmaco.ops.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration

@Database(
    entities = [
        UserEntity::class, ProductEntity::class, MaterialEntity::class, LineEntity::class, MachineEntity::class, ShiftEntity::class,
        DowntimeCategoryEntity::class, DefectCategoryEntity::class, ThresholdEntity::class, TargetEntity::class, RunRateEntity::class,
        ReportEntity::class, EntryEntity::class, MaterialLineEntity::class, DowntimeEntity::class, InspectionEntity::class,
        ReportVersionEntity::class, ApprovalEntity::class, TxnEntity::class, StockCountEntity::class, StockCountLineEntity::class,
        AuditEntity::class, NotificationEntity::class, OutboxEntity::class, ConflictEntity::class, ImportBatchEntity::class,
    ],
    version = PlasmacoDatabase.VERSION,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class PlasmacoDatabase : RoomDatabase() {
    abstract fun users(): UserDao
    abstract fun master(): MasterDao
    abstract fun reports(): ReportDao
    abstract fun inventory(): InventoryDao
    abstract fun audit(): AuditDao
    abstract fun notifications(): NotificationDao
    abstract fun outbox(): OutboxDao
    abstract fun imports(): ImportDao
    abstract fun conflicts(): ConflictDao

    companion object {
        const val VERSION = 1
        const val NAME = "plasmaco-ops.db"

        /**
         * Explicit migrations, one per schema version. Destructive fallback is never enabled: production data must
         * survive every upgrade. For each new version, add its migration here and an instrumented test that migrates
         * from the previous exported schema (app/schemas) with Room's MigrationTestHelper.
         */
        val MIGRATIONS: Array<Migration> = arrayOf()
    }
}
