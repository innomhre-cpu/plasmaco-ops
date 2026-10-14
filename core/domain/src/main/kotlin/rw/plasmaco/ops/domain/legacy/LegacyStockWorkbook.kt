package rw.plasmaco.ops.domain.legacy

import rw.plasmaco.ops.domain.common.Severity
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.LocalDate

/** How a block's date was established. Anything other than EXACT is logged so a person can check it. */
enum class DateSource(val label: String) {
    EXACT("Date as entered"),
    DAY_MONTH_SWAPPED("Day and month were swapped by Excel; repaired"),
    TEXT_PARSED("Date was stored as text; parsed"),
    YEAR_CORRECTED("Year was auto-filled wrongly; corrected"),
    FROM_RAW_MATERIAL_ROW("Date blank; taken from the raw-material row that references this day's TOTAL"),
    INTERPOLATED("Date blank; inferred from neighbouring days"),
    UNRESOLVED("Date could not be established"),
}

/** Cell content class, mirroring the discovery report's formula census. */
enum class CellOrigin { TYPED_VALUE, TYPED_SUM, FORMULA, LINK_PREVIOUS, CROSS_SHEET, EXTERNAL_LINK, SUMIF_TEMPLATE, BLANK, ERROR }

data class LegacyValue(val value: BigDecimal?, val formula: String?, val origin: CellOrigin, val cell: String) {
    val orZero: BigDecimal get() = value ?: BigDecimal.ZERO
}

data class LegacyProductRow(
    val row: Int,
    val name: String,
    val opening: LegacyValue,
    val inward: LegacyValue,
    val outward: LegacyValue,
    val closing: LegacyValue,
    val kgPerMetre: LegacyValue,
    val kg: LegacyValue,
    val minQty: LegacyValue,
)

data class LegacyDay(
    val sheet: String,
    val blockIndex: Int,
    val headerRow: Int,
    val date: LocalDate?,
    val dateSource: DateSource,
    val rawDate: String?,
    val rows: List<LegacyProductRow>,
    val totalRow: Int?,
    val totalKg: LegacyValue?,
)

data class LegacyRawMaterialRow(
    val sheet: String,
    val row: Int,
    val date: LocalDate?,
    val dateSource: DateSource,
    val rawDate: String?,
    val opening: LegacyValue,
    val received: LegacyValue,
    val issue: LegacyValue,
    val balance: LegacyValue,
    /** Row of the finished-goods TOTAL this ISSUE cell points at (same sheet), when it does. */
    val issueTotalRow: Int?,
)

data class ImportIssue(val sheet: String, val cell: String?, val code: String, val message: String, val severity: Severity = Severity.WARNING)

data class LegacyWorkbook(
    val fileName: String,
    val sha256: String,
    val year: Int,
    val days: List<LegacyDay>,
    val rawMaterialRows: List<LegacyRawMaterialRow>,
    val issues: List<ImportIssue>,
    val sheetNames: List<String>,
    /** Month sheets that can be imported (visible, or hidden when asked for), whether or not they were selected. */
    val monthSheetNames: List<String> = emptyList(),
)

/**
 * Parses PLASMACO's "Finished Goods & Raw Material Stock" workbook. The layout drifts between months (product
 * column in A, B or C; raw-material block between L and S), so every block is located by its header text rather
 * than by column letter.
 */
object LegacyStockParser {
    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    fun monthOf(sheetName: String): Int? {
        val n = sheetName.trim().lowercase()
        return months.indexOfFirst { n == it || n.startsWith(it) && n.length <= 9 && n.all { c -> c.isLetter() } }.takeIf { it >= 0 }?.plus(1)
    }

    data class Options(
        val year: Int? = null,
        /** Hidden month sheets are templates in the files seen so far; skipped unless asked for. */
        val includeHidden: Boolean = false,
        val sheets: Set<String>? = null,
    )

    fun parse(bytes: ByteArray, fileName: String, options: Options = Options()): LegacyWorkbook {
        val sha = sha256(bytes)
        val reader = XlsxReader.open(bytes.inputStream())
        val issues = mutableListOf<ImportIssue>()
        val days = mutableListOf<LegacyDay>()
        val rmRows = mutableListOf<LegacyRawMaterialRow>()
        val monthSheets = reader.sheets.filter { monthOf(it.name) != null && (options.includeHidden || it.state == "visible") && (options.sheets == null || it.name in options.sheets) }
        val year = options.year ?: inferYear(reader, monthSheets)

        for (info in monthSheets) {
            val month = monthOf(info.name)!!
            val sheet = reader.readSheet(info)
            val sheetDays = parseFinishedGoods(sheet, info.name, month, year, reader.date1904, issues)
            val sheetRm = parseRawMaterials(sheet, info.name, month, year, reader.date1904, issues)
            resolveBlankDates(sheetDays, sheetRm, info.name, month, issues).let(days::addAll)
            rmRows += sheetRm
        }
        val importable = reader.sheets.filter { monthOf(it.name) != null && (options.includeHidden || it.state == "visible") }.map { it.name }
        return LegacyWorkbook(fileName, sha, year, days, rmRows, issues, reader.sheets.map { it.name }, importable)
    }

    private fun inferYear(reader: XlsxReader, sheets: List<XlsxReader.SheetInfo>): Int {
        val years = mutableMapOf<Int, Int>()
        sheets.take(3).forEach { info ->
            val s = reader.readSheet(info, 20)
            for (r in 1..40) for (c in 1..20) {
                s[r, c]?.dateValue(reader.date1904)?.let { years.merge(it.year, 1, Int::plus) }
            }
        }
        return years.maxByOrNull { it.value }?.key ?: LocalDate.now().year
    }

    // ------------------------------------------------------------------ finished goods

    private fun parseFinishedGoods(sheet: XlsxReader.Sheet, name: String, month: Int, year: Int, d1904: Boolean, issues: MutableList<ImportIssue>): List<LegacyDay> {
        val headers = (1..sheet.maxRow).mapNotNull { r -> (1..3).firstOrNull { c -> sheet[r, c]?.text?.trim()?.equals("Product Name", true) == true }?.let { r to it } }
        return headers.mapIndexed { bi, (hr, pc) ->
            val next = headers.getOrNull(bi + 1)?.first ?: (sheet.maxRow + 2)
            val dateCell = (1..3).asSequence().mapNotNull { sheet[hr - 1, it] }.firstOrNull { !(it.text?.isBlank() ?: false) && it.type != XlsxReader.CellType.BLANK }
            val (date, source) = interpretDate(dateCell, month, year, d1904)
            if (source != DateSource.EXACT && source != DateSource.UNRESOLVED) {
                issues += ImportIssue(name, dateCell?.address, "date.repaired", "${source.label}: '${display(dateCell)}' → $date", Severity.INFO)
            }
            val rows = mutableListOf<LegacyProductRow>()
            var totalRow: Int? = null
            var totalKg: LegacyValue? = null
            for (r in hr + 1 until next - 1) {
                val nameCell = sheet[r, pc]
                val productName = nameCell?.text?.trim()
                if (productName.isNullOrEmpty()) {
                    // TOTAL row: label in any column of the block, value in the next column.
                    for (c in pc until pc + 9) {
                        val t = sheet[r, c]?.text?.trim()
                        if (t != null && t.uppercase().startsWith("TOTAL")) { totalRow = r; totalKg = value(sheet, r, c + 1) }
                    }
                    continue
                }
                rows += LegacyProductRow(
                    r, productName,
                    value(sheet, r, pc + 1), value(sheet, r, pc + 2), value(sheet, r, pc + 3), value(sheet, r, pc + 4),
                    value(sheet, r, pc + 5), value(sheet, r, pc + 6), value(sheet, r, pc + 7),
                )
            }
            LegacyDay(name, bi, hr, date, source, dateCell?.let(::display), rows, totalRow, totalKg)
        }
    }

    // ------------------------------------------------------------------ raw materials

    private fun parseRawMaterials(sheet: XlsxReader.Sheet, name: String, month: Int, year: Int, d1904: Boolean, issues: MutableList<ImportIssue>): List<LegacyRawMaterialRow> {
        var header: Pair<Int, Int>? = null
        loop@ for (r in 4..8) for (c in 10..25) {
            if (sheet[r, c]?.text?.trim()?.equals("RECEIVED", true) == true) { header = r to c; break@loop }
        }
        val (hr, rc) = header ?: run {
            issues += ImportIssue(name, null, "rm.missing", "No raw-material block found"); return emptyList()
        }
        val out = mutableListOf<LegacyRawMaterialRow>()
        for (r in hr + 1..hr + 45) {
            val cells = (-2..2).map { sheet[r, rc + it] }
            if (cells.all { it == null }) continue
            val dateCell = cells[0]
            val (date, source) = interpretDate(dateCell, month, year, d1904)
            val opening = value(sheet, r, rc - 1)
            val received = value(sheet, r, rc)
            val issue = value(sheet, r, rc + 1)
            val balance = value(sheet, r, rc + 2)
            if (dateCell == null && opening.value == null && received.value == null && issue.value == null) continue
            val issueTotal = issue.formula?.let { Regex("""^=?\$?[A-Z]{1,3}\$?(\d+)$""").find(it.trim())?.groupValues?.get(1)?.toInt() }
            if (source != DateSource.EXACT && source != DateSource.UNRESOLVED && dateCell != null) {
                issues += ImportIssue(name, dateCell.address, "date.repaired", "${source.label}: '${display(dateCell)}' → $date", Severity.INFO)
            }
            listOf(opening, received, issue, balance).filter { it.origin == CellOrigin.ERROR }.forEach {
                issues += ImportIssue(name, it.cell, "rm.error_value", "Cell shows an Excel error; value ignored")
            }
            if (issue.origin == CellOrigin.EXTERNAL_LINK) issues += ImportIssue(name, issue.cell, "rm.external_link", "ISSUE refers to another workbook (${issue.formula}); its cached value is used")
            out += LegacyRawMaterialRow(name, r, date, source, dateCell?.let(::display), opening, received, issue, balance, issueTotal)
        }
        return out
    }

    // ------------------------------------------------------------------ dates

    fun interpretDate(cell: XlsxReader.Cell?, month: Int, year: Int, d1904: Boolean): Pair<LocalDate?, DateSource> {
        if (cell == null) return null to DateSource.UNRESOLVED
        cell.dateValue(d1904)?.let { d ->
            if (d.monthValue == month) return d to DateSource.EXACT
            // Typed "01/09/2026" (1 Sep) read as 9 Jan: the day field holds the month.
            if (d.dayOfMonth == month) return safeDate(year, month, d.monthValue)?.let { it to DateSource.DAY_MONTH_SWAPPED } ?: (null to DateSource.UNRESOLVED)
            return null to DateSource.UNRESOLVED
        }
        val text = cell.text?.trim() ?: return null to DateSource.UNRESOLVED
        if (text.isEmpty()) return null to DateSource.UNRESOLVED
        val m = Regex("""^(\d{1,2})[/.-](\d{1,2})(?:[/.-](\d{2,4}))?""").find(text) ?: return null to DateSource.UNRESOLVED
        val a = m.groupValues[1].toInt()
        val b = m.groupValues[2].toInt()
        val y = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()?.let { if (it < 100) 2000 + it else it }
        val day = when (month) { b -> a; a -> b; else -> return null to DateSource.UNRESOLVED }
        val date = safeDate(year, month, day) ?: return null to DateSource.UNRESOLVED
        return date to if (y != null && y != year) DateSource.YEAR_CORRECTED else DateSource.TEXT_PARSED
    }

    private fun safeDate(y: Int, m: Int, d: Int): LocalDate? = runCatching { LocalDate.of(y, m, d) }.getOrNull()

    /**
     * Blank block dates (all of August) are recovered from the raw-material rows whose ISSUE cell points at that
     * block's TOTAL row; anything still missing is interpolated between known neighbours when the gap is unambiguous.
     */
    private fun resolveBlankDates(days: List<LegacyDay>, rm: List<LegacyRawMaterialRow>, sheet: String, month: Int, issues: MutableList<ImportIssue>): List<LegacyDay> {
        val byTotal = rm.filter { it.date != null && it.issueTotalRow != null }.associate { it.issueTotalRow!! to it.date!! }
        val first = days.map { d ->
            if (d.date != null) d else {
                val viaRm = d.totalRow?.let { byTotal[it] }
                if (viaRm != null) d.copy(date = viaRm, dateSource = DateSource.FROM_RAW_MATERIAL_ROW) else d
            }
        }.toMutableList()
        // A block dated the same as (or before) the previous one is a typing slip; the raw-material row that
        // points at this block's TOTAL settles the real date when there is one.
        for (i in 1 until first.size) {
            val prevDate = first[i - 1].date ?: continue
            val cur = first[i]
            val curDate = cur.date ?: continue
            if (curDate.isAfter(prevDate)) continue
            val viaRm = cur.totalRow?.let { byTotal[it] }
            if (viaRm != null && viaRm.isAfter(prevDate)) {
                issues += ImportIssue(sheet, "row ${cur.headerRow - 1}", "date.duplicate_fixed",
                    "Block labelled $curDate repeats the previous day; the raw-material row referencing its TOTAL dates it $viaRm", Severity.WARNING)
                first[i] = cur.copy(date = viaRm, dateSource = DateSource.FROM_RAW_MATERIAL_ROW)
            }
        }
        for (i in first.indices) {
            if (first[i].date != null) continue
            val prev = first.subList(0, i).lastOrNull { it.date != null }
            val nextIdx = (i + 1 until first.size).firstOrNull { first[it].date != null }
            val prevDate = prev?.date
            val guess: LocalDate? = when {
                prevDate != null && nextIdx != null -> {
                    val nextDate = first[nextIdx].date!!
                    val gapBlocks = nextIdx - first.indexOf(prev)
                    // Unambiguous only when the calendar gap equals the number of blocks (consecutive days).
                    if (java.time.temporal.ChronoUnit.DAYS.between(prevDate, nextDate).toInt() == gapBlocks) prevDate.plusDays((i - first.indexOf(prev)).toLong()) else null
                }
                else -> null
            }
            if (guess != null && guess.monthValue == month) {
                first[i] = first[i].copy(date = guess, dateSource = DateSource.INTERPOLATED)
            }
        }
        first.forEach { d ->
            when (d.dateSource) {
                DateSource.FROM_RAW_MATERIAL_ROW, DateSource.INTERPOLATED ->
                    issues += ImportIssue(sheet, "row ${d.headerRow - 1}", "date.inferred", "${d.dateSource.label}: ${d.date}", Severity.WARNING)
                DateSource.UNRESOLVED -> issues += ImportIssue(sheet, "row ${d.headerRow - 1}", "date.unresolved", "Daily block ${d.blockIndex + 1} has no usable date; it is not imported", Severity.ERROR)
                else -> Unit
            }
        }
        // Dates must increase within a sheet.
        first.zipWithNext().forEach { (a, b) ->
            if (a.date != null && b.date != null && !b.date.isAfter(a.date)) {
                issues += ImportIssue(sheet, "row ${b.headerRow - 1}", "date.order", "Block date ${b.date} is not after the previous block (${a.date})", Severity.WARNING)
            }
        }
        return first
    }

    // ------------------------------------------------------------------ cells

    fun value(sheet: XlsxReader.Sheet, row: Int, col: Int): LegacyValue {
        val c = sheet[row, col]
        val addr = XlsxReader.columnName(col) + row
        if (c == null) return LegacyValue(null, null, CellOrigin.BLANK, addr)
        // Excel stores binary doubles (33.839999999999989 for 33.84); six decimals removes that noise without
        // touching any real figure (the workbooks never use more than three).
        val v = when (c.type) {
            XlsxReader.CellType.NUMBER -> c.number
            XlsxReader.CellType.STRING -> c.text?.trim()?.toBigDecimalOrNull()
            else -> null
        }?.setScale(6, java.math.RoundingMode.HALF_UP)?.stripTrailingZeros()
        val f = c.formula
        val origin = when {
            c.type == XlsxReader.CellType.ERROR -> CellOrigin.ERROR
            f == null -> if (v == null && c.type != XlsxReader.CellType.NUMBER) CellOrigin.BLANK else CellOrigin.TYPED_VALUE
            '[' in f -> CellOrigin.EXTERNAL_LINK
            f.contains("SUMIF", ignoreCase = true) -> CellOrigin.SUMIF_TEMPLATE
            '!' in f -> CellOrigin.CROSS_SHEET
            Regex("""^[\d.\s+*\-/()]+$""").matches(f) -> CellOrigin.TYPED_SUM
            Regex("""^\$?[A-Z]{1,3}\$?\d+$""").matches(f.trim()) -> CellOrigin.LINK_PREVIOUS
            else -> CellOrigin.FORMULA
        }
        return LegacyValue(v, f?.let { "=$it" }, origin, addr)
    }

    private fun display(c: XlsxReader.Cell?): String = when {
        c == null -> ""
        c.isDateStyle && c.number != null -> XlsxReader.excelSerialToDate(c.number!!, false)?.toString() ?: c.raw.orEmpty()
        else -> c.raw.orEmpty()
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
