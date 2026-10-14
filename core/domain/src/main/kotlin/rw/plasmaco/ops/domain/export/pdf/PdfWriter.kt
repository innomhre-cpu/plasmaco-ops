package rw.plasmaco.ops.domain.export.pdf

import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.Deflater

/** An RGB colour with components 0..1, built from a hex string. */
data class PdfColor(val r: Float, val g: Float, val b: Float) {
    companion object {
        fun hex(hex: String): PdfColor {
            val v = hex.removePrefix("#").toInt(16)
            return PdfColor(((v shr 16) and 0xFF) / 255f, ((v shr 8) and 0xFF) / 255f, (v and 0xFF) / 255f)
        }
    }
}

enum class PdfFont(val resource: String, val baseFont: String) { REGULAR("F1", "Helvetica"), BOLD("F2", "Helvetica-Bold") }

/**
 * A small, dependency-free PDF 1.4 writer: standard Helvetica fonts (WinAnsi), text, lines, rectangles, rounded
 * rectangles and polylines, Flate-compressed page streams and a correct cross-reference table. Coordinates in the
 * public API are measured from the TOP-left corner in points, which keeps layout code readable.
 */
class PdfWriter(val pageWidth: Float, val pageHeight: Float) {
    inner class Page internal constructor() {
        internal val ops = StringBuilder()

        private fun y(top: Float) = pageHeight - top

        fun text(x: Float, top: Float, value: String, size: Float, font: PdfFont = PdfFont.REGULAR, color: PdfColor = BLACK) {
            if (value.isEmpty()) return
            ops.append("BT /").append(font.resource).append(' ').append(n(size)).append(" Tf ")
            fill(color)
            ops.append(n(x)).append(' ').append(n(y(top))).append(" Td (").append(encode(value)).append(") Tj ET\n")
        }

        fun textRight(xRight: Float, top: Float, value: String, size: Float, font: PdfFont = PdfFont.REGULAR, color: PdfColor = BLACK) =
            text(xRight - width(value, size, font), top, value, size, font, color)

        fun textCentered(xCenter: Float, top: Float, value: String, size: Float, font: PdfFont = PdfFont.REGULAR, color: PdfColor = BLACK) =
            text(xCenter - width(value, size, font) / 2, top, value, size, font, color)

        fun line(x1: Float, top1: Float, x2: Float, top2: Float, color: PdfColor, width: Float = 0.5f, dash: FloatArray? = null) {
            ops.append("q ")
            stroke(color)
            ops.append(n(width)).append(" w ")
            if (dash != null) ops.append('[').append(dash.joinToString(" ") { n(it) }).append("] 0 d ")
            ops.append(n(x1)).append(' ').append(n(y(top1))).append(" m ").append(n(x2)).append(' ').append(n(y(top2))).append(" l S Q\n")
        }

        fun rect(x: Float, top: Float, w: Float, h: Float, fill: PdfColor? = null, stroke: PdfColor? = null, strokeWidth: Float = 0.5f) {
            if (fill == null && stroke == null) return
            ops.append("q ")
            fill?.let { fill(it) }
            stroke?.let { stroke(it); ops.append(n(strokeWidth)).append(" w ") }
            ops.append(n(x)).append(' ').append(n(y(top + h))).append(' ').append(n(w)).append(' ').append(n(h)).append(" re ")
            ops.append(if (fill != null && stroke != null) "B" else if (fill != null) "f" else "S").append(" Q\n")
        }

        fun roundedRect(x: Float, top: Float, w: Float, h: Float, radius: Float, fill: PdfColor? = null, stroke: PdfColor? = null, strokeWidth: Float = 0.5f) {
            if (fill == null && stroke == null) return
            val r = minOf(radius, w / 2, h / 2)
            val k = 0.5523f * r
            val x0 = x; val x1 = x + w; val yt = y(top); val yb = y(top + h)
            ops.append("q ")
            fill?.let { fill(it) }
            stroke?.let { stroke(it); ops.append(n(strokeWidth)).append(" w ") }
            ops.append("${n(x0 + r)} ${n(yb)} m ${n(x1 - r)} ${n(yb)} l ")
            ops.append("${n(x1 - r + k)} ${n(yb)} ${n(x1)} ${n(yb + r - k)} ${n(x1)} ${n(yb + r)} c ")
            ops.append("${n(x1)} ${n(yt - r)} l ${n(x1)} ${n(yt - r + k)} ${n(x1 - r + k)} ${n(yt)} ${n(x1 - r)} ${n(yt)} c ")
            ops.append("${n(x0 + r)} ${n(yt)} l ${n(x0 + r - k)} ${n(yt)} ${n(x0)} ${n(yt - r + k)} ${n(x0)} ${n(yt - r)} c ")
            ops.append("${n(x0)} ${n(yb + r)} l ${n(x0)} ${n(yb + r - k)} ${n(x0 + r - k)} ${n(yb)} ${n(x0 + r)} ${n(yb)} c h ")
            ops.append(if (fill != null && stroke != null) "B" else if (fill != null) "f" else "S").append(" Q\n")
        }

        /** Open polyline through [points] (x, top) pairs; segments with a null point are broken (gaps for missing data). */
        fun polyline(points: List<Pair<Float, Float>?>, color: PdfColor, width: Float = 1.5f, dash: FloatArray? = null) {
            ops.append("q ")
            stroke(color)
            ops.append(n(width)).append(" w 1 J 1 j ")
            if (dash != null) ops.append('[').append(dash.joinToString(" ") { n(it) }).append("] 0 d ")
            var drawing = false
            for (p in points) {
                if (p == null) { drawing = false; continue }
                ops.append(n(p.first)).append(' ').append(n(y(p.second))).append(if (drawing) " l " else " m ")
                drawing = true
            }
            ops.append("S Q\n")
        }

        fun dot(x: Float, top: Float, radius: Float, color: PdfColor) = roundedRect(x - radius, top - radius, radius * 2, radius * 2, radius, fill = color)

        private fun fill(c: PdfColor) { ops.append(n(c.r)).append(' ').append(n(c.g)).append(' ').append(n(c.b)).append(" rg ") }
        private fun stroke(c: PdfColor) { ops.append(n(c.r)).append(' ').append(n(c.g)).append(' ').append(n(c.b)).append(" RG ") }
    }

    private val pages = mutableListOf<Page>()

    fun newPage(): Page = Page().also { pages += it }

    val pageCount: Int get() = pages.size

    fun page(index: Int): Page = pages[index]

    fun build(title: String, author: String, subject: String? = null, creationDate: java.time.Instant = java.time.Instant.now()): ByteArray {
        val out = ByteArrayOutputStream()
        val offsets = mutableListOf<Int>()
        fun obj(body: ByteArray) {
            offsets += out.size()
            out.write("${offsets.size} 0 obj\n".toByteArray(Charsets.ISO_8859_1))
            out.write(body)
            out.write("\nendobj\n".toByteArray(Charsets.ISO_8859_1))
        }
        fun obj(body: String) = obj(body.toByteArray(Charsets.ISO_8859_1))

        out.write("%PDF-1.4\n%âãÏÓ\n".toByteArray(Charsets.ISO_8859_1))
        // 1 catalog, 2 pages, 3 F1, 4 F2, 5 info, then (page, content) pairs
        val firstPageObj = 6
        val kids = pages.indices.joinToString(" ") { "${firstPageObj + it * 2} 0 R" }
        obj("<< /Type /Catalog /Pages 2 0 R >>")
        obj("<< /Type /Pages /Kids [$kids] /Count ${pages.size} >>")
        obj("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>")
        obj("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>")
        val date = pdfDate(creationDate)
        obj("<< /Title (${encode(title)}) /Author (${encode(author)})${subject?.let { " /Subject (${encode(it)})" } ?: ""} /Producer (Plasmaco Operations) /CreationDate ($date) >>")
        pages.forEachIndexed { i, page ->
            val contentObj = firstPageObj + i * 2 + 1
            obj("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${n(pageWidth)} ${n(pageHeight)}] /Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents $contentObj 0 R >>")
            val raw = page.ops.toString().toByteArray(Charsets.ISO_8859_1)
            val compressed = deflate(raw)
            val head = "<< /Length ${compressed.size} /Filter /FlateDecode >>\nstream\n".toByteArray(Charsets.ISO_8859_1)
            obj(head + compressed + "\nendstream".toByteArray(Charsets.ISO_8859_1))
        }
        val xref = out.size()
        val sb = StringBuilder("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { sb.append(String.format(Locale.ROOT, "%010d 00000 n \n", it)) }
        sb.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R /Info 5 0 R >>\nstartxref\n$xref\n%%EOF\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        return out.toByteArray()
    }

    companion object {
        val BLACK = PdfColor(0f, 0f, 0f)

        fun width(text: String, size: Float, font: PdfFont = PdfFont.REGULAR): Float {
            val table = if (font == PdfFont.BOLD) HelveticaMetrics.BOLD else HelveticaMetrics.REGULAR
            var w = 0
            for (b in winAnsi(text)) {
                val code = b.toInt() and 0xFF
                w += if (code in 32..255) table[code - 32] else 500
            }
            return w * size / 1000f
        }

        /** Truncates with an ellipsis so the text fits [maxWidth]. */
        fun fit(text: String, maxWidth: Float, size: Float, font: PdfFont = PdfFont.REGULAR): String {
            if (width(text, size, font) <= maxWidth) return text
            var s = text
            while (s.isNotEmpty() && width("$s…", size, font) > maxWidth) s = s.dropLast(1)
            return if (s.isEmpty()) "" else "$s…"
        }

        /** Greedy word wrap. */
        fun wrap(text: String, maxWidth: Float, size: Float, font: PdfFont = PdfFont.REGULAR): List<String> {
            val lines = mutableListOf<String>()
            text.split('\n').forEach { para ->
                var cur = ""
                para.split(' ').forEach { word ->
                    val candidate = if (cur.isEmpty()) word else "$cur $word"
                    if (width(candidate, size, font) <= maxWidth || cur.isEmpty()) cur = candidate else { lines += cur; cur = word }
                }
                lines += cur
            }
            return lines.map { fit(it, maxWidth, size, font) }
        }

        private val special = mapOf(
            '€' to 0x80, '‚' to 0x82, 'ƒ' to 0x83, '„' to 0x84, '…' to 0x85, '†' to 0x86, '‡' to 0x87, 'ˆ' to 0x88, '‰' to 0x89,
            'Š' to 0x8A, '‹' to 0x8B, 'Œ' to 0x8C, 'Ž' to 0x8E, '‘' to 0x91, '’' to 0x92, '“' to 0x93, '”' to 0x94, '•' to 0x95,
            '–' to 0x96, '—' to 0x97, '˜' to 0x98, '™' to 0x99, 'š' to 0x9A, '›' to 0x9B, 'œ' to 0x9C, 'ž' to 0x9E, 'Ÿ' to 0x9F,
        )
        private val substitutes = mapOf('−' to "-", '≤' to "<=", '≥' to ">=", '≠' to "!=", 'Σ' to "Sum ", 'Δ' to "Delta ", '→' to "->", '←' to "<-", '✓' to "OK", '≈' to "~", '·' to "·")

        /** Unicode → WinAnsi bytes; characters outside the encoding are substituted or replaced by '?'. */
        fun winAnsi(text: String): ByteArray {
            val out = ByteArrayOutputStream(text.length)
            for (ch in text) {
                val code = ch.code
                when {
                    code in 32..126 || code in 160..255 -> out.write(code)
                    special.containsKey(ch) -> out.write(special.getValue(ch))
                    substitutes.containsKey(ch) -> substitutes.getValue(ch).forEach { out.write(it.code) }
                    ch == '\t' -> out.write(32)
                    else -> out.write('?'.code)
                }
            }
            return out.toByteArray()
        }

        internal fun encode(text: String): String {
            val sb = StringBuilder()
            for (b in winAnsi(text)) {
                val c = b.toInt() and 0xFF
                when {
                    c == '('.code || c == ')'.code || c == '\\'.code -> sb.append('\\').append(c.toChar())
                    c in 32..126 -> sb.append(c.toChar())
                    else -> sb.append('\\').append(String.format(Locale.ROOT, "%03o", c))
                }
            }
            return sb.toString()
        }

        internal fun n(v: Float): String {
            val r = Math.round(v * 100) / 100.0
            return if (r == Math.floor(r)) r.toLong().toString() else String.format(Locale.ROOT, "%.2f", r).trimEnd('0').trimEnd('.')
        }

        private fun deflate(data: ByteArray): ByteArray {
            val d = Deflater(Deflater.BEST_COMPRESSION)
            d.setInput(data); d.finish()
            val buf = ByteArray(8192)
            val out = ByteArrayOutputStream()
            while (!d.finished()) { val k = d.deflate(buf); out.write(buf, 0, k) }
            d.end()
            return out.toByteArray()
        }

        private fun pdfDate(i: java.time.Instant): String {
            val z = java.time.ZonedDateTime.ofInstant(i, java.time.ZoneOffset.UTC)
            return String.format(Locale.ROOT, "D:%04d%02d%02d%02d%02d%02dZ", z.year, z.monthValue, z.dayOfMonth, z.hour, z.minute, z.second)
        }
    }
}
