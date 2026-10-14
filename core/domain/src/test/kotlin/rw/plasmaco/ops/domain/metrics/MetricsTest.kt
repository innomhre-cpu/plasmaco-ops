package rw.plasmaco.ops.domain.metrics

import org.junit.Test
import rw.plasmaco.ops.domain.TestData
import rw.plasmaco.ops.domain.TestData.entry
import rw.plasmaco.ops.domain.TestData.report
import rw.plasmaco.ops.domain.analytics.Analytics
import rw.plasmaco.ops.domain.analytics.DataSet
import rw.plasmaco.ops.domain.analytics.Filters
import rw.plasmaco.ops.domain.common.DateRange
import rw.plasmaco.ops.domain.export.ExportFormat
import rw.plasmaco.ops.domain.export.Exporter
import rw.plasmaco.ops.domain.legacy.XlsxReader
import rw.plasmaco.ops.domain.production.DowntimeEvent
import rw.plasmaco.ops.domain.production.ReportStatus
import rw.plasmaco.ops.domain.reports.ReportBuilder
import rw.plasmaco.ops.domain.reports.ReportType
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MetricsTest {
    private val master = TestData.masterFromCatalogue()

    private fun value(s: ProductionSummary, m: Metric) = (s.value(m) as MetricValue.Value).value

    @Test
    fun `achievement, variance and rejection rate`() {
        val s = ProductionMetrics.summarize(listOf(report(status = ReportStatus.APPROVED)), master)
        assertEquals(0, BigDecimal("80.0").compareTo(value(s, Metric.TARGET_ACHIEVEMENT)))   // 400 / 500
        assertEquals(0, BigDecimal(-100).compareTo(value(s, Metric.TARGET_VARIANCE)))
        assertEquals(0, BigDecimal("4.76").compareTo(value(s, Metric.REJECTION_RATE)))       // 20 / 420
        assertEquals(0, BigDecimal("950").compareTo(value(s, Metric.ACTUAL_CONSUMPTION)))    // 1000 - 50
        assertEquals(0, BigDecimal("42.80").compareTo(value(s, Metric.MATERIAL_VARIANCE)))   // 950 - 907.2
    }

    @Test
    fun `missing inputs give a reason, never a zero`() {
        val noTarget = ProductionMetrics.summarize(listOf(report(entries = listOf(entry(target = null)))), master)
        assertIs<MetricValue.NoData>(noTarget.value(Metric.TARGET_ACHIEVEMENT))
        val empty = ProductionMetrics.summarize(emptyList(), master)
        assertIs<MetricValue.NoData>(empty.value(Metric.GROSS_OUTPUT))
        assertIs<MetricValue.NoData>(empty.value(Metric.OEE))
        val noIssue = ProductionMetrics.summarize(listOf(report(materials = emptyList())), master)
        assertEquals("No material issue recorded", (noIssue.value(Metric.ACTUAL_CONSUMPTION) as MetricValue.NoData).reason)
    }

    @Test
    fun `availability excludes planned stops and OEE needs approved reports`() {
        val d = LocalDateTime.of(2026, 10, 7, 0, 0)
        val downtime = listOf(
            DowntimeEvent("d1", TestData.MACHINE.id, d.withHour(9), d.withHour(10), TestData.BREAKDOWN.id),       // 60 unplanned
            DowntimeEvent("d2", TestData.MACHINE.id, d.withHour(12), d.withHour(12).plusMinutes(30), TestData.BREAK.id), // 30 planned
        )
        val r = report(downtime = downtime)
        val s = ProductionMetrics.summarize(listOf(r), master)
        // Planned time = 720 − 30 = 690; availability = (690 − 60) / 690 = 91.3 %
        assertEquals(0, BigDecimal("91.3").compareTo(value(s, Metric.AVAILABILITY)))
        assertEquals("Includes reports that are not approved", (s.value(Metric.OEE) as MetricValue.NoData).reason)
        val approved = ProductionMetrics.summarize(listOf(r.copy(status = ReportStatus.APPROVED)), master)
        // Run time = 720 − 90 = 630 min = 10.5 h × 120 m/h = 1260 m ideal; performance = 420 / 1260 = 33.3 %
        assertEquals(0, BigDecimal("33.3").compareTo(value(approved, Metric.PERFORMANCE)))
        assertIs<MetricValue.Value>(approved.value(Metric.OEE))
    }

    @Test
    fun `dashboard, report and Excel export agree`() {
        val reports = listOf(
            report("r1", date = TestData.TODAY.minusDays(2), status = ReportStatus.APPROVED),
            report("r2", date = TestData.TODAY.minusDays(1), status = ReportStatus.SUBMITTED, entries = listOf(entry("e9", "PE100-DN63-PN16", "300", "290", "10", "250"))),
            report("r3", date = TestData.TODAY.minusDays(1), status = ReportStatus.DRAFT),
        )
        val data = DataSet(master, reports, emptyList())
        val f = Filters(DateRange(TestData.TODAY.minusDays(7), TestData.TODAY))
        val dash = Analytics.dashboard(data, f, TestData.TODAY)
        val good = (dash.kpis.first { it.metricKey == "good_output" }.value as MetricValue.Value).value
        assertEquals(0, BigDecimal(690).compareTo(good)) // drafts excluded by default
        val doc = ReportBuilder.build(ReportBuilder.Request(ReportType.DAILY_PRODUCTION, f, "Test", Instant.parse("2026-10-08T12:00:00Z")), data)
        val production = doc.tables.first { it.title == "Production" }
        val goodCol = production.columns.indexOfFirst { it.header == "Good" }
        assertEquals(0, good.compareTo(production.totals[goodCol]))
        val xlsx = Exporter.export(doc, ExportFormat.XLSX)
        val reader = XlsxReader.open(xlsx.bytes.inputStream())
        val sheet = reader.readSheet(reader.sheets.first { it.name == "Production" })
        val totalRow = (1..sheet.maxRow).first { sheet[it, 1]?.text == "Total" }
        assertEquals(0, good.compareTo(sheet[totalRow, goodCol + 1]!!.number!!))
        // Every report type builds and renders without error.
        ReportType.entries.forEach { type ->
            val d = ReportBuilder.build(ReportBuilder.Request(type, f, "Test", Instant.parse("2026-10-08T12:00:00Z")), data)
            assertTrue(Exporter.export(d, ExportFormat.PDF).bytes.size > 500, type.name)
            assertTrue(Exporter.export(d, ExportFormat.XLSX).bytes.size > 500, type.name)
        }
    }

    @Test
    fun `missing reports are flagged per line and shift on working days`() {
        val data = DataSet(master, listOf(report(date = TestData.TODAY.minusDays(1), status = ReportStatus.SUBMITTED)), emptyList())
        val f = Filters(DateRange(TestData.TODAY.minusDays(1), TestData.TODAY))
        val missing = Analytics.missingReports(data, f, TestData.TODAY, rw.plasmaco.ops.domain.analytics.AlertThresholds())
        // Yesterday's day shift is reported; the night shift is not. Today is not checked yet.
        assertEquals(1, missing.size)
        assertTrue(missing.single().title.contains(TestData.NIGHT.name))
    }

    @Test
    fun `filtering by product leaves materials and downtime unattributed`() {
        val data = DataSet(master, listOf(report(status = ReportStatus.APPROVED)), emptyList())
        val pid = TestData.product("PE100-DN110-PN10").id
        val dash = Analytics.dashboard(data, Filters(DateRange(TestData.TODAY.minusDays(3), TestData.TODAY), productIds = setOf(pid)), TestData.TODAY)
        assertIs<MetricValue.NoData>(dash.kpis.first { it.metricKey == "rm_consumption" }.value)
        assertEquals("Material use cannot be split by product", dash.charts.getValue("material_trend").emptyReason)
    }
}
