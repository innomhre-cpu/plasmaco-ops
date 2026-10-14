package rw.plasmaco.ops.server.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import rw.plasmaco.ops.api.ApprovalDto
import rw.plasmaco.ops.api.CategoryDto
import rw.plasmaco.ops.api.Change
import rw.plasmaco.ops.api.LineDto
import rw.plasmaco.ops.api.MachineDto
import rw.plasmaco.ops.api.MaterialDto
import rw.plasmaco.ops.api.ProductDto
import rw.plasmaco.ops.api.PullResponse
import rw.plasmaco.ops.api.PushOperation
import rw.plasmaco.ops.api.PushRequest
import rw.plasmaco.ops.api.PushResponse
import rw.plasmaco.ops.api.PushResult
import rw.plasmaco.ops.api.PushStatus
import rw.plasmaco.ops.api.ReportActionDto
import rw.plasmaco.ops.api.ReportDto
import rw.plasmaco.ops.api.RunRateDto
import rw.plasmaco.ops.api.ShiftDto
import rw.plasmaco.ops.api.TargetDto
import rw.plasmaco.ops.api.ThresholdDto
import rw.plasmaco.ops.api.TxnDto
import rw.plasmaco.ops.api.WireEntity
import rw.plasmaco.ops.api.WireJson
import rw.plasmaco.ops.api.toDefectCategory
import rw.plasmaco.ops.api.toDomain
import rw.plasmaco.ops.api.toDowntimeCategory
import rw.plasmaco.ops.api.toDto
import rw.plasmaco.ops.domain.common.Outcome
import rw.plasmaco.ops.domain.common.TimeSource
import rw.plasmaco.ops.domain.common.errors
import rw.plasmaco.ops.domain.common.newId
import rw.plasmaco.ops.domain.inventory.InventorySettings
import rw.plasmaco.ops.domain.inventory.InventoryTransaction
import rw.plasmaco.ops.domain.inventory.MovementRequest
import rw.plasmaco.ops.domain.inventory.MovementService
import rw.plasmaco.ops.domain.inventory.TxnOrigin
import rw.plasmaco.ops.domain.inventory.TxnStatus
import rw.plasmaco.ops.domain.inventory.TxnType
import rw.plasmaco.ops.domain.master.MasterData
import rw.plasmaco.ops.domain.production.ProductionReport
import rw.plasmaco.ops.domain.production.ReportStatus
import rw.plasmaco.ops.domain.production.ReportValidator
import rw.plasmaco.ops.domain.security.Actor
import rw.plasmaco.ops.domain.security.Permission
import rw.plasmaco.ops.domain.workflow.ReportAction
import rw.plasmaco.ops.domain.workflow.ReportWorkflow
import java.time.DateTimeException

/**
 * The server side of offline sync. Devices push their queued operations and pull the change feed; the server is the
 * authority and re-checks every operation against its own copy with the same domain rules the app uses:
 *
 *  - idempotency: an operation whose key was applied before is answered DUPLICATE and not applied again;
 *  - versioning: a change based on an older server version than the current one is a CONFLICT (unless every newer
 *    version came from the same device), and the server copy is returned for side-by-side review;
 *  - locking: submitted and approved reports cannot be edited; workflow actions go through [ReportWorkflow] with
 *    the server's actor, so self-approval and missing reasons are refused here too;
 *  - stock: movements are re-validated against the server ledger (permissions, units, cut-off, receipts against
 *    approved output, and negative stock, which is refused unless the audited exception is enabled on the server).
 *
 * Each operation is applied in its own transaction, so one refused operation never blocks the others in a batch.
 */
class SyncService(
    private val store: ServerStore,
    private val time: TimeSource,
    private val rules: Rules = Rules(),
) {
    data class Rules(
        /** Server-wide switch for the negative-stock override (off by default). */
        val allowNegativeOverride: Boolean = false,
        val maxOperationsPerPush: Int = 200,
        val maxPageSize: Int = 1000,
    )

    private sealed interface Handled {
        data class Applied(val record: StoredRecord, val summary: String) : Handled
        data class Unchanged(val record: StoredRecord) : Handled
        data class Conflict(val message: String, val server: StoredRecord?) : Handled
        data class Rejected(val message: String) : Handled
    }

    /** Per-request caches of server state; rebuilt lazily and dropped after a rolled-back operation. */
    private inner class Context {
        private var master: MasterData? = null
        private var txns: MutableList<InventoryTransaction>? = null
        private var reports: MutableMap<String, ProductionReport>? = null

        fun master(): MasterData = master ?: loadMaster().also { master = it }
        fun txns(): MutableList<InventoryTransaction> = txns ?: store.records(WireEntity.TXN).mapNotNull { decode(it, TxnDto.serializer())?.toDomain() }.toMutableList().also { txns = it }
        fun reports(): MutableMap<String, ProductionReport> = reports ?: store.records(WireEntity.REPORT).mapNotNull { decode(it, ReportDto.serializer())?.toDomain() }
            .associateBy { it.id }.toMutableMap().also { reports = it }

        fun masterChanged() { master = null }
        fun reset() { master = null; txns = null; reports = null }
    }

    // ------------------------------------------------------------------------------------------------ push

    fun push(principal: RequestPrincipal, request: PushRequest): PushResponse {
        require(request.operations.size <= rules.maxOperationsPerPush) { "At most ${rules.maxOperationsPerPush} operations per request" }
        val ctx = Context()
        return PushResponse(request.operations.map { op -> pushOne(principal, op, ctx) })
    }

    private fun pushOne(principal: RequestPrincipal, op: PushOperation, ctx: Context): PushResult {
        store.appliedOp(op.idempotencyKey)?.let { return PushResult(op.id, PushStatus.DUPLICATE, it.version) }
        return try {
            store.transaction {
                val handled = handle(principal, op, ctx)
                when (handled) {
                    is Handled.Applied -> {
                        val now = time.now()
                        store.recordApplied(AppliedOp(op.idempotencyKey, principal.deviceId, op.entityType, op.entityId, handled.record.version, now, principal.actor.userId))
                        store.audit(AuditEntry(newId(), now, principal.actor.userId, principal.actor.displayName, principal.deviceId, op.entityType, op.entityId, op.kind, handled.summary))
                        PushResult(op.id, PushStatus.APPLIED, handled.record.version)
                    }
                    is Handled.Unchanged -> PushResult(op.id, PushStatus.APPLIED, handled.record.version)
                    is Handled.Conflict -> PushResult(op.id, PushStatus.CONFLICT, handled.server?.version, handled.message, handled.server?.payload?.let { WireJson.parseToJsonElement(it) })
                    is Handled.Rejected -> PushResult(op.id, PushStatus.REJECTED, null, handled.message)
                }
            }
        } catch (e: SerializationException) {
            ctx.reset()
            PushResult(op.id, PushStatus.REJECTED, message = "The ${op.entityType} data is not in the expected format")
        } catch (e: IllegalArgumentException) {
            ctx.reset()
            PushResult(op.id, PushStatus.REJECTED, message = e.message ?: "Invalid ${op.entityType} data")
        } catch (e: DateTimeException) {
            ctx.reset()
            PushResult(op.id, PushStatus.REJECTED, message = "A date or time in the ${op.entityType} is not valid")
        }
    }

    private fun handle(p: RequestPrincipal, op: PushOperation, ctx: Context): Handled = when (op.entityType) {
        WireEntity.REPORT -> when (op.kind) {
            "UPSERT" -> upsertReport(p, op, ctx)
            "DELETE" -> deleteReport(p, op, ctx)
            else -> Handled.Rejected("Unsupported operation ${op.kind} on a report")
        }
        WireEntity.REPORT_ACTION -> reportAction(p, op, ctx)
        WireEntity.TXN -> if (op.kind == "UPSERT") upsertTxn(p, op, ctx) else Handled.Rejected("Stock movements are never deleted; record a correcting movement")
        in WireEntity.MASTER -> if (op.kind == "UPSERT") upsertMaster(p, op, ctx) else Handled.Rejected("Master data is deactivated, not deleted")
        else -> Handled.Rejected("Unknown record type ${op.entityType}")
    }

    /**
     * A change based on [base] may be applied to [current] when nobody else changed the record since: either the
     * base is the current version, or every version after it was written by this same device (offline edits queued
     * one after another all carry the version the device last saw).
     */
    private fun stale(current: StoredRecord?, base: Long?, deviceId: String): Boolean =
        current != null && (base ?: 0) != current.version && current.updatedByDevice != deviceId

    private fun write(type: String, id: String, json: (Long) -> String, current: StoredRecord?, p: RequestPrincipal): StoredRecord {
        val next = (current?.version ?: 0) + 1
        return store.write(type, id, json(next), false, p.actor.userId, p.deviceId, time.now()).also {
            check(it.version == next) { "Version race on $type $id" }
        }
    }

    // ------------------------------------------------------------------------------------------------ reports

    private fun upsertReport(p: RequestPrincipal, op: PushOperation, ctx: Context): Handled {
        val dto = WireJson.decodeFromJsonElement(ReportDto.serializer(), op.payload)
        require(dto.id == op.entityId) { "The report id does not match the operation" }
        val incoming = dto.toDomain()
        val actor = p.actor
        val current = store.record(WireEntity.REPORT, op.entityId, forUpdate = true)
        if (current?.deleted == true) return Handled.Rejected("This draft was deleted on another device")
        val server = current?.let { decode(it, ReportDto.serializer())?.toDomain() }

        val merged = if (server == null) {
            if (!actor.can(Permission.REPORT_CREATE)) return Handled.Rejected("Your role cannot create production reports")
            if (incoming.status != ReportStatus.DRAFT || incoming.version != 0) return Handled.Rejected("A new report must start as a draft")
            if (incoming.createdBy != actor.userId && !actor.can(Permission.REPORT_EDIT_ANY_DRAFT)) return Handled.Rejected("Reports are created in your own name")
            ctx.reports().values.firstOrNull { it.id != incoming.id && it.date == incoming.date && it.shiftId == incoming.shiftId && it.lineId == incoming.lineId && it.status != ReportStatus.REJECTED }
                ?.let { return Handled.Rejected("Report ${it.reference} already exists for this line, shift and date") }
            incoming
        } else {
            if (!ReportWorkflow.canEdit(server, actor)) {
                return Handled.Conflict("${server.reference} is ${server.status.label.lowercase()} on the server and can no longer be edited", current)
            }
            if (stale(current, op.baseVersion, p.deviceId)) return Handled.Conflict("${server.reference} was changed on another device", current)
            // Status, submission count, authorship and decisions are server-controlled; only content comes from the device.
            incoming.copy(
                reference = server.reference, status = server.status, version = server.version, createdBy = server.createdBy, createdAt = server.createdAt,
                submittedAt = server.submittedAt, decidedAt = server.decidedAt, decidedBy = server.decidedBy, duplicatedFrom = server.duplicatedFrom,
            )
        }
        val others = ctx.reports().values.filter { it.id != merged.id }
        ReportValidator.validate(merged, ctx.master(), time.today(), others, ReportValidator.Stage.DRAFT).errors().firstOrNull()
            ?.let { return Handled.Rejected(it.message) }
        val record = write(WireEntity.REPORT, merged.id, { v -> encode(ReportDto.serializer(), merged.toDto(v)) }, current, p)
        ctx.reports()[merged.id] = merged
        return Handled.Applied(record, "${if (server == null) "Created" else "Saved"} ${merged.reference}")
    }

    private fun deleteReport(p: RequestPrincipal, op: PushOperation, ctx: Context): Handled {
        val current = store.record(WireEntity.REPORT, op.entityId, forUpdate = true) ?: return Handled.Rejected("Report not found on the server")
        if (current.deleted) return Handled.Unchanged(current)
        val server = decode(current, ReportDto.serializer())?.toDomain() ?: return Handled.Rejected("Report not readable")
        if (server.status != ReportStatus.DRAFT || server.version > 0 || !ReportWorkflow.canEdit(server, p.actor)) {
            return Handled.Conflict("Only an unsubmitted draft can be deleted; ${server.reference} is ${server.status.label.lowercase()}", current)
        }
        val record = store.write(WireEntity.REPORT, server.id, null, true, p.actor.userId, p.deviceId, time.now())
        ctx.reports().remove(server.id)
        return Handled.Applied(record, "Deleted unsubmitted draft ${server.reference}")
    }

    private fun reportAction(p: RequestPrincipal, op: PushOperation, ctx: Context): Handled {
        val dto = WireJson.decodeFromJsonElement(ReportActionDto.serializer(), op.payload)
        require(dto.reportId == op.entityId) { "The report id does not match the operation" }
        val action = ReportAction.valueOf(dto.action)
        val current = store.record(WireEntity.REPORT, dto.reportId, forUpdate = true)
        if (current == null || current.deleted) return Handled.Rejected("The report has not reached the server")
        val server = decode(current, ReportDto.serializer())?.toDomain() ?: return Handled.Rejected("Report not readable")

        if (action == ReportAction.SUBMIT) {
            // The device sends what it submitted; the server copy must have exactly that content.
            val snapshot = dto.snapshot?.toDomain() ?: return Handled.Rejected("Submission without the submitted content")
            if (!sameContent(snapshot, server)) return Handled.Conflict("The server copy of ${server.reference} differs from what was submitted", current)
        } else if (stale(current, op.baseVersion, p.deviceId)) {
            return Handled.Conflict("${server.reference} changed since you opened it; review the latest version first", current)
        }

        val issues = if (action == ReportAction.SUBMIT) {
            ReportValidator.validate(server, ctx.master(), time.today(), ctx.reports().values.filter { it.id != server.id }, ReportValidator.Stage.SUBMIT)
        } else emptyList()
        val now = time.now()
        val t = when (val o = ReportWorkflow.apply(server, action, p.actor, dto.reason, now, issues)) {
            is Outcome.Refused -> {
                val first = o.issues.first()
                return if (first.code == "workflow.invalid_state") Handled.Conflict(first.message, current) else Handled.Rejected(first.message)
            }
            is Outcome.Ok -> o.value
        }
        val record = write(WireEntity.REPORT, t.report.id, { v -> encode(ReportDto.serializer(), t.report.toDto(v)) }, current, p)
        // The approval keeps the id the device generated, so both sides hold one row for it.
        val approval = t.record.copy(id = dto.approvalId)
        store.write(WireEntity.APPROVAL, approval.id, encode(ApprovalDto.serializer(), approval.toDto()), false, p.actor.userId, p.deviceId, now)
        if (t.snapshotRequired) {
            store.write(WireEntity.REPORT_VERSION, "${t.report.id}:${t.report.version}", encode(ReportDto.serializer(), t.report.toDto(record.version)), false, p.actor.userId, p.deviceId, now)
        }
        ctx.reports()[t.report.id] = t.report
        return Handled.Applied(record, "${action.label}: ${t.report.reference}${approval.reason?.let { " — $it" } ?: ""}")
    }

    /** Same report content, ignoring the fields the workflow and the server manage. */
    private fun sameContent(a: ProductionReport, b: ProductionReport): Boolean {
        fun ProductionReport.content() = copy(status = ReportStatus.DRAFT, version = 0, updatedAt = createdAt, submittedAt = null, decidedAt = null, decidedBy = null, supervisorId = null)
        return a.content() == b.content()
    }

    // ------------------------------------------------------------------------------------------------ stock

    private fun upsertTxn(p: RequestPrincipal, op: PushOperation, ctx: Context): Handled {
        val dto = WireJson.decodeFromJsonElement(TxnDto.serializer(), op.payload)
        require(dto.id == op.entityId) { "The movement id does not match the operation" }
        val incoming = dto.toDomain()
        val actor = p.actor
        val current = store.record(WireEntity.TXN, incoming.id, forUpdate = true)
        val txns = ctx.txns()

        if (current != null) {
            val server = decode(current, TxnDto.serializer())?.toDomain() ?: return Handled.Rejected("Movement not readable")
            if (server == incoming) return Handled.Unchanged(current)
            val decision = server.status == TxnStatus.PENDING_APPROVAL && incoming.status in setOf(TxnStatus.POSTED, TxnStatus.REJECTED) &&
                server.copy(status = incoming.status, approvedBy = incoming.approvedBy, approvedAt = incoming.approvedAt, note = incoming.note, negativeOverride = incoming.negativeOverride) == incoming
            if (!decision) {
                return if (server.status != TxnStatus.PENDING_APPROVAL) Handled.Conflict("This movement was already decided on the server", current)
                else Handled.Rejected("Posted movements cannot be changed; record a correcting movement instead")
            }
            val note = incoming.note?.removePrefix(server.note ?: "")?.removePrefix(" — ")?.trim()?.ifEmpty { null }
            val decided = when (val o = MovementService.decide(server, incoming.status == TxnStatus.POSTED, actor, txns.filter { it.id != server.id }, time.now(), note, inventorySettings(txns))) {
                is Outcome.Refused -> return Handled.Rejected(o.issues.first().message)
                is Outcome.Ok -> o.value
            }
            val record = write(WireEntity.TXN, decided.id, { v -> encode(TxnDto.serializer(), decided.toDto(v)) }, current, p)
            txns.replaceAll { if (it.id == decided.id) decided else it }
            return Handled.Applied(record, "${if (decided.status == TxnStatus.POSTED) "Approved" else "Rejected"} ${decided.type.label.lowercase()}")
        }

        // Another device already posted this movement (same key, e.g. the same workbook imported twice).
        txns.firstOrNull { it.idempotencyKey == incoming.idempotencyKey }?.let { existing ->
            return Handled.Unchanged(store.record(WireEntity.TXN, existing.id) ?: return Handled.Rejected("Movement not readable"))
        }
        if (incoming.createdBy != actor.userId) return Handled.Rejected("Movements are recorded in your own name")

        val accepted = when {
            incoming.historical || incoming.origin == TxnOrigin.LEGACY_IMPORT -> {
                if (!actor.can(Permission.IMPORT_RUN)) return Handled.Rejected("Only an administrator can import workbook history")
                if (!incoming.historical || incoming.origin != TxnOrigin.LEGACY_IMPORT || incoming.status != TxnStatus.POSTED) return Handled.Rejected("Imported history must be marked historical")
                incoming
            }
            incoming.type == TxnType.OPENING_BALANCE -> {
                if (!actor.can(Permission.OPENING_BALANCE_MIGRATE)) return Handled.Rejected("Only an administrator can set opening balances")
                if (txns.any { !it.historical && it.type == TxnType.OPENING_BALANCE && it.itemKind == incoming.itemKind && it.itemId == incoming.itemId }) {
                    return Handled.Rejected("This item already has an opening balance; use an adjustment")
                }
                if (incoming.quantity.signum() < 0) return Handled.Rejected("An opening balance cannot be negative")
                incoming
            }
            incoming.type == TxnType.COUNT_VARIANCE -> {
                // Posted when a stock count is approved; the approver records the count's differences.
                if (!actor.can(Permission.ADJUSTMENT_APPROVE) || incoming.approvedBy != actor.userId || incoming.status != TxnStatus.POSTED) {
                    return Handled.Rejected("Stock-count variances are posted by the person approving the count")
                }
                incoming
            }
            else -> {
                val request = MovementRequest(
                    itemKind = incoming.itemKind, itemId = incoming.itemId, type = incoming.type,
                    quantity = incoming.enteredQuantity ?: incoming.quantity.abs(), unit = incoming.enteredUnit ?: incoming.unit, date = incoming.date,
                    adjustmentSign = if (incoming.type.direction == 0) incoming.quantity.signum() else 0,
                    reference = incoming.reference, counterparty = incoming.counterparty, reportId = incoming.reportId, entryId = incoming.entryId,
                    location = incoming.location, note = incoming.note, overrideNegative = incoming.negativeOverride, idempotencyKey = incoming.idempotencyKey,
                )
                val prepared = when (val o = MovementService.prepare(request, actor, ctx.master(), txns, { id -> ctx.reports()[id] }, time.today(), time.now(), inventorySettings(txns))) {
                    is Outcome.Refused -> return Handled.Rejected(o.issues.first().message)
                    is Outcome.Ok -> o.value
                }
                if (prepared.quantity.compareTo(incoming.quantity) != 0 || prepared.unit != incoming.unit || prepared.status != incoming.status) {
                    return Handled.Rejected("The movement does not match the server's calculation (quantity ${prepared.quantity.toPlainString()} ${prepared.unit.code}, ${prepared.status.name.lowercase()})")
                }
                incoming.copy(negativeOverride = prepared.negativeOverride, approvedBy = prepared.approvedBy, approvedAt = prepared.approvedAt?.let { incoming.approvedAt ?: it })
            }
        }
        val record = write(WireEntity.TXN, accepted.id, { v -> encode(TxnDto.serializer(), accepted.toDto(v)) }, null, p)
        txns += accepted
        return Handled.Applied(record, "${accepted.type.label}: ${accepted.quantity.stripTrailingZeros().toPlainString()} ${accepted.unit.code}${if (accepted.negativeOverride) " (negative-stock override)" else ""}")
    }

    /** Live stock starts at the earliest go-live opening balance; the negative-stock exception is a server setting. */
    private fun inventorySettings(txns: List<InventoryTransaction>): InventorySettings =
        InventorySettings(
            cutoffDate = txns.filter { !it.historical && it.type == TxnType.OPENING_BALANCE }.minOfOrNull { it.date },
            allowNegativeWithOverride = rules.allowNegativeOverride,
        )

    // ------------------------------------------------------------------------------------------------ master data

    private fun upsertMaster(p: RequestPrincipal, op: PushOperation, ctx: Context): Handled {
        val actor = p.actor
        val allowed = actor.can(Permission.MASTER_DATA_MANAGE) || (op.entityType == WireEntity.PRODUCT && actor.can(Permission.IMPORT_RUN))
        if (!allowed) return Handled.Rejected("Only an administrator can change master data")
        val current = store.record(op.entityType, op.entityId, forUpdate = true)
        val m = ctx.master()
        // Master data is edited by administrators only; the latest accepted change wins and every change is audited.
        val (json, summary) = when (op.entityType) {
            WireEntity.PRODUCT -> {
                val d = WireJson.decodeFromJsonElement(ProductDto.serializer(), op.payload)
                val x = d.toDomain()
                require(x.code.isNotBlank() && x.name.isNotBlank()) { "Product code and name are required" }
                x.kgPerMetre?.let { require(it.signum() > 0) { "kg per metre must be positive" } }
                m.products.firstOrNull { it.id != x.id && it.code.equals(x.code, true) }?.let {
                    // The same catalogue imported on two devices: the first copy stays, the second is not created.
                    return if (current == null) Handled.Rejected("Product code ${x.code} already exists") else Handled.Rejected("Product code ${x.code} is used by another product")
                }
                ({ v: Long -> encode(ProductDto.serializer(), x.toDto(v)) }) to "Saved product ${x.code}"
            }
            WireEntity.MATERIAL -> {
                val x = WireJson.decodeFromJsonElement(MaterialDto.serializer(), op.payload).toDomain()
                require(x.code.isNotBlank() && x.name.isNotBlank()) { "Material code and name are required" }
                m.materials.firstOrNull { it.id != x.id && it.code.equals(x.code, true) }?.let { return Handled.Rejected("Material code ${x.code} already exists") }
                ({ v: Long -> encode(MaterialDto.serializer(), x.toDto(v)) }) to "Saved material ${x.code}"
            }
            WireEntity.LINE -> {
                val x = WireJson.decodeFromJsonElement(LineDto.serializer(), op.payload).toDomain()
                require(x.code.isNotBlank() && x.name.isNotBlank()) { "Line code and name are required" }
                m.lines.firstOrNull { it.id != x.id && it.code.equals(x.code, true) }?.let { return Handled.Rejected("Line code ${x.code} already exists") }
                ({ v: Long -> encode(LineDto.serializer(), x.toDto(v)) }) to "Saved line ${x.code}"
            }
            WireEntity.MACHINE -> {
                val x = WireJson.decodeFromJsonElement(MachineDto.serializer(), op.payload).toDomain()
                require(m.line(x.lineId) != null) { "Machine ${x.code} refers to a line the server does not know" }
                ({ v: Long -> encode(MachineDto.serializer(), x.toDto(v)) }) to "Saved machine ${x.code}"
            }
            WireEntity.SHIFT -> {
                val x = WireJson.decodeFromJsonElement(ShiftDto.serializer(), op.payload).toDomain()
                require(x.start != x.end) { "Shift start and end cannot be the same" }
                ({ v: Long -> encode(ShiftDto.serializer(), x.toDto(v)) }) to "Saved shift ${x.code}"
            }
            WireEntity.DOWNTIME_CATEGORY -> {
                val x = WireJson.decodeFromJsonElement(CategoryDto.serializer(), op.payload).toDowntimeCategory()
                ({ v: Long -> encode(CategoryDto.serializer(), x.toDto(v)) }) to "Saved downtime cause ${x.name}"
            }
            WireEntity.DEFECT_CATEGORY -> {
                val x = WireJson.decodeFromJsonElement(CategoryDto.serializer(), op.payload).toDefectCategory()
                ({ v: Long -> encode(CategoryDto.serializer(), x.toDto(v)) }) to "Saved defect category ${x.name}"
            }
            WireEntity.THRESHOLD -> {
                val x = WireJson.decodeFromJsonElement(ThresholdDto.serializer(), op.payload).toDomain()
                require(op.entityId == "${x.itemKind}:${x.itemId}") { "Threshold key does not match" }
                ({ v: Long -> encode(ThresholdDto.serializer(), x.toDto(v)) }) to "Set stock thresholds"
            }
            WireEntity.TARGET -> {
                val x = WireJson.decodeFromJsonElement(TargetDto.serializer(), op.payload).toDomain()
                require(x.quantity.signum() > 0) { "Target must be greater than zero" }
                ({ v: Long -> encode(TargetDto.serializer(), x.toDto(v)) }) to "Saved production target"
            }
            WireEntity.RUN_RATE -> {
                val x = WireJson.decodeFromJsonElement(RunRateDto.serializer(), op.payload).toDomain()
                require(op.entityId == "${x.lineId}:${x.productId ?: "*"}") { "Run-rate key does not match" }
                require(x.metresPerHour.signum() > 0) { "Run rate must be greater than zero" }
                ({ v: Long -> encode(RunRateDto.serializer(), x.toDto(v)) }) to "Saved run rate"
            }
            else -> return Handled.Rejected("Unknown master data ${op.entityType}")
        }
        val record = write(op.entityType, op.entityId, json, current, p)
        ctx.masterChanged()
        return Handled.Applied(record, summary)
    }

    private fun loadMaster(): MasterData {
        fun <T, R> all(type: String, s: KSerializer<T>, map: (T) -> R): List<R> = store.records(type).mapNotNull { r -> decode(r, s)?.let(map) }
        return MasterData(
            products = all(WireEntity.PRODUCT, ProductDto.serializer()) { it.toDomain() },
            materials = all(WireEntity.MATERIAL, MaterialDto.serializer()) { it.toDomain() },
            lines = all(WireEntity.LINE, LineDto.serializer()) { it.toDomain() },
            machines = all(WireEntity.MACHINE, MachineDto.serializer()) { it.toDomain() },
            shifts = all(WireEntity.SHIFT, ShiftDto.serializer()) { it.toDomain() },
            downtimeCategories = all(WireEntity.DOWNTIME_CATEGORY, CategoryDto.serializer()) { it.toDowntimeCategory() },
            defectCategories = all(WireEntity.DEFECT_CATEGORY, CategoryDto.serializer()) { it.toDefectCategory() },
            thresholds = all(WireEntity.THRESHOLD, ThresholdDto.serializer()) { it.toDomain() },
            targets = all(WireEntity.TARGET, TargetDto.serializer()) { it.toDomain() },
            runRates = all(WireEntity.RUN_RATE, RunRateDto.serializer()) { it.toDomain() },
        )
    }

    // ------------------------------------------------------------------------------------------------ pull

    /**
     * Changes after [cursor] that [actor] may see, oldest first. The cursor advances past changes the actor may not
     * see, so a page can hold fewer than [limit] changes while [PullResponse.hasMore] is still true.
     */
    fun pull(actor: Actor, cursor: String?, limit: Int): PullResponse {
        val after = cursor?.toLongOrNull()?.coerceAtLeast(0) ?: 0
        val page = limit.coerceIn(1, rules.maxPageSize)
        val rows = store.changesSince(after, page + 1)
        val shown = rows.take(page)
        val reportCache = HashMap<String, ProductionReport?>()
        fun report(id: String): ProductionReport? = reportCache.getOrPut(id) { store.record(WireEntity.REPORT, id)?.let { r -> decode(r, ReportDto.serializer())?.toDomain() } }
        val changes = shown.mapNotNull { c ->
            val r = c.record
            val visible = when (r.type) {
                in WireEntity.MASTER, WireEntity.USER -> true
                WireEntity.REPORT -> r.deleted || r.payload?.let { decode(r, ReportDto.serializer())?.toDomain()?.let { rep -> ReportWorkflow.canView(rep, actor) } } == true
                WireEntity.APPROVAL -> decode(r, ApprovalDto.serializer())?.let { a -> report(a.reportId)?.let { ReportWorkflow.canView(it, actor) } } == true
                WireEntity.TXN -> actor.can(Permission.INVENTORY_VIEW)
                else -> false
            }
            if (!visible) null
            else Change(r.type, r.id, r.version, r.deleted, if (r.deleted) null else r.payload?.let { WireJson.parseToJsonElement(it) })
        }
        return PullResponse(cursor = (shown.lastOrNull()?.seq ?: after).toString(), hasMore = rows.size > page, changes = changes)
    }

    // ------------------------------------------------------------------------------------------------ json

    private fun <T> encode(s: KSerializer<T>, value: T): String = WireJson.encodeToString(s, value)

    private fun <T> decode(r: StoredRecord, s: KSerializer<T>): T? = r.payload?.let {
        try {
            WireJson.decodeFromString(s, it)
        } catch (_: SerializationException) {
            null
        }
    }
}
