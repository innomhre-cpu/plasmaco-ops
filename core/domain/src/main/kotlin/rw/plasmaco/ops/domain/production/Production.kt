package rw.plasmaco.ops.domain.production

import rw.plasmaco.ops.domain.common.Id
import rw.plasmaco.ops.domain.units.UnitOfMeasure
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

enum class ReportStatus(val label: String) {
    DRAFT("Draft"),
    SUBMITTED("Submitted"),
    RETURNED("Returned for correction"),
    APPROVED("Approved"),
    REJECTED("Rejected"),
    REOPENED("Reopened");

    val editable: Boolean get() = this == DRAFT || this == RETURNED || this == REOPENED
}

/** A daily production report for one line and shift. Quantities are what happened on the line; stock moves only through store transactions. */
data class ProductionReport(
    val id: Id,
    val reference: String,
    val date: LocalDate,
    val shiftId: Id,
    val lineId: Id,
    val operatorId: Id,
    val supervisorId: Id? = null,
    val startTime: LocalTime? = null,
    val endTime: LocalTime? = null,
    val status: ReportStatus = ReportStatus.DRAFT,
    /** Number of submissions so far; each submission stores an immutable snapshot. */
    val version: Int = 0,
    val createdBy: Id,
    val createdAt: Instant,
    val updatedAt: Instant = createdAt,
    val submittedAt: Instant? = null,
    val decidedAt: Instant? = null,
    val decidedBy: Id? = null,
    val remarks: String = "",
    val entries: List<ProductionEntry> = emptyList(),
    val materials: List<MaterialConsumption> = emptyList(),
    val downtime: List<DowntimeEvent> = emptyList(),
    val inspections: List<QualityInspection> = emptyList(),
    val handover: ShiftHandover? = null,
    val duplicatedFrom: Id? = null,
) {
    /** Report window as date-times; an end at or before the start crosses midnight. */
    val window: Pair<LocalDateTime, LocalDateTime>?
        get() {
            val s = startTime ?: return null
            val e = endTime ?: return null
            val start = date.atTime(s)
            val end = if (!e.isAfter(s)) date.plusDays(1).atTime(e) else date.atTime(e)
            return start to end
        }

    val plannedDuration: Duration? get() = window?.let { Duration.between(it.first, it.second) }
}

data class ProductionEntry(
    val id: Id,
    val productId: Id,
    val machineId: Id? = null,
    /** Target for this entry in [unit]; null = no target set. */
    val target: BigDecimal? = null,
    val gross: BigDecimal,
    val good: BigDecimal,
    val rejected: BigDecimal,
    val unit: UnitOfMeasure = UnitOfMeasure.METRE,
    val batch: String? = null,
    val start: LocalDateTime? = null,
    val end: LocalDateTime? = null,
    val remarks: String = "",
)

/**
 * Material drawn for the report. Consumption = issued − returned + line-side opening − line-side closing.
 * Scrap is part of consumption (purge, start-up waste, lumps), never an extra deduction, so nothing is counted twice.
 */
data class MaterialConsumption(
    val id: Id,
    val materialId: Id,
    val issued: BigDecimal,
    val returned: BigDecimal = BigDecimal.ZERO,
    val lineOpening: BigDecimal? = null,
    val lineClosing: BigDecimal? = null,
    val scrap: BigDecimal = BigDecimal.ZERO,
    val unit: UnitOfMeasure = UnitOfMeasure.KILOGRAM,
    val batch: String? = null,
    val remarks: String = "",
) {
    val consumed: BigDecimal get() = issued - returned + (lineOpening ?: BigDecimal.ZERO) - (lineClosing ?: BigDecimal.ZERO)
    val netIssuedFromStore: BigDecimal get() = issued - returned
}

data class DowntimeEvent(
    val id: Id,
    val machineId: Id? = null,
    val start: LocalDateTime,
    val end: LocalDateTime? = null,
    val categoryId: Id? = null,
    val reason: String = "",
    val correctiveAction: String = "",
    val remarks: String = "",
) {
    /** Duration in minutes, or null while the stoppage is still open. Works across midnight because both ends carry dates. */
    val minutes: Long? get() = end?.let { Duration.between(start, it).toMinutes() }
}

enum class InspectionResult(val label: String) { PASS("Pass"), FAIL("Fail"), CONDITIONAL("Conditional release") }

data class QualityInspection(
    val id: Id,
    val productId: Id,
    val entryId: Id? = null,
    val batch: String? = null,
    val time: LocalDateTime,
    val sampleQty: BigDecimal,
    val accepted: BigDecimal,
    val rejected: BigDecimal,
    val unit: UnitOfMeasure = UnitOfMeasure.METRE,
    val defectCategoryId: Id? = null,
    val result: InspectionResult,
    val correctiveAction: String = "",
    val inspectorId: Id? = null,
    val remarks: String = "",
)

data class ShiftHandover(
    val outstandingProduction: String = "",
    val machineCondition: String = "",
    val pendingMaintenance: String = "",
    val materialShortages: String = "",
    val qualityIssues: String = "",
    val safetyIncidents: String = "",
    val instructions: String = "",
) {
    val isEmpty: Boolean
        get() = listOf(outstandingProduction, machineCondition, pendingMaintenance, materialShortages, qualityIssues, safetyIncidents, instructions).all { it.isBlank() }
}

object ReportReferences {
    /**
     * Human-readable unique reference: PR-<yyyyMMdd>-<line>-<shift>-<n>. The sequence [n] is the count of
     * existing reports for the same date, line and shift plus one, so a legitimate second report (after a
     * rejection) still gets a unique reference.
     */
    fun next(date: LocalDate, lineCode: String, shiftCode: String, existingReferences: Collection<String>): String {
        val prefix = "PR-%04d%02d%02d-%s-%s-".format(date.year, date.monthValue, date.dayOfMonth, clean(lineCode), clean(shiftCode))
        val used = existingReferences.filter { it.startsWith(prefix) }.mapNotNull { it.removePrefix(prefix).toIntOrNull() }
        return prefix + ((used.maxOrNull() ?: 0) + 1)
    }

    private fun clean(code: String) = code.uppercase().replace(Regex("[^A-Z0-9]"), "").ifEmpty { "X" }
}
