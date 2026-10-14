package rw.plasmaco.ops.domain.export.xlsx

import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes valid Office Open XML spreadsheets without third-party libraries: named sheets, typed cells (numbers stay
 * numbers, dates are real Excel dates), number formats, bold headers, frozen header rows, column widths, and
 * Excel tables (structured references with filter buttons).
 */
class XlsxWriter {
    enum class Style(val id: Int) {
        DEFAULT(0), HEADER(1), INT(2), DEC1(3), DEC2(4), DEC3(5), PCT1(6), PCT2(7), DATE(8), TITLE(9), BOLD(10),
        BOLD_DEC1(11), BOLD_DEC2(12), BOLD_INT(13), MUTED(14), DATETIME(15),
    }

    sealed interface XCell {
        data class Str(val value: String, val style: Style = Style.DEFAULT) : XCell
        data class Num(val value: BigDecimal, val style: Style = Style.DEC2) : XCell
        data class Day(val value: LocalDate, val style: Style = Style.DATE) : XCell
        data object Blank : XCell
    }

    data class TableSpec(val name: String, val headerRow: Int, val firstCol: Int, val lastCol: Int, val lastRow: Int, val headers: List<String>)

    class Sheet internal constructor(val name: String) {
        internal val rows = mutableListOf<List<XCell>>()
        internal var widths: List<Double>? = null
        internal var freezeRows = 0
        internal var table: TableSpec? = null

        fun row(vararg cells: XCell) { rows += cells.toList() }
        fun row(cells: List<XCell>) { rows += cells }
        fun blankRow() { rows += emptyList<XCell>() }
        val nextRow: Int get() = rows.size + 1
        fun columnWidths(w: List<Double>) { widths = w }
        fun freezeTopRows(n: Int) { freezeRows = n }
        fun table(spec: TableSpec) { table = spec }
    }

    private val sheets = mutableListOf<Sheet>()

    fun sheet(name: String): Sheet {
        val clean = uniqueName(name)
        return Sheet(clean).also { sheets += it }
    }

    private fun uniqueName(raw: String): String {
        val base = raw.replace(Regex("""[\\/?*\[\]:]"""), " ").trim().take(31).ifEmpty { "Sheet" }
        var name = base
        var i = 2
        while (sheets.any { it.name.equals(name, ignoreCase = true) }) { val suffix = " ($i)"; name = base.take(31 - suffix.length) + suffix; i++ }
        return name
    }

    fun build(title: String, creator: String, created: Instant): ByteArray {
        require(sheets.isNotEmpty()) { "A workbook needs at least one sheet" }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(path: String, content: String) {
                zip.putNextEntry(ZipEntry(path)); zip.write(content.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            }
            val tables = sheets.mapIndexedNotNull { i, s -> s.table?.let { i to it } }
            put("[Content_Types].xml", contentTypes(tables.size))
            put("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/><Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/></Relationships>""")
            put("docProps/core.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"><dc:title>${esc(title)}</dc:title><dc:creator>${esc(creator)}</dc:creator><dcterms:created xsi:type="dcterms:W3CDTF">${created.truncatedTo(ChronoUnit.SECONDS)}</dcterms:created></cp:coreProperties>""")
            put("docProps/app.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties"><Application>Plasmaco Operations</Application></Properties>""")
            put("xl/workbook.xml", workbookXml())
            put("xl/_rels/workbook.xml.rels", workbookRels())
            put("xl/styles.xml", STYLES)
            var tableIndex = 0
            sheets.forEachIndexed { i, s ->
                val tIdx = if (s.table != null) ++tableIndex else null
                put("xl/worksheets/sheet${i + 1}.xml", sheetXml(s, tIdx))
                if (tIdx != null) {
                    put("xl/worksheets/_rels/sheet${i + 1}.xml.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/table" Target="../tables/table$tIdx.xml"/></Relationships>""")
                    put("xl/tables/table$tIdx.xml", tableXml(s.table!!, tIdx))
                }
            }
        }
        return out.toByteArray()
    }

    private fun contentTypes(tables: Int): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/><Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/><Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>""")
        sheets.indices.forEach { append("""<Override PartName="/xl/worksheets/sheet${it + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""") }
        (1..tables).forEach { append("""<Override PartName="/xl/tables/table$it.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.table+xml"/>""") }
        append("</Types>")
    }

    private fun workbookXml(): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><bookViews><workbookView activeTab="0"/></bookViews><sheets>""")
        sheets.forEachIndexed { i, s -> append("""<sheet name="${esc(s.name)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""") }
        append("</sheets></workbook>")
    }

    private fun workbookRels(): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        sheets.indices.forEach { append("""<Relationship Id="rId${it + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${it + 1}.xml"/>""") }
        append("""<Relationship Id="rId${sheets.size + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""")
    }

    private fun sheetXml(s: Sheet, tableIdx: Int?): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""")
        val maxCols = s.rows.maxOfOrNull { it.size } ?: 1
        append("""<dimension ref="A1:${col(maxCols)}${maxOf(1, s.rows.size)}"/>""")
        append("<sheetViews><sheetView workbookViewId=\"0\"")
        if (s.freezeRows > 0) append(""" tabSelected="${if (sheets.first() === s) 1 else 0}"><pane ySplit="${s.freezeRows}" topLeftCell="A${s.freezeRows + 1}" activePane="bottomLeft" state="frozen"/><selection pane="bottomLeft" activeCell="A${s.freezeRows + 1}" sqref="A${s.freezeRows + 1}"/></sheetView>""")
        else append("/>")
        append("</sheetViews><sheetFormatPr defaultRowHeight=\"15\"/>")
        s.widths?.let { w ->
            append("<cols>")
            w.forEachIndexed { i, width -> append("""<col min="${i + 1}" max="${i + 1}" width="${"%.1f".format(java.util.Locale.ROOT, width)}" customWidth="1"/>""") }
            append("</cols>")
        }
        append("<sheetData>")
        s.rows.forEachIndexed { r, cells ->
            append("""<row r="${r + 1}">""")
            cells.forEachIndexed { c, cell ->
                val ref = "${col(c + 1)}${r + 1}"
                when (cell) {
                    is XCell.Str -> append("""<c r="$ref" t="inlineStr" s="${cell.style.id}"><is><t xml:space="preserve">${esc(cell.value)}</t></is></c>""")
                    is XCell.Num -> append("""<c r="$ref" s="${cell.style.id}"><v>${cell.value.stripTrailingZeros().toPlainString()}</v></c>""")
                    is XCell.Day -> append("""<c r="$ref" s="${cell.style.id}"><v>${serial(cell.value)}</v></c>""")
                    XCell.Blank -> Unit
                }
            }
            append("</row>")
        }
        append("</sheetData>")
        append("""<pageMargins left="0.5" right="0.5" top="0.6" bottom="0.6" header="0.3" footer="0.3"/>""")
        append("""<pageSetup orientation="landscape" fitToWidth="1" fitToHeight="0"/>""")
        if (tableIdx != null) append("""<tableParts count="1"><tablePart r:id="rId1"/></tableParts>""")
        append("</worksheet>")
    }

    private fun tableXml(t: TableSpec, idx: Int): String = buildString {
        val ref = "${col(t.firstCol)}${t.headerRow}:${col(t.lastCol)}${maxOf(t.lastRow, t.headerRow + 1)}"
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<table xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" id="$idx" name="${esc(t.name)}" displayName="${esc(t.name)}" ref="$ref" totalsRowShown="0"><autoFilter ref="$ref"/><tableColumns count="${t.headers.size}">""")
        t.headers.forEachIndexed { i, h -> append("""<tableColumn id="${i + 1}" name="${esc(h)}"/>""") }
        append("""</tableColumns><tableStyleInfo name="TableStyleLight1" showFirstColumn="0" showLastColumn="0" showRowStripes="1" showColumnStripes="0"/></table>""")
    }

    companion object {
        fun col(index: Int): String {
            var n = index
            val sb = StringBuilder()
            while (n > 0) { val r = (n - 1) % 26; sb.append('A' + r); n = (n - 1) / 26 }
            return sb.reverse().toString()
        }

        fun serial(date: LocalDate): Long = ChronoUnit.DAYS.between(LocalDate.of(1899, 12, 30), date)

        fun esc(s: String): String = buildString {
            for (ch in s) when {
                ch == '&' -> append("&amp;")
                ch == '<' -> append("&lt;")
                ch == '>' -> append("&gt;")
                ch == '"' -> append("&quot;")
                ch.code < 0x20 && ch != '\t' && ch != '\n' && ch != '\r' -> append(' ')
                else -> append(ch)
            }
        }

        /** Excel table names: letters, digits, underscores; must start with a letter; unique in the workbook. */
        fun tableName(raw: String, index: Int): String {
            val cleaned = raw.replace(Regex("[^A-Za-z0-9_]"), "_").trim('_').ifEmpty { "Table" }
            val start = if (cleaned.first().isLetter()) cleaned else "T_$cleaned"
            return (start.take(200) + "_$index")
        }

        // Header fill is the primary tint (#E3EEED) with primary text (#17504E), matching the app's tables.
        private val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<numFmts count="7"><numFmt numFmtId="164" formatCode="#,##0.0"/><numFmt numFmtId="165" formatCode="#,##0.000"/><numFmt numFmtId="166" formatCode="0.0%"/><numFmt numFmtId="167" formatCode="yyyy-mm-dd"/><numFmt numFmtId="168" formatCode="0.00%"/><numFmt numFmtId="169" formatCode="yyyy-mm-dd hh:mm"/><numFmt numFmtId="170" formatCode="#,##0.00"/></numFmts>
<fonts count="4"><font><sz val="10"/><name val="Arial"/><family val="2"/></font><font><b/><sz val="10"/><color rgb="FF17504E"/><name val="Arial"/><family val="2"/></font><font><b/><sz val="14"/><color rgb="FF111414"/><name val="Arial"/><family val="2"/></font><font><sz val="9"/><color rgb="FF626B6B"/><name val="Arial"/><family val="2"/></font></fonts>
<fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FFE3EEED"/><bgColor indexed="64"/></patternFill></fill></fills>
<borders count="2"><border><left/><right/><top/><bottom/><diagonal/></border><border><left/><right/><top style="thin"><color rgb="FF17504E"/></top><bottom/><diagonal/></border></borders>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="16">
<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
<xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"><alignment wrapText="1" vertical="center"/></xf>
<xf numFmtId="3" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
<xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
<xf numFmtId="170" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
<xf numFmtId="165" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
<xf numFmtId="166" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
<xf numFmtId="168" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
<xf numFmtId="167" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
<xf numFmtId="0" fontId="2" fillId="0" borderId="0" xfId="0" applyFont="1"/>
<xf numFmtId="0" fontId="1" fillId="0" borderId="1" xfId="0" applyFont="1" applyBorder="1"/>
<xf numFmtId="164" fontId="1" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyFont="1" applyBorder="1"/>
<xf numFmtId="170" fontId="1" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyFont="1" applyBorder="1"/>
<xf numFmtId="3" fontId="1" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyFont="1" applyBorder="1"/>
<xf numFmtId="0" fontId="3" fillId="0" borderId="0" xfId="0" applyFont="1"/>
<xf numFmtId="169" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
</cellXfs>
<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
</styleSheet>"""
    }
}
