package rw.plasmaco.ops.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SyncProblem
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import rw.plasmaco.ops.BuildConfig
import rw.plasmaco.ops.data.local.ConflictEntity
import rw.plasmaco.ops.data.local.OutboxEntity
import rw.plasmaco.ops.data.repository.AdminSettings
import rw.plasmaco.ops.data.repository.SessionManager
import rw.plasmaco.ops.data.settings.AppSettings
import rw.plasmaco.ops.data.settings.SettingsRepository
import rw.plasmaco.ops.data.sync.ConnectivityMonitor
import rw.plasmaco.ops.data.sync.SyncRepository
import rw.plasmaco.ops.data.sync.SyncScheduler
import rw.plasmaco.ops.domain.common.TimeSource
import rw.plasmaco.ops.domain.security.Actor
import rw.plasmaco.ops.domain.security.Permission
import rw.plasmaco.ops.ui.common.DetailHeader
import rw.plasmaco.ops.ui.common.Fmt
import rw.plasmaco.ops.ui.common.KeyValueRow
import rw.plasmaco.ops.ui.common.ListRow
import rw.plasmaco.ops.ui.common.PlasmacoViewModel
import rw.plasmaco.ops.ui.common.RowDivider
import rw.plasmaco.ops.ui.common.ScreenForm
import rw.plasmaco.ops.ui.common.ScreenList
import rw.plasmaco.ops.ui.common.UserMessages
import rw.plasmaco.ops.ui.components.AppFilterChip
import rw.plasmaco.ops.ui.components.AppTextField
import rw.plasmaco.ops.ui.components.ConfirmDialog
import rw.plasmaco.ops.ui.components.EmptyState
import rw.plasmaco.ops.ui.components.FieldHint
import rw.plasmaco.ops.ui.components.FieldLabel
import rw.plasmaco.ops.ui.components.FilterChipRow
import rw.plasmaco.ops.ui.components.LoadingState
import rw.plasmaco.ops.ui.components.NoticeBanner
import rw.plasmaco.ops.ui.components.NumericField
import rw.plasmaco.ops.ui.components.PageHeaderCard
import rw.plasmaco.ops.ui.components.SecondaryButton
import rw.plasmaco.ops.ui.components.SectionCard
import rw.plasmaco.ops.ui.components.SmallButton
import rw.plasmaco.ops.ui.components.TextLink
import rw.plasmaco.ops.ui.components.Tone
import rw.plasmaco.ops.ui.navigation.Navigator
import rw.plasmaco.ops.ui.navigation.Routes
import rw.plasmaco.ops.ui.theme.Plasmaco
import rw.plasmaco.ops.ui.theme.ThemeMode
import java.time.DayOfWeek
import java.time.ZoneId
import javax.inject.Inject

data class SettingsUi(
    val loading: Boolean = true,
    val actor: Actor? = null,
    val settings: AppSettings = AppSettings(),
    val pending: Int = 0,
    val problems: List<OutboxEntity> = emptyList(),
    val conflicts: List<ConflictEntity> = emptyList(),
    val online: Boolean = true,
    val zone: ZoneId = ZoneId.of("Africa/Kigali"),
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val admin: AdminSettings,
    private val session: SessionManager,
    private val sync: SyncRepository,
    private val scheduler: SyncScheduler,
    connectivity: ConnectivityMonitor,
    time: TimeSource,
    messages: UserMessages,
) : PlasmacoViewModel(messages) {
    private data class SyncParts(val pending: Int, val problems: List<OutboxEntity>, val conflicts: List<ConflictEntity>, val online: Boolean)

    private val syncParts = combine(sync.pendingCount, sync.problems, sync.conflicts, connectivity.online) { p, pr, c, o -> SyncParts(p, pr, c, o) }

    val state: StateFlow<SettingsUi> = combine(settings.settings, session.user, syncParts) { s, u, p ->
        SettingsUi(false, u?.toActor(), s, p.pending, p.problems, p.conflicts, p.online, time.zone())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUi())

    fun signOut() {
        session.signOut()
        viewModelScope.launch { settings.clearToken() }
    }

    fun setTheme(mode: ThemeMode) = action { settings.setTheme(mode) }
    fun setServer(url: String) = action { admin.setServerUrl(url); messages.show(if (url.isBlank()) "Standalone mode" else "Server saved; sign in again to connect") }
    fun setThresholds(a: String, r: String, d: String) = action {
        val values = listOf(a, r, d).map { it.trim().toIntOrNull() }
        if (values.any { it == null }) { messages.show("Enter whole numbers"); return@action }
        admin.setThresholds(values[0]!!, values[1]!!, values[2]!!)
        messages.show("Alert thresholds saved")
        scheduler.refreshAlerts()
    }
    fun toggleDay(day: DayOfWeek) = action {
        val days = state.value.settings.workingDays
        admin.setWorkingDays(if (day in days) days - day else days + day)
    }
    fun setIdle(minutes: String) = action {
        val m = minutes.trim().toIntOrNull()
        if (m == null || m !in 1..240) { messages.show("Use 1 to 240 minutes"); return@action }
        admin.setIdleTimeout(m)
        messages.show("Saved")
    }
    fun setNegativeOverride(on: Boolean) = action { admin.setAllowNegativeOverride(on) }
    fun syncNow() {
        if (!state.value.settings.serverConfigured) { messages.show("No server is configured; this device works on its own"); return }
        scheduler.syncNow()
        messages.show(if (state.value.online) "Sync started" else "Sync will run when the device is back online")
    }
    fun retryFailed() = action { sync.retryFailed(); scheduler.syncNow(); messages.show("Failed changes queued again") }
    fun useServer(c: ConflictEntity) = action { sync.resolveUseServer(c.id); messages.show("Server copy kept") }
    fun keepMine(c: ConflictEntity) = action { sync.resolveKeepMine(c.id); scheduler.syncNow(); messages.show("Your copy will be sent again") }
}

@Composable
fun SettingsScreen(nav: Navigator, vm: SettingsViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val actor = ui.actor
    if (ui.loading || actor == null) { LoadingState(label = "Loading settings"); return }
    val s = ui.settings
    val manage = actor.can(Permission.SETTINGS_MANAGE)
    var server by rememberSaveable(s.serverUrl) { mutableStateOf(s.serverUrl.ifEmpty { "https://" }) }
    var minAch by rememberSaveable(s.minAchievementPct) { mutableStateOf(s.minAchievementPct.toString()) }
    var maxRej by rememberSaveable(s.maxRejectionPct) { mutableStateOf(s.maxRejectionPct.toString()) }
    var maxDown by rememberSaveable(s.maxDowntimeMinutes) { mutableStateOf(s.maxDowntimeMinutes.toString()) }
    var idle by rememberSaveable(s.idleTimeoutMinutes) { mutableStateOf(s.idleTimeoutMinutes.toString()) }
    var confirmOverride by rememberSaveable { mutableStateOf(false) }

    ScreenForm {
        PageHeaderCard("Settings", "${actor.displayName} · ${actor.roles.joinToString { it.label }}")
        SectionCard("Appearance") {
            FilterChipRow {
                ThemeMode.entries.forEach { m ->
                    AppFilterChip(when (m) { ThemeMode.SYSTEM -> "Match device"; ThemeMode.LIGHT -> "Light"; ThemeMode.DARK -> "Dark" }, s.themeMode == m, { vm.setTheme(m) })
                }
            }
        }
        SectionCard("Sync", subtitle = if (s.serverConfigured) s.serverUrl else "Standalone: data stays on this device", onOpen = { nav.open(Routes.SYNC) }, openLabel = "Sync details") {
            KeyValueRow("Connection", if (ui.online) "Online" else "Offline")
            KeyValueRow("Last sync", s.lastSyncAt?.let { Fmt.epoch(it, ui.zone) } ?: "Never")
            KeyValueRow("Waiting to send", ui.pending.toString())
            if (ui.conflicts.isNotEmpty() || ui.problems.isNotEmpty()) {
                NoticeBanner("${ui.conflicts.size} conflict(s) and ${ui.problems.size} failed change(s) need review.", Tone.WARNING, icon = Icons.Outlined.SyncProblem)
            }
            Spacer(Modifier.height(8.dp))
            SecondaryButton("Sync now", vm::syncNow, icon = Icons.Outlined.CloudSync, enabled = s.serverConfigured)
            if (manage) {
                Spacer(Modifier.height(12.dp))
                AppTextField(server, { server = it }, "Server address", hint = "HTTPS only. Leave empty for standalone mode. Everyone signs in again after a change.")
                Spacer(Modifier.height(8.dp))
                SmallButton("Save server", { vm.setServer(if (server.trim() == "https://") "" else server) })
            }
        }
        if (manage) {
            SectionCard("Alerts", subtitle = "When the dashboard and notifications raise an alert") {
                NumericField(minAch, { minAch = it }, "Target achievement below", "%")
                Spacer(Modifier.height(8.dp))
                NumericField(maxRej, { maxRej = it }, "Rejection rate above", "%")
                Spacer(Modifier.height(8.dp))
                NumericField(maxDown, { maxDown = it }, "Downtime per report above", "min")
                Spacer(Modifier.height(8.dp))
                SmallButton("Save thresholds", { vm.setThresholds(minAch, maxRej, maxDown) })
                Spacer(Modifier.height(12.dp))
                FieldLabel("Working days (a missing report is flagged only on these days)")
                FilterChipRow {
                    DayOfWeek.entries.forEach { d -> AppFilterChip(d.name.take(3).lowercase().replaceFirstChar { it.uppercase() }, d in s.workingDays, { vm.toggleDay(d) }) }
                }
            }
            SectionCard("Security and stock rules") {
                NumericField(idle, { idle = it }, "Sign out after idle", "min", hint = "Shared factory devices: keep this short")
                Spacer(Modifier.height(8.dp))
                SmallButton("Save", { vm.setIdle(idle) })
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Allow negative-stock override", style = Plasmaco.type.bodyStrong, color = Plasmaco.colors.textPrimary, modifier = Modifier.weight(1f))
                    Switch(
                        checked = s.allowNegativeOverride,
                        onCheckedChange = { on -> if (on) confirmOverride = true else vm.setNegativeOverride(false) },
                        colors = SwitchDefaults.colors(checkedTrackColor = Plasmaco.colors.primary),
                    )
                }
                FieldHint("Off: stock can never go below zero. On: a manager may override with a written reason; every override is flagged in the audit log.")
                Spacer(Modifier.height(8.dp))
                KeyValueRow("Live stock starts", s.inventoryCutoff?.let { Fmt.date(it) } ?: "Not set (no go-live migration yet)")
            }
        } else {
            SectionCard("Rules set by your administrator") {
                KeyValueRow("Automatic sign-out", "${s.idleTimeoutMinutes} min idle")
                KeyValueRow("Negative stock", if (s.allowNegativeOverride) "Blocked; manager override with a reason" else "Blocked")
            }
        }
        SectionCard("About") {
            KeyValueRow("App version", BuildConfig.VERSION_NAME)
            KeyValueRow("Device id", s.deviceId.take(8).ifEmpty { "—" })
            KeyValueRow("Plant time zone", ui.zone.id)
        }
        SecondaryButton("Sign out", vm::signOut, icon = Icons.AutoMirrored.Outlined.Logout, loading = busy)
    }
    if (confirmOverride) {
        ConfirmDialog(
            "Allow negative-stock overrides?",
            "Managers will be able to post a movement that takes stock below zero after writing a reason. Use this only while missing receipts are being traced.",
            "Allow", { confirmOverride = false; vm.setNegativeOverride(true) }, { confirmOverride = false }, destructive = true,
        )
    }
}

@Composable
fun SyncScreen(nav: Navigator, vm: SettingsViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    if (ui.loading) { LoadingState(label = "Loading sync status"); return }
    val s = ui.settings
    ScreenList {
        item { DetailHeader("Sync", nav::back, subtitle = if (s.serverConfigured) s.serverUrl else "Standalone device") }
        item {
            SectionCard("Status") {
                KeyValueRow("Connection", if (ui.online) "Online" else "Offline — changes are kept and sent later")
                KeyValueRow("Last sync", s.lastSyncAt?.let { Fmt.epoch(it, ui.zone) } ?: "Never")
                KeyValueRow("Waiting to send", ui.pending.toString())
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallButton("Sync now", vm::syncNow, icon = Icons.Outlined.CloudSync, enabled = s.serverConfigured)
                    if (ui.problems.isNotEmpty()) SmallButton("Retry failed", { vm.retryFailed() }, icon = Icons.Outlined.Refresh)
                }
            }
        }
        if (!s.serverConfigured) item { NoticeBanner("No server is configured. Everything is saved on this device only; back it up regularly (Administration → Backup).", Tone.INFO) }
        if (ui.conflicts.isEmpty() && ui.problems.isEmpty()) item { EmptyState("Nothing to review", "Changes made on this device and on the server agree.", icon = Icons.Outlined.CloudSync) }
        if (ui.conflicts.isNotEmpty()) {
            item {
                SectionCard("Conflicts", subtitle = "Changed here and on the server. Approved or locked records always keep the server copy.") {
                    ui.conflicts.forEachIndexed { i, c ->
                        if (i > 0) RowDivider()
                        ListRow("${Fmt.enumLabel(c.entityType)} · ${c.entityId.take(8)}", subtitle = "${c.reason}\n${Fmt.epoch(c.detectedAt, ui.zone)}", icon = Icons.Outlined.SyncProblem, iconTone = Tone.WARNING)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextLink("Use server copy", { vm.useServer(c) })
                            if (!c.serverApplied) TextLink("Keep mine", { vm.keepMine(c) })
                        }
                    }
                }
            }
        }
        if (ui.problems.isNotEmpty()) {
            item {
                SectionCard("Failed changes", subtitle = "The server refused these; fix the cause, then retry") {
                    ui.problems.forEachIndexed { i, p ->
                        if (i > 0) RowDivider()
                        ListRow(
                            "${Fmt.enumLabel(p.entityType)} · ${Fmt.enumLabel(p.kind)}",
                            subtitle = "${p.lastError ?: "Unknown error"} · ${p.attempts} attempt(s) · ${Fmt.epoch(p.createdAt, ui.zone)}",
                            icon = Icons.Outlined.ErrorOutline, iconTone = Tone.DANGER,
                        )
                    }
                }
            }
        }
    }
}
