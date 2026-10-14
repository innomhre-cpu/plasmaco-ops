package rw.plasmaco.ops.domain.units

import java.math.BigDecimal
import java.math.RoundingMode

/** Physical dimension of a unit. Quantities of different dimensions can never be added. */
enum class Dimension { LENGTH, MASS, COUNT }

/**
 * Units of measure. METRE and KILOGRAM are the base units the legacy workbook used
 * ("Inward Qty mtr", raw material in kg). COIL, PIECE and BAG are counted packaging units that
 * convert to a base unit only through a factor configured per item.
 */
enum class UnitOfMeasure(val code: String, val label: String, val dimension: Dimension, val displayDecimals: Int) {
    METRE("m", "metres", Dimension.LENGTH, 1),
    KILOGRAM("kg", "kilograms", Dimension.MASS, 2),
    TONNE("t", "tonnes", Dimension.MASS, 3),
    PIECE("pcs", "pieces", Dimension.COUNT, 0),
    COIL("coil", "coils", Dimension.COUNT, 0),
    BAG("bag", "bags", Dimension.COUNT, 0);

    companion object {
        fun fromCode(code: String): UnitOfMeasure? = entries.firstOrNull { it.code.equals(code, ignoreCase = true) || it.name.equals(code, ignoreCase = true) }
    }
}

/** Exact quantity with its unit. Arithmetic refuses mismatched units instead of guessing. */
data class Quantity(val value: BigDecimal, val unit: UnitOfMeasure) : Comparable<Quantity> {
    operator fun plus(other: Quantity): Quantity {
        requireSameUnit(other); return Quantity(value + other.value, unit)
    }

    operator fun minus(other: Quantity): Quantity {
        requireSameUnit(other); return Quantity(value - other.value, unit)
    }

    operator fun unaryMinus(): Quantity = Quantity(value.negate(), unit)
    fun isNegative(): Boolean = value.signum() < 0
    fun isZero(): Boolean = value.signum() == 0

    override fun compareTo(other: Quantity): Int {
        requireSameUnit(other); return value.compareTo(other.value)
    }

    private fun requireSameUnit(other: Quantity) {
        if (other.unit != unit) throw IncompatibleUnitsException(unit, other.unit)
    }

    fun format(): String = Formatting.quantity(value, unit)

    companion object {
        fun zero(unit: UnitOfMeasure) = Quantity(BigDecimal.ZERO, unit)
        fun of(text: String, unit: UnitOfMeasure) = Quantity(BigDecimal(text), unit)
    }
}

class IncompatibleUnitsException(val a: UnitOfMeasure, val b: UnitOfMeasure) :
    IllegalArgumentException("Cannot combine ${a.label} with ${b.label}")

/**
 * A conversion between an entry unit and an item's base unit, e.g. 1 BAG = 25 KILOGRAM for a resin,
 * 1 COIL = 100 METRE for a coiled pipe. Factors are configured per item; there are no global
 * conversions between length and mass (that needs the product's kg-per-metre and is a separate metric).
 */
data class UnitConversion(val from: UnitOfMeasure, val to: UnitOfMeasure, val factor: BigDecimal) {
    init {
        require(factor.signum() > 0) { "Conversion factor must be positive" }
    }
}

object UnitConverter {
    private val builtIn = mapOf(
        (UnitOfMeasure.TONNE to UnitOfMeasure.KILOGRAM) to BigDecimal(1000),
    )

    /**
     * Converts [quantity] into [target]. Returns null when no rule exists: callers must refuse the input
     * rather than combine incompatible units.
     */
    fun convert(quantity: Quantity, target: UnitOfMeasure, itemConversions: List<UnitConversion> = emptyList()): Quantity? {
        if (quantity.unit == target) return quantity
        val direct = itemConversions.firstOrNull { it.from == quantity.unit && it.to == target }?.factor
            ?: builtIn[quantity.unit to target]
        if (direct != null) return Quantity(quantity.value * direct, target)
        val inverse = itemConversions.firstOrNull { it.from == target && it.to == quantity.unit }?.factor
            ?: builtIn[target to quantity.unit]
        if (inverse != null) return Quantity(quantity.value.divide(inverse, 6, RoundingMode.HALF_UP).stripTrailingZeros(), target)
        return null
    }
}

object Formatting {
    /** Display form with grouping, e.g. "12,345.5 m". Never used for storage or export values. */
    fun quantity(value: BigDecimal, unit: UnitOfMeasure, decimals: Int = unit.displayDecimals): String =
        "${number(value, decimals)} ${unit.code}"

    fun number(value: BigDecimal, decimals: Int): String {
        val scaled = value.setScale(decimals, RoundingMode.HALF_UP)
        val negative = scaled.signum() < 0
        val plain = scaled.abs().toPlainString()
        val intPart = plain.substringBefore('.')
        val fraction = if (plain.contains('.')) "." + plain.substringAfter('.') else ""
        val grouped = intPart.reversed().chunked(3).joinToString(",").reversed()
        return (if (negative) "-" else "") + grouped + fraction
    }

    fun percent(value: BigDecimal?, decimals: Int = 1): String = value?.let { number(it, decimals) + "%" } ?: "—"

    fun signedPercent(value: BigDecimal?, decimals: Int = 1): String = when {
        value == null -> "—"
        value.signum() > 0 -> "+" + percent(value, decimals)
        else -> percent(value, decimals)
    }

    /** Axis-tick style: 200k, 1.5M, 2,500, 0.5 — no trailing ".0". */
    fun compact(value: BigDecimal): String {
        val abs = value.abs()
        fun trim(v: BigDecimal, decimals: Int): String {
            val scaled = v.setScale(decimals, RoundingMode.HALF_UP)
            val plain = scaled.stripTrailingZeros()
            return number(scaled, if (plain.scale() <= 0) 0 else minOf(decimals, plain.scale()))
        }
        return when {
            abs >= BigDecimal(1_000_000) -> trim(value.divide(BigDecimal(1_000_000), 2, RoundingMode.HALF_UP), 2) + "M"
            abs >= BigDecimal(10_000) -> trim(value.divide(BigDecimal(1_000), 1, RoundingMode.HALF_UP), 1) + "k"
            else -> trim(value, 1)
        }
    }
}
