package rw.plasmaco.ops.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import rw.plasmaco.ops.data.local.ImportBatchEntity
import rw.plasmaco.ops.data.local.PlasmacoDatabase
import rw.plasmaco.ops.data.local.toEntity
import rw.plasmaco.ops.api.toDto
import rw.plasmaco.ops.domain.analytics.Filters
import rw.plasmaco.ops.domain.common.Outcome
import rw.plasmaco.ops.domain.common.TimeSource
import rw.plasmaco.ops.domain.common.newId
import rw.plasmaco.ops.domain.export.ExportFormat
import rw.plasmaco.ops.domain.export.ExportResult
import rw.plasmaco.ops.domain.export.Exporter
import rw.plasmaco.ops.domain.legacy.ImportPlan
import rw.plasmaco.ops.domain.legacy.ImportPlanner
import rw.plasmaco.ops.domain.legacy.InvalidWorkbookException
import rw.plasmaco.ops.domain.legacy.LegacyStockParser
import rw.plasmaco.ops.domain.reports.ReportBuilder
import rw.plasmaco.ops.domain.reports.ReportDocument
import rw.plasmaco.ops.domain.reports.ReportType
import rw.plasmaco.ops.domain.security.Actor
import rw.plasmaco.ops.domain.security.Permission
import rw.plasmaco.ops.domain.sync.OpKind
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_IMPORT_BYTES = 40L * 1024 * 1024

/** Report generation, file export through the Storage Access Framework, and sharing. */
@Singleton
class ExportRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val analytics: AnalyticsRepository,
    private val session: SessionManager,
    private val recorder: ChangeRecorder,
    private val db: PlasmacoDatabase,
    private val time: TimeSource,
) {
    /**
     * Builds a report from the data the signed-in person may see. Period reports need [Permission.REPORTS_EXPORT]
     * (stock reports also [Permission.INVENTORY_VIEW]); a single production report ([reportId]) can be exported by
     * anyone allowed to view it, so an operator can print their own shift report.
     */
    suspend fun build(type: ReportType, filters: Filters, reportId: String? = null): ReportDocument = withContext(Dispatchers.Default) {
        val actor = session.requireActor()
        if (!canExport(actor, type, reportId)) throw NotAllowedException("Your role cannot export this report")
        val data = analytics.snapshotFor(actor)
        if (reportId != null && data.reports.none { it.id == reportId }) throw NotAllowedException("You cannot view this report")
        ReportBuilder.build(ReportBuilder.Request(type, filters, actor.displayName, time.now(), time.zone(), reportId), data)
    }

    suspend fun render(doc: ReportDocument, format: ExportFormat): ExportResult = withContext(Dispatchers.Default) {
        session.requireActor()
        Exporter.export(doc, format, time.zone())
    }

    fun canExport(actor: Actor, type: ReportType, reportId: String?): Boolean = Companion.canExport(actor, type, reportId)

    companion object {
        val STOCK_REPORTS = setOf(ReportType.RAW_MATERIAL_STOCK, ReportType.FINISHED_GOODS_STOCK, ReportType.INVENTORY_MOVEMENT)

        fun canExport(actor: Actor, type: ReportType, reportId: String?): Boolean = when {
            reportId != null && type == ReportType.DAILY_PRODUCTION -> actor.can(Permission.REPORT_VIEW_OWN) || actor.can(Permission.REPORT_VIEW_ALL)
            type in STOCK_REPORTS -> actor.can(Permission.INVENTORY_VIEW) && actor.can(Permission.REPORTS_EXPORT)
            else -> actor.can(Permission.REPORTS_EXPORT)
        }
    }

    /** Writes to a document the user picked with ACTION_CREATE_DOCUMENT. */
    suspend fun saveTo(uri: Uri, result: ExportResult) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(result.bytes) } ?: error("Could not open the chosen file for writing")
        val actor = session.requireActor()
        db.withTransaction { recorder.audit(actor, "export", result.fileName, "EXPORT", "Exported ${result.fileName} (${result.bytes.size / 1024} KB)") }
    }

    /** Returns a share intent for the file, served from app cache through FileProvider (content:// only). */
    suspend fun shareIntent(result: ExportResult): Intent = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000 }?.forEach { it.delete() }
        val file = File(dir, result.fileName).apply { writeBytes(result.bytes) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        Intent(Intent.ACTION_SEND).apply {
            type = result.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, result.fileName.removeSuffix(".pdf").removeSuffix(".xlsx").replace('_', ' '))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.let { Intent.createChooser(it, "Share report") }
    }
}

/**
 * Historical workbook import: read (untrusted, size-limited, no formulas or macros executed) → preview plan with
 * row-level issues → explicit confirmation → historical transactions + an import batch record.
 */
@Singleton
class ImportRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: PlasmacoDatabase,
    private val session: SessionManager,
    private val master: MasterDataRepository,
    private val recorder: ChangeRecorder,
    private val time: TimeSource,
) {
    /** [sheetNames]: the month sheets that can be selected for import. */
    data class Preview(val fileName: String, val plan: ImportPlan, val alreadyImported: Boolean, val sheetNames: List<String>)

    suspend fun preview(uri: Uri, resinMaterialId: String?, sheets: Set<String>? = null): Outcome<Preview> = withContext(Dispatchers.IO) {
        val actor = session.requireActor()
        if (!actor.can(Permission.IMPORT_RUN)) return@withContext Outcome.Refused("actor", "import.forbidden", "Only an administrator can import workbooks")
        val name = displayName(uri)
        if (!name.endsWith(".xlsx", true) && !name.endsWith(".xlsm", true)) return@withContext Outcome.Refused("file", "import.type", "Choose an .xlsx or .xlsm workbook")
        val size = fileSize(uri)
        if (size != null && size > MAX_IMPORT_BYTES) return@withContext Outcome.Refused("file", "import.size", "The file is larger than 40 MB")
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext Outcome.Refused("file", "import.read", "The file could not be read")
        try {
            val wb = LegacyStockParser.parse(bytes, name, LegacyStockParser.Options(sheets = sheets))
            val existingKeys = db.inventory().legacyKeys().associate { it.idempotencyKey to it.quantity }
            val plan = ImportPlanner.plan(wb, ImportPlanner.Context(master.snapshot(), resinMaterialId, existingKeys, actor.userId, time.now()))
            Outcome.Ok(Preview(name, plan, db.imports().bySha(wb.sha256) != null, wb.monthSheetNames))
        } catch (e: InvalidWorkbookException) {
            Outcome.Refused("file", "import.invalid", e.message ?: "Not a valid workbook")
        } catch (e: Exception) {
            Outcome.Refused("file", "import.failed", "The workbook could not be read: ${e.javaClass.simpleName}")
        }
    }

    /** Commits a previewed plan. A file already imported (same SHA-256) is refused; changed past values are kept as first imported. */
    suspend fun commit(preview: Preview): Outcome<Int> {
        val actor = session.requireActor()
        if (!actor.can(Permission.IMPORT_RUN)) return Outcome.Refused("actor", "import.forbidden", "Only an administrator can import workbooks")
        if (preview.alreadyImported) return Outcome.Refused("file", "import.duplicate", "This exact file was imported before")
        if (preview.plan.blocking.any { it.code == "rm.no_material" }) return Outcome.Refused("material", "import.material", "Choose the raw material for the workbook's resin balance")
        val now = time.now().toEpochMilli()
        val plan = preview.plan
        db.withTransaction {
            if (plan.newProducts.isNotEmpty()) db.master().upsertProducts(plan.newProducts.map { it.toEntity(now) })
            plan.newProducts.forEach { recorder.enqueue(EntityTypes.PRODUCT, it.id, OpKind.UPSERT, AppJson.encodeToJsonElement(it.toDto()), null) }
            val inserted = db.inventory().insertAll(plan.transactions.map { it.toEntity(now) })
            plan.transactions.zip(inserted).filter { it.second != -1L }.forEach { (t, _) ->
                recorder.enqueue(EntityTypes.TXN, t.id, OpKind.UPSERT, AppJson.encodeToJsonElement(t.toDto()), null, t.idempotencyKey)
            }
            val issues = JsonArray(plan.issues.take(2000).map { i ->
                JsonObject(mapOf("sheet" to JsonPrimitive(i.sheet), "cell" to JsonPrimitive(i.cell), "code" to JsonPrimitive(i.code), "message" to JsonPrimitive(i.message), "severity" to JsonPrimitive(i.severity.name)))
            })
            db.imports().insert(ImportBatchEntity(newId(), preview.fileName, plan.sha256, now, actor.userId, plan.stats.firstDate, plan.stats.lastDate, plan.transactions.size, plan.stats.reconciliations, issues.toString()))
            recorder.audit(actor, EntityTypes.IMPORT, plan.sha256, "IMPORT",
                "Imported ${preview.fileName}: ${plan.transactions.size} historical movements (${plan.stats.firstDate} to ${plan.stats.lastDate}), ${plan.stats.reconciliations} reconciliations, ${plan.newProducts.size} new products")
        }
        return Outcome.Ok(plan.transactions.size)
    }

    private fun displayName(uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?: uri.lastPathSegment ?: "workbook.xlsx"

    private fun fileSize(uri: Uri): Long? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
}

/**
 * Device backup: copies the SQLite database (after a WAL checkpoint) to a file the administrator chooses. Restore is
 * done by an administrator from Settings and requires an app restart; the server remains the shared source of truth.
 */
@Singleton
class BackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: PlasmacoDatabase,
    private val session: SessionManager,
    private val recorder: ChangeRecorder,
) {
    suspend fun backupTo(uri: Uri): Long = withContext(Dispatchers.IO) {
        val actor = session.requireActor()
        if (!actor.can(Permission.BACKUP_MANAGE)) throw NotAllowedException("Only an administrator can create backups")
        db.query("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }
        val file = context.getDatabasePath(PlasmacoDatabase.NAME)
        val bytes = file.readBytes()
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: error("Could not open the chosen file")
        db.withTransaction { recorder.audit(actor, "backup", file.name, "BACKUP", "Database backup written (${bytes.size / 1024} KB)") }
        bytes.size.toLong()
    }

    /** Validates the SQLite header and stages the file; it replaces the live database at next start. */
    suspend fun stageRestore(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val actor = session.requireActor()
        if (!actor.can(Permission.BACKUP_MANAGE)) throw NotAllowedException("Only an administrator can restore backups")
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext false
        if (bytes.size < 100 || String(bytes, 0, 15, Charsets.US_ASCII) != "SQLite format 3") return@withContext false
        File(context.filesDir, "restore-pending.db").writeBytes(bytes)
        db.withTransaction { recorder.audit(actor, "backup", "restore", "RESTORE_STAGED", "Backup staged for restore at next start (${bytes.size / 1024} KB)") }
        true
    }

    companion object {
        /** Called from Application.onCreate before Room opens the database. */
        fun applyPendingRestore(context: Context) {
            val pending = File(context.filesDir, "restore-pending.db")
            if (!pending.exists()) return
            val target = context.getDatabasePath(PlasmacoDatabase.NAME)
            listOf("", "-wal", "-shm").forEach { File(target.path + it).delete() }
            pending.copyTo(target, overwrite = true)
            pending.delete()
        }
    }
}
