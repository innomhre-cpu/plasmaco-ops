package rw.plasmaco.ops.domain.legacy

import rw.plasmaco.ops.domain.common.Id
import rw.plasmaco.ops.domain.common.Severity
import rw.plasmaco.ops.domain.common.newId
import rw.plasmaco.ops.domain.inventory.InventoryTransaction
import rw.plasmaco.ops.domain.inventory.TxnOrigin
import rw.plasmaco.ops.domain.inventory.TxnStatus
import rw.plasmaco.ops.domain.inventory.TxnType
import rw.plasmaco.ops.domain.master.ItemKind
import rw.plasmaco.ops.domain.master.MasterData
import rw.plasmaco.ops.domain.master.Product
import rw.plasmaco.ops.domain.master.ProductNaming
import rw.plasmaco.ops.domain.units.UnitOfMeasure
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate

data class ProductMatch(val legacyName: String, val key: String?, val productId: Id?, val proposal: Product?, val rows: Int, val nonZeroRows: Int)

data class ProposedOpening(val kind: ItemKind, val itemId: Id, val quantity: BigDecimal, val asOf: LocalDate)

data class ImportStats(
    val days: Int,
    val productRows: Int,
    val finishedGoodsTransactions: Int,
    val rawMaterialTransactions: Int,
    val reconciliations: Int,
    val firstDate: LocalDate?,
    val lastDate: LocalDate?,
    val workbookResinBalance: BigDecimal?,
    val importedResinBalance: BigDecimal?,
)

data class ImportPlan(
    val fileName: String,
    val sha256: String,
    val productMatches: List<ProductMatch>,
    val newProducts: List<Product>,
    val transactions: List<InventoryTransaction>,
    val skippedAlreadyImported: Int,
    val conflicts: List<ImportIssue>,
    val issues: List<ImportIssue>,
    val proposedOpenings: List<ProposedOpening>,
    val stats: ImportStats,
) {
    val blocking: List<ImportIssue> get() = issues.filter { it.severity == Severity.ERROR }
}

/**
 * Turns a parsed legacy workbook into historical ledger transactions. Rules (discovery report, sections 3 and 8):
 *  - history never changes live balances; transactions are flagged historical and carry a source cell reference;
 *  - each jump between a legacy closing and the next opening, and each closing that is not opening + in − out,
 *    becomes an explicit LEGACY_RECONCILIATION so the imported ledger reproduces the workbook's own figures
 *    while every discontinuity stays visible;
 *  - idempotency keys depend on product, date and movement, not on the file, so importing a later save of the
 *    same workbook adds only the new days and reports any changed past value as a conflict.
 */
object ImportPlanner {
    data class Context(
        val master: MasterData,
        /** Raw-material item that receives the workbook's single resin balance. */
        val resinMaterialId: Id?,
        val existingKeys: Map<String, BigDecimal>,
        val actorId: Id,
        val now: Instant,
        val createMissingProducts: Boolean = true,
        val idFactory: () -> Id = ::newId,
    )

    fun plan(wb: LegacyWorkbook, ctx: Context): ImportPlan {
        val issues = wb.issues.toMutableList()
        val conflicts = mutableListOf<ImportIssue>()
        val txns = mutableListOf<InventoryTransaction>()
        var skipped = 0
        var recon = 0
        val sha8 = wb.sha256.take(8)

        // ---------------- products
        val byKey = ctx.master.products.associateBy { "${it.materialGrade}|${it.dn}|${it.pn?.let(ProductNaming::pnText)}" }
        val legacyRows = wb.days.filter { it.date != null }.flatMap { d -> d.rows.map { d to it } }
        val names = legacyRows.groupBy { it.second.name }
        val newProducts = mutableMapOf<String, Product>()
        val matches = names.map { (name, rows) ->
            val key = ProductNaming.matchKey(name)
            val nonZero = rows.count { (_, r) -> listOf(r.opening, r.inward, r.outward, r.closing).any { (it.value ?: BigDecimal.ZERO).signum() != 0 } }
            val existing = key?.let { byKey[it] }
            val proposal = if (existing == null && key != null && ctx.createMissingProducts) newProducts.getOrPut(key) {
                val p = ProductNaming.parse(name)!!
                Product(ctx.idFactory(), ProductNaming.code(p.dn, p.pn, p.grade), ProductNaming.canonicalName(p.dn, p.pn, p.grade), p.dn, p.pn,
                    ProductNaming.sdrFor(p.pn, p.grade), p.grade,
                    kgPerMetre = rw.plasmaco.ops.domain.master.CatalogueBuilder.isoSpec(p.dn, p.pn)?.kgPerMetre ?: rows.firstNotNullOfOrNull { it.second.kgPerMetre.value },
                    note = "Created by workbook import", legacyNames = listOf(name))
            } else null
            if (key == null) issues += ImportIssue(rows.first().first.sheet, "C${rows.first().second.row}", "product.unparsed",
                "Product '$name' has no diameter/pressure class; ${rows.size} rows skipped (${nonZero} with figures)", if (nonZero > 0) Severity.ERROR else Severity.WARNING)
            ProductMatch(name, key, existing?.id ?: proposal?.id, proposal, rows.size, nonZero)
        }
        val productIdByName = matches.associate { it.legacyName to it.productId }

        // ---------------- finished goods
        data class Point(val day: LegacyDay, val row: LegacyProductRow)
        val perProduct = legacyRows.mapNotNull { (d, r) -> productIdByName[r.name]?.let { it to Point(d, r) } }.groupBy({ it.first }, { it.second })
        val running = mutableMapOf<Id, BigDecimal>()
        val lastDate = mutableMapOf<Id, LocalDate>()
        for ((productId, points) in perProduct) {
            val sorted = points.sortedWith(compareBy<Point> { it.day.date }.thenBy { it.row.row })
            val seenDates = mutableSetOf<LocalDate>()
            for (p in sorted) {
                val date = p.day.date!!
                val ref = "$sha8/${p.day.sheet}!"
                if (!seenDates.add(date)) {
                    issues += ImportIssue(p.day.sheet, "C${p.row.row}", "product.duplicate_row", "'${p.row.name}' appears twice on $date; the second row is skipped")
                    continue
                }
                val opening = p.row.opening.value ?: BigDecimal.ZERO
                val inward = p.row.inward.value ?: BigDecimal.ZERO
                val outward = p.row.outward.value ?: BigDecimal.ZERO
                val closing = p.row.closing.value
                val prev = running[productId]
                fun add(type: TxnType, qty: BigDecimal, keySuffix: String, cell: String, note: String?, entered: BigDecimal? = null) {
                    if (qty.signum() == 0) return
                    val key = "legacy:fg:$productId:$date:$keySuffix"
                    val already = ctx.existingKeys[key]
                    if (already != null) {
                        if (already.compareTo(qty) != 0) conflicts += ImportIssue(p.day.sheet, cell, "import.changed_value",
                            "${type.label} on $date was imported earlier as ${already.toPlainString()}; this file says ${qty.toPlainString()}. Kept the earlier value")
                        skipped++; return
                    }
                    txns += InventoryTransaction(ctx.idFactory(), ItemKind.FINISHED_GOOD, productId, type, qty.setScale(3, RoundingMode.HALF_UP), UnitOfMeasure.METRE, date,
                        enteredQuantity = entered, reference = "Workbook ${p.day.sheet}", note = note, status = TxnStatus.POSTED, historical = true,
                        origin = TxnOrigin.LEGACY_IMPORT, sourceRef = ref + cell, createdBy = ctx.actorId, createdAt = ctx.now, idempotencyKey = key)
                    if (type == TxnType.LEGACY_RECONCILIATION) recon++
                }
                if (prev == null) add(TxnType.OPENING_BALANCE, opening, "opening", p.row.opening.cell, "Opening as found in the workbook")
                else if (opening.compareTo(prev) != 0) {
                    add(TxnType.LEGACY_RECONCILIATION, opening - prev, "open-jump", p.row.opening.cell,
                        "Opening ${opening.toPlainString()} (${p.row.opening.formula ?: "typed"}) ≠ previous closing ${prev.toPlainString()}")
                    issues += ImportIssue(p.day.sheet, p.row.opening.cell, "fg.opening_jump", "${p.row.name} on $date: opening ${opening.toPlainString()} m but previous closing ${prev.toPlainString()} m")
                }
                if (inward.signum() > 0) add(TxnType.PRODUCTION_RECEIPT, inward, "in", p.row.inward.cell, p.row.inward.formula?.takeIf { p.row.inward.origin == CellOrigin.TYPED_SUM }?.let { "Typed sum $it" })
                if (inward.signum() < 0) add(TxnType.LEGACY_RECONCILIATION, inward, "in-negative", p.row.inward.cell, "Negative inward in workbook")
                if (outward.signum() > 0) add(TxnType.DISPATCH, outward.negate(), "out", p.row.outward.cell, p.row.outward.formula?.takeIf { p.row.outward.origin == CellOrigin.TYPED_SUM }?.let { "Typed sum $it — individual dispatches not recorded" })
                if (outward.signum() < 0) add(TxnType.LEGACY_RECONCILIATION, outward.negate(), "out-negative", p.row.outward.cell, "Negative outward in workbook")
                val expected = opening + inward - outward
                val shown = closing ?: expected
                if (closing != null && closing.compareTo(expected) != 0) {
                    add(TxnType.LEGACY_RECONCILIATION, closing - expected, "close-jump", p.row.closing.cell,
                        "Closing shows ${closing.toPlainString()} (${p.row.closing.formula ?: "typed"}) but opening + in − out = ${expected.toPlainString()}")
                    issues += ImportIssue(p.day.sheet, p.row.closing.cell, "fg.closing_mismatch", "${p.row.name} on $date: closing ${closing.toPlainString()} m ≠ opening + in − out ${expected.toPlainString()} m")
                }
                if (shown.signum() < 0) issues += ImportIssue(p.day.sheet, p.row.closing.cell, "fg.negative", "${p.row.name} closed at ${shown.toPlainString()} m on $date")
                running[productId] = shown
                lastDate[productId] = date
            }
        }

        // ---------------- raw material (single aggregate balance in the workbook)
        var rmRunning: BigDecimal? = null
        var rmLast: LocalDate? = null
        var workbookRmBalance: BigDecimal? = null
        val rmId = ctx.resinMaterialId
        if (rmId == null && wb.rawMaterialRows.isNotEmpty()) issues += ImportIssue("", null, "rm.no_material", "Choose the raw material that the workbook's resin balance belongs to", Severity.ERROR)
        val rmRows = wb.rawMaterialRows.filter { it.date != null }
        val firstRowOfSheet = rmRows.groupBy { it.sheet }.mapValues { (_, rs) -> rs.minOf { it.row } }
        if (rmId != null) for (r in rmRows) {
            val date = r.date!!
            val ref = "$sha8/${r.sheet}!"
            fun add(type: TxnType, qty: BigDecimal, keySuffix: String, cell: String, note: String?, entered: BigDecimal? = null, enteredUnit: UnitOfMeasure? = null) {
                if (qty.signum() == 0) return
                val key = "legacy:rm:$date:$keySuffix:${r.sheet}:${r.row}"
                val already = ctx.existingKeys[key]
                if (already != null) {
                    if (already.compareTo(qty) != 0) conflicts += ImportIssue(r.sheet, cell, "import.changed_value", "${type.label} on $date was imported earlier as ${already.toPlainString()}; this file says ${qty.toPlainString()}")
                    skipped++; return
                }
                txns += InventoryTransaction(ctx.idFactory(), ItemKind.RAW_MATERIAL, rmId, type, qty.setScale(3, RoundingMode.HALF_UP), UnitOfMeasure.KILOGRAM, date,
                    enteredQuantity = entered, enteredUnit = enteredUnit, reference = "Workbook ${r.sheet}", note = note, historical = true,
                    origin = TxnOrigin.LEGACY_IMPORT, sourceRef = ref + cell, createdBy = ctx.actorId, createdAt = ctx.now, idempotencyKey = key)
                if (type == TxnType.LEGACY_RECONCILIATION) recon++
            }
            val o = r.opening
            val isFirstOfSheet = r.row == firstRowOfSheet[r.sheet]
            if (rmRunning == null) {
                add(TxnType.OPENING_BALANCE, o.orZero, "opening", o.cell, "Opening as found in the workbook")
                rmRunning = o.orZero
            } else if (o.value != null && !isFirstOfSheet && (o.origin == CellOrigin.TYPED_SUM || o.origin == CellOrigin.TYPED_VALUE)) {
                // Receipts typed into the OPENING column (September): treat as a receipt and keep the previous balance.
                add(TxnType.GOODS_RECEIPT, o.value, "recv-in-opening", o.cell, "Typed into OPENING as ${o.formula ?: o.value.toPlainString()}; imported as a receipt", bagsOf(o.formula), bagsOf(o.formula)?.let { UnitOfMeasure.BAG })
                issues += ImportIssue(r.sheet, o.cell, "rm.receipt_in_opening", "Receipt ${o.value.toPlainString()} kg typed into OPENING on $date; the workbook's balance dropped the previous ${rmRunning.setScale(2, RoundingMode.HALF_UP).toPlainString()} kg")
                rmRunning = rmRunning + o.value
            } else if (o.value != null && o.origin != CellOrigin.ERROR && o.value.compareTo(rmRunning) != 0) {
                add(TxnType.LEGACY_RECONCILIATION, o.value - rmRunning, "open-jump", o.cell, "Opening ${o.value.toPlainString()} ≠ previous balance ${rmRunning.toPlainString()}")
                issues += ImportIssue(r.sheet, o.cell, "rm.opening_jump", "Resin opening ${o.value.toPlainString()} kg on $date ≠ previous balance ${rmRunning.setScale(2, RoundingMode.HALF_UP).toPlainString()} kg")
                rmRunning = o.value
            }
            r.received.value?.takeIf { it.signum() > 0 }?.let {
                add(TxnType.GOODS_RECEIPT, it, "recv", r.received.cell, r.received.formula?.let { f -> "Typed as $f" }, bagsOf(r.received.formula), bagsOf(r.received.formula)?.let { UnitOfMeasure.BAG })
                rmRunning = rmRunning!! + it
            }
            val issue = r.issue
            when {
                issue.origin == CellOrigin.ERROR -> Unit
                issue.value != null && issue.value.signum() > 0 -> {
                    add(TxnType.ISSUE_TO_PRODUCTION, issue.value.negate(), "issue", issue.cell, "Theoretical issue from the workbook (inward metres × kg/m), not a measured quantity")
                    rmRunning = rmRunning!! - issue.value
                }
            }
            if (r.balance.origin == CellOrigin.FORMULA && r.balance.formula?.replace("$", "")?.matches(Regex("""=[A-Z]+\d+\+[A-Z]+\d+-[A-Z]+\d+""")) == true && o.value == null && !isFirstOfSheet) {
                issues += ImportIssue(r.sheet, r.balance.cell, "rm.balance_unchained", "Balance ${r.balance.formula} ignores the previous balance (opening blank); imported balance continues the chain")
            }
            if (r.balance.origin != CellOrigin.ERROR && r.balance.value != null) workbookRmBalance = r.balance.value
            rmLast = date
        }

        // Production without any raw-material issue on the same day.
        val issueDates = rmRows.filter { (it.issue.value ?: BigDecimal.ZERO).signum() > 0 }.map { it.date }.toSet()
        wb.days.filter { d -> d.date != null && d.rows.any { (it.inward.value ?: BigDecimal.ZERO).signum() > 0 } && d.date !in issueDates }
            .forEach { d -> issues += ImportIssue(d.sheet, "row ${d.headerRow - 1}", "rm.issue_missing", "Production recorded on ${d.date} but no raw-material issue in the workbook") }

        val dates = wb.days.mapNotNull { it.date }
        val openings = running.map { (id, q) -> ProposedOpening(ItemKind.FINISHED_GOOD, id, q, lastDate.getValue(id)) } +
            listOfNotNull(if (rmId != null && rmRunning != null) ProposedOpening(ItemKind.RAW_MATERIAL, rmId, rmRunning!!, rmLast!!) else null)

        return ImportPlan(
            fileName = wb.fileName, sha256 = wb.sha256, productMatches = matches, newProducts = newProducts.values.toList(),
            transactions = txns, skippedAlreadyImported = skipped, conflicts = conflicts, issues = issues, proposedOpenings = openings,
            stats = ImportStats(
                days = wb.days.count { it.date != null }, productRows = legacyRows.size,
                finishedGoodsTransactions = txns.count { it.itemKind == ItemKind.FINISHED_GOOD },
                rawMaterialTransactions = txns.count { it.itemKind == ItemKind.RAW_MATERIAL },
                reconciliations = recon, firstDate = dates.minOrNull(), lastDate = dates.maxOrNull(),
                workbookResinBalance = workbookRmBalance, importedResinBalance = rmRunning,
            ),
        )
    }

    /** "=990*25" or "=5*990*25" → number of bags (990, 4950) when the last factor is the 25 kg bag weight. */
    fun bagsOf(formula: String?): BigDecimal? {
        val f = formula?.removePrefix("=")?.replace(" ", "") ?: return null
        val parts = f.split('*')
        if (parts.size < 2 || parts.last() != "25" || parts.any { it.toBigDecimalOrNull() == null }) return null
        return parts.dropLast(1).map { BigDecimal(it) }.reduce { a, b -> a * b }
    }
}
