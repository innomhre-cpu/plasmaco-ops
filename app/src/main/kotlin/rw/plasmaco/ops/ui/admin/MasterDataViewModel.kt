package rw.plasmaco.ops.ui.admin

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import rw.plasmaco.ops.data.repository.MasterDataRepository
import rw.plasmaco.ops.domain.master.CatalogueBuilder
import rw.plasmaco.ops.domain.master.DefectCategory
import rw.plasmaco.ops.domain.master.DowntimeCategory
import rw.plasmaco.ops.domain.master.Machine
import rw.plasmaco.ops.domain.master.MasterData
import rw.plasmaco.ops.domain.master.Product
import rw.plasmaco.ops.domain.master.ProductionLine
import rw.plasmaco.ops.domain.master.ProductionTarget
import rw.plasmaco.ops.domain.master.RawMaterial
import rw.plasmaco.ops.domain.master.RunRate
import rw.plasmaco.ops.domain.master.Shift
import rw.plasmaco.ops.domain.master.StockThreshold
import rw.plasmaco.ops.ui.common.PlasmacoViewModel
import rw.plasmaco.ops.ui.common.UserMessages
import javax.inject.Inject

enum class MasterSection(val key: String, val title: String, val description: String) {
    PRODUCTS("products", "Products", "HDPE pipes by DN and PN, with kg per metre from ISO 4427-2"),
    MATERIALS("materials", "Raw materials", "Resin, masterbatch and other inputs, with bag weights"),
    LINES("lines", "Production lines", "Extrusion lines that report production"),
    MACHINES("machines", "Machines", "Equipment on each line, for output and downtime"),
    SHIFTS("shifts", "Shifts", "Shift names and times; night shifts may cross midnight"),
    DOWNTIME("downtime-causes", "Downtime causes", "Categories for stoppages, planned or unplanned"),
    DEFECTS("defects", "Defect categories", "Quality defect types"),
    THRESHOLDS("thresholds", "Stock thresholds", "Minimum stock and reorder points"),
    TARGETS("targets", "Production targets", "Targets per line, product and shift, with validity dates"),
    RUN_RATES("run-rates", "Ideal run rates", "Metres per hour used for performance and OEE");

    companion object {
        fun of(key: String?): MasterSection? = entries.firstOrNull { it.key == key }
    }
}

data class MasterUi(val loading: Boolean = true, val master: MasterData = MasterData(), val section: MasterSection? = null)

/**
 * Master data maintenance. Records are never deleted (reports and the ledger refer to them); they are deactivated,
 * which hides them from new entries but keeps history readable.
 */
@HiltViewModel
class MasterDataViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val repo: MasterDataRepository,
    messages: UserMessages,
) : PlasmacoViewModel(messages) {
    private val section = MasterSection.of(handle.get<String>("section"))

    val state: StateFlow<MasterUi> = repo.master.map { MasterUi(false, it, section) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MasterUi(section = section))

    /** The PLASMACO product list from the latest stock-sheet block, with ISO weights and review flags. */
    val catalogue: List<CatalogueBuilder.Proposal> by lazy { CatalogueBuilder.fromWorkbookCatalogue() }

    private fun uniqueCode(code: String, id: String, existing: List<Pair<String, String>>): Boolean =
        existing.none { (otherId, otherCode) -> otherId != id && otherCode.equals(code.trim(), ignoreCase = true) }

    private fun saved(what: String, done: () -> Unit) {
        messages.show("$what saved")
        done()
    }

    fun saveProduct(p: Product, done: () -> Unit) = action {
        if (!uniqueCode(p.code, p.id, state.value.master.products.map { it.id to it.code })) { messages.show("Code ${p.code} is already used"); return@action }
        repo.saveProduct(p); saved(p.code, done)
    }

    fun saveMaterial(m: RawMaterial, done: () -> Unit) = action {
        if (!uniqueCode(m.code, m.id, state.value.master.materials.map { it.id to it.code })) { messages.show("Code ${m.code} is already used"); return@action }
        repo.saveMaterial(m); saved(m.code, done)
    }

    fun saveLine(l: ProductionLine, done: () -> Unit) = action {
        if (!uniqueCode(l.code, l.id, state.value.master.lines.map { it.id to it.code })) { messages.show("Code ${l.code} is already used"); return@action }
        repo.saveLine(l); saved(l.name, done)
    }

    fun saveMachine(m: Machine, done: () -> Unit) = action {
        if (!uniqueCode(m.code, m.id, state.value.master.machines.map { it.id to it.code })) { messages.show("Code ${m.code} is already used"); return@action }
        repo.saveMachine(m); saved(m.name, done)
    }

    fun saveShift(s: Shift, done: () -> Unit) = action {
        if (!uniqueCode(s.code, s.id, state.value.master.shifts.map { it.id to it.code })) { messages.show("Code ${s.code} is already used"); return@action }
        repo.saveShift(s); saved(s.name, done)
    }

    fun saveDowntimeCategory(c: DowntimeCategory, done: () -> Unit) = action { repo.saveDowntimeCategory(c); saved(c.name, done) }
    fun saveDefectCategory(c: DefectCategory, done: () -> Unit) = action { repo.saveDefectCategory(c); saved(c.name, done) }
    fun saveThreshold(t: StockThreshold, done: () -> Unit) = action { repo.saveThreshold(t); saved("Thresholds", done) }
    fun saveTarget(t: ProductionTarget, done: () -> Unit) = action { repo.saveTarget(t); saved("Target", done) }
    fun saveRunRate(r: RunRate, done: () -> Unit) = action { repo.saveRunRate(r); saved("Run rate", done) }

    fun importCatalogue(done: () -> Unit) = action {
        val n = repo.importWorkbookCatalogue(catalogue)
        messages.show(if (n == 0) "All catalogue products already exist" else "$n products added")
        done()
    }
}
