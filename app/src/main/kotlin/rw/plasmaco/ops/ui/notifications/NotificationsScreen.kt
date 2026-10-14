package rw.plasmaco.ops.ui.notifications

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import rw.plasmaco.ops.data.local.NotificationEntity
import rw.plasmaco.ops.data.repository.NotificationRepository
import rw.plasmaco.ops.data.repository.SessionManager
import rw.plasmaco.ops.domain.analytics.AlertSeverity
import rw.plasmaco.ops.domain.analytics.AlertType
import rw.plasmaco.ops.domain.common.TimeSource
import rw.plasmaco.ops.domain.security.Actor
import rw.plasmaco.ops.domain.security.Permission
import rw.plasmaco.ops.ui.common.Fmt
import rw.plasmaco.ops.ui.common.ListCard
import rw.plasmaco.ops.ui.common.ListRow
import rw.plasmaco.ops.ui.common.PlasmacoViewModel
import rw.plasmaco.ops.ui.common.ScreenList
import rw.plasmaco.ops.ui.common.UserMessages
import rw.plasmaco.ops.ui.common.notificationRoute
import rw.plasmaco.ops.ui.common.tone
import rw.plasmaco.ops.ui.components.EmptyState
import rw.plasmaco.ops.ui.components.LoadingState
import rw.plasmaco.ops.ui.components.PageHeaderCard
import rw.plasmaco.ops.ui.components.PriorityText
import rw.plasmaco.ops.ui.components.Tone
import rw.plasmaco.ops.ui.navigation.Navigator
import java.time.ZoneId
import javax.inject.Inject

data class NotificationsUi(
    val loading: Boolean = true,
    val actor: Actor? = null,
    val items: List<NotificationEntity> = emptyList(),
    val zone: ZoneId = ZoneId.of("Africa/Kigali"),
)

/** Which alert types a role is shown (stock alerts to inventory roles, production alerts to report viewers). */
fun visibleTo(type: String, actor: Actor): Boolean = when (runCatching { AlertType.valueOf(type) }.getOrNull()) {
    AlertType.LOW_STOCK -> actor.can(Permission.INVENTORY_VIEW)
    AlertType.PENDING_APPROVAL -> actor.can(Permission.REPORT_APPROVE) || actor.can(Permission.ADJUSTMENT_APPROVE)
    AlertType.SYNC_FAILURE -> true
    null -> true
    else -> actor.can(Permission.REPORT_VIEW_ALL) || actor.can(Permission.DASHBOARD_VIEW)
}

@HiltViewModel
class NotificationsViewModel @Inject constructor(
    private val notifications: NotificationRepository,
    session: SessionManager,
    private val time: TimeSource,
    messages: UserMessages,
) : PlasmacoViewModel(messages) {
    val state: StateFlow<NotificationsUi> = combine(notifications.open, session.user) { items, u ->
        val actor = u?.toActor() ?: return@combine NotificationsUi(loading = false)
        NotificationsUi(false, actor, items.filter { visibleTo(it.type, actor) }.sortedWith(compareBy<NotificationEntity> { it.read }.thenByDescending { it.createdAt }), time.zone())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotificationsUi())

    init {
        viewModelScope.launch { runCatching { notifications.refresh() } }
    }

    fun open(n: NotificationEntity) = viewModelScope.launch { notifications.markRead(n.key) }
    fun markAllRead() = action { notifications.markAllRead(); messages.show("All alerts marked as read") }
}

@Composable
fun NotificationsScreen(nav: Navigator, vm: NotificationsViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    if (ui.loading) { LoadingState(label = "Loading alerts"); return }
    val unread = ui.items.count { !it.read }
    ScreenList {
        item {
            PageHeaderCard(
                "Notifications", if (ui.items.isEmpty()) "No open alerts" else "${ui.items.size} open · $unread unread",
                actionText = if (unread > 0) "Mark all read" else null, actionIcon = Icons.Outlined.DoneAll, onAction = if (unread > 0) ({ vm.markAllRead() }) else null,
            )
        }
        if (ui.items.isEmpty()) item { EmptyState("Nothing needs attention", "Alerts for low stock, missing reports, pending approvals and sync problems appear here.", icon = Icons.Outlined.NotificationsNone) }
        else item {
            ListCard(ui.items) { n ->
                val route = notificationRoute(n.reportId, n.target)
                val severity = runCatching { AlertSeverity.valueOf(n.severity) }.getOrDefault(AlertSeverity.INFO)
                ListRow(
                    n.title,
                    subtitle = "${n.message}\n${Fmt.epoch(n.createdAt, ui.zone)}",
                    onClick = { vm.open(n); route?.let(nav::open) },
                ) { PriorityText(if (n.read) "Read" else Fmt.enumLabel(severity.name), if (n.read) Tone.NEUTRAL else severity.tone()) }
            }
        }
    }
}
