package rw.plasmaco.ops.domain.master

import rw.plasmaco.ops.domain.common.Id
import rw.plasmaco.ops.domain.common.newId
import java.math.BigDecimal

/**
 * Turns the verified workbook catalogue into product master records an administrator reviews before saving.
 * kg/m comes from the ISO 4427-2 sheet; where the stock sheets used a different figure, or the standard has
 * no figure, the product carries a note so the difference is visible rather than silently resolved.
 */
object CatalogueBuilder {
    data class Proposal(val product: Product, val threshold: StockThreshold?, val flags: List<String>)

    fun fromWorkbookCatalogue(idFactory: () -> Id = ::newId): List<Proposal> =
        Seeds.PLASMACO_CATALOGUE.mapNotNull { legacy ->
            val dn = legacy.dn ?: return@mapNotNull null
            val pn = legacy.pn ?: return@mapNotNull null
            val parsed = ProductNaming.parse(legacy.legacyName)
            val grade = parsed?.grade ?: "PE100"
            val flags = mutableListOf<String>()
            val kg: BigDecimal? = when {
                legacy.isoKgPerMetre != null -> {
                    if (legacy.workbookKgPerMetre != null && legacy.workbookKgPerMetre.compareTo(legacy.isoKgPerMetre) != 0) {
                        flags += "Stock sheet used ${legacy.workbookKgPerMetre.toPlainString()} kg/m; ISO sheet says ${legacy.isoKgPerMetre.toPlainString()} kg/m"
                    }
                    legacy.isoKgPerMetre
                }
                legacy.workbookKgPerMetre != null -> {
                    flags += "No ISO 4427-2 weight for DN$dn PN${ProductNaming.pnText(pn)}; using the stock sheet's ${legacy.workbookKgPerMetre.toPlainString()} kg/m"
                    legacy.workbookKgPerMetre
                }
                else -> {
                    flags += "No kg/m available"
                    null
                }
            }
            val sdr = ProductNaming.sdrFor(pn, grade)
            if (sdr == null) flags += "PN${ProductNaming.pnText(pn)} is not a PE100 class in the ISO sheet"
            val canonical = ProductNaming.canonicalName(dn, pn, grade)
            val product = Product(
                id = idFactory(),
                code = ProductNaming.code(dn, pn, grade),
                name = canonical,
                dn = dn,
                pn = pn,
                sdr = sdr,
                materialGrade = grade,
                kgPerMetre = kg,
                note = flags.joinToString("; ").ifEmpty { null },
                legacyNames = listOf(legacy.legacyName).filter { it != canonical },
            )
            val threshold = legacy.workbookMinQty?.takeIf { it.signum() > 0 }?.let {
                if (it.compareTo(BigDecimal(5001)) == 0) flags += "Workbook minimum 5,001 m looks like a typo for 5,000 m"
                StockThreshold(ItemKind.FINISHED_GOOD, product.id, it, null)
            }
            Proposal(product.copy(note = flags.joinToString("; ").ifEmpty { null }), threshold, flags.toList())
        }

    fun isoSpec(dn: Int, pn: BigDecimal): PipeSpec? = Seeds.ISO_4427_2.firstOrNull { it.dn == dn && it.pn.compareTo(pn) == 0 }
}
