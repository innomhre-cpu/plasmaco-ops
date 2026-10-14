package rw.plasmaco.ops.ui.admin

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import rw.plasmaco.ops.domain.master.MasterData
import rw.plasmaco.ops.ui.common.DetailHeader
import rw.plasmaco.ops.ui.common.Fmt
import rw.plasmaco.ops.ui.common.KeyValueRow
import rw.plasmaco.ops.ui.common.ListCard
import rw.plasmaco.ops.ui.common.ListRow
import rw.plasmaco.ops.ui.common.ScreenList
import rw.plasmaco.ops.ui.components.AppBottomSheet
import rw.plasmaco.ops.ui.components.CircleIconButton
import rw.plasmaco.ops.ui.components.EmptyState
import rw.plasmaco.ops.ui.components.LoadingState
import rw.plasmaco.ops.ui.components.NoticeBanner
import rw.plasmaco.ops.ui.components.PageHeaderCard
import rw.plasmaco.ops.ui.components.PrimaryButton
import rw.plasmaco.ops.ui.components.SearchField
import rw.plasmaco.ops.ui.components.SecondaryButton
import rw.plasmaco.ops.ui.components.SectionCard
import rw.plasmaco.ops.ui.components.StatusBadge
import rw.plasmaco.ops.ui.components.Tone
import rw.plasmaco.ops.ui.navigation.Navigator
import rw.plasmaco.ops.ui.navigation.Routes
import rw.plasmaco.ops.ui.theme.Plasmaco
import java.time.LocalDate

private fun MasterData.count(s: MasterSection): Int = when (s) {
    MasterSection.PRODUCTS -> products.size
    MasterSection.MATERIALS -> materials.size
    MasterSection.LINES -> lines.size
    MasterSection.MACHINES -> machines.size
    MasterSection.SHIFTS -> shifts.size
    MasterSection.DOWNTIME -> downtimeCategories.size
    MasterSection.DEFECTS -> defectCategories.size
    MasterSection.THRESHOLDS -> thresholds.size
    MasterSection.TARGETS -> targets.size
    MasterSection.RUN_RATES -> runRates.size
}

@Composable
fun MasterDataScreen(nav: Navigator, vm: MasterDataViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var showCatalogue by rememberSaveable { mutableStateOf(false) }
    if (ui.loading) { LoadingState(label = "Loading master data"); return }
    val m = ui.master
    val missing = vm.catalogue.count { p -> m.products.none { it.code == p.product.code } }
    ScreenList {
        item { PageHeaderCard("Master data", "Products, materials, lines, shifts and the rules reports are checked against") }
        if (m.setupGaps().isNotEmpty()) item { NoticeBanner("Still needed before the first report: ${m.setupGaps().joinToString("; ")}.", Tone.WARNING) }
        if (missing > 0) {
            item {
                SectionCard("PLASMACO product list", subtitle = "$missing product(s) from the stock workbook are not set up yet") {
                    Text(
                        "The list comes from the latest block of the 2026 stock workbook, with weights from its ISO 4427-2 sheet. Review the flagged items before using them.",
                        style = Plasmaco.type.bodySmall, color = Plasmaco.colors.textSecondary,
                    )
                    Spacer(Modifier.height(8.dp))
                    SecondaryButton("Review and add", { showCatalogue = true }, icon = Icons.AutoMirrored.Outlined.PlaylistAdd)
                }
            }
        }
        item {
            ListCard(MasterSection.entries) { s ->
                ListRow(s.title, subtitle = s.description, icon = Icons.Outlined.Category, onClick = { nav.open(Routes.masterSection(s.key)) }) {
                    StatusBadge(m.count(s).toString(), if (m.count(s) == 0) Tone.WARNING else Tone.NEUTRAL)
                }
            }
        }
    }
    if (showCatalogue) {
        val flagged = vm.catalogue.filter { it.flags.isNotEmpty() }
        AppBottomSheet("PLASMACO product list", { showCatalogue = false }) {
            KeyValueRow("Products in the list", vm.catalogue.size.toString())
            KeyValueRow("Already set up (same code)", (vm.catalogue.size - missing).toString())
            KeyValueRow("To add", missing.toString(), emphasise = true)
            KeyValueRow("With review notes", flagged.size.toString())
            Spacer(Modifier.height(8.dp))
            flagged.take(40).forEach { p -> KeyValueRow(p.product.code, p.flags.joinToString("; ")) }
            if (flagged.size > 40) Text("+${flagged.size - 40} more; every note is kept on the product.", style = Plasmaco.type.label, color = Plasmaco.colors.textSecondary)
            Spacer(Modifier.height(16.dp))
            PrimaryButton("Add $missing products", { vm.importCatalogue { showCatalogue = false } }, loading = busy, enabled = missing > 0)
        }
    }
}

@Composable
fun MasterSectionScreen(nav: Navigator, vm: MasterDataViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    val section = ui.section
    if (ui.loading) { LoadingState(label = "Loading"); return }
    if (section == null) { EmptyState("Unknown section", "", actionText = "Back", onAction = nav::back); return }
    val m = ui.master
    val q = query.trim().lowercase().replace(" ", "")
    fun matches(vararg text: String?) = q.isEmpty() || text.filterNotNull().joinToString("").lowercase().replace(" ", "").contains(q)
    fun activeTone(active: Boolean) = if (active) Tone.SUCCESS else Tone.NEUTRAL
    fun activeLabel(active: Boolean) = if (active) "Active" else "Inactive"

    ScreenList {
        item {
            DetailHeader(section.title, nav::back, subtitle = section.description) {
                CircleIconButton(Icons.Outlined.Add, "Add", { editing = "new" })
            }
        }
        if (section == MasterSection.PRODUCTS || section == MasterSection.MATERIALS || section == MasterSection.THRESHOLDS) {
            item { SearchField(query, { query = it }, "Search") }
        }
        item {
            when (section) {
                MasterSection.PRODUCTS -> {
                    val list = m.products.filter { matches(it.name, it.code) }.sortedWith(compareBy({ it.dn ?: 0 }, { it.pn }))
                    if (list.isEmpty()) EmptyState("No products", "Add products one by one or review the PLASMACO list on the master data screen.")
                    else ListCard(list) { p ->
                        ListRow(p.name, subtitle = listOfNotNull(p.code, p.kgPerMetre?.let { "${Fmt.number(it, 3)} kg/m" } ?: "no kg/m", p.sdr?.let { "SDR ${Fmt.input(it)}" }, p.note).joinToString(" · "),
                            onClick = { editing = p.id }) { StatusBadge(activeLabel(p.active), activeTone(p.active)) }
                    }
                }
                MasterSection.MATERIALS -> {
                    val list = m.materials.filter { matches(it.name, it.code) }
                    if (list.isEmpty()) EmptyState("No raw materials", "Add the resin and other inputs used on the lines.")
                    else ListCard(list) { x ->
                        ListRow(x.name, subtitle = listOfNotNull(x.code, Fmt.enumLabel(x.category.name), x.bagWeightKg?.let { "bag ${Fmt.input(it)} kg" }).joinToString(" · "),
                            onClick = { editing = x.id }) { StatusBadge(activeLabel(x.active), activeTone(x.active)) }
                    }
                }
                MasterSection.LINES -> if (m.lines.isEmpty()) EmptyState("No lines", "Add each extrusion line that reports production.") else ListCard(m.lines) { l ->
                    ListRow(l.name, subtitle = l.code, onClick = { editing = l.id }) { StatusBadge(activeLabel(l.active), activeTone(l.active)) }
                }
                MasterSection.MACHINES -> if (m.machines.isEmpty()) EmptyState("No machines", "Optional: add machines to record output and downtime per machine.") else ListCard(m.machines) { x ->
                    ListRow(x.name, subtitle = "${x.code} · ${m.line(x.lineId)?.name ?: "—"}", onClick = { editing = x.id }) { StatusBadge(activeLabel(x.active), activeTone(x.active)) }
                }
                MasterSection.SHIFTS -> if (m.shifts.isEmpty()) EmptyState("No shifts", "Add the shifts worked, with their start and end times.") else ListCard(m.shifts) { s ->
                    ListRow(s.name, subtitle = "${s.code} · ${Fmt.time(s.start)} – ${Fmt.time(s.end)}${if (s.crossesMidnight) " (next day)" else ""}", onClick = { editing = s.id }) {
                        StatusBadge(activeLabel(s.active), activeTone(s.active))
                    }
                }
                MasterSection.DOWNTIME -> if (m.downtimeCategories.isEmpty()) EmptyState("No downtime causes", "Add causes such as power cut, die change or material shortage.") else ListCard(m.downtimeCategories) { c ->
                    ListRow(c.name, subtitle = if (c.planned) "Planned" else "Unplanned", onClick = { editing = c.id }) { StatusBadge(activeLabel(c.active), activeTone(c.active)) }
                }
                MasterSection.DEFECTS -> if (m.defectCategories.isEmpty()) EmptyState("No defect categories", "Add the defect types your inspectors record.") else ListCard(m.defectCategories) { c ->
                    ListRow(c.name, onClick = { editing = c.id }) { StatusBadge(activeLabel(c.active), activeTone(c.active)) }
                }
                MasterSection.THRESHOLDS -> {
                    val list = m.thresholds.filter { matches(m.itemName(it.itemKind, it.itemId)) }
                    if (list.isEmpty()) EmptyState("No thresholds", "Set a minimum and reorder point for items that must not run out.")
                    else ListCard(list) { t ->
                        val unit = m.itemUnit(t.itemKind, t.itemId)
                        ListRow(m.itemName(t.itemKind, t.itemId), subtitle = "Minimum ${Fmt.qty(t.minimum, unit)} · reorder at ${Fmt.qty(t.reorderPoint, unit)}",
                            onClick = { editing = "${t.itemKind}:${t.itemId}" })
                    }
                }
                MasterSection.TARGETS -> if (m.targets.isEmpty()) EmptyState("No targets", "Without targets, achievement shows \"No data\" rather than a misleading figure.") else ListCard(m.targets.sortedByDescending { it.validFrom }) { t ->
                    ListRow(
                        "${m.line(t.lineId)?.name ?: "—"} · ${t.productId?.let { m.product(it)?.name } ?: "any product"} · ${t.shiftId?.let { m.shift(it)?.name } ?: "any shift"}",
                        subtitle = "${Fmt.qty(t.quantity, t.unit)} per shift · from ${Fmt.date(t.validFrom)}${t.validTo?.let { " to ${Fmt.date(it)}" } ?: ""}",
                        onClick = { editing = t.id },
                    )
                }
                MasterSection.RUN_RATES -> if (m.runRates.isEmpty()) EmptyState("No run rates", "Performance and OEE show \"No data\" until an ideal speed is set.") else ListCard(m.runRates) { r ->
                    ListRow("${m.line(r.lineId)?.name ?: "—"} · ${r.productId?.let { m.product(it)?.name } ?: "all products"}", subtitle = "${Fmt.number(r.metresPerHour, 1)} m/h",
                        onClick = { editing = "${r.lineId}:${r.productId ?: "*"}" })
                }
            }
        }
    }

    val key = editing ?: return
    val close = { editing = null }
    val isNew = key == "new"
    when (section) {
        MasterSection.PRODUCTS -> ProductSheet(if (isNew) null else m.product(key), { vm.saveProduct(it, close) }, close)
        MasterSection.MATERIALS -> MaterialSheetAdmin(if (isNew) null else m.material(key), { vm.saveMaterial(it, close) }, close)
        MasterSection.LINES -> LineSheet(if (isNew) null else m.line(key), { vm.saveLine(it, close) }, close)
        MasterSection.MACHINES -> MachineSheet(if (isNew) null else m.machine(key), m, { vm.saveMachine(it, close) }, close)
        MasterSection.SHIFTS -> ShiftSheet(if (isNew) null else m.shift(key), { vm.saveShift(it, close) }, close)
        MasterSection.DOWNTIME -> DowntimeCategorySheet(if (isNew) null else m.downtimeCategory(key), { vm.saveDowntimeCategory(it, close) }, close)
        MasterSection.DEFECTS -> DefectCategorySheet(if (isNew) null else m.defectCategories.firstOrNull { it.id == key }, { vm.saveDefectCategory(it, close) }, close)
        MasterSection.THRESHOLDS -> ThresholdSheet(
            if (isNew) null else m.thresholds.firstOrNull { "${it.itemKind}:${it.itemId}" == key }, m, { vm.saveThreshold(it, close) }, close,
        )
        MasterSection.TARGETS -> TargetSheet(if (isNew) null else m.targets.firstOrNull { it.id == key }, m, LocalDate.now(), { vm.saveTarget(it, close) }, close)
        MasterSection.RUN_RATES -> RunRateSheet(if (isNew) null else m.runRates.firstOrNull { "${it.lineId}:${it.productId ?: "*"}" == key }, m, { vm.saveRunRate(it, close) }, close)
    }
}
