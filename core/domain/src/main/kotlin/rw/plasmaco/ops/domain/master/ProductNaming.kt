package rw.plasmaco.ops.domain.master

import java.math.BigDecimal

/**
 * Parses and normalises the legacy product names ("HDPE PIPE PE-PE100 DN110 PN10", "…PE-PE100DN160 PN10",
 * "…DN400PN25", "…PE-PE100E DN40 PN10") into structured DN/PN and generates canonical names and codes.
 */
object ProductNaming {
    private val dnPn = Regex("""DN\s*(\d+)\s*PN\s*(\d+(?:[.,]\d+)?)""", RegexOption.IGNORE_CASE)
    private val grade = Regex("""PE\s*-?\s*(PE)?\s*(80|100)""", RegexOption.IGNORE_CASE)

    /** SDR for PE100 per pressure class, as printed in the workbook's ISO 4427-2 sheet header. */
    val PE100_SDR: Map<BigDecimal, BigDecimal> = linkedMapOf(
        BigDecimal("4") to BigDecimal("41"),
        BigDecimal("5") to BigDecimal("33"),
        BigDecimal("6.3") to BigDecimal("26"),
        BigDecimal("8") to BigDecimal("21"),
        BigDecimal("10") to BigDecimal("17"),
        BigDecimal("12.5") to BigDecimal("13.6"),
        BigDecimal("16") to BigDecimal("11"),
        BigDecimal("20") to BigDecimal("9"),
        BigDecimal("25") to BigDecimal("7.4"),
    )

    data class Parsed(val dn: Int, val pn: BigDecimal, val grade: String)

    fun parse(legacyName: String): Parsed? {
        val m = dnPn.find(legacyName) ?: return null
        val dn = m.groupValues[1].toInt()
        val pn = BigDecimal(m.groupValues[2].replace(',', '.')).stripTrailingZeros()
        val g = grade.find(legacyName)?.groupValues?.get(2)?.let { "PE$it" } ?: "PE100"
        return Parsed(dn, pn, g)
    }

    fun pnText(pn: BigDecimal): String = pn.stripTrailingZeros().toPlainString()

    /** Canonical display name, keeping PLASMACO's own wording but with consistent spacing. */
    fun canonicalName(dn: Int, pn: BigDecimal, grade: String = "PE100"): String = "HDPE PIPE PE-$grade DN$dn PN${pnText(pn)}"

    fun code(dn: Int, pn: BigDecimal, grade: String = "PE100"): String = "$grade-DN$dn-PN${pnText(pn)}"

    fun sdrFor(pn: BigDecimal, grade: String = "PE100"): BigDecimal? =
        if (grade == "PE100") PE100_SDR.entries.firstOrNull { it.key.compareTo(pn) == 0 }?.value else null

    /** Key used to match legacy name variants to one product. */
    fun matchKey(legacyName: String): String? = parse(legacyName)?.let { "${it.grade}|${it.dn}|${pnText(it.pn)}" }
}
