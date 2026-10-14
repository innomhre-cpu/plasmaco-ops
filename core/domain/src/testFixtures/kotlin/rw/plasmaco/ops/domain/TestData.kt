package rw.plasmaco.ops.domain

import rw.plasmaco.ops.domain.master.CatalogueBuilder
import rw.plasmaco.ops.domain.master.DefectCategory
import rw.plasmaco.ops.domain.master.DowntimeCategory
import rw.plasmaco.ops.domain.master.ItemKind
import rw.plasmaco.ops.domain.master.Machine
import rw.plasmaco.ops.domain.master.MasterData
import rw.plasmaco.ops.domain.master.MaterialCategory
import rw.plasmaco.ops.domain.master.Product
import rw.plasmaco.ops.domain.master.ProductionLine
import rw.plasmaco.ops.domain.master.RawMaterial
import rw.plasmaco.ops.domain.master.RunRate
import rw.plasmaco.ops.domain.master.Shift
import rw.plasmaco.ops.domain.master.StockThreshold
import rw.plasmaco.ops.domain.production.DowntimeEvent
import rw.plasmaco.ops.domain.production.MaterialConsumption
import rw.plasmaco.ops.domain.production.ProductionEntry
import rw.plasmaco.ops.domain.production.ProductionReport
import rw.plasmaco.ops.domain.production.ReportStatus
import rw.plasmaco.ops.domain.security.Actor
import rw.plasmaco.ops.domain.security.Role
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Test fixtures. Products and kg/m are PLASMACO's real catalogue; lines, shifts and categories are TEST-ONLY
 * placeholders (the workbook has none), named so they cannot be mistaken for configured business data.
 */
object TestData {
    val NOW: Instant = Instant.parse("2026-10-08T08:00:00Z")
    val TODAY: LocalDate = LocalDate.of(2026, 10, 8)

    val RESIN = RawMaterial("mat-resin", "RES-PE100", "HDPE PE100 resin (grade not specified)", MaterialCategory.RESIN, bagWeightKg = BigDecimal(25))
    val LINE = ProductionLine("line-test-1", "T1", "Test line 1")
    val MACHINE = Machine("mc-test-1", "T1-EXT", "Test extruder 1", LINE.id)
    val DAY = Shift("shift-test-day", "D", "Test day shift", LocalTime.of(6, 0), LocalTime.of(18, 0))
    val NIGHT = Shift("shift-test-night", "N", "Test night shift", LocalTime.of(18, 0), LocalTime.of(6, 0))
    val BREAKDOWN = DowntimeCategory("dt-test-breakdown", "Test: breakdown")
    val BREAK = DowntimeCategory("dt-test-break", "Test: planned break", planned = true)

    fun product(code: String): Product = masterFromCatalogue().products.first { it.code == code }

    private val cachedMaster by lazy {
        val ps = CatalogueBuilder.fromWorkbookCatalogue(idFactory = seqIds("prod"))
        MasterData(
            products = ps.map { it.product },
            materials = listOf(RESIN),
            lines = listOf(LINE),
            machines = listOf(MACHINE),
            shifts = listOf(DAY, NIGHT),
            downtimeCategories = listOf(BREAKDOWN, BREAK),
            defectCategories = listOf(DefectCategory("def-test", "Test: ovality")),
            thresholds = ps.mapNotNull { it.threshold } + StockThreshold(ItemKind.RAW_MATERIAL, RESIN.id, BigDecimal(20_000), BigDecimal(40_000)),
            runRates = listOf(RunRate(LINE.id, null, BigDecimal(120))),
        )
    }

    fun masterFromCatalogue(): MasterData = cachedMaster

    private fun seqIds(prefix: String): () -> String { var n = 0; return { "$prefix-${++n}" } }

    val operator = Actor("u-op", "Test Operator", setOf(Role.OPERATOR))
    val supervisor = Actor("u-sup", "Test Supervisor", setOf(Role.SUPERVISOR))
    val storekeeper = Actor("u-store", "Test Storekeeper", setOf(Role.STOREKEEPER))
    val manager = Actor("u-mgr", "Test Manager", setOf(Role.MANAGER))
    val admin = Actor("u-admin", "Test Admin", setOf(Role.ADMIN))

    fun report(
        id: String = "r1",
        date: LocalDate = TODAY.minusDays(1),
        status: ReportStatus = ReportStatus.DRAFT,
        entries: List<ProductionEntry> = listOf(entry()),
        materials: List<MaterialConsumption> = listOf(MaterialConsumption("m1", RESIN.id, issued = BigDecimal(1000), returned = BigDecimal(50), scrap = BigDecimal(12))),
        downtime: List<DowntimeEvent> = emptyList(),
        shift: Shift = DAY,
        createdBy: String = operator.userId,
    ) = ProductionReport(
        id = id, reference = "PR-TEST-$id", date = date, shiftId = shift.id, lineId = LINE.id, operatorId = createdBy,
        startTime = shift.start, endTime = shift.end, status = status, createdBy = createdBy, createdAt = NOW,
        entries = entries, materials = materials, downtime = downtime,
    )

    /** DN110 PN10: ISO 4427-2 gives 2.16 kg/m. */
    fun entry(
        id: String = "e1",
        productCode: String = "PE100-DN110-PN10",
        gross: String = "420",
        good: String = "400",
        rejected: String = "20",
        target: String? = "500",
    ) = ProductionEntry(id, product(productCode).id, MACHINE.id, target?.let(::BigDecimal), BigDecimal(gross), BigDecimal(good), BigDecimal(rejected))
}
