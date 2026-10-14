package rw.plasmaco.ops.data.local

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/*
 * Room schema (version 1). Every synced table carries:
 *   version   – server version the row is based on (0 = created on this device, never synced)
 *   syncState – rw.plasmaco.ops.domain.sync.SyncState name
 *   updatedAt – epoch millis of the last local change
 * Quantities are BigDecimal stored as text (exact), dates as ISO-8601 text, instants as epoch millis.
 */

// ------------------------------------------------------------------ people

@Entity(tableName = "users", indices = [Index(value = ["username"], unique = true)])
data class UserEntity(
    @PrimaryKey val id: String,
    val username: String,
    val displayName: String,
    /** Comma-separated Role names. */
    val roles: String,
    val active: Boolean,
    val pinHash: String?,
    val lastServerVerificationAt: Long?,
    val version: Long = 0,
    val syncState: String = "LOCAL_ONLY",
    val updatedAt: Long,
)

// ------------------------------------------------------------------ master data

@Entity(tableName = "products", indices = [Index(value = ["code"], unique = true)])
data class ProductEntity(
    @PrimaryKey val id: String,
    val code: String,
    val name: String,
    val dn: Int?,
    val pn: BigDecimal?,
    val sdr: BigDecimal?,
    val materialGrade: String,
    val kgPerMetre: BigDecimal?,
    val baseUnit: String,
    /** Metres per coil / per piece when those entry units are allowed for this product. */
    val metresPerCoil: BigDecimal?,
    val metresPerPiece: BigDecimal?,
    val active: Boolean,
    val note: String?,
    /** Legacy workbook names, '|' separated, for import matching. */
    val legacyNames: String,
    val version: Long = 0,
    val syncState: String = "LOCAL_ONLY",
    val updatedAt: Long,
)

@Entity(tableName = "materials", indices = [Index(value = ["code"], unique = true)])
data class MaterialEntity(
    @PrimaryKey val id: String,
    val code: String,
    val name: String,
    val category: String,
    val baseUnit: String,
    val bagWeightKg: BigDecimal?,
    val active: Boolean,
    val version: Long = 0,
    val syncState: String = "LOCAL_ONLY",
    val updatedAt: Long,
)

@Entity(tableName = "lines", indices = [Index(value = ["code"], unique = true)])
data class LineEntity(
    @PrimaryKey val id: String,
    val code: String,
    val name: String,
    val active: Boolean,
    val version: Long = 0,
    val syncState: String = "LOCAL_ONLY",
    val updatedAt: Long,
)

@Entity(
    tableName = "machines",
    indices = [Index(value = ["code"], unique = true), Index("lineId")],
    foreignKeys = [ForeignKey(entity = LineEntity::class, parentColumns = ["id"], childColumns = ["lineId"], onDelete = ForeignKey.RESTRICT)],
)
data class MachineEntity(
    @PrimaryKey val id: String,
    val code: String,
    val name: String,
    val lineId: String,
    val active: Boolean,
    val version: Long = 0,
    val syncState: String = "LOCAL_ONLY",
    val updatedAt: Long,
)

@Entity(tableName = "shifts", indices = [Index(value = ["code"], unique = true)])
data class ShiftEntity(
    @PrimaryKey val id: String,
    val code: String,
    val name: String,
    val start: LocalTime,
    val end: LocalTime,
    val active: Boolean,
    val version: Long = 0,
    val syncState: String = "LOCAL_ONLY",
    val updatedAt: Long,
)

@Entity(tableName = "downtime_categories")
data class DowntimeCategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val planned: Boolean,
    val active: Boolean,
    val version: Long = 0,
    val syncState: String = "LOCAL_ONLY",
    val updatedAt: Long,
)

@Entity(tableName = "defect_categories")
data class DefectCategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val active: Boolean,
    val version: Long = 0,
    val syncState: String = "LOCAL_ONLY",
    val updatedAt: Long,
)

@Entity(tableName = "stock_thresholds", primaryKeys = ["itemKind", "itemId"])
data class ThresholdEntity(
    val itemKind: String,
    val itemId: String,
    val minimum: BigDecimal?,
    val reorderPoint: BigDecimal?,
    val version: Long = 0,
    val syncState: String = "LOCAL_ONLY",
    val updatedAt: Long,
)

@Entity(tableName = "production_targets", indices = [Index("lineId")])
data class TargetEntity(
    @PrimaryKey val id: String,
    val lineId: String,
    val productId: String?,
    val shiftId: String?,
    val quantity: BigDecimal,
    val unit: String,
    val validFrom: LocalDate,
    val validTo: LocalDate?,
    val version: Long = 0,
    val syncState: String = "LOCAL_ONLY",
    val updatedAt: Long,
)

@Entity(tableName = "run_rates", primaryKeys = ["lineId", "productKey"])
data class RunRateEntity(
    val lineId: String,
    /** Product id, or "*" for the line default (Room primary keys cannot be null). */
    val productKey: String,
    val metresPerHour: BigDecimal,
    val version: Long = 0,
    val syncState: String = "LOCAL_ONLY",
    val updatedAt: Long,
)

// ------------------------------------------------------------------ production reports

@Entity(
    tableName = "production_reports",
    indices = [Index(value = ["reference"], unique = true), Index(value = ["date", "shiftId", "lineId"]), Index("status"), Index("operatorId")],
)
data class ReportEntity(
    @PrimaryKey val id: String,
    val reference: String,
    val date: LocalDate,
    val shiftId: String,
    val lineId: String,
    val operatorId: String,
    val supervisorId: String?,
    val startTime: LocalTime?,
    val endTime: LocalTime?,
    val status: String,
    val reportVersion: Int,
    val createdBy: String,
    val createdAt: Long,
    val submittedAt: Long?,
    val decidedAt: Long?,
    val decidedBy: String?,
    val remarks: String,
    val duplicatedFrom: String?,
    /** Always present; all-empty means no handover was written. */
    @Embedded(prefix = "handover_") val handover: HandoverColumns,
    val version: Long = 0,
    val syncState: String = "LOCAL_ONLY",
    val updatedAt: Long,
)

data class HandoverColumns(
    val outstandingProduction: String = "",
    val machineCondition: String = "",
    val pendingMaintenance: String = "",
    val materialShortages: String = "",
    val qualityIssues: String = "",
    val safetyIncidents: String = "",
    val instructions: String = "",
)

@Entity(
    tableName = "production_entries",
    indices = [Index("reportId"), Index("productId")],
    foreignKeys = [ForeignKey(entity = ReportEntity::class, parentColumns = ["id"], childColumns = ["reportId"], onDelete = ForeignKey.CASCADE)],
)
data class EntryEntity(
    @PrimaryKey val id: String,
    val reportId: String,
    val position: Int,
    val productId: String,
    val machineId: String?,
    val target: BigDecimal?,
    val gross: BigDecimal,
    val good: BigDecimal,
    val rejected: BigDecimal,
    val unit: String,
    val batch: String?,
    val start: LocalDateTime?,
    val end: LocalDateTime?,
    val remarks: String,
)

@Entity(
    tableName = "material_lines",
    indices = [Index("reportId"), Index("materialId")],
    foreignKeys = [ForeignKey(entity = ReportEntity::class, parentColumns = ["id"], childColumns = ["reportId"], onDelete = ForeignKey.CASCADE)],
)
data class MaterialLineEntity(
    @PrimaryKey val id: String,
    val reportId: String,
    val position: Int,
    val materialId: String,
    val issued: BigDecimal,
    val returned: BigDecimal,
    val lineOpening: BigDecimal?,
    val lineClosing: BigDecimal?,
    val scrap: BigDecimal,
    val unit: String,
    val batch: String?,
    val remarks: String,
)

@Entity(
    tableName = "downtime_events",
    indices = [Index("reportId"), Index("machineId")],
    foreignKeys = [ForeignKey(entity = ReportEntity::class, parentColumns = ["id"], childColumns = ["reportId"], onDelete = ForeignKey.CASCADE)],
)
data class DowntimeEntity(
    @PrimaryKey val id: String,
    val reportId: String,
    val position: Int,
    val machineId: String?,
    val start: LocalDateTime,
    val end: LocalDateTime?,
    val categoryId: String?,
    val reason: String,
    val correctiveAction: String,
    val remarks: String,
)

@Entity(
    tableName = "quality_inspections",
    indices = [Index("reportId")],
    foreignKeys = [ForeignKey(entity = ReportEntity::class, parentColumns = ["id"], childColumns = ["reportId"], onDelete = ForeignKey.CASCADE)],
)
data class InspectionEntity(
    @PrimaryKey val id: String,
    val reportId: String,
    val position: Int,
    val productId: String,
    val entryId: String?,
    val batch: String?,
    val time: LocalDateTime,
    val sampleQty: BigDecimal,
    val accepted: BigDecimal,
    val rejected: BigDecimal,
    val unit: String,
    val defectCategoryId: String?,
    val result: String,
    val correctiveAction: String,
    val inspectorId: String?,
    val remarks: String,
)

data class ReportWithChildren(
    @Embedded val report: ReportEntity,
    @Relation(parentColumn = "id", entityColumn = "reportId") val entries: List<EntryEntity>,
    @Relation(parentColumn = "id", entityColumn = "reportId") val materials: List<MaterialLineEntity>,
    @Relation(parentColumn = "id", entityColumn = "reportId") val downtime: List<DowntimeEntity>,
    @Relation(parentColumn = "id", entityColumn = "reportId") val inspections: List<InspectionEntity>,
)

/** Immutable JSON snapshot written on every submission. Never updated or deleted. */
@Entity(tableName = "report_versions", indices = [Index(value = ["reportId", "reportVersion"], unique = true)])
data class ReportVersionEntity(
    @PrimaryKey val id: String,
    val reportId: String,
    val reportVersion: Int,
    val snapshotJson: String,
    val createdAt: Long,
    val createdBy: String,
)

@Entity(tableName = "approval_actions", indices = [Index("reportId")])
data class ApprovalEntity(
    @PrimaryKey val id: String,
    val reportId: String,
    val action: String,
    val fromStatus: String,
    val toStatus: String,
    val actorId: String,
    val actorName: String,
    val reason: String?,
    val at: Long,
    val reportVersion: Int,
    val syncState: String = "LOCAL_ONLY",
)

// ------------------------------------------------------------------ inventory

@Entity(
    tableName = "inventory_transactions",
    indices = [Index(value = ["itemKind", "itemId", "date"]), Index(value = ["idempotencyKey"], unique = true), Index("reportId"), Index("status")],
)
data class TxnEntity(
    @PrimaryKey val id: String,
    val itemKind: String,
    val itemId: String,
    val type: String,
    val quantity: BigDecimal,
    val unit: String,
    val date: LocalDate,
    val enteredQuantity: BigDecimal?,
    val enteredUnit: String?,
    val reference: String?,
    val counterparty: String?,
    val reportId: String?,
    val entryId: String?,
    val location: String?,
    val note: String?,
    val status: String,
    val historical: Boolean,
    val origin: String,
    val sourceRef: String?,
    val negativeOverride: Boolean,
    val createdBy: String,
    val createdAt: Long,
    val approvedBy: String?,
    val approvedAt: Long?,
    val idempotencyKey: String,
    val version: Long = 0,
    val syncState: String = "LOCAL_ONLY",
    val updatedAt: Long,
)

@Entity(tableName = "stock_counts")
data class StockCountEntity(
    @PrimaryKey val id: String,
    val date: LocalDate,
    val itemKind: String,
    val status: String,
    val createdBy: String,
    val createdAt: Long,
    val note: String?,
    val version: Long = 0,
    val syncState: String = "LOCAL_ONLY",
    val updatedAt: Long,
)

@Entity(
    tableName = "stock_count_lines",
    primaryKeys = ["countId", "itemId"],
    foreignKeys = [ForeignKey(entity = StockCountEntity::class, parentColumns = ["id"], childColumns = ["countId"], onDelete = ForeignKey.CASCADE)],
)
data class StockCountLineEntity(
    val countId: String,
    val itemId: String,
    val systemQty: BigDecimal,
    val countedQty: BigDecimal?,
    val unit: String,
)

data class StockCountWithLines(
    @Embedded val count: StockCountEntity,
    @Relation(parentColumn = "id", entityColumn = "countId") val lines: List<StockCountLineEntity>,
)

// ------------------------------------------------------------------ audit, notifications, sync, import

@Entity(tableName = "audit_log", indices = [Index(value = ["entityType", "entityId"]), Index("at")])
data class AuditEntity(
    @PrimaryKey val id: String,
    val entityType: String,
    val entityId: String,
    val action: String,
    val actorId: String,
    val actorName: String,
    val at: Long,
    val summary: String,
    /** Field-level changes or context as JSON; never contains credentials. */
    val detailJson: String?,
    val deviceId: String,
    val syncState: String = "LOCAL_ONLY",
)

@Entity(tableName = "notifications", indices = [Index("createdAt")])
data class NotificationEntity(
    /** Stable alert key (e.g. "stock:FINISHED_GOOD:<id>") so the same condition is not notified twice. */
    @PrimaryKey val key: String,
    val type: String,
    val severity: String,
    val title: String,
    val message: String,
    val createdAt: Long,
    val read: Boolean,
    val resolved: Boolean,
    val reportId: String?,
    /** Drill target encoded as "kind:id" (e.g. "item:FINISHED_GOOD:p1", "day:2026-10-07"). */
    val target: String?,
)

@Entity(tableName = "sync_outbox", indices = [Index("state"), Index(value = ["entityType", "entityId"])])
data class OutboxEntity(
    @PrimaryKey val id: String,
    val entityType: String,
    val entityId: String,
    val kind: String,
    val payload: String,
    val baseVersion: Long?,
    val createdAt: Long,
    val attempts: Int,
    val state: String,
    val lastError: String?,
    val nextAttemptAt: Long?,
    val idempotencyKey: String,
)

/** A record changed on both sides; kept until a person chooses which copy wins (Settings → Sync). */
@Entity(tableName = "sync_conflicts", indices = [Index(value = ["entityType", "entityId"])])
data class ConflictEntity(
    @PrimaryKey val id: String,
    val entityType: String,
    val entityId: String,
    val serverVersion: Long,
    val serverPayload: String,
    val localPayload: String?,
    /** True when the server copy was applied automatically (approved/locked records). */
    val serverApplied: Boolean,
    val reason: String,
    val detectedAt: Long,
    val resolved: Boolean,
)

@Entity(tableName = "import_batches", indices = [Index(value = ["sha256"], unique = true)])
data class ImportBatchEntity(
    @PrimaryKey val id: String,
    val fileName: String,
    val sha256: String,
    val importedAt: Long,
    val importedBy: String,
    val firstDate: LocalDate?,
    val lastDate: LocalDate?,
    val transactions: Int,
    val reconciliations: Int,
    val issuesJson: String,
)
