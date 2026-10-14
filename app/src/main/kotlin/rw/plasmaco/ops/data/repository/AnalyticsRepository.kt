package rw.plasmaco.ops.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import retrofit2.HttpException
import rw.plasmaco.ops.api.SaveUserRequest
import rw.plasmaco.ops.api.toDomain
import rw.plasmaco.ops.api.toDto
import rw.plasmaco.ops.data.local.AuditEntity
import rw.plasmaco.ops.data.local.ImportBatchEntity
import rw.plasmaco.ops.data.local.NotificationEntity
import rw.plasmaco.ops.data.local.PlasmacoDatabase
import rw.plasmaco.ops.data.local.toDomain
import rw.plasmaco.ops.data.local.toEntity
import rw.plasmaco.ops.data.remote.ApiProvider
import rw.plasmaco.ops.data.remote.serverMessage
import rw.plasmaco.ops.data.settings.SettingsRepository
import rw.plasmaco.ops.domain.analytics.Alert
import rw.plasmaco.ops.domain.analytics.AlertSeverity
import rw.plasmaco.ops.domain.analytics.AlertType
import rw.plasmaco.ops.domain.analytics.Analytics
import rw.plasmaco.ops.domain.analytics.DataSet
import rw.plasmaco.ops.domain.analytics.DrillTarget
import rw.plasmaco.ops.domain.analytics.Filters
import rw.plasmaco.ops.domain.common.DateRange
import rw.plasmaco.ops.domain.common.Outcome
import rw.plasmaco.ops.domain.common.TimeSource
import rw.plasmaco.ops.domain.common.newId
import rw.plasmaco.ops.domain.security.Actor
import rw.plasmaco.ops.domain.security.Permission
import rw.plasmaco.ops.domain.security.PinHasher
import rw.plasmaco.ops.domain.security.Role
import rw.plasmaco.ops.domain.security.User
import rw.plasmaco.ops.domain.workflow.ReportWorkflow
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** One live snapshot of everything analytics, reports and exports read, so all three show the same numbers. */
@Singleton
class AnalyticsRepository @Inject constructor(
    private val db: PlasmacoDatabase,
    private val master: MasterDataRepository,
    private val reports: ReportRepository,
    private val inventory: InventoryRepository,
) {
    val dataSet: Flow<DataSet> = combine(master.master, reports.reports, inventory.transactions, reports.approvals, db.users().observeAll()) { m, r, t, a, u ->
        DataSet(m, r, t, a, u.associate { it.id to it.displayName } + ("importer" to "Workbook import"))
    }

    suspend fun snapshot(): DataSet = dataSet.first()

    /** What [actor] may see: reports filtered by [ReportWorkflow.canView]; stock movements only with inventory access. */
    fun dataSetFor(actor: Actor): Flow<DataSet> = dataSet.map { it.visibleTo(actor) }

    suspend fun snapshotFor(actor: Actor): DataSet = snapshot().visibleTo(actor)
}

fun DataSet.visibleTo(actor: Actor): DataSet = copy(
    reports = reports.filter { ReportWorkflow.canView(it, actor) },
    approvals = if (actor.can(Permission.REPORT_VIEW_ALL)) approvals else approvals.filter { a -> reports.any { it.id == a.reportId && ReportWorkflow.canView(it, actor) } },
    transactions = if (actor.can(Permission.INVENTORY_VIEW)) transactions else emptyList(),
)

@Singleton
class UserRepository @Inject constructor(
    private val db: PlasmacoDatabase,
    private val session: SessionManager,
    private val recorder: ChangeRecorder,
    private val time: TimeSource,
    private val apiProvider: ApiProvider,
) {
    val users: Flow<List<User>> = db.users().observeAll().map { l -> l.map { it.toDomain() } }

    /**
     * Saves an account. With a server configured the server is the authority (it keeps the password hash and syncs the
     * account to every device), so the change needs a connection; in standalone mode the account lives on this device.
     */
    suspend fun save(user: User, newPin: String?): Outcome<User> {
        val actor = session.requireActor()
        if (!actor.can(Permission.USERS_MANAGE)) return Outcome.Refused("actor", "users.forbidden", "Only an administrator can manage users")
        if (user.username.isBlank() || user.displayName.isBlank()) return Outcome.Refused("username", "users.required", "Username and name are required")
        if (user.roles.isEmpty()) return Outcome.Refused("roles", "users.roles", "Give the user at least one role")
        val clash = db.users().byUsername(user.username)
        if (clash != null && clash.id != user.id) return Outcome.Refused("username", "users.taken", "Username ${user.username} is already used")
        if (user.id == actor.userId && Role.ADMIN !in user.roles) return Outcome.Refused("roles", "users.self_demote", "You cannot remove your own administrator role")
        newPin?.let { pin -> PinHasher.strengthIssues(pin).takeIf { it.isNotEmpty() }?.let { return Outcome.Refused("pin", "users.pin", it.joinToString(". ")) } }
        val existing = db.users().byId(user.id)
        val now = time.now().toEpochMilli()
        val api = apiProvider.api()
        val saved: User
        var version = existing?.version ?: 0
        var syncState = "LOCAL_ONLY"
        if (api != null) {
            val dto = try {
                api.saveUser(SaveUserRequest(user.toDto(existing?.version ?: 0), newPin))
            } catch (e: HttpException) {
                val message = e.serverMessage() ?: if (e.code() == 401) "Your server session has expired; sign in again" else "The server refused the change"
                return Outcome.Refused("server", "users.server", message)
            } catch (_: IOException) {
                return Outcome.Refused("server", "users.offline", "Connect to the server to add or change users")
            }
            // The password hash stays on the server; this device learns it only when that person signs in here.
            saved = dto.toDomain().copy(pinHash = existing?.pinHash)
            version = dto.version
            syncState = "SYNCED"
        } else {
            saved = user.copy(pinHash = newPin?.let { PinHasher.hash(it.toCharArray()) } ?: existing?.pinHash)
        }
        db.withTransaction {
            db.users().upsert(saved.toEntity(now, existing?.lastServerVerificationAt).copy(version = version, syncState = syncState))
            recorder.audit(actor, EntityTypes.USER, saved.id, if (existing == null) "CREATE" else "EDIT",
                "${if (existing == null) "Created" else "Updated"} user ${saved.username} (${saved.roles.joinToString { it.label }})${if (newPin != null) "; PIN set" else ""}${if (!saved.active) "; deactivated" else ""}")
        }
        return Outcome.Ok(saved)
    }

    fun newUser() = User(newId(), "", "", setOf(Role.OPERATOR), true)
}

@Singleton
class AuditRepository @Inject constructor(private val db: PlasmacoDatabase) {
    fun recent(limit: Int = 500): Flow<List<AuditEntity>> = db.audit().observeRecent(limit)
    fun forEntity(type: String, id: String): Flow<List<AuditEntity>> = db.audit().observeFor(type, id)
    val imports: Flow<List<ImportBatchEntity>> = db.imports().observeAll()
}

/**
 * Turns domain alerts into stored notifications. A notification is created once per alert key while its condition
 * lasts, resolved automatically when the condition clears, and raised again if the condition returns.
 */
@Singleton
class NotificationRepository @Inject constructor(
    private val db: PlasmacoDatabase,
    private val analytics: AnalyticsRepository,
    private val settings: SettingsRepository,
    private val session: SessionManager,
    private val time: TimeSource,
) {
    val open: Flow<List<NotificationEntity>> = db.notifications().observeOpen()
    val unread: Flow<Int> = db.notifications().observeUnread()

    /**
     * Recomputes alerts for the last 14 days from what the signed-in person may see, plus a sync alert while changes
     * are failed or in conflict; returns the alerts that are new since the last refresh. Nothing runs while no one is
     * signed in, so a locked shared device never raises notifications from data nobody there may view.
     */
    suspend fun refresh(): List<Alert> {
        val actor = session.actor ?: return emptyList()
        val today = time.today()
        val data = analytics.snapshotFor(actor)
        val f = Filters(DateRange(today.minusDays(14), today))
        val problems = db.outbox().problemCount()
        val alerts = Analytics.dashboard(data, f, today, settings.current().alertThresholds).alerts +
            listOfNotNull(
                if (problems > 0) Alert(SYNC_ALERT_KEY, AlertType.SYNC_FAILURE, AlertSeverity.WARNING, "Changes not synced",
                    "$problems change(s) could not be sent or conflict with the server. Open Sync to review them.", today) else null,
            )
        val known = db.notifications().openKeys().toSet()
        val fresh = alerts.filter { it.key !in known }
        db.withTransaction {
            db.notifications().upsertAll(fresh.map { it.toEntity(time.now().toEpochMilli()) })
            db.notifications().resolveAllExcept(alerts.map { it.key })
        }
        return fresh
    }

    suspend fun markRead(key: String) = db.notifications().markRead(key)
    suspend fun markAllRead() = db.notifications().markAllRead()

    private fun Alert.toEntity(now: Long) = NotificationEntity(
        key = key, type = type.name, severity = severity.name, title = title, message = message, createdAt = now, read = false, resolved = false,
        reportId = reportId,
        target = if (type == AlertType.SYNC_FAILURE) "sync" else when (val t = target) {
            is DrillTarget.Item -> "item:${t.kind.name}:${t.itemId}"
            is DrillTarget.Day -> "day:${t.date}"
            is DrillTarget.Product -> "product:${t.productId}"
            is DrillTarget.Line -> "line:${t.lineId}"
            is DrillTarget.Machine -> "machine:${t.machineId}"
            is DrillTarget.Shift -> "shift:${t.shiftId}"
            null -> null
        },
    )

    companion object {
        const val SYNC_ALERT_KEY = "sync:problems"
    }
}
