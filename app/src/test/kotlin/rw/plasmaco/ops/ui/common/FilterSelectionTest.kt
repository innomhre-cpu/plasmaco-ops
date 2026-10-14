package rw.plasmaco.ops.ui.common

import rw.plasmaco.ops.domain.common.DateRange
import rw.plasmaco.ops.domain.production.ReportStatus
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class FilterSelectionTest {
    private val today = LocalDate.of(2026, 1, 14)

    @Test
    fun `periods resolve to calendar ranges, including across a year boundary`() {
        assertEquals(DateRange.single(today), Period.TODAY.range(today, null))
        assertEquals(DateRange.single(LocalDate.of(2026, 1, 13)), Period.YESTERDAY.range(today, null))
        assertEquals(DateRange(LocalDate.of(2026, 1, 8), today), Period.LAST_7.range(today, null))
        assertEquals(DateRange(LocalDate.of(2026, 1, 1), today), Period.THIS_MONTH.range(today, null))
        assertEquals(DateRange(LocalDate.of(2025, 12, 1), LocalDate.of(2025, 12, 31)), Period.LAST_MONTH.range(today, null))
    }

    @Test
    fun `a custom period uses the chosen range, or this month until one is chosen`() {
        val chosen = DateRange(LocalDate.of(2025, 11, 3), LocalDate.of(2025, 11, 9))
        assertEquals(chosen, Period.CUSTOM.range(today, chosen))
        assertEquals(Period.THIS_MONTH.range(today, null), Period.CUSTOM.range(today, null))
    }

    @Test
    fun `analytics include submitted and approved reports unless approved-only is chosen`() {
        val all = FilterSelection(lineId = "line-1").toFilters(today)
        assertEquals(setOf(ReportStatus.SUBMITTED, ReportStatus.APPROVED), all.statuses)
        assertEquals(setOf("line-1"), all.lineIds)
        assertEquals(emptySet(), all.productIds)
        val approved = FilterSelection(approvedOnly = true).toFilters(today)
        assertEquals(setOf(ReportStatus.APPROVED), approved.statuses)
    }

    @Test
    fun `the badge counts the narrowing filters, not the period`() {
        assertEquals(0, FilterSelection(period = Period.LAST_7).activeCount)
        assertEquals(3, FilterSelection(lineId = "l", shiftId = "s", approvedOnly = true).activeCount)
    }
}
