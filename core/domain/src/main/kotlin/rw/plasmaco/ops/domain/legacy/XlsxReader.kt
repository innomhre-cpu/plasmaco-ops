package rw.plasmaco.ops.domain.legacy

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory

/**
 * Minimal, streaming reader for .xlsx/.xlsm files. It reads cached values and formula text; it never evaluates
 * formulas or runs macros, so an untrusted workbook cannot execute anything. Size limits guard against zip bombs.
 */
class XlsxReader private constructor(private val parts: Map<String, ByteArray>) {

    data class SheetInfo(val name: String, val path: String, val state: String)

    enum class CellType { NUMBER, STRING, BOOLEAN, ERROR, BLANK }

    data class Cell(
        val row: Int,
        val col: Int,
        val type: CellType,
        /** Raw cached value text (number text, string, "TRUE", "#VALUE!"). */
        val raw: String?,
        /** Formula text without the leading '=', expanded for shared formulas; null for constants. */
        val formula: String?,
        val isDateStyle: Boolean,
    ) {
        val number: BigDecimal? get() = if (type == CellType.NUMBER) raw?.toBigDecimalOrNull() else null
        val text: String? get() = if (type == CellType.STRING) raw else null
        val address: String get() = columnName(col) + row
        val isFormula: Boolean get() = formula != null

        fun dateValue(date1904: Boolean): LocalDate? {
            val n = number ?: return null
            if (!isDateStyle) return null
            return excelSerialToDate(n, date1904)
        }
    }

    class Sheet(val info: SheetInfo, private val cells: Map<Long, Cell>, val maxRow: Int) {
        operator fun get(row: Int, col: Int): Cell? = cells[key(row, col)]
        fun rowCells(row: Int): List<Cell> = (1..MAX_COL).mapNotNull { cells[key(row, it)] }
        val cellCount: Int get() = cells.size
    }

    val date1904: Boolean
    val sheets: List<SheetInfo>
    private val sharedStrings: List<String>
    private val dateStyles: Set<Int>

    init {
        val workbook = parts["xl/workbook.xml"] ?: throw InvalidWorkbookException("Not an Excel workbook (xl/workbook.xml missing)")
        val rels = parts["xl/_rels/workbook.xml.rels"]?.let { parseRelationships(it) }.orEmpty()
        var is1904 = false
        val infos = mutableListOf<SheetInfo>()
        parse(workbook, object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String, a: Attributes) {
                when (local(qName)) {
                    "workbookPr" -> is1904 = a.getValue("date1904").let { it == "1" || it == "true" }
                    "sheet" -> {
                        val rid = a.getValue("r:id") ?: (0 until a.length).firstOrNull { a.getQName(it).endsWith(":id") }?.let { a.getValue(it) }
                        val target = rels[rid] ?: return
                        val path = if (target.startsWith("/")) target.removePrefix("/") else "xl/" + target.removePrefix("./")
                        infos += SheetInfo(a.getValue("name") ?: "Sheet", path, a.getValue("state") ?: "visible")
                    }
                }
            }
        })
        date1904 = is1904
        sheets = infos
        sharedStrings = parts["xl/sharedStrings.xml"]?.let { parseSharedStrings(it) }.orEmpty()
        dateStyles = parts["xl/styles.xml"]?.let { parseDateStyles(it) }.orEmpty()
    }

    fun sheet(name: String): Sheet? = sheets.firstOrNull { it.name == name }?.let { readSheet(it) }

    fun readSheet(info: SheetInfo, maxCol: Int = MAX_COL): Sheet {
        val bytes = parts[info.path] ?: throw InvalidWorkbookException("Sheet ${info.name} is missing its data")
        val cells = HashMap<Long, Cell>(4096)
        val sharedMasters = HashMap<String, Pair<String, Pair<Int, Int>>>() // si -> (formula, (row,col))
        var maxRow = 0
        parse(bytes, object : DefaultHandler() {
            var row = 0; var col = 0; var type = "n"; var style = 0
            var formula: String? = null; var sharedSi: String? = null; var sharedRef = false
            var value: String? = null; var inline = StringBuilder()
            val text = StringBuilder(); var collecting = false; var inIs = false

            override fun startElement(uri: String?, localName: String?, qName: String, a: Attributes) {
                when (local(qName)) {
                    "c" -> {
                        val ref = a.getValue("r") ?: ""
                        val (r, c) = parseRef(ref) ?: (row to col + 1)
                        row = r; col = c
                        type = a.getValue("t") ?: "n"
                        style = a.getValue("s")?.toIntOrNull() ?: 0
                        formula = null; sharedSi = null; sharedRef = false; value = null; inline = StringBuilder()
                    }
                    "f" -> {
                        text.setLength(0); collecting = true
                        sharedSi = if (a.getValue("t") == "shared") a.getValue("si") else null
                        sharedRef = a.getValue("ref") != null
                    }
                    "v" -> { text.setLength(0); collecting = true }
                    "is" -> inIs = true
                    "t" -> if (inIs) { text.setLength(0); collecting = true }
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (collecting) text.appendRange(ch, start, start + length)
            }

            override fun endElement(uri: String?, localName: String?, qName: String) {
                when (local(qName)) {
                    "f" -> {
                        collecting = false
                        val f = text.toString()
                        val si = sharedSi
                        formula = when {
                            si != null && f.isNotEmpty() -> { sharedMasters[si] = f to (row to col); f }
                            si != null -> sharedMasters[si]?.let { (master, at) -> FormulaShifter.shift(master, row - at.first, col - at.second) }
                            else -> f
                        }
                    }
                    "v" -> { collecting = false; value = text.toString() }
                    "t" -> if (inIs) { collecting = false; inline.append(text) }
                    "is" -> inIs = false
                    "c" -> {
                        if (col in 1..maxCol) {
                            val (cellType, raw) = when (type) {
                                "s" -> CellType.STRING to value?.toIntOrNull()?.let { sharedStrings.getOrNull(it) }
                                "str" -> CellType.STRING to value
                                "inlineStr" -> CellType.STRING to inline.toString()
                                "b" -> CellType.BOOLEAN to value
                                "e" -> CellType.ERROR to value
                                else -> if (value == null) CellType.BLANK to null else CellType.NUMBER to value
                            }
                            if (cellType != CellType.BLANK || formula != null) {
                                cells[key(row, col)] = Cell(row, col, cellType, raw, formula, style in dateStyles)
                                if (row > maxRow) maxRow = row
                            }
                        }
                    }
                }
            }
        })
        return Sheet(info, cells, maxRow)
    }

    companion object {
        const val MAX_COL = 40
        private const val MAX_PART_BYTES = 64L * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 256L * 1024 * 1024
        private val wanted = Regex("""^(xl/workbook\.xml|xl/_rels/workbook\.xml\.rels|xl/sharedStrings\.xml|xl/styles\.xml|xl/worksheets/[^/]+\.xml)$""")

        fun open(input: InputStream): XlsxReader {
            val parts = HashMap<String, ByteArray>()
            var total = 0L
            ZipInputStream(input).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    val name = entry.name.trimStart('/')
                    if (!entry.isDirectory && wanted.matches(name)) {
                        val buf = java.io.ByteArrayOutputStream()
                        val chunk = ByteArray(64 * 1024)
                        var read: Int
                        var size = 0L
                        while (zip.read(chunk).also { read = it } > 0) {
                            size += read; total += read
                            if (size > MAX_PART_BYTES || total > MAX_TOTAL_BYTES) throw InvalidWorkbookException("Workbook is too large to import safely")
                            buf.write(chunk, 0, read)
                        }
                        parts[name] = buf.toByteArray()
                    }
                    entry = zip.nextEntry
                }
            }
            if (parts.isEmpty()) throw InvalidWorkbookException("The file is not an .xlsx or .xlsm workbook")
            return XlsxReader(parts)
        }

        fun key(row: Int, col: Int): Long = row.toLong() * 1024 + col

        fun columnName(col: Int): String {
            var n = col
            val sb = StringBuilder()
            while (n > 0) { val r = (n - 1) % 26; sb.append('A' + r); n = (n - 1) / 26 }
            return sb.reverse().toString()
        }

        fun columnIndex(name: String): Int = name.uppercase().fold(0) { acc, ch -> acc * 26 + (ch - 'A' + 1) }

        fun parseRef(ref: String): Pair<Int, Int>? {
            val m = Regex("""^\$?([A-Za-z]{1,3})\$?(\d+)$""").find(ref) ?: return null
            return m.groupValues[2].toInt() to columnIndex(m.groupValues[1])
        }

        fun excelSerialToDate(serial: BigDecimal, date1904: Boolean): LocalDate? {
            val days = serial.toLong()
            if (days <= 0 || days > 2_958_465) return null
            return if (date1904) LocalDate.of(1904, 1, 1).plusDays(days)
            else LocalDate.of(1899, 12, 30).plusDays(days) // Excel's 1900 leap-year bug is absorbed by the 30 Dec epoch for dates after Feb 1900
        }

        private fun local(qName: String) = qName.substringAfter(':')

        private fun parse(bytes: ByteArray, handler: DefaultHandler) {
            val factory = SAXParserFactory.newInstance()
            factory.isNamespaceAware = false
            runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            factory.newSAXParser().parse(InputSource(ByteArrayInputStream(bytes)), handler)
        }

        private fun parseRelationships(bytes: ByteArray): Map<String, String> {
            val map = HashMap<String, String>()
            parse(bytes, object : DefaultHandler() {
                override fun startElement(uri: String?, localName: String?, qName: String, a: Attributes) {
                    if (local(qName) == "Relationship") a.getValue("Id")?.let { id -> a.getValue("Target")?.let { map[id] = it } }
                }
            })
            return map
        }

        private fun parseSharedStrings(bytes: ByteArray): List<String> {
            val out = ArrayList<String>()
            parse(bytes, object : DefaultHandler() {
                val si = StringBuilder(); var inT = false; var inRph = false
                override fun startElement(uri: String?, localName: String?, qName: String, a: Attributes) {
                    when (local(qName)) { "si" -> si.setLength(0); "t" -> inT = true; "rPh" -> inRph = true }
                }
                override fun characters(ch: CharArray, start: Int, length: Int) { if (inT && !inRph) si.appendRange(ch, start, start + length) }
                override fun endElement(uri: String?, localName: String?, qName: String) {
                    when (local(qName)) { "t" -> inT = false; "rPh" -> inRph = false; "si" -> out += si.toString() }
                }
            })
            return out
        }

        private val builtInDateFormats = setOf(14, 15, 16, 17, 18, 19, 20, 21, 22, 27, 30, 36, 45, 46, 47, 50, 57)

        private fun parseDateStyles(bytes: ByteArray): Set<Int> {
            val customDate = HashSet<Int>()
            val xfFormats = ArrayList<Int>()
            parse(bytes, object : DefaultHandler() {
                var inCellXfs = false
                override fun startElement(uri: String?, localName: String?, qName: String, a: Attributes) {
                    when (local(qName)) {
                        "numFmt" -> {
                            val id = a.getValue("numFmtId")?.toIntOrNull() ?: return
                            val code = a.getValue("formatCode") ?: return
                            if (isDateFormat(code)) customDate += id
                        }
                        "cellXfs" -> inCellXfs = true
                        "xf" -> if (inCellXfs) xfFormats += (a.getValue("numFmtId")?.toIntOrNull() ?: 0)
                    }
                }
                override fun endElement(uri: String?, localName: String?, qName: String) { if (local(qName) == "cellXfs") inCellXfs = false }
            })
            return xfFormats.withIndex().filter { (_, fmt) -> fmt in builtInDateFormats || fmt in customDate }.map { it.index }.toSet()
        }

        /** A number format is a date format when it uses d, m or y outside quoted text and colour/condition brackets. */
        fun isDateFormat(code: String): Boolean {
            val stripped = code.replace(Regex("\"[^\"]*\""), "").replace(Regex("""\[[^\]]*\]"""), "").replace(Regex("""\\."""), "")
            return stripped.any { it == 'd' || it == 'y' || it == 'D' || it == 'Y' } || (stripped.contains('m', true) && !stripped.contains('0') && !stripped.contains('#'))
        }
    }
}

class InvalidWorkbookException(message: String) : Exception(message)

/** Expands a shared formula for a cell offset from its master, shifting relative A1 references (not $-anchored parts). */
object FormulaShifter {
    private val ref = Regex("""(?<![A-Za-z_\d.!])(\$?)([A-Z]{1,3})(\$?)(\d{1,7})(?![\d(A-Za-z_])""")
    private val sheetRef = Regex("""(?<=!)(\$?)([A-Z]{1,3})(\$?)(\d{1,7})(?![\d(A-Za-z_])""")

    fun shift(formula: String, dRow: Int, dCol: Int): String {
        if (dRow == 0 && dCol == 0) return formula
        val out = StringBuilder()
        var i = 0
        // Walk the formula, skipping string literals and quoted sheet names.
        while (i < formula.length) {
            val ch = formula[i]
            if (ch == '"' || ch == '\'') {
                val end = formula.indexOf(ch, i + 1).let { if (it < 0) formula.length - 1 else it }
                out.append(formula, i, end + 1); i = end + 1; continue
            }
            val next = formula.indexOfAny(charArrayOf('"', '\''), i).let { if (it < 0) formula.length else it }
            out.append(shiftPlain(formula.substring(i, next), dRow, dCol)); i = next
        }
        return out.toString()
    }

    private fun shiftPlain(s: String, dRow: Int, dCol: Int): String {
        val fn = { m: MatchResult ->
            val (colAbs, col, rowAbs, row) = m.destructured
            val newCol = if (colAbs.isEmpty()) XlsxReader.columnName(XlsxReader.columnIndex(col) + dCol) else col
            val newRow = if (rowAbs.isEmpty()) (row.toInt() + dRow).toString() else row
            "$colAbs$newCol$rowAbs$newRow"
        }
        return sheetRef.replace(ref.replace(s, fn), fn)
    }
}
