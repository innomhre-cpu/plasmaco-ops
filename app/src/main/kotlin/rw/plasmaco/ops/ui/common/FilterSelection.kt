package rw.plasmaco.ops.ui.common

import rw.plasmaco.ops.domain.analytics.Filters
import rw.plasmaco.ops.domain.common.DateRange
import rw.plasmaco.ops.domain.production.ReportStatus
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

enum class Period(val label: String) {
    TODAY("Today"),
    YESTERDAY("Yesterday"),
    LAST_7("Last 7 days"),
    THIS_MONTH("This month"),
    LAST_MONTH("Last month"),
    CUSTOM("Custom range");

    fun range(today: LocalDate, custom: DateRange?): DateRange = when (this) {
        TODAY -> DateRange.single(today)
        YESTERDAY -> DateRange.single(today.minusDays(1))
        LAST_7 -> DateRange(today.minusDays(6), today)
        THIS_MONTH -> DateRange(today.withDayOfMonth(1), today)
        LAST_MONTH -> today.minusMonths(1).let { DateRange(it.withDayOfMonth(1), it.with(TemporalAdjusters.lastDayOfMonth())) }
        CUSTOM -> custom ?: DateRange(today.withDayOfMonth(1), today)
    }
}

/**
 * The filter bar's choices (one line, product and shift at a time keeps the controls usable on a phone; the
 * domain [Filters] supports sets for the server and reports).
 */
data class FilterSelection(
    val period: Period = Period.THIS_MONTH,
    val custom: DateRange? = null,
    val lineId: String? = null,
    val productId: String? = null,
    val shiftId: String? = null,
    /** Default analytics scope is submitted + approved; "approved only" matches what management signs off. */
    val approvedOnly: Boolean = false,
) {
    fun range(today: LocalDate): DateRange = period.range(today, custom)

    fun toFilters(today: LocalDate): Filters = Filters(
        range = range(today),
        lineIds = setOfNotNull(lineId),
        productIds = setOfNotNull(productId),
        shiftIds = setOfNotNull(shiftId),
        statuses = if (approvedOnly) setOf(ReportStatus.APPROVED) else setOf(ReportStatus.SUBMITTED, ReportStatus.APPROVED),
    )

    val activeCount: Int get() = listOfNotNull(lineId, productId, shiftId).size + (if (approvedOnly) 1 else 0)
}
