package rw.plasmaco.ops

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import rw.plasmaco.ops.data.repository.NotificationRepository
import rw.plasmaco.ops.data.repository.SessionManager
import rw.plasmaco.ops.data.repository.UserRepository
import rw.plasmaco.ops.data.settings.SettingsRepository
import rw.plasmaco.ops.data.sync.ConnectivityMonitor
import rw.plasmaco.ops.data.sync.SyncRepository
import rw.plasmaco.ops.data.sync.SyncScheduler
import rw.plasmaco.ops.domain.security.User
import rw.plasmaco.ops.ui.common.UserMessages
import rw.plasmaco.ops.ui.theme.ThemeMode
import javax.inject.Inject

data class MainState(
    val loading: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val user: User? = null,
    val hasUsers: Boolean = false,
    val serverConfigured: Boolean = false,
    val online: Boolean = true,
    val unread: Int = 0,
    val pendingSync: Int = 0,
)

/** App-wide state: theme, who is signed in, unread alerts, sync backlog and connectivity. */
@OptIn(FlowPreview::class)
@HiltViewModel
class MainViewModel @Inject constructor(
    private val session: SessionManager,
    private val settings: SettingsRepository,
    users: UserRepository,
    notifications: NotificationRepository,
    sync: SyncRepository,
    connectivity: ConnectivityMonitor,
    private val scheduler: SyncScheduler,
    val messages: UserMessages,
) : ViewModel() {

    private data class Base(val themeMode: ThemeMode, val serverConfigured: Boolean, val user: User?, val hasUsers: Boolean)
    private data class Live(val online: Boolean, val unread: Int, val pending: Int)

    private val base = combine(settings.settings, session.user, users.users.map { it.isNotEmpty() }) { s, u, has ->
        Base(s.themeMode, s.serverConfigured, u, has)
    }
    private val live = combine(connectivity.online, notifications.unread, sync.pendingCount) { o, n, p -> Live(o, n, p) }

    val state: StateFlow<MainState> = combine(base, live) { b, l ->
        MainState(false, b.themeMode, b.user, b.hasUsers, b.serverConfigured, l.online, l.unread, l.pending)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MainState())

    init {
        // Local changes are pushed shortly after they are made (coalesced), not only by the 15-minute schedule.
        viewModelScope.launch {
            sync.pendingCount.debounce(2_000).filter { it > 0 }.collect {
                if (settings.current().serverConfigured) scheduler.syncNow()
            }
        }
        // Alerts are recomputed whenever someone signs in.
        viewModelScope.launch {
            session.user.map { it?.id }.distinctUntilChanged().filter { it != null }.collect { scheduler.refreshAlerts() }
        }
    }

    fun onResume() {
        viewModelScope.launch { session.enforceIdleTimeout() }
    }

    fun touch() = session.touch()

    fun signOut() {
        session.signOut()
        viewModelScope.launch { settings.clearToken() }
    }
}
