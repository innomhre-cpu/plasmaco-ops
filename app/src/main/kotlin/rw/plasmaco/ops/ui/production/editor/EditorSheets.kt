package rw.plasmaco.ops.ui.production.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import rw.plasmaco.ops.domain.analytics.Analytics
import rw.plasmaco.ops.domain.common.Decimals
import rw.plasmaco.ops.domain.common.Issue
import rw.plasmaco.ops.domain.common.newId
import rw.plasmaco.ops.domain.master.MasterData
import rw.plasmaco.ops.domain.master.Targets
import rw.plasmaco.ops.domain.metrics.ProductionMetrics
import rw.plasmaco.ops.domain.production.DowntimeEvent
import rw.plasmaco.ops.domain.production.InspectionResult
import rw.plasmaco.ops.domain.production.MaterialConsumption
import rw.plasmaco.ops.domain.production.ProductionEntry
import rw.plasmaco.ops.domain.production.ProductionReport
import rw.plasmaco.ops.domain.production.QualityInspection
import rw.plasmaco.ops.domain.units.UnitConversion
import rw.plasmaco.ops.domain.units.UnitConverter
import rw.plasmaco.ops.domain.units.Quantity
import rw.plasmaco.ops.domain.units.UnitOfMeasure
import rw.plasmaco.ops.ui.common.Fmt
import rw.plasmaco.ops.ui.common.messageFor
import rw.plasmaco.ops.ui.components.AppBottomSheet
import rw.plasmaco.ops.ui.components.AppFilterChip
import rw.plasmaco.ops.ui.components.AppTextField
import rw.plasmaco.ops.ui.components.DropdownField
import rw.plasmaco.ops.ui.components.FieldHint
import rw.plasmaco.ops.ui.components.FieldLabel
import rw.plasmaco.ops.ui.components.FilterChipRow
import rw.plasmaco.ops.ui.components.NoticeBanner
import rw.plasmaco.ops.ui.components.NumericField
import rw.plasmaco.ops.ui.components.PrimaryButton
import rw.plasmaco.ops.ui.components.SearchablePickerField
import rw.plasmaco.ops.ui.components.TimeField
import rw.plasmaco.ops.ui.components.Tone
import rw.plasmaco.ops.ui.theme.Plasmaco
import java.math.BigDecimal
import java.time.LocalTime

/** Units an item may be entered in: its base unit plus every unit it has a conversion for. */
fun unitsFor(base: UnitOfMeasure, conversions: List<UnitConversion>): List<UnitOfMeasure> =
    (listOf(base) + conversions.flatMap { listOf(it.from, it.to) }).distinct()

private fun parse(text: String): BigDecimal? = Decimals.parseInput(text)

@Composable
private fun Gap() = Spacer(Modifier.height(12.dp))

// ------------------------------------------------------------------------------------------------ production entry

@Composable
fun EntrySheet(
    report: ProductionReport,
    master: MasterData,
    existing: ProductionEntry?,
    index: Int,
    issues: List<Issue>,
    onSave: (ProductionEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    val key = existing?.id ?: "new"
    var productId by rememberSaveable(key) { mutableStateOf(existing?.productId) }
    var machineId by rememberSaveable(key) { mutableStateOf(existing?.machineId) }
    var unit by rememberSaveable(key) { mutableStateOf(existing?.unit ?: UnitOfMeasure.METRE) }
    var target by rememberSaveable(key) { mutableStateOf(Fmt.input(existing?.target)) }
    var targetFromPlan by rememberSaveable(key) { mutableStateOf(false) }
    var gross by rememberSaveable(key) { mutableStateOf(Fmt.input(existing?.gross)) }
    var good by rememberSaveable(key) { mutableStateOf(Fmt.input(existing?.good)) }
    var rejected by rememberSaveable(key) { mutableStateOf(Fmt.input(existing?.rejected)) }
    var batch by rememberSaveable(key) { mutableStateOf(existing?.batch.orEmpty()) }
    var start by rememberSaveable(key) { mutableStateOf(existing?.start?.toLocalTime()) }
    var end by rememberSaveable(key) { mutableStateOf(existing?.end?.toLocalTime()) }
    var remarks by rememberSaveable(key) { mutableStateOf(existing?.remarks.orEmpty()) }
    var localError by rememberSaveable(key) { mutableStateOf<String?>(null) }

    val product = productId?.let { master.product(it) }
    val units = product?.let { unitsFor(it.baseUnit, it.conversions) } ?: listOf(UnitOfMeasure.METRE)
    val f = "entries[$index]"

    // A configured target for this line, shift and product is filled in once; the operator can still change it.
    LaunchedEffect(productId, unit) {
        if (existing == null && target.isBlank() && productId != null) {
            Targets.resolve(master.targets, report.date, report.lineId, productId, report.shiftId)?.takeIf { it.unit == unit }?.let {
                target = Fmt.input(it.quantity)
                targetFromPlan = true
            }
        }
    }

    val goodV = parse(good)
    val rejV = parse(rejected)
    val sum = if (goodV != null && rejV != null) goodV + rejV else null

    AppBottomSheet(if (existing == null) "Add product output" else "Edit product output", onDismiss) {
        SearchablePickerField(
            "Product", master.products.filter { it.active || it.id == productId }.sortedWith(compareBy({ it.dn ?: 0 }, { it.pn })), product,
            { productId = it.id; if (unit !in unitsFor(it.baseUnit, it.conversions)) unit = it.baseUnit }, { it.name },
            required = true, error = issues.messageFor("$f.productId"),
            itemSupporting = { p -> listOfNotNull(p.code, p.kgPerMetre?.let { "${Fmt.number(it, 3)} kg/m" } ?: "no kg/m on file").joinToString(" · ") },
        )
        Gap()
        val machines = master.machines.filter { it.lineId == report.lineId && (it.active || it.id == machineId) }
        if (machines.isNotEmpty()) {
            DropdownField("Machine", machines, machines.firstOrNull { it.id == machineId }, { machineId = it.id }, { it.name }, placeholder = "Not specified")
            Gap()
        }
        DropdownField("Unit", units, unit, { unit = it }, { "${it.label} (${it.code})" }, error = issues.messageFor("$f.unit"))
        Gap()
        NumericField(target, { target = it; targetFromPlan = false }, "Target", unit.code, error = issues.messageFor("$f.target"),
            hint = if (targetFromPlan) "From the production plan for this line and shift" else "Leave empty when no target applies")
        Gap()
        NumericField(good, { good = it }, "Good output", unit.code, required = true, error = issues.messageFor("$f.good"))
        Gap()
        NumericField(rejected, { rejected = it }, "Rejected output", unit.code, required = true, error = issues.messageFor("$f.rejected"))
        Gap()
        NumericField(gross, { gross = it }, "Gross output", unit.code, error = issues.messageFor("$f.gross"),
            hint = sum?.let { "Good + rejected = ${Fmt.input(it)} ${unit.code}. Leave empty to use this." } ?: "Good + rejected")
        val achievement = goodV?.let { ProductionMetrics.entryAchievement(it, parse(target)) }
        if (achievement != null) FieldHint("Target achievement ${Fmt.number(achievement, 1)}%")
        Gap()
        AppTextField(batch, { batch = it }, "Batch or lot")
        Gap()
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TimeField("Started", start, { start = it }, Modifier.weight(1f), error = issues.messageFor("$f.start"))
            TimeField("Finished", end, { end = it }, Modifier.weight(1f), error = issues.messageFor("$f.end"))
        }
        Gap()
        AppTextField(remarks, { remarks = it }, "Remarks", singleLine = false, minLines = 2)
        localError?.let { Gap(); NoticeBanner(it, Tone.DANGER) }
        Gap()
        PrimaryButton(if (existing == null) "Add" else "Save changes", {
            val pid = productId
            val g = parse(good)
            val rj = parse(rejected)
            localError = when {
                pid == null -> "Choose the product"
                g == null || rj == null -> "Enter good and rejected output (0 when none)"
                gross.isNotBlank() && parse(gross) == null -> "Gross output is not a number"
                target.isNotBlank() && parse(target) == null -> "Target is not a number"
                else -> null
            }
            if (localError == null && pid != null && g != null && rj != null) {
                onSave(
                    ProductionEntry(
                        id = existing?.id ?: newId(), productId = pid, machineId = machineId, target = parse(target),
                        gross = parse(gross) ?: (g + rj), good = g, rejected = rj, unit = unit, batch = batch.trim().ifEmpty { null },
                        start = start?.let { ReportEditorViewModel.resolve(report, it) }, end = end?.let { ReportEditorViewModel.resolve(report, it) },
                        remarks = remarks.trim(),
                    ),
                )
            }
        })
    }
}

// ------------------------------------------------------------------------------------------------ materials

@Composable
fun MaterialSheet(
    master: MasterData,
    existing: MaterialConsumption?,
    index: Int,
    issues: List<Issue>,
    onSave: (MaterialConsumption) -> Unit,
    onDismiss: () -> Unit,
) {
    val key = existing?.id ?: "new"
    var materialId by rememberSaveable(key) { mutableStateOf(existing?.materialId) }
    var unit by rememberSaveable(key) { mutableStateOf(existing?.unit ?: UnitOfMeasure.KILOGRAM) }
    var issued by rememberSaveable(key) { mutableStateOf(Fmt.input(existing?.issued)) }
    var returned by rememberSaveable(key) { mutableStateOf(Fmt.input(existing?.returned?.takeIf { it.signum() != 0 })) }
    var opening by rememberSaveable(key) { mutableStateOf(Fmt.input(existing?.lineOpening)) }
    var closing by rememberSaveable(key) { mutableStateOf(Fmt.input(existing?.lineClosing)) }
    var scrap by rememberSaveable(key) { mutableStateOf(Fmt.input(existing?.scrap?.takeIf { it.signum() != 0 })) }
    var batch by rememberSaveable(key) { mutableStateOf(existing?.batch.orEmpty()) }
    var remarks by rememberSaveable(key) { mutableStateOf(existing?.remarks.orEmpty()) }
    var localError by rememberSaveable(key) { mutableStateOf<String?>(null) }

    val material = materialId?.let { master.material(it) }
    val units = material?.let { unitsFor(it.baseUnit, it.conversions) } ?: listOf(UnitOfMeasure.KILOGRAM)
    val f = "materials[$index]"
    val consumed = parse(issued)?.let { i -> i - (parse(returned) ?: BigDecimal.ZERO) + (parse(opening) ?: BigDecimal.ZERO) - (parse(closing) ?: BigDecimal.ZERO) }

    AppBottomSheet(if (existing == null) "Add material" else "Edit material", onDismiss) {
        DropdownField(
            "Material", master.materials.filter { it.active || it.id == materialId }, material,
            { materialId = it.id; if (unit !in unitsFor(it.baseUnit, it.conversions)) unit = it.baseUnit }, { it.name },
            required = true, error = issues.messageFor("$f.materialId"), itemSupporting = { "${it.code} · ${Fmt.enumLabel(it.category.name)}" },
        )
        Gap()
        DropdownField("Unit", units, unit, { unit = it }, { "${it.label} (${it.code})" }, error = issues.messageFor("$f.unit"),
            itemSupporting = { u -> material?.bagWeightKg?.takeIf { u == UnitOfMeasure.BAG }?.let { "1 bag = ${Fmt.input(it)} kg" } })
        Gap()
        NumericField(issued, { issued = it }, "Issued from store", unit.code, required = true, error = issues.messageFor("$f.issued"))
        Gap()
        NumericField(returned, { returned = it }, "Returned to store", unit.code, error = issues.messageFor("$f.returned"))
        Gap()
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NumericField(opening, { opening = it }, "Line-side at start", unit.code, Modifier.weight(1f), error = issues.messageFor("$f.lineOpening"))
            NumericField(closing, { closing = it }, "Line-side at end", unit.code, Modifier.weight(1f), error = issues.messageFor("$f.lineClosing"))
        }
        FieldHint("Material left beside the machine from the previous shift, and left for the next one. Leave empty if none.")
        Gap()
        NumericField(scrap, { scrap = it }, "Of which scrap, purge or lumps", unit.code, error = issues.messageFor("$f.scrap"),
            hint = "Part of the consumption, not an extra deduction")
        consumed?.let { FieldHint("Consumed = issued − returned + line-side start − line-side end = ${Fmt.input(it)} ${unit.code}") }
        Gap()
        AppTextField(batch, { batch = it }, "Batch or lot")
        Gap()
        AppTextField(remarks, { remarks = it }, "Remarks", singleLine = false, minLines = 2)
        localError?.let { Gap(); NoticeBanner(it, Tone.DANGER) }
        Gap()
        PrimaryButton(if (existing == null) "Add" else "Save changes", {
            val mid = materialId
            val i = parse(issued)
            val bad = listOf(returned, opening, closing, scrap).any { it.isNotBlank() && parse(it) == null }
            localError = when {
                mid == null -> "Choose the material"
                i == null -> "Enter the quantity issued (0 when none)"
                bad -> "One of the quantities is not a number"
                else -> null
            }
            if (localError == null && mid != null && i != null) {
                onSave(
                    MaterialConsumption(
                        id = existing?.id ?: newId(), materialId = mid, issued = i, returned = parse(returned) ?: BigDecimal.ZERO,
                        lineOpening = parse(opening), lineClosing = parse(closing), scrap = parse(scrap) ?: BigDecimal.ZERO,
                        unit = unit, batch = batch.trim().ifEmpty { null }, remarks = remarks.trim(),
                    ),
                )
            }
        })
    }
}

/** Resin consumed in kg across the report's material lines (resin category only), or null if a unit cannot be converted. */
fun resinConsumedKg(report: ProductionReport, master: MasterData): BigDecimal? {
    var total = BigDecimal.ZERO
    for (m in report.materials) {
        val mat = master.material(m.materialId) ?: continue
        if (mat.category != rw.plasmaco.ops.domain.master.MaterialCategory.RESIN) continue
        total += UnitConverter.convert(Quantity(m.consumed, m.unit), UnitOfMeasure.KILOGRAM, mat.conversions)?.value ?: return null
    }
    return total
}

// ------------------------------------------------------------------------------------------------ downtime

@Composable
fun DowntimeSheet(
    report: ProductionReport,
    master: MasterData,
    existing: DowntimeEvent?,
    index: Int,
    issues: List<Issue>,
    onSave: (DowntimeEvent) -> Unit,
    onDismiss: () -> Unit,
) {
    val key = existing?.id ?: "new"
    var machineId by rememberSaveable(key) { mutableStateOf(existing?.machineId) }
    var categoryId by rememberSaveable(key) { mutableStateOf(existing?.categoryId) }
    var start by rememberSaveable(key) { mutableStateOf(existing?.start?.toLocalTime()) }
    var end by rememberSaveable(key) { mutableStateOf(existing?.end?.toLocalTime()) }
    var open by rememberSaveable(key) { mutableStateOf(existing != null && existing.end == null) }
    var reason by rememberSaveable(key) { mutableStateOf(existing?.reason.orEmpty()) }
    var action by rememberSaveable(key) { mutableStateOf(existing?.correctiveAction.orEmpty()) }
    var remarks by rememberSaveable(key) { mutableStateOf(existing?.remarks.orEmpty()) }
    var localError by rememberSaveable(key) { mutableStateOf<String?>(null) }
    val f = "downtime[$index]"

    fun build(): DowntimeEvent? {
        val s = start ?: return null
        val startAt = ReportEditorViewModel.resolve(report, s)
        var endAt = if (open) null else end?.let { ReportEditorViewModel.resolve(report, it) }
        // A stoppage that ends after midnight on a day shift (rare) is placed on the next day rather than before its start.
        if (endAt != null && endAt.isBefore(startAt)) endAt = endAt.plusDays(1)
        return DowntimeEvent(existing?.id ?: newId(), machineId, startAt, endAt, categoryId, reason.trim(), action.trim(), remarks.trim())
    }
    val preview = build()

    AppBottomSheet(if (existing == null) "Add stoppage" else "Edit stoppage", onDismiss) {
        val machines = master.machines.filter { it.lineId == report.lineId && (it.active || it.id == machineId) }
        DropdownField("Machine", machines, machines.firstOrNull { it.id == machineId }, { machineId = it.id }, { it.name },
            placeholder = if (machines.isEmpty()) "No machines set up for this line" else "Whole line")
        Gap()
        DropdownField(
            "Cause category", master.downtimeCategories.filter { it.active || it.id == categoryId }, master.downtimeCategory(categoryId ?: ""),
            { categoryId = it.id }, { it.name }, required = true, error = issues.messageFor("$f.categoryId"),
            itemSupporting = { if (it.planned) "Planned" else "Unplanned" },
        )
        Gap()
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TimeField("Stopped at", start, { start = it }, Modifier.weight(1f), required = true, error = issues.messageFor("$f.start"))
            TimeField("Restarted at", end, { end = it; open = false }, Modifier.weight(1f), error = issues.messageFor("$f.end"))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(open, { open = it }, colors = CheckboxDefaults.colors(checkedColor = Plasmaco.colors.primary))
            Text("Still stopped (must be closed before submitting)", style = Plasmaco.type.bodySmall, color = Plasmaco.colors.textPrimary)
        }
        preview?.minutes?.let { FieldHint("Duration ${Fmt.minutes(it)}") }
        Gap()
        AppTextField(reason, { reason = it }, "Reason", required = true, error = issues.messageFor("$f.reason"), singleLine = false)
        Gap()
        AppTextField(action, { action = it }, "Corrective action", singleLine = false)
        Gap()
        AppTextField(remarks, { remarks = it }, "Remarks", singleLine = false)
        localError?.let { Gap(); NoticeBanner(it, Tone.DANGER) }
        Gap()
        PrimaryButton(if (existing == null) "Add" else "Save changes", {
            val e = build()
            localError = when {
                e == null -> "Enter when the stoppage started"
                !open && end == null -> "Enter the restart time, or tick \"Still stopped\""
                else -> null
            }
            if (localError == null && e != null) onSave(e)
        })
    }
}

// ------------------------------------------------------------------------------------------------ quality

@Composable
fun InspectionSheet(
    report: ProductionReport,
    master: MasterData,
    existing: QualityInspection?,
    index: Int,
    issues: List<Issue>,
    inspectorId: String,
    nowTime: LocalTime,
    onSave: (QualityInspection) -> Unit,
    onDismiss: () -> Unit,
) {
    val key = existing?.id ?: "new"
    val reportProducts = report.entries.map { it.productId }.distinct().mapNotNull { master.product(it) }
    var productId by rememberSaveable(key) { mutableStateOf(existing?.productId ?: reportProducts.singleOrNull()?.id) }
    var entryId by rememberSaveable(key) { mutableStateOf(existing?.entryId) }
    var batch by rememberSaveable(key) { mutableStateOf(existing?.batch.orEmpty()) }
    var time by rememberSaveable(key) { mutableStateOf(existing?.time?.toLocalTime() ?: nowTime.withSecond(0).withNano(0)) }
    var unit by rememberSaveable(key) { mutableStateOf(existing?.unit ?: UnitOfMeasure.METRE) }
    var sample by rememberSaveable(key) { mutableStateOf(Fmt.input(existing?.sampleQty)) }
    var accepted by rememberSaveable(key) { mutableStateOf(Fmt.input(existing?.accepted)) }
    var rejected by rememberSaveable(key) { mutableStateOf(Fmt.input(existing?.rejected)) }
    var defectId by rememberSaveable(key) { mutableStateOf(existing?.defectCategoryId) }
    var result by rememberSaveable(key) { mutableStateOf(existing?.result) }
    var action by rememberSaveable(key) { mutableStateOf(existing?.correctiveAction.orEmpty()) }
    var remarks by rememberSaveable(key) { mutableStateOf(existing?.remarks.orEmpty()) }
    var localError by rememberSaveable(key) { mutableStateOf<String?>(null) }
    val f = "inspections[$index]"
    val product = productId?.let { master.product(it) }
    val options = (reportProducts + master.products.filter { it.active }).distinctBy { it.id }
    val entries = report.entries.filter { it.productId == productId }

    AppBottomSheet(if (existing == null) "Add inspection" else "Edit inspection", onDismiss) {
        SearchablePickerField("Product", options, product, { productId = it.id; entryId = null }, { it.name }, required = true,
            error = issues.messageFor("$f.productId"), itemSupporting = { p -> if (p in reportProducts) "In this report" else p.code })
        if (entries.size > 1) {
            Gap()
            DropdownField("Production entry", entries, entries.firstOrNull { it.id == entryId }, { entryId = it.id },
                { e -> "${Analytics.shortName(product?.name.orEmpty())} · ${Fmt.input(e.good)} ${e.unit.code}${e.batch?.let { " · $it" } ?: ""}" }, placeholder = "Any")
        }
        Gap()
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TimeField("Inspected at", time, { time = it }, Modifier.weight(1f), required = true)
            DropdownField("Unit", listOf(UnitOfMeasure.METRE, UnitOfMeasure.PIECE, UnitOfMeasure.COIL), unit, { unit = it }, { it.code }, Modifier.weight(1f))
        }
        Gap()
        NumericField(sample, { sample = it }, "Sample size", unit.code, required = true, error = issues.messageFor("$f.sampleQty"))
        Gap()
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NumericField(accepted, { accepted = it }, "Accepted", unit.code, Modifier.weight(1f), required = true, error = issues.messageFor("$f.accepted"))
            NumericField(rejected, { rejected = it }, "Rejected", unit.code, Modifier.weight(1f), required = true, error = issues.messageFor("$f.rejected"))
        }
        Gap()
        FieldLabel("Result", required = true)
        FilterChipRow(Modifier.fillMaxWidth()) {
            InspectionResult.entries.forEach { r -> AppFilterChip(r.label, result == r, { result = r }) }
        }
        issues.messageFor("$f.result")?.let { FieldHint(it) }
        Gap()
        DropdownField("Defect", master.defectCategories.filter { it.active || it.id == defectId }, master.defectCategories.firstOrNull { it.id == defectId },
            { defectId = it.id }, { it.name }, placeholder = "None", error = issues.messageFor("$f.defectCategoryId"))
        Gap()
        AppTextField(batch, { batch = it }, "Batch or lot")
        Gap()
        AppTextField(action, { action = it }, "Corrective action", singleLine = false)
        Gap()
        AppTextField(remarks, { remarks = it }, "Remarks", singleLine = false)
        localError?.let { Gap(); NoticeBanner(it, Tone.DANGER) }
        Gap()
        PrimaryButton(if (existing == null) "Add" else "Save changes", {
            val pid = productId
            val s = parse(sample)
            val a = parse(accepted)
            val rj = parse(rejected)
            val res = result
            localError = when {
                pid == null -> "Choose the product"
                s == null || a == null || rj == null -> "Enter sample size, accepted and rejected"
                res == null -> "Choose the result"
                else -> null
            }
            if (localError == null && pid != null && s != null && a != null && rj != null && res != null) {
                onSave(
                    QualityInspection(
                        id = existing?.id ?: newId(), productId = pid, entryId = entryId ?: entries.singleOrNull()?.id, batch = batch.trim().ifEmpty { null },
                        time = ReportEditorViewModel.resolve(report, time), sampleQty = s, accepted = a, rejected = rj, unit = unit,
                        defectCategoryId = defectId, result = res, correctiveAction = action.trim(), inspectorId = existing?.inspectorId ?: inspectorId,
                        remarks = remarks.trim(),
                    ),
                )
            }
        })
    }
}
