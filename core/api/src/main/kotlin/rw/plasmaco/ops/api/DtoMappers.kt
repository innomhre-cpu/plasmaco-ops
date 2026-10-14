package rw.plasmaco.ops.api

import rw.plasmaco.ops.domain.inventory.InventoryTransaction
import rw.plasmaco.ops.domain.inventory.TxnOrigin
import rw.plasmaco.ops.domain.inventory.TxnStatus
import rw.plasmaco.ops.domain.inventory.TxnType
import rw.plasmaco.ops.domain.master.DefectCategory
import rw.plasmaco.ops.domain.master.DowntimeCategory
import rw.plasmaco.ops.domain.master.ItemKind
import rw.plasmaco.ops.domain.master.Machine
import rw.plasmaco.ops.domain.master.MaterialCategory
import rw.plasmaco.ops.domain.master.Product
import rw.plasmaco.ops.domain.master.ProductionLine
import rw.plasmaco.ops.domain.master.ProductionTarget
import rw.plasmaco.ops.domain.master.RawMaterial
import rw.plasmaco.ops.domain.master.RunRate
import rw.plasmaco.ops.domain.master.Shift
import rw.plasmaco.ops.domain.master.StockThreshold
import rw.plasmaco.ops.domain.production.DowntimeEvent
import rw.plasmaco.ops.domain.production.InspectionResult
import rw.plasmaco.ops.domain.production.MaterialConsumption
import rw.plasmaco.ops.domain.production.ProductionEntry
import rw.plasmaco.ops.domain.production.ProductionReport
import rw.plasmaco.ops.domain.production.QualityInspection
import rw.plasmaco.ops.domain.production.ReportStatus
import rw.plasmaco.ops.domain.production.ShiftHandover
import rw.plasmaco.ops.domain.security.Role
import rw.plasmaco.ops.domain.security.User
import rw.plasmaco.ops.domain.units.UnitConversion
import rw.plasmaco.ops.domain.units.UnitOfMeasure
import rw.plasmaco.ops.domain.workflow.ApprovalRecord
import rw.plasmaco.ops.domain.workflow.ReportAction
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

private fun d(s: String?) = s?.let(::BigDecimal)
private fun BigDecimal.s() = toPlainString()

fun User.toDto(version: Long = 0) = UserDto(id, username, displayName, roles.map { it.name }, active, version)
fun UserDto.toDomain() = User(id, username, displayName, roles.mapNotNull { r -> Role.entries.firstOrNull { it.name == r } }.toSet(), active)

fun Product.toDto(version: Long = 0) = ProductDto(
    id, code, name, dn, pn?.s(), sdr?.s(), materialGrade, kgPerMetre?.s(), baseUnit.name,
    conversions.firstOrNull { it.from == UnitOfMeasure.COIL }?.factor?.s(), conversions.firstOrNull { it.from == UnitOfMeasure.PIECE }?.factor?.s(),
    active, note, legacyNames, version,
)

fun ProductDto.toDomain() = Product(
    id, code, name, dn, d(pn), d(sdr), materialGrade, d(kgPerMetre), UnitOfMeasure.valueOf(baseUnit),
    listOfNotNull(d(metresPerCoil)?.let { UnitConversion(UnitOfMeasure.COIL, UnitOfMeasure.METRE, it) }, d(metresPerPiece)?.let { UnitConversion(UnitOfMeasure.PIECE, UnitOfMeasure.METRE, it) }),
    active, note, legacyNames,
)

fun RawMaterial.toDto(version: Long = 0) = MaterialDto(id, code, name, category.name, baseUnit.name, bagWeightKg?.s(), active, version)
fun MaterialDto.toDomain() = RawMaterial(id, code, name, MaterialCategory.valueOf(category), UnitOfMeasure.valueOf(baseUnit), d(bagWeightKg), active)
fun ProductionLine.toDto(version: Long = 0) = LineDto(id, code, name, active, version)
fun LineDto.toDomain() = ProductionLine(id, code, name, active)
fun Machine.toDto(version: Long = 0) = MachineDto(id, code, name, lineId, active, version)
fun MachineDto.toDomain() = Machine(id, code, name, lineId, active)
fun Shift.toDto(version: Long = 0) = ShiftDto(id, code, name, start.toString(), end.toString(), active, version)
fun ShiftDto.toDomain() = Shift(id, code, name, LocalTime.parse(start), LocalTime.parse(end), active)
fun DowntimeCategory.toDto(version: Long = 0) = CategoryDto(id, name, planned, active, version)
fun CategoryDto.toDowntimeCategory() = DowntimeCategory(id, name, planned, active)
fun DefectCategory.toDto(version: Long = 0) = CategoryDto(id, name, false, active, version)
fun CategoryDto.toDefectCategory() = DefectCategory(id, name, active)
fun StockThreshold.toDto(version: Long = 0) = ThresholdDto(itemKind.name, itemId, minimum?.s(), reorderPoint?.s(), version)
fun ThresholdDto.toDomain() = StockThreshold(ItemKind.valueOf(itemKind), itemId, d(minimum), d(reorderPoint))
fun ProductionTarget.toDto(version: Long = 0) = TargetDto(id, lineId, productId, shiftId, quantity.s(), unit.name, validFrom.toString(), validTo?.toString(), version)
fun TargetDto.toDomain() = ProductionTarget(id, lineId, productId, shiftId, BigDecimal(quantity), UnitOfMeasure.valueOf(unit), LocalDate.parse(validFrom), validTo?.let(LocalDate::parse))
fun RunRate.toDto(version: Long = 0) = RunRateDto(lineId, productId, metresPerHour.s(), version)
fun RunRateDto.toDomain() = RunRate(lineId, productId, BigDecimal(metresPerHour))

fun ProductionReport.toDto(version: Long = 0) = ReportDto(
    id, reference, date.toString(), shiftId, lineId, operatorId, supervisorId, startTime?.toString(), endTime?.toString(), status.name, this.version,
    createdBy, createdAt.toEpochMilli(), updatedAt.toEpochMilli(), submittedAt?.toEpochMilli(), decidedAt?.toEpochMilli(), decidedBy, remarks,
    entries.map { EntryDto(it.id, it.productId, it.machineId, it.target?.s(), it.gross.s(), it.good.s(), it.rejected.s(), it.unit.name, it.batch, it.start?.toString(), it.end?.toString(), it.remarks) },
    materials.map { MaterialLineDto(it.id, it.materialId, it.issued.s(), it.returned.s(), it.lineOpening?.s(), it.lineClosing?.s(), it.scrap.s(), it.unit.name, it.batch, it.remarks) },
    downtime.map { DowntimeDto(it.id, it.machineId, it.start.toString(), it.end?.toString(), it.categoryId, it.reason, it.correctiveAction, it.remarks) },
    inspections.map {
        InspectionDto(it.id, it.productId, it.entryId, it.batch, it.time.toString(), it.sampleQty.s(), it.accepted.s(), it.rejected.s(), it.unit.name, it.defectCategoryId, it.result.name, it.correctiveAction, it.inspectorId, it.remarks)
    },
    handover?.let { HandoverDto(it.outstandingProduction, it.machineCondition, it.pendingMaintenance, it.materialShortages, it.qualityIssues, it.safetyIncidents, it.instructions) },
    duplicatedFrom, version,
)

fun ReportDto.toDomain() = ProductionReport(
    id = id, reference = reference, date = LocalDate.parse(date), shiftId = shiftId, lineId = lineId, operatorId = operatorId, supervisorId = supervisorId,
    startTime = startTime?.let(LocalTime::parse), endTime = endTime?.let(LocalTime::parse), status = ReportStatus.valueOf(status), version = reportVersion,
    createdBy = createdBy, createdAt = Instant.ofEpochMilli(createdAt), updatedAt = Instant.ofEpochMilli(updatedAt),
    submittedAt = submittedAt?.let(Instant::ofEpochMilli), decidedAt = decidedAt?.let(Instant::ofEpochMilli), decidedBy = decidedBy, remarks = remarks,
    entries = entries.map { ProductionEntry(it.id, it.productId, it.machineId, d(it.target), BigDecimal(it.gross), BigDecimal(it.good), BigDecimal(it.rejected), UnitOfMeasure.valueOf(it.unit), it.batch, it.start?.let(LocalDateTime::parse), it.end?.let(LocalDateTime::parse), it.remarks) },
    materials = materials.map { MaterialConsumption(it.id, it.materialId, BigDecimal(it.issued), BigDecimal(it.returned), d(it.lineOpening), d(it.lineClosing), BigDecimal(it.scrap), UnitOfMeasure.valueOf(it.unit), it.batch, it.remarks) },
    downtime = downtime.map { DowntimeEvent(it.id, it.machineId, LocalDateTime.parse(it.start), it.end?.let(LocalDateTime::parse), it.categoryId, it.reason, it.correctiveAction, it.remarks) },
    inspections = inspections.map {
        QualityInspection(it.id, it.productId, it.entryId, it.batch, LocalDateTime.parse(it.time), BigDecimal(it.sampleQty), BigDecimal(it.accepted), BigDecimal(it.rejected), UnitOfMeasure.valueOf(it.unit), it.defectCategoryId, InspectionResult.valueOf(it.result), it.correctiveAction, it.inspectorId, it.remarks)
    },
    handover = handover?.let { ShiftHandover(it.outstandingProduction, it.machineCondition, it.pendingMaintenance, it.materialShortages, it.qualityIssues, it.safetyIncidents, it.instructions) },
    duplicatedFrom = duplicatedFrom,
)

fun ApprovalRecord.toDto() = ApprovalDto(id, reportId, action.name, fromStatus.name, toStatus.name, actorId, actorName, reason, at.toEpochMilli(), version)
fun ApprovalDto.toDomain() = ApprovalRecord(id, reportId, ReportAction.valueOf(action), ReportStatus.valueOf(fromStatus), ReportStatus.valueOf(toStatus), actorId, actorName, reason, Instant.ofEpochMilli(at), reportVersion)

fun InventoryTransaction.toDto(version: Long = 0) = TxnDto(
    id, itemKind.name, itemId, type.name, quantity.s(), unit.name, date.toString(), enteredQuantity?.s(), enteredUnit?.name, reference, counterparty,
    reportId, entryId, location, note, status.name, historical, origin.name, sourceRef, negativeOverride, createdBy, createdAt.toEpochMilli(),
    approvedBy, approvedAt?.toEpochMilli(), idempotencyKey, version,
)

fun TxnDto.toDomain() = InventoryTransaction(
    id, ItemKind.valueOf(itemKind), itemId, TxnType.valueOf(type), BigDecimal(quantity), UnitOfMeasure.valueOf(unit), LocalDate.parse(date),
    d(enteredQuantity), enteredUnit?.let(UnitOfMeasure::valueOf), reference, counterparty, reportId, entryId, location, note,
    TxnStatus.valueOf(status), historical, TxnOrigin.valueOf(origin), sourceRef, negativeOverride, createdBy, Instant.ofEpochMilli(createdAt),
    approvedBy, approvedAt?.let(Instant::ofEpochMilli), idempotencyKey,
)
