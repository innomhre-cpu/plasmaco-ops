package rw.plasmaco.ops.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/*
 * Wire format for /api/v1 (docs/server.md). Decimals, dates and times travel as strings so no value is ever
 * rounded through a binary double. The same DTOs serialise report snapshots and outbox payloads.
 */

@Serializable data class LoginRequest(val username: String, val password: String, val deviceId: String)
@Serializable data class LoginResponse(val token: String, val expiresAt: String, val user: UserDto)
@Serializable data class UserDto(val id: String, val username: String, val displayName: String, val roles: List<String>, val active: Boolean, val version: Long = 0)

/** Administrator creates or edits an account on the server; [password] is required for a new account, optional otherwise. */
@Serializable data class SaveUserRequest(val user: UserDto, val password: String? = null)

@Serializable
data class ProductDto(
    val id: String, val code: String, val name: String, val dn: Int?, val pn: String?, val sdr: String?, val materialGrade: String,
    val kgPerMetre: String?, val baseUnit: String, val metresPerCoil: String?, val metresPerPiece: String?, val active: Boolean,
    val note: String?, val legacyNames: List<String> = emptyList(), val version: Long = 0,
)

@Serializable data class MaterialDto(val id: String, val code: String, val name: String, val category: String, val baseUnit: String, val bagWeightKg: String?, val active: Boolean, val version: Long = 0)
@Serializable data class LineDto(val id: String, val code: String, val name: String, val active: Boolean, val version: Long = 0)
@Serializable data class MachineDto(val id: String, val code: String, val name: String, val lineId: String, val active: Boolean, val version: Long = 0)
@Serializable data class ShiftDto(val id: String, val code: String, val name: String, val start: String, val end: String, val active: Boolean, val version: Long = 0)
@Serializable data class CategoryDto(val id: String, val name: String, val planned: Boolean = false, val active: Boolean, val version: Long = 0)
@Serializable data class ThresholdDto(val itemKind: String, val itemId: String, val minimum: String?, val reorderPoint: String?, val version: Long = 0)
@Serializable data class TargetDto(val id: String, val lineId: String, val productId: String?, val shiftId: String?, val quantity: String, val unit: String, val validFrom: String, val validTo: String?, val version: Long = 0)
@Serializable data class RunRateDto(val lineId: String, val productId: String?, val metresPerHour: String, val version: Long = 0)

@Serializable
data class EntryDto(
    val id: String, val productId: String, val machineId: String?, val target: String?, val gross: String, val good: String, val rejected: String,
    val unit: String, val batch: String?, val start: String?, val end: String?, val remarks: String,
)

@Serializable
data class MaterialLineDto(
    val id: String, val materialId: String, val issued: String, val returned: String, val lineOpening: String?, val lineClosing: String?,
    val scrap: String, val unit: String, val batch: String?, val remarks: String,
)

@Serializable data class DowntimeDto(val id: String, val machineId: String?, val start: String, val end: String?, val categoryId: String?, val reason: String, val correctiveAction: String, val remarks: String)

@Serializable
data class InspectionDto(
    val id: String, val productId: String, val entryId: String?, val batch: String?, val time: String, val sampleQty: String, val accepted: String,
    val rejected: String, val unit: String, val defectCategoryId: String?, val result: String, val correctiveAction: String, val inspectorId: String?, val remarks: String,
)

@Serializable
data class HandoverDto(
    val outstandingProduction: String = "", val machineCondition: String = "", val pendingMaintenance: String = "", val materialShortages: String = "",
    val qualityIssues: String = "", val safetyIncidents: String = "", val instructions: String = "",
)

@Serializable
data class ReportDto(
    val id: String, val reference: String, val date: String, val shiftId: String, val lineId: String, val operatorId: String,
    val supervisorId: String?, val startTime: String?, val endTime: String?, val status: String, val reportVersion: Int,
    val createdBy: String, val createdAt: Long, val updatedAt: Long, val submittedAt: Long?, val decidedAt: Long?, val decidedBy: String?,
    val remarks: String, val entries: List<EntryDto>, val materials: List<MaterialLineDto>, val downtime: List<DowntimeDto>,
    val inspections: List<InspectionDto>, val handover: HandoverDto?, val duplicatedFrom: String?, val version: Long = 0,
)

@Serializable data class ApprovalDto(val id: String, val reportId: String, val action: String, val fromStatus: String, val toStatus: String, val actorId: String, val actorName: String, val reason: String?, val at: Long, val reportVersion: Int)

@Serializable
data class TxnDto(
    val id: String, val itemKind: String, val itemId: String, val type: String, val quantity: String, val unit: String, val date: String,
    val enteredQuantity: String?, val enteredUnit: String?, val reference: String?, val counterparty: String?, val reportId: String?,
    val entryId: String?, val location: String?, val note: String?, val status: String, val historical: Boolean, val origin: String,
    val sourceRef: String?, val negativeOverride: Boolean, val createdBy: String, val createdAt: Long, val approvedBy: String?,
    val approvedAt: Long?, val idempotencyKey: String, val version: Long = 0,
)

@Serializable data class AuditDto(val id: String, val entityType: String, val entityId: String, val action: String, val actorId: String, val actorName: String, val at: Long, val summary: String, val detailJson: String?, val deviceId: String)

/** A workflow action sent as its own operation so the server re-checks permissions and state. */
@Serializable data class ReportActionDto(val reportId: String, val action: String, val reason: String?, val actorId: String, val at: Long, val approvalId: String, val snapshot: ReportDto?)

// ---------------------------------------------------------------- sync envelope

@Serializable
data class PushOperation(
    val id: String, val idempotencyKey: String, val entityType: String, val entityId: String, val kind: String,
    val baseVersion: Long?, val payload: JsonElement,
)

@Serializable data class PushRequest(val deviceId: String, val operations: List<PushOperation>)

/** status: APPLIED | DUPLICATE | CONFLICT | REJECTED. A CONFLICT carries the server copy for side-by-side review. */
@Serializable data class PushResult(val id: String, val status: String, val version: Long? = null, val message: String? = null, val serverCopy: JsonElement? = null)

@Serializable data class PushResponse(val results: List<PushResult>)

@Serializable data class Change(val entityType: String, val entityId: String, val version: Long, val deleted: Boolean = false, val payload: JsonElement? = null)

@Serializable data class PullResponse(val cursor: String, val hasMore: Boolean, val changes: List<Change>)

@Serializable data class ApiError(val code: String, val message: String, val details: List<String> = emptyList())
