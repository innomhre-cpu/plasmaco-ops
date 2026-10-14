package rw.plasmaco.ops.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import rw.plasmaco.ops.domain.common.Decimals
import rw.plasmaco.ops.domain.common.newId
import rw.plasmaco.ops.domain.master.CatalogueBuilder
import rw.plasmaco.ops.domain.master.DefectCategory
import rw.plasmaco.ops.domain.master.DowntimeCategory
import rw.plasmaco.ops.domain.master.ItemKind
import rw.plasmaco.ops.domain.master.Machine
import rw.plasmaco.ops.domain.master.MasterData
import rw.plasmaco.ops.domain.master.MaterialCategory
import rw.plasmaco.ops.domain.master.Product
import rw.plasmaco.ops.domain.master.ProductNaming
import rw.plasmaco.ops.domain.master.ProductionLine
import rw.plasmaco.ops.domain.master.ProductionTarget
import rw.plasmaco.ops.domain.master.RawMaterial
import rw.plasmaco.ops.domain.master.RunRate
import rw.plasmaco.ops.domain.master.Shift
import rw.plasmaco.ops.domain.master.StockThreshold
import rw.plasmaco.ops.domain.units.UnitConversion
import rw.plasmaco.ops.domain.units.UnitOfMeasure
import rw.plasmaco.ops.ui.common.Fmt
import rw.plasmaco.ops.ui.components.AppBottomSheet
import rw.plasmaco.ops.ui.components.AppTextField
import rw.plasmaco.ops.ui.components.DateField
import rw.plasmaco.ops.ui.components.DropdownField
import rw.plasmaco.ops.ui.components.FieldHint
import rw.plasmaco.ops.ui.components.NoticeBanner
import rw.plasmaco.ops.ui.components.NumericField
import rw.plasmaco.ops.ui.components.PrimaryButton
import rw.plasmaco.ops.ui.components.SearchablePickerField
import rw.plasmaco.ops.ui.components.TextLink
import rw.plasmaco.ops.ui.components.TimeField
import rw.plasmaco.ops.ui.components.Tone
import rw.plasmaco.ops.ui.theme.Plasmaco
import java.math.BigDecimal
import java.time.LocalDate

@Composable
private fun Gap() = Spacer(Modifier.height(12.dp))

@Composable
private fun ActiveSwitch(active: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Active", style = Plasmaco.type.bodyStrong, color = Plasmaco.colors.textPrimary, modifier = Modifier.weight(1f))
        Switch(active, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Plasmaco.colors.primary))
    }
    if (!active) FieldHint("Inactive items stay in history and reports but cannot be chosen for new entries.")
}

private fun dec(text: String): BigDecimal? = Decimals.parseInput(text)

@Composable
private fun FormError(text: String?) {
    if (text != null) { Gap(); NoticeBanner(text, Tone.DANGER) }
}

// ------------------------------------------------------------------------------------------------ product

@Composable
fun ProductSheet(existing: Product?, onSave: (Product) -> Unit, onDismiss: () -> Unit) {
    val k = existing?.id ?: "new"
    var dn by rememberSaveable(k) { mutableStateOf(existing?.dn?.toString().orEmpty()) }
    var pn by rememberSaveable(k) { mutableStateOf(Fmt.input(existing?.pn)) }
    var grade by rememberSaveable(k) { mutableStateOf(existing?.materialGrade ?: "PE100") }
    var code by rememberSaveable(k) { mutableStateOf(existing?.code.orEmpty()) }
    var name by rememberSaveable(k) { mutableStateOf(existing?.name.orEmpty()) }
    var kg by rememberSaveable(k) { mutableStateOf(Fmt.input(existing?.kgPerMetre)) }
    var perCoil by rememberSaveable(k) { mutableStateOf(Fmt.input(existing?.conversions?.firstOrNull { it.from == UnitOfMeasure.COIL }?.factor)) }
    var perPiece by rememberSaveable(k) { mutableStateOf(Fmt.input(existing?.conversions?.firstOrNull { it.from == UnitOfMeasure.PIECE }?.factor)) }
    var note by rememberSaveable(k) { mutableStateOf(existing?.note.orEmpty()) }
    var active by rememberSaveable(k) { mutableStateOf(existing?.active ?: true) }
    var error by rememberSaveable(k) { mutableStateOf<String?>(null) }

    val dnV = dn.trim().toIntOrNull()
    val pnV = dec(pn)
    val iso = if (dnV != null && pnV != null) CatalogueBuilder.isoSpec(dnV, pnV) else null
    val sdr = pnV?.let { ProductNaming.sdrFor(it, grade) }

    AppBottomSheet(if (existing == null) "New product" else existing.code, onDismiss) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AppTextField(dn, { dn = it.filter(Char::isDigit) }, "DN (mm)", Modifier.weight(1f), required = true, keyboardType = KeyboardType.Number)
            NumericField(pn, { pn = it }, "PN", "bar", Modifier.weight(1f), required = true)
        }
        Gap()
        AppTextField(grade, { grade = it.uppercase() }, "Material grade", hint = "PE100 unless the product sheet says otherwise")
        if (dnV != null && pnV != null) {
            TextLink("Use standard code and name", { code = ProductNaming.code(dnV, pnV, grade); name = ProductNaming.canonicalName(dnV, pnV, grade) })
        }
        Gap()
        AppTextField(code, { code = it }, "Code", required = true)
        Gap()
        AppTextField(name, { name = it }, "Name", required = true)
        Gap()
        NumericField(kg, { kg = it }, "Weight", "kg/m", hint = iso?.kgPerMetre?.let { "ISO 4427-2 (workbook sheet): ${Fmt.input(it)} kg/m · SDR ${Fmt.input(iso.sdr)}" }
            ?: "No ISO 4427-2 row for this DN and PN; enter the weight from the product specification")
        if (iso?.kgPerMetre != null && kg.isBlank()) TextLink("Use the ISO weight", { kg = Fmt.input(iso.kgPerMetre) })
        Gap()
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NumericField(perCoil, { perCoil = it }, "Metres per coil", "m", Modifier.weight(1f))
            NumericField(perPiece, { perPiece = it }, "Metres per length", "m", Modifier.weight(1f))
        }
        FieldHint("Only if output or dispatches are counted in coils or pipe lengths; leave empty otherwise.")
        Gap()
        AppTextField(note, { note = it }, "Note", singleLine = false)
        Gap()
        ActiveSwitch(active) { active = it }
        FormError(error)
        Gap()
        PrimaryButton("Save", {
            val kgV = dec(kg)
            val coil = dec(perCoil)
            val piece = dec(perPiece)
            error = when {
                code.isBlank() || name.isBlank() -> "Enter a code and a name"
                kg.isNotBlank() && (kgV == null || kgV.signum() <= 0) -> "Weight must be a positive number"
                (perCoil.isNotBlank() && (coil == null || coil.signum() <= 0)) || (perPiece.isNotBlank() && (piece == null || piece.signum() <= 0)) -> "Lengths must be positive numbers"
                else -> null
            }
            if (error == null) {
                onSave(
                    Product(
                        id = existing?.id ?: newId(), code = code.trim(), name = name.trim(), dn = dnV, pn = pnV, sdr = sdr ?: existing?.sdr,
                        materialGrade = grade.trim().ifEmpty { "PE100" }, kgPerMetre = kgV, baseUnit = UnitOfMeasure.METRE,
                        conversions = listOfNotNull(coil?.let { UnitConversion(UnitOfMeasure.COIL, UnitOfMeasure.METRE, it) }, piece?.let { UnitConversion(UnitOfMeasure.PIECE, UnitOfMeasure.METRE, it) }),
                        active = active, note = note.trim().ifEmpty { null }, legacyNames = existing?.legacyNames.orEmpty(),
                    ),
                )
            }
        })
    }
}

// ------------------------------------------------------------------------------------------------ material

@Composable
fun MaterialSheetAdmin(existing: RawMaterial?, onSave: (RawMaterial) -> Unit, onDismiss: () -> Unit) {
    val k = existing?.id ?: "new"
    var code by rememberSaveable(k) { mutableStateOf(existing?.code.orEmpty()) }
    var name by rememberSaveable(k) { mutableStateOf(existing?.name.orEmpty()) }
    var category by rememberSaveable(k) { mutableStateOf(existing?.category ?: MaterialCategory.RESIN) }
    var bag by rememberSaveable(k) { mutableStateOf(Fmt.input(existing?.bagWeightKg)) }
    var active by rememberSaveable(k) { mutableStateOf(existing?.active ?: true) }
    var error by rememberSaveable(k) { mutableStateOf<String?>(null) }
    AppBottomSheet(if (existing == null) "New raw material" else existing.code, onDismiss) {
        AppTextField(code, { code = it }, "Code", required = true)
        Gap()
        AppTextField(name, { name = it }, "Name", required = true, hint = "e.g. the resin grade as written on the bag")
        Gap()
        DropdownField("Category", MaterialCategory.entries, category, { category = it }, { Fmt.enumLabel(it.name) }, required = true)
        Gap()
        NumericField(bag, { bag = it }, "Bag weight", "kg", hint = "When receipts are counted in bags. The 2026 stock sheets multiply bags by 25.")
        Gap()
        ActiveSwitch(active) { active = it }
        FormError(error)
        Gap()
        PrimaryButton("Save", {
            val b = dec(bag)
            error = when {
                code.isBlank() || name.isBlank() -> "Enter a code and a name"
                bag.isNotBlank() && (b == null || b.signum() <= 0) -> "Bag weight must be a positive number"
                else -> null
            }
            if (error == null) onSave(RawMaterial(existing?.id ?: newId(), code.trim(), name.trim(), category, UnitOfMeasure.KILOGRAM, b, active))
        })
    }
}

// ------------------------------------------------------------------------------------------------ simple records

@Composable
fun LineSheet(existing: ProductionLine?, onSave: (ProductionLine) -> Unit, onDismiss: () -> Unit) {
    val k = existing?.id ?: "new"
    var code by rememberSaveable(k) { mutableStateOf(existing?.code.orEmpty()) }
    var name by rememberSaveable(k) { mutableStateOf(existing?.name.orEmpty()) }
    var active by rememberSaveable(k) { mutableStateOf(existing?.active ?: true) }
    AppBottomSheet(if (existing == null) "New production line" else existing.name, onDismiss) {
        AppTextField(code, { code = it.uppercase() }, "Code", required = true, hint = "Short code used in report references, e.g. L1")
        Gap()
        AppTextField(name, { name = it }, "Name", required = true)
        Gap()
        ActiveSwitch(active) { active = it }
        Gap()
        PrimaryButton("Save", { onSave(ProductionLine(existing?.id ?: newId(), code.trim(), name.trim(), active)) }, enabled = code.isNotBlank() && name.isNotBlank())
    }
}

@Composable
fun MachineSheet(existing: Machine?, master: MasterData, onSave: (Machine) -> Unit, onDismiss: () -> Unit) {
    val k = existing?.id ?: "new"
    var code by rememberSaveable(k) { mutableStateOf(existing?.code.orEmpty()) }
    var name by rememberSaveable(k) { mutableStateOf(existing?.name.orEmpty()) }
    var lineId by rememberSaveable(k) { mutableStateOf(existing?.lineId ?: master.lines.singleOrNull()?.id) }
    var active by rememberSaveable(k) { mutableStateOf(existing?.active ?: true) }
    AppBottomSheet(if (existing == null) "New machine" else existing.name, onDismiss) {
        AppTextField(code, { code = it.uppercase() }, "Code", required = true)
        Gap()
        AppTextField(name, { name = it }, "Name", required = true)
        Gap()
        DropdownField("Line", master.lines, master.lines.firstOrNull { it.id == lineId }, { lineId = it.id }, { it.name }, required = true)
        Gap()
        ActiveSwitch(active) { active = it }
        Gap()
        val l = lineId
        PrimaryButton("Save", { if (l != null) onSave(Machine(existing?.id ?: newId(), code.trim(), name.trim(), l, active)) }, enabled = code.isNotBlank() && name.isNotBlank() && l != null)
    }
}

@Composable
fun ShiftSheet(existing: Shift?, onSave: (Shift) -> Unit, onDismiss: () -> Unit) {
    val k = existing?.id ?: "new"
    var code by rememberSaveable(k) { mutableStateOf(existing?.code.orEmpty()) }
    var name by rememberSaveable(k) { mutableStateOf(existing?.name.orEmpty()) }
    var start by rememberSaveable(k) { mutableStateOf(existing?.start) }
    var end by rememberSaveable(k) { mutableStateOf(existing?.end) }
    var active by rememberSaveable(k) { mutableStateOf(existing?.active ?: true) }
    AppBottomSheet(if (existing == null) "New shift" else existing.name, onDismiss) {
        AppTextField(code, { code = it.uppercase() }, "Code", required = true, hint = "Short code used in report references, e.g. D or N")
        Gap()
        AppTextField(name, { name = it }, "Name", required = true)
        Gap()
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TimeField("Starts", start, { start = it }, Modifier.weight(1f), required = true)
            TimeField("Ends", end, { end = it }, Modifier.weight(1f), required = true)
        }
        val s = start
        val e = end
        if (s != null && e != null && !e.isAfter(s)) FieldHint("Crosses midnight: reports are dated on the day the shift starts.")
        Gap()
        ActiveSwitch(active) { active = it }
        Gap()
        PrimaryButton(
            "Save", { if (s != null && e != null) onSave(Shift(existing?.id ?: newId(), code.trim(), name.trim(), s, e, active)) },
            enabled = code.isNotBlank() && name.isNotBlank() && s != null && e != null && s != e,
        )
    }
}

@Composable
fun DowntimeCategorySheet(existing: DowntimeCategory?, onSave: (DowntimeCategory) -> Unit, onDismiss: () -> Unit) {
    val k = existing?.id ?: "new"
    var name by rememberSaveable(k) { mutableStateOf(existing?.name.orEmpty()) }
    var planned by rememberSaveable(k) { mutableStateOf(existing?.planned ?: false) }
    var active by rememberSaveable(k) { mutableStateOf(existing?.active ?: true) }
    AppBottomSheet(if (existing == null) "New downtime cause" else existing.name, onDismiss) {
        AppTextField(name, { name = it }, "Name", required = true)
        Gap()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Planned stop", style = Plasmaco.type.bodyStrong, color = Plasmaco.colors.textPrimary, modifier = Modifier.weight(1f))
            Switch(planned, { planned = it }, colors = SwitchDefaults.colors(checkedTrackColor = Plasmaco.colors.primary))
        }
        FieldHint("Planned stops (e.g. scheduled maintenance, die change) are excluded from availability.")
        Gap()
        ActiveSwitch(active) { active = it }
        Gap()
        PrimaryButton("Save", { onSave(DowntimeCategory(existing?.id ?: newId(), name.trim(), planned, active)) }, enabled = name.isNotBlank())
    }
}

@Composable
fun DefectCategorySheet(existing: DefectCategory?, onSave: (DefectCategory) -> Unit, onDismiss: () -> Unit) {
    val k = existing?.id ?: "new"
    var name by rememberSaveable(k) { mutableStateOf(existing?.name.orEmpty()) }
    var active by rememberSaveable(k) { mutableStateOf(existing?.active ?: true) }
    AppBottomSheet(if (existing == null) "New defect category" else existing.name, onDismiss) {
        AppTextField(name, { name = it }, "Name", required = true)
        Gap()
        ActiveSwitch(active) { active = it }
        Gap()
        PrimaryButton("Save", { onSave(DefectCategory(existing?.id ?: newId(), name.trim(), active)) }, enabled = name.isNotBlank())
    }
}

// ------------------------------------------------------------------------------------------------ thresholds, targets, run rates

@Composable
fun ThresholdSheet(existing: StockThreshold?, master: MasterData, onSave: (StockThreshold) -> Unit, onDismiss: () -> Unit) {
    val k = existing?.let { "${it.itemKind}:${it.itemId}" } ?: "new"
    var kind by rememberSaveable(k) { mutableStateOf(existing?.itemKind ?: ItemKind.FINISHED_GOOD) }
    var itemId by rememberSaveable(k) { mutableStateOf(existing?.itemId) }
    var min by rememberSaveable(k) { mutableStateOf(Fmt.input(existing?.minimum)) }
    var reorder by rememberSaveable(k) { mutableStateOf(Fmt.input(existing?.reorderPoint)) }
    var error by rememberSaveable(k) { mutableStateOf<String?>(null) }
    val unit = itemId?.let { master.itemUnit(kind, it) }
    AppBottomSheet("Stock thresholds", onDismiss) {
        if (existing == null) {
            DropdownField("Item type", ItemKind.entries, kind, { kind = it; itemId = null }, { if (it == ItemKind.FINISHED_GOOD) "Finished good" else "Raw material" })
            Gap()
            if (kind == ItemKind.FINISHED_GOOD) {
                SearchablePickerField("Product", master.products.filter { it.active }, master.products.firstOrNull { it.id == itemId }, { itemId = it.id }, { it.name }, required = true)
            } else {
                DropdownField("Material", master.materials.filter { it.active }, master.materials.firstOrNull { it.id == itemId }, { itemId = it.id }, { it.name }, required = true)
            }
        } else {
            Text(master.itemName(existing.itemKind, existing.itemId), style = Plasmaco.type.bodyStrong, color = Plasmaco.colors.textPrimary)
        }
        Gap()
        NumericField(min, { min = it }, "Minimum stock", unit?.code, hint = "Below this the item shows as low stock")
        Gap()
        NumericField(reorder, { reorder = it }, "Reorder point", unit?.code, hint = "At or below this a reorder is suggested; at or above the minimum")
        FormError(error)
        Gap()
        PrimaryButton("Save", {
            val id = itemId
            val m = dec(min)
            val r = dec(reorder)
            error = when {
                id == null -> "Choose the item"
                (min.isNotBlank() && m == null) || (reorder.isNotBlank() && r == null) -> "Enter numbers only"
                else -> null
            }
            if (error == null && id != null) onSave(StockThreshold(kind, id, m, r))
        })
    }
}

@Composable
fun TargetSheet(existing: ProductionTarget?, master: MasterData, today: LocalDate, onSave: (ProductionTarget) -> Unit, onDismiss: () -> Unit) {
    val k = existing?.id ?: "new"
    var lineId by rememberSaveable(k) { mutableStateOf(existing?.lineId ?: master.lines.singleOrNull()?.id) }
    var productId by rememberSaveable(k) { mutableStateOf(existing?.productId) }
    var shiftId by rememberSaveable(k) { mutableStateOf(existing?.shiftId) }
    var qty by rememberSaveable(k) { mutableStateOf(Fmt.input(existing?.quantity)) }
    var unit by rememberSaveable(k) { mutableStateOf(existing?.unit ?: UnitOfMeasure.METRE) }
    var from by rememberSaveable(k) { mutableStateOf(existing?.validFrom ?: today) }
    var to by rememberSaveable(k) { mutableStateOf(existing?.validTo) }
    var error by rememberSaveable(k) { mutableStateOf<String?>(null) }
    AppBottomSheet(if (existing == null) "New production target" else "Production target", onDismiss) {
        DropdownField("Line", master.lines.filter { it.active || it.id == lineId }, master.lines.firstOrNull { it.id == lineId }, { lineId = it.id }, { it.name }, required = true)
        Gap()
        SearchablePickerField("Product", listOf<Product?>(null) + master.products.filter { it.active || it.id == productId },
            master.products.firstOrNull { it.id == productId }, { productId = it?.id }, { it?.name ?: "Any product" }, placeholder = "Any product")
        Gap()
        DropdownField("Shift", listOf<Shift?>(null) + master.shifts.filter { it.active || it.id == shiftId }, master.shifts.firstOrNull { it.id == shiftId },
            { shiftId = it?.id }, { it?.name ?: "Any shift" }, placeholder = "Any shift")
        FieldHint("The most specific target applies: product and shift, then product, then shift, then the whole line.")
        Gap()
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NumericField(qty, { qty = it }, "Target per shift", unit.code, Modifier.weight(1f), required = true)
            DropdownField("Unit", listOf(UnitOfMeasure.METRE, UnitOfMeasure.PIECE, UnitOfMeasure.COIL), unit, { unit = it }, { it.code }, Modifier.weight(1f))
        }
        Gap()
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DateField("Valid from", from, { from = it }, Modifier.weight(1f), required = true)
            DateField("Valid to", to, { to = it }, Modifier.weight(1f))
        }
        if (to != null) TextLink("No end date", { to = null })
        FormError(error)
        Gap()
        PrimaryButton("Save", {
            val l = lineId
            val q = dec(qty)
            val end = to
            error = when {
                l == null -> "Choose the line"
                q == null || q.signum() <= 0 -> "Enter a target greater than zero"
                end != null && end.isBefore(from) -> "The end date is before the start date"
                else -> null
            }
            if (error == null && l != null && q != null) onSave(ProductionTarget(existing?.id ?: newId(), l, productId, shiftId, q, unit, from, end))
        })
    }
}

@Composable
fun RunRateSheet(existing: RunRate?, master: MasterData, onSave: (RunRate) -> Unit, onDismiss: () -> Unit) {
    val k = existing?.let { "${it.lineId}:${it.productId}" } ?: "new"
    var lineId by rememberSaveable(k) { mutableStateOf(existing?.lineId ?: master.lines.singleOrNull()?.id) }
    var productId by rememberSaveable(k) { mutableStateOf(existing?.productId) }
    var rate by rememberSaveable(k) { mutableStateOf(Fmt.input(existing?.metresPerHour)) }
    AppBottomSheet("Ideal run rate", onDismiss) {
        DropdownField("Line", master.lines, master.lines.firstOrNull { it.id == lineId }, { lineId = it.id }, { it.name }, required = true, enabled = existing == null)
        Gap()
        SearchablePickerField("Product", listOf<Product?>(null) + master.products.filter { it.active }, master.products.firstOrNull { it.id == productId },
            { productId = it?.id }, { it?.name ?: "All products on this line" }, placeholder = "All products on this line", enabled = existing == null)
        Gap()
        NumericField(rate, { rate = it }, "Ideal speed", "m/h", required = true, hint = "The rated output at full speed, from the line specification. Used only for performance and OEE.")
        Gap()
        val l = lineId
        val r = dec(rate)
        PrimaryButton("Save", { if (l != null && r != null) onSave(RunRate(l, productId, r)) }, enabled = l != null && r != null && r.signum() > 0)
    }
}
