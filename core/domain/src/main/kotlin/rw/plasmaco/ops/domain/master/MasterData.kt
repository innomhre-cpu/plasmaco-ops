package rw.plasmaco.ops.domain.master

import rw.plasmaco.ops.domain.common.Id
import rw.plasmaco.ops.domain.units.UnitConversion
import rw.plasmaco.ops.domain.units.UnitOfMeasure
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime

/** Finished product: an HDPE pipe identified by nominal diameter (DN, mm) and pressure class (PN, bar). */
data class Product(
    val id: Id,
    val code: String,
    val name: String,
    val dn: Int?,
    val pn: BigDecimal?,
    val sdr: BigDecimal?,
    val materialGrade: String = "PE100",
    /** Theoretical mass per metre (kg/m). Source of truth is the ISO 4427-2 sheet unless overridden. */
    val kgPerMetre: BigDecimal?,
    val baseUnit: UnitOfMeasure = UnitOfMeasure.METRE,
    val conversions: List<UnitConversion> = emptyList(),
    val active: Boolean = true,
    /** Free-text note, e.g. why kg/m differs from the standard. */
    val note: String? = null,
    val legacyNames: List<String> = emptyList(),
)

/** One row of the ISO 4427-2 dimension table kept in the PLASMACO workbook ("Rs iso 4427-2"). */
data class PipeSpec(
    val dn: Int,
    val pn: BigDecimal,
    val sdr: BigDecimal,
    val meanOdMin: BigDecimal,
    val meanOdMax: BigDecimal,
    val ovalityMax: BigDecimal,
    val wallMin: BigDecimal?,
    val wallMax: BigDecimal?,
    val kgPerMetre: BigDecimal?,
)

enum class MaterialCategory { RESIN, MASTERBATCH, ADDITIVE, REGRIND, PACKAGING, OTHER }

data class RawMaterial(
    val id: Id,
    val code: String,
    val name: String,
    val category: MaterialCategory,
    val baseUnit: UnitOfMeasure = UnitOfMeasure.KILOGRAM,
    /** kg per bag, when deliveries are counted in bags (the workbook types receipts as bags x 25). */
    val bagWeightKg: BigDecimal? = null,
    val active: Boolean = true,
) {
    val conversions: List<UnitConversion>
        get() = listOfNotNull(bagWeightKg?.let { UnitConversion(UnitOfMeasure.BAG, UnitOfMeasure.KILOGRAM, it) }) +
            listOf(UnitConversion(UnitOfMeasure.TONNE, UnitOfMeasure.KILOGRAM, BigDecimal(1000)))
}

data class ProductionLine(val id: Id, val code: String, val name: String, val active: Boolean = true)

data class Machine(val id: Id, val code: String, val name: String, val lineId: Id, val active: Boolean = true)

/** A shift. [end] earlier than [start] means the shift crosses midnight (e.g. 22:00–06:00). */
data class Shift(val id: Id, val code: String, val name: String, val start: LocalTime, val end: LocalTime, val active: Boolean = true) {
    val crossesMidnight: Boolean get() = !end.isAfter(start)
    val duration: Duration
        get() = if (crossesMidnight) Duration.between(start, LocalTime.MAX).plusNanos(1).plus(Duration.between(LocalTime.MIDNIGHT, end))
        else Duration.between(start, end)
}

/** [planned] downtime (breaks, planned maintenance) reduces planned production time instead of availability. */
data class DowntimeCategory(val id: Id, val name: String, val planned: Boolean = false, val active: Boolean = true)

data class DefectCategory(val id: Id, val name: String, val active: Boolean = true)

enum class ItemKind { FINISHED_GOOD, RAW_MATERIAL }

/** Minimum stock and optional reorder point, in the item's base unit. */
data class StockThreshold(val itemKind: ItemKind, val itemId: Id, val minimum: BigDecimal?, val reorderPoint: BigDecimal?)

/** Planned output for a line (optionally per product and shift), valid over a date window. */
data class ProductionTarget(
    val id: Id,
    val lineId: Id,
    val productId: Id?,
    val shiftId: Id?,
    val quantity: BigDecimal,
    val unit: UnitOfMeasure,
    val validFrom: LocalDate,
    val validTo: LocalDate?,
) {
    fun appliesTo(date: LocalDate, lineId: Id, productId: Id?, shiftId: Id?): Boolean =
        this.lineId == lineId &&
            (this.productId == null || this.productId == productId) &&
            (this.shiftId == null || this.shiftId == shiftId) &&
            !date.isBefore(validFrom) && (validTo == null || !date.isAfter(validTo))

    /** More specific targets win: product+shift > product > shift > line. */
    val specificity: Int get() = (if (productId != null) 2 else 0) + (if (shiftId != null) 1 else 0)
}

object Targets {
    fun resolve(targets: List<ProductionTarget>, date: LocalDate, lineId: Id, productId: Id?, shiftId: Id?): ProductionTarget? =
        targets.filter { it.appliesTo(date, lineId, productId, shiftId) }
            .sortedWith(compareByDescending<ProductionTarget> { it.specificity }.thenByDescending { it.validFrom })
            .firstOrNull()
}

/** Ideal output rate for performance/OEE. Without one, performance is reported as "Insufficient data". */
data class RunRate(val lineId: Id, val productId: Id?, val metresPerHour: BigDecimal)

/** Snapshot of all master data, passed to pure business functions. */
data class MasterData(
    val products: List<Product> = emptyList(),
    val materials: List<RawMaterial> = emptyList(),
    val lines: List<ProductionLine> = emptyList(),
    val machines: List<Machine> = emptyList(),
    val shifts: List<Shift> = emptyList(),
    val downtimeCategories: List<DowntimeCategory> = emptyList(),
    val defectCategories: List<DefectCategory> = emptyList(),
    val thresholds: List<StockThreshold> = emptyList(),
    val targets: List<ProductionTarget> = emptyList(),
    val runRates: List<RunRate> = emptyList(),
) {
    private val productIndex by lazy { products.associateBy { it.id } }
    private val materialIndex by lazy { materials.associateBy { it.id } }
    fun product(id: Id): Product? = productIndex[id]
    fun material(id: Id): RawMaterial? = materialIndex[id]
    fun line(id: Id): ProductionLine? = lines.firstOrNull { it.id == id }
    fun machine(id: Id): Machine? = machines.firstOrNull { it.id == id }
    fun shift(id: Id): Shift? = shifts.firstOrNull { it.id == id }
    fun downtimeCategory(id: Id): DowntimeCategory? = downtimeCategories.firstOrNull { it.id == id }
    fun threshold(kind: ItemKind, itemId: Id): StockThreshold? = thresholds.firstOrNull { it.itemKind == kind && it.itemId == itemId }
    fun runRate(lineId: Id, productId: Id?): RunRate? =
        runRates.firstOrNull { it.lineId == lineId && it.productId == productId } ?: runRates.firstOrNull { it.lineId == lineId && it.productId == null }

    fun itemName(kind: ItemKind, id: Id): String = when (kind) {
        ItemKind.FINISHED_GOOD -> product(id)?.name
        ItemKind.RAW_MATERIAL -> material(id)?.name
    } ?: "Unknown item"

    fun itemUnit(kind: ItemKind, id: Id): UnitOfMeasure? = when (kind) {
        ItemKind.FINISHED_GOOD -> product(id)?.baseUnit
        ItemKind.RAW_MATERIAL -> material(id)?.baseUnit
    }

    fun itemConversions(kind: ItemKind, id: Id): List<UnitConversion> = when (kind) {
        ItemKind.FINISHED_GOOD -> product(id)?.conversions.orEmpty()
        ItemKind.RAW_MATERIAL -> material(id)?.conversions.orEmpty()
    }

    /** Readiness for the first production report: what an administrator still has to configure. */
    fun setupGaps(): List<String> = buildList {
        if (products.none { it.active }) add("No active products")
        if (lines.none { it.active }) add("No production lines")
        if (shifts.none { it.active }) add("No shifts")
        if (materials.none { it.active }) add("No raw materials")
    }
}
