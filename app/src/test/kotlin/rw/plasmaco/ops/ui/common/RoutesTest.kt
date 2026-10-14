package rw.plasmaco.ops.ui.common

import rw.plasmaco.ops.domain.analytics.DrillTarget
import rw.plasmaco.ops.domain.master.ItemKind
import rw.plasmaco.ops.domain.reports.ReportType
import rw.plasmaco.ops.ui.navigation.Routes
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RoutesTest {
    @Test
    fun `route arguments are percent-encoded so ids cannot break a route`() {
        assertEquals("PE100-DN110-PN10", Routes.enc("PE100-DN110-PN10"))
        assertEquals("HDPE%201%2F2%22%20%26%20more%3F", Routes.enc("HDPE 1/2\" & more?"))
        assertEquals("Gr%C3%BCn", Routes.enc("Grün"))
        assertEquals("", Routes.enc(null))
        assertEquals("item/FINISHED_GOOD/a%2Fb", Routes.item(ItemKind.FINISHED_GOOD, "a/b"))
        assertEquals("history?date=2026-10-07&product=&line=L%261&shift=", Routes.history(date = LocalDate.of(2026, 10, 7), lineId = "L&1"))
    }

    @Test
    fun `every route matches the pattern it is registered under`() {
        fun shape(route: String) = route.substringBefore('?').split('/').map { if (it.isEmpty()) it else "x" }.size
        assertEquals(shape(Routes.EDITOR_PATTERN), shape(Routes.editor("r 1", 2)))
        assertEquals(shape(Routes.DETAIL_PATTERN), shape(Routes.detail("r/1")))
        assertEquals(shape(Routes.PREVIEW_PATTERN), shape(Routes.preview("r/1")))
        assertEquals(shape(Routes.ITEM_PATTERN), shape(Routes.item(ItemKind.RAW_MATERIAL, "x/y")))
        assertEquals(shape(Routes.BUILDER_PATTERN), shape(Routes.builder(ReportType.DOWNTIME, "r/1")))
        assertEquals(shape(Routes.COUNT_PATTERN), shape(Routes.count("c/1")))
    }

    @Test
    fun `chart and alert drill-downs open the records behind the number`() {
        val day = LocalDate.of(2026, 10, 7)
        assertEquals(Routes.history(date = day), DrillTarget.Day(day).route())
        assertEquals(Routes.history(productId = "p1"), DrillTarget.Product("p1").route())
        assertEquals(Routes.history(shiftId = "s1"), DrillTarget.Shift("s1").route())
        assertEquals(Routes.builder(ReportType.DOWNTIME), DrillTarget.Machine("m1").route())
        assertEquals(Routes.item(ItemKind.RAW_MATERIAL, "mat-1"), DrillTarget.Item(ItemKind.RAW_MATERIAL, "mat-1").route())
    }

    @Test
    fun `stored notification targets decode, and malformed ones open nothing`() {
        assertEquals(Routes.detail("r9"), notificationRoute("r9", "day:2026-10-07"))
        assertEquals(Routes.item(ItemKind.FINISHED_GOOD, "p1"), notificationRoute(null, "item:FINISHED_GOOD:p1"))
        assertEquals(Routes.history(date = LocalDate.of(2026, 10, 7)), notificationRoute(null, "day:2026-10-07"))
        assertEquals(Routes.history(lineId = "l1"), notificationRoute(null, "line:l1"))
        assertEquals(Routes.SYNC, notificationRoute(null, "sync"))
        assertNull(notificationRoute(null, null))
        assertNull(notificationRoute(null, "item:NOT_A_KIND:p1"))
        assertNull(notificationRoute(null, "item:FINISHED_GOOD"))
        assertNull(notificationRoute(null, "day:yesterday"))
        assertNull(notificationRoute(null, "unknown:x"))
    }
}
