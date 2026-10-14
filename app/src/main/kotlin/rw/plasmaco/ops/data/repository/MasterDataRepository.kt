package rw.plasmaco.ops.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.encodeToJsonElement
import rw.plasmaco.ops.data.local.PlasmacoDatabase
import rw.plasmaco.ops.data.local.toDomain
import rw.plasmaco.ops.data.local.toEntity
import rw.plasmaco.ops.api.toDto
import rw.plasmaco.ops.domain.common.TimeSource
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
import rw.plasmaco.ops.domain.security.Permission
import rw.plasmaco.ops.domain.sync.OpKind
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Master data (products, materials, lines, machines, shifts, categories, thresholds, targets, run rates).
 * Only administrators change it; every change is audited and queued for sync.
 */
@Singleton
class MasterDataRepository @Inject constructor(
    private val db: PlasmacoDatabase,
    private val session: SessionManager,
    private val recorder: ChangeRecorder,
    private val time: TimeSource,
) {
    private val dao get() = db.master()

    val master: Flow<MasterData> = combine(
        combine(dao.observeProducts(), dao.observeMaterials(), dao.observeLines(), dao.observeMachines(), dao.observeShifts()) { p, m, l, mc, s ->
            MasterData(products = p.map { it.toDomain() }, materials = m.map { it.toDomain() }, lines = l.map { it.toDomain() }, machines = mc.map { it.toDomain() }, shifts = s.map { it.toDomain() })
        },
        combine(dao.observeDowntimeCategories(), dao.observeDefectCategories(), dao.observeThresholds(), dao.observeTargets(), dao.observeRunRates()) { dc, df, th, tg, rr ->
            MasterData(downtimeCategories = dc.map { it.toDomain() }, defectCategories = df.map { it.toDomain() }, thresholds = th.map { it.toDomain() }, targets = tg.map { it.toDomain() }, runRates = rr.map { it.toDomain() })
        },
    ) { a, b -> a.copy(downtimeCategories = b.downtimeCategories, defectCategories = b.defectCategories, thresholds = b.thresholds, targets = b.targets, runRates = b.runRates) }

    suspend fun snapshot(): MasterData = master.first()

    private fun requireAdmin() = session.requireActor().also {
        if (!it.can(Permission.MASTER_DATA_MANAGE)) throw NotAllowedException("Only an administrator can change master data")
    }

    private val now get() = time.now().toEpochMilli()

    suspend fun saveProduct(p: Product) {
        val actor = requireAdmin()
        require(p.code.isNotBlank() && p.name.isNotBlank()) { "Code and name are required" }
        p.kgPerMetre?.let { require(it.signum() > 0) { "kg per metre must be positive" } }
        db.withTransaction {
            dao.upsertProducts(listOf(p.toEntity(now)))
            recorder.audit(actor, EntityTypes.PRODUCT, p.id, "SAVE", "Saved product ${p.code}", AppJson.encodeToJsonElement(p.toDto()))
            recorder.enqueue(EntityTypes.PRODUCT, p.id, OpKind.UPSERT, AppJson.encodeToJsonElement(p.toDto()), null)
        }
    }

    suspend fun saveMaterial(m: RawMaterial) {
        val actor = requireAdmin()
        require(m.code.isNotBlank() && m.name.isNotBlank()) { "Code and name are required" }
        m.bagWeightKg?.let { require(it.signum() > 0) { "Bag weight must be positive" } }
        db.withTransaction {
            dao.upsertMaterials(listOf(m.toEntity(now)))
            recorder.audit(actor, EntityTypes.MATERIAL, m.id, "SAVE", "Saved material ${m.code}")
            recorder.enqueue(EntityTypes.MATERIAL, m.id, OpKind.UPSERT, AppJson.encodeToJsonElement(m.toDto()), null)
        }
    }

    suspend fun saveLine(l: ProductionLine) {
        val actor = requireAdmin()
        require(l.code.isNotBlank() && l.name.isNotBlank()) { "Code and name are required" }
        db.withTransaction {
            dao.upsertLines(listOf(l.toEntity(now)))
            recorder.audit(actor, EntityTypes.LINE, l.id, "SAVE", "Saved line ${l.code}")
            recorder.enqueue(EntityTypes.LINE, l.id, OpKind.UPSERT, AppJson.encodeToJsonElement(l.toDto()), null)
        }
    }

    suspend fun saveMachine(m: Machine) {
        val actor = requireAdmin()
        require(m.code.isNotBlank() && m.name.isNotBlank()) { "Code and name are required" }
        db.withTransaction {
            dao.upsertMachines(listOf(m.toEntity(now)))
            recorder.audit(actor, EntityTypes.MACHINE, m.id, "SAVE", "Saved machine ${m.code}")
            recorder.enqueue(EntityTypes.MACHINE, m.id, OpKind.UPSERT, AppJson.encodeToJsonElement(m.toDto()), null)
        }
    }

    suspend fun saveShift(s: Shift) {
        val actor = requireAdmin()
        require(s.code.isNotBlank() && s.name.isNotBlank()) { "Code and name are required" }
        require(s.start != s.end) { "Start and end cannot be the same time" }
        db.withTransaction {
            dao.upsertShifts(listOf(s.toEntity(now)))
            recorder.audit(actor, EntityTypes.SHIFT, s.id, "SAVE", "Saved shift ${s.code} ${s.start}–${s.end}")
            recorder.enqueue(EntityTypes.SHIFT, s.id, OpKind.UPSERT, AppJson.encodeToJsonElement(s.toDto()), null)
        }
    }

    suspend fun saveDowntimeCategory(c: DowntimeCategory) {
        val actor = requireAdmin()
        require(c.name.isNotBlank()) { "Name is required" }
        db.withTransaction {
            dao.upsertDowntimeCategories(listOf(c.toEntity(now)))
            recorder.audit(actor, EntityTypes.DOWNTIME_CATEGORY, c.id, "SAVE", "Saved downtime cause ${c.name}")
            recorder.enqueue(EntityTypes.DOWNTIME_CATEGORY, c.id, OpKind.UPSERT, AppJson.encodeToJsonElement(c.toDto()), null)
        }
    }

    suspend fun saveDefectCategory(c: DefectCategory) {
        val actor = requireAdmin()
        require(c.name.isNotBlank()) { "Name is required" }
        db.withTransaction {
            dao.upsertDefectCategories(listOf(c.toEntity(now)))
            recorder.audit(actor, EntityTypes.DEFECT_CATEGORY, c.id, "SAVE", "Saved defect category ${c.name}")
            recorder.enqueue(EntityTypes.DEFECT_CATEGORY, c.id, OpKind.UPSERT, AppJson.encodeToJsonElement(c.toDto()), null)
        }
    }

    suspend fun saveThreshold(t: StockThreshold) {
        val actor = requireAdmin()
        t.minimum?.let { require(it.signum() >= 0) { "Minimum cannot be negative" } }
        t.reorderPoint?.let { r -> require(r.signum() >= 0) { "Reorder point cannot be negative" }; t.minimum?.let { m -> require(r >= m) { "Reorder point should be at or above the minimum" } } }
        db.withTransaction {
            dao.upsertThresholds(listOf(t.toEntity(now)))
            recorder.audit(actor, EntityTypes.THRESHOLD, "${t.itemKind}:${t.itemId}", "SAVE", "Set stock thresholds")
            recorder.enqueue(EntityTypes.THRESHOLD, "${t.itemKind}:${t.itemId}", OpKind.UPSERT, AppJson.encodeToJsonElement(t.toDto()), null)
        }
    }

    suspend fun saveTarget(t: ProductionTarget) {
        val actor = requireAdmin()
        require(t.quantity.signum() > 0) { "Target must be greater than zero" }
        t.validTo?.let { require(!it.isBefore(t.validFrom)) { "End date is before start date" } }
        db.withTransaction {
            dao.upsertTargets(listOf(t.toEntity(now)))
            recorder.audit(actor, EntityTypes.TARGET, t.id, "SAVE", "Saved production target ${t.quantity.toPlainString()} ${t.unit.code}")
            recorder.enqueue(EntityTypes.TARGET, t.id, OpKind.UPSERT, AppJson.encodeToJsonElement(t.toDto()), null)
        }
    }

    suspend fun saveRunRate(r: RunRate) {
        val actor = requireAdmin()
        require(r.metresPerHour.signum() > 0) { "Run rate must be greater than zero" }
        db.withTransaction {
            dao.upsertRunRates(listOf(r.toEntity(now)))
            recorder.audit(actor, EntityTypes.RUN_RATE, "${r.lineId}:${r.productId ?: "*"}", "SAVE", "Saved run rate ${r.metresPerHour.toPlainString()} m/h")
            recorder.enqueue(EntityTypes.RUN_RATE, "${r.lineId}:${r.productId ?: "*"}", OpKind.UPSERT, AppJson.encodeToJsonElement(r.toDto()), null)
        }
    }

    /**
     * Seeds the verified PLASMACO product list (latest workbook block, ISO 4427-2 weights) after the administrator
     * reviews it. Existing products with the same code are left untouched.
     */
    suspend fun importWorkbookCatalogue(proposals: List<CatalogueBuilder.Proposal>): Int {
        val actor = requireAdmin()
        val existing = dao.products().map { it.code }.toSet()
        val fresh = proposals.filter { it.product.code !in existing }
        db.withTransaction {
            dao.upsertProducts(fresh.map { it.product.toEntity(now) })
            dao.upsertThresholds(fresh.mapNotNull { it.threshold?.toEntity(now) })
            fresh.forEach { recorder.enqueue(EntityTypes.PRODUCT, it.product.id, OpKind.UPSERT, AppJson.encodeToJsonElement(it.product.toDto()), null) }
            recorder.audit(actor, EntityTypes.PRODUCT, "catalogue", "IMPORT", "Imported ${fresh.size} products from the workbook catalogue")
        }
        return fresh.size
    }
}
