package rw.plasmaco.ops.data.local

import rw.plasmaco.ops.domain.inventory.InventoryTransaction
import rw.plasmaco.ops.domain.inventory.StockCount
import rw.plasmaco.ops.domain.inventory.StockCountLine
import rw.plasmaco.ops.domain.inventory.StockCountStatus
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
import java.time.Instant

private fun unit(code: String) = UnitOfMeasure.valueOf(code)

// ---------------------------------------------------------------- users

fun UserEntity.toDomain() = User(id, username, displayName, roles.split(',').filter { it.isNotBlank() }.map { Role.valueOf(it) }.toSet(), active, pinHash)
fun User.toEntity(now: Long, lastVerified: Long?) = UserEntity(id, username, displayName, roles.joinToString(",") { it.name }, active, pinHash, lastVerified, updatedAt = now)

// ---------------------------------------------------------------- master data

fun ProductEntity.toDomain() = Product(
    id = id, code = code, name = name, dn = dn, pn = pn, sdr = sdr, materialGrade = materialGrade, kgPerMetre = kgPerMetre,
    baseUnit = unit(baseUnit),
    conversions = listOfNotNull(
        metresPerCoil?.let { UnitConversion(UnitOfMeasure.COIL, UnitOfMeasure.METRE, it) },
        metresPerPiece?.let { UnitConversion(UnitOfMeasure.PIECE, UnitOfMeasure.METRE, it) },
    ),
    active = active, note = note, legacyNames = legacyNames.split('|').filter { it.isNotBlank() },
)

fun Product.toEntity(now: Long, version: Long = 0, syncState: String = "PENDING") = ProductEntity(
    id, code, name, dn, pn, sdr, materialGrade, kgPerMetre, baseUnit.name,
    conversions.firstOrNull { it.from == UnitOfMeasure.COIL }?.factor, conversions.firstOrNull { it.from == UnitOfMeasure.PIECE }?.factor,
    active, note, legacyNames.joinToString("|"), version, syncState, now,
)

fun MaterialEntity.toDomain() = RawMaterial(id, code, name, MaterialCategory.valueOf(category), unit(baseUnit), bagWeightKg, active)
fun RawMaterial.toEntity(now: Long, version: Long = 0, syncState: String = "PENDING") = MaterialEntity(id, code, name, category.name, baseUnit.name, bagWeightKg, active, version, syncState, now)

fun LineEntity.toDomain() = ProductionLine(id, code, name, active)
fun ProductionLine.toEntity(now: Long, version: Long = 0, syncState: String = "PENDING") = LineEntity(id, code, name, active, version, syncState, now)

fun MachineEntity.toDomain() = Machine(id, code, name, lineId, active)
fun Machine.toEntity(now: Long, version: Long = 0, syncState: String = "PENDING") = MachineEntity(id, code, name, lineId, active, version, syncState, now)

fun ShiftEntity.toDomain() = Shift(id, code, name, start, end, active)
fun Shift.toEntity(now: Long, version: Long = 0, syncState: String = "PENDING") = ShiftEntity(id, code, name, start, end, active, version, syncState, now)

fun DowntimeCategoryEntity.toDomain() = DowntimeCategory(id, name, planned, active)
fun DowntimeCategory.toEntity(now: Long) = DowntimeCategoryEntity(id, name, planned, active, syncState = "PENDING", updatedAt = now)

fun DefectCategoryEntity.toDomain() = DefectCategory(id, name, active)
fun DefectCategory.toEntity(now: Long) = DefectCategoryEntity(id, name, active, syncState = "PENDING", updatedAt = now)

fun ThresholdEntity.toDomain() = StockThreshold(ItemKind.valueOf(itemKind), itemId, minimum, reorderPoint)
fun StockThreshold.toEntity(now: Long) = ThresholdEntity(itemKind.name, itemId, minimum, reorderPoint, syncState = "PENDING", updatedAt = now)

fun TargetEntity.toDomain() = ProductionTarget(id, lineId, productId, shiftId, quantity, unit(unit), validFrom, validTo)
fun ProductionTarget.toEntity(now: Long) = TargetEntity(id, lineId, productId, shiftId, quantity, unit.name, validFrom, validTo, syncState = "PENDING", updatedAt = now)

fun RunRateEntity.toDomain() = RunRate(lineId, productKey.takeIf { it != "*" }, metresPerHour)
fun RunRate.toEntity(now: Long) = RunRateEntity(lineId, productId ?: "*", metresPerHour, syncState = "PENDING", updatedAt = now)

// ---------------------------------------------------------------- reports

fun ReportWithChildren.toDomain(): ProductionReport = ProductionReport(
    id = report.id, reference = report.reference, date = report.date, shiftId = report.shiftId, lineId = report.lineId,
    operatorId = report.operatorId, supervisorId = report.supervisorId, startTime = report.startTime, endTime = report.endTime,
    status = ReportStatus.valueOf(report.status), version = report.reportVersion, createdBy = report.createdBy,
    createdAt = Instant.ofEpochMilli(report.createdAt), updatedAt = Instant.ofEpochMilli(report.updatedAt),
    submittedAt = report.submittedAt?.let(Instant::ofEpochMilli), decidedAt = report.decidedAt?.let(Instant::ofEpochMilli), decidedBy = report.decidedBy,
    remarks = report.remarks,
    entries = entries.sortedBy { it.position }.map { ProductionEntry(it.id, it.productId, it.machineId, it.target, it.gross, it.good, it.rejected, unit(it.unit), it.batch, it.start, it.end, it.remarks) },
    materials = materials.sortedBy { it.position }.map { MaterialConsumption(it.id, it.materialId, it.issued, it.returned, it.lineOpening, it.lineClosing, it.scrap, unit(it.unit), it.batch, it.remarks) },
    downtime = downtime.sortedBy { it.position }.map { DowntimeEvent(it.id, it.machineId, it.start, it.end, it.categoryId, it.reason, it.correctiveAction, it.remarks) },
    inspections = inspections.sortedBy { it.position }.map {
        QualityInspection(it.id, it.productId, it.entryId, it.batch, it.time, it.sampleQty, it.accepted, it.rejected, unit(it.unit), it.defectCategoryId, InspectionResult.valueOf(it.result), it.correctiveAction, it.inspectorId, it.remarks)
    },
    handover = report.handover.let { h ->
        ShiftHandover(h.outstandingProduction, h.machineCondition, h.pendingMaintenance, h.materialShortages, h.qualityIssues, h.safetyIncidents, h.instructions).takeIf { !it.isEmpty }
    },
    duplicatedFrom = report.duplicatedFrom,
)

fun ProductionReport.toEntity(version: Long, syncState: String): ReportWithChildren = ReportWithChildren(
    report = ReportEntity(
        id, reference, date, shiftId, lineId, operatorId, supervisorId, startTime, endTime, status.name, this.version, createdBy,
        createdAt.toEpochMilli(), submittedAt?.toEpochMilli(), decidedAt?.toEpochMilli(), decidedBy, remarks, duplicatedFrom,
        handover?.let { HandoverColumns(it.outstandingProduction, it.machineCondition, it.pendingMaintenance, it.materialShortages, it.qualityIssues, it.safetyIncidents, it.instructions) } ?: HandoverColumns(),
        version, syncState, updatedAt.toEpochMilli(),
    ),
    entries = entries.mapIndexed { i, e -> EntryEntity(e.id, id, i, e.productId, e.machineId, e.target, e.gross, e.good, e.rejected, e.unit.name, e.batch, e.start, e.end, e.remarks) },
    materials = materials.mapIndexed { i, m -> MaterialLineEntity(m.id, id, i, m.materialId, m.issued, m.returned, m.lineOpening, m.lineClosing, m.scrap, m.unit.name, m.batch, m.remarks) },
    downtime = downtime.mapIndexed { i, d -> DowntimeEntity(d.id, id, i, d.machineId, d.start, d.end, d.categoryId, d.reason, d.correctiveAction, d.remarks) },
    inspections = inspections.mapIndexed { i, q ->
        InspectionEntity(q.id, id, i, q.productId, q.entryId, q.batch, q.time, q.sampleQty, q.accepted, q.rejected, q.unit.name, q.defectCategoryId, q.result.name, q.correctiveAction, q.inspectorId, q.remarks)
    },
)

fun ApprovalEntity.toDomain() = ApprovalRecord(id, reportId, ReportAction.valueOf(action), ReportStatus.valueOf(fromStatus), ReportStatus.valueOf(toStatus), actorId, actorName, reason, Instant.ofEpochMilli(at), reportVersion)
fun ApprovalRecord.toEntity() = ApprovalEntity(id, reportId, action.name, fromStatus.name, toStatus.name, actorId, actorName, reason, at.toEpochMilli(), version, "PENDING")

// ---------------------------------------------------------------- inventory

fun TxnEntity.toDomain() = InventoryTransaction(
    id, ItemKind.valueOf(itemKind), itemId, TxnType.valueOf(type), quantity, unit(unit), date, enteredQuantity, enteredUnit?.let(::unit),
    reference, counterparty, reportId, entryId, location, note, TxnStatus.valueOf(status), historical, TxnOrigin.valueOf(origin), sourceRef,
    negativeOverride, createdBy, Instant.ofEpochMilli(createdAt), approvedBy, approvedAt?.let(Instant::ofEpochMilli), idempotencyKey,
)

fun InventoryTransaction.toEntity(now: Long, version: Long = 0, syncState: String = "PENDING") = TxnEntity(
    id, itemKind.name, itemId, type.name, quantity, unit.name, date, enteredQuantity, enteredUnit?.name, reference, counterparty, reportId,
    entryId, location, note, status.name, historical, origin.name, sourceRef, negativeOverride, createdBy, createdAt.toEpochMilli(),
    approvedBy, approvedAt?.toEpochMilli(), idempotencyKey, version, syncState, now,
)

fun StockCountWithLines.toDomain() = StockCount(
    count.id, count.date, ItemKind.valueOf(count.itemKind),
    lines.map { StockCountLine(it.itemId, it.systemQty, it.countedQty, unit(it.unit)) },
    StockCountStatus.valueOf(count.status), count.createdBy, Instant.ofEpochMilli(count.createdAt), count.note,
)

fun StockCount.toEntity(now: Long) = StockCountWithLines(
    StockCountEntity(id, date, itemKind.name, status.name, createdBy, createdAt.toEpochMilli(), note, syncState = "PENDING", updatedAt = now),
    lines.map { StockCountLineEntity(id, it.itemId, it.systemQty, it.countedQty, it.unit.name) },
)
