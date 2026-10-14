package rw.plasmaco.ops.domain.analytics

import java.math.BigDecimal

enum class ChartKind { BAR, STACKED_BAR, HBAR, STACKED_HBAR, LINE, COMBO_BAR_LINE, DIVERGING_BAR }

/**
 * Colour role of a series. Renderers map roles to design tokens: PRIMARY = brand teal, CONTEXT = sage,
 * TARGET = dark ink dashed line, NEGATIVE = critical red, CATEGORY_n = categorical slot n (fixed order).
 */
enum class SeriesRole { PRIMARY, CONTEXT, TARGET, NEGATIVE, CATEGORY_1, CATEGORY_2, CATEGORY_3, CATEGORY_4, CATEGORY_5, OTHER }

data class Series(
    val name: String,
    /** One value per x label; null = no data for that x (drawn as a gap, never as zero). */
    val values: List<BigDecimal?>,
    val role: SeriesRole,
    /** Lines draw over bars in COMBO charts. */
    val asLine: Boolean = false,
)

/** Where tapping an x position should navigate (records behind the bar or point). */
sealed interface DrillTarget {
    data class Day(val date: java.time.LocalDate) : DrillTarget
    data class Product(val productId: String) : DrillTarget
    data class Line(val lineId: String) : DrillTarget
    data class Machine(val machineId: String?) : DrillTarget
    data class Shift(val shiftId: String) : DrillTarget
    data class Item(val kind: rw.plasmaco.ops.domain.master.ItemKind, val itemId: String) : DrillTarget
}

data class ChartSpec(
    val id: String,
    val title: String,
    val kind: ChartKind,
    val unit: String,
    val xLabels: List<String>,
    val series: List<Series>,
    val drill: List<DrillTarget?> = emptyList(),
    /** Horizontal reference, e.g. 100 % on achievement charts. */
    val reference: BigDecimal? = null,
    val referenceLabel: String? = null,
    /** Shown instead of the plot when there is nothing honest to draw. */
    val emptyReason: String? = null,
) {
    val isEmpty: Boolean get() = emptyReason != null || series.all { s -> s.values.all { it == null } }

    /** Screen-reader summary: what the chart shows and its extremes, so the picture is never the only channel. */
    fun accessibleSummary(): String {
        if (isEmpty) return "$title: ${emptyReason ?: "no data"}"
        val parts = series.map { s ->
            val pairs = xLabels.zip(s.values).filter { it.second != null }
            val max = pairs.maxByOrNull { it.second!! }
            val min = pairs.minByOrNull { it.second!! }
            "${s.name}: ${pairs.size} values" +
                (if (max != null) ", highest ${max.second!!.stripTrailingZeros().toPlainString()} $unit at ${max.first}" else "") +
                (if (min != null && pairs.size > 1) ", lowest ${min.second!!.stripTrailingZeros().toPlainString()} $unit at ${min.first}" else "")
        }
        return "$title. " + parts.joinToString(". ")
    }
}
