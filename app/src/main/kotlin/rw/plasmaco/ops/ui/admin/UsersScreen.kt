package rw.plasmaco.ops.ui.admin

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import rw.plasmaco.ops.data.repository.SessionManager
import rw.plasmaco.ops.data.repository.UserRepository
import rw.plasmaco.ops.data.settings.SettingsRepository
import rw.plasmaco.ops.domain.security.Actor
import rw.plasmaco.ops.domain.security.Role
import rw.plasmaco.ops.domain.security.RolePermissions
import rw.plasmaco.ops.domain.security.User
import rw.plasmaco.ops.ui.common.Fmt
import rw.plasmaco.ops.ui.common.ListCard
import rw.plasmaco.ops.ui.common.ListRow
import rw.plasmaco.ops.ui.common.PlasmacoViewModel
import rw.plasmaco.ops.ui.common.ScreenList
import rw.plasmaco.ops.ui.common.UserMessages
import rw.plasmaco.ops.ui.components.AppBottomSheet
import rw.plasmaco.ops.ui.components.AppFilterChip
import rw.plasmaco.ops.ui.components.AppTextField
import rw.plasmaco.ops.ui.components.FieldHint
import rw.plasmaco.ops.ui.components.FieldLabel
import rw.plasmaco.ops.ui.components.FilterChipRow
import rw.plasmaco.ops.ui.components.LoadingState
import rw.plasmaco.ops.ui.components.NoticeBanner
import rw.plasmaco.ops.ui.components.PageHeaderCard
import rw.plasmaco.ops.ui.components.PrimaryButton
import rw.plasmaco.ops.ui.components.SectionCard
import rw.plasmaco.ops.ui.components.StatusBadge
import rw.plasmaco.ops.ui.components.Tone
import rw.plasmaco.ops.ui.navigation.Navigator
import rw.plasmaco.ops.ui.theme.Plasmaco
import javax.inject.Inject

data class UsersUi(val loading: Boolean = true, val actor: Actor? = null, val users: List<User> = emptyList(), val serverConfigured: Boolean = false)

@HiltViewModel
class UsersViewModel @Inject constructor(
    private val repo: UserRepository,
    session: SessionManager,
    settings: SettingsRepository,
    messages: UserMessages,
) : PlasmacoViewModel(messages) {
    val state: StateFlow<UsersUi> = combine(repo.users, session.user, settings.settings) { us, u, s ->
        UsersUi(false, u?.toActor(), us.sortedWith(compareBy({ !it.active }, { it.displayName })), s.serverConfigured)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UsersUi())

    fun newUser(): User = repo.newUser()

    fun save(user: User, pin: String, confirm: String, done: () -> Unit) = action {
        if (pin.isNotEmpty() && pin != confirm) { messages.show("The two PIN entries do not match"); return@action }
        repo.save(user, pin.ifEmpty { null }).handle("${user.displayName} saved")?.let { done() }
    }
}

@Composable
fun UsersScreen(nav: Navigator, vm: UsersViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<User?>(null) }
    if (ui.loading) { LoadingState(label = "Loading users"); return }
    ScreenList {
        item {
            PageHeaderCard(
                "Users and roles", "${ui.users.count { it.active }} active account(s)",
                actionText = "Add user", actionIcon = Icons.Outlined.PersonAdd, onAction = { editing = vm.newUser() },
            )
        }
        if (ui.serverConfigured) item { NoticeBanner("This device is connected to a server: accounts are managed there. Changes here only affect sign-in on this device.", Tone.INFO) }
        item {
            ListCard(ui.users) { u ->
                ListRow(
                    u.displayName, subtitle = "${u.username} · ${u.roles.joinToString { it.label }}", icon = Icons.Outlined.Person,
                    onClick = { editing = u },
                ) { StatusBadge(if (u.active) "Active" else "Inactive", if (u.active) Tone.SUCCESS else Tone.NEUTRAL) }
            }
        }
        item {
            SectionCard("What each role can do") {
                Role.entries.forEach { r ->
                    Text(r.label, style = Plasmaco.type.bodyStrong, color = Plasmaco.colors.textPrimary)
                    Text(RolePermissions.of(r).joinToString { Fmt.enumLabel(it.name) }, style = Plasmaco.type.label, color = Plasmaco.colors.textSecondary)
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
    editing?.let { u ->
        UserSheet(u, isSelf = u.id == ui.actor?.userId, isNew = ui.users.none { it.id == u.id }, busy = busy, onSave = { user, pin, confirm -> vm.save(user, pin, confirm) { editing = null } }, onDismiss = { editing = null })
    }
}

@Composable
private fun UserSheet(user: User, isSelf: Boolean, isNew: Boolean, busy: Boolean, onSave: (User, String, String) -> Unit, onDismiss: () -> Unit) {
    var username by rememberSaveable(user.id) { mutableStateOf(user.username) }
    var name by rememberSaveable(user.id) { mutableStateOf(user.displayName) }
    var roles by remember(user.id) { mutableStateOf(user.roles) }
    var active by rememberSaveable(user.id) { mutableStateOf(user.active) }
    var pin by remember(user.id) { mutableStateOf("") }
    var confirm by remember(user.id) { mutableStateOf("") }
    AppBottomSheet(if (isNew) "New user" else user.displayName, onDismiss) {
        AppTextField(username, { username = it.trim() }, "Username", required = true, enabled = isNew)
        Spacer(Modifier.height(12.dp))
        AppTextField(name, { name = it }, "Full name", required = true)
        Spacer(Modifier.height(12.dp))
        FieldLabel("Roles", required = true)
        FilterChipRow {
            Role.entries.forEach { r ->
                AppFilterChip(r.label, r in roles, { roles = if (r in roles) roles - r else roles + r })
            }
        }
        if (isSelf) FieldHint("You cannot remove your own administrator role.")
        Spacer(Modifier.height(12.dp))
        AppTextField(pin, { pin = it }, if (isNew) "PIN or password" else "New PIN (leave empty to keep)", required = isNew, isPassword = true, keyboardType = KeyboardType.Password)
        Spacer(Modifier.height(12.dp))
        AppTextField(confirm, { confirm = it }, "Repeat PIN", isPassword = true, keyboardType = KeyboardType.Password)
        FieldHint("At least 6 characters. PINs are stored only as a salted hash.")
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Account active", style = Plasmaco.type.bodyStrong, color = Plasmaco.colors.textPrimary, modifier = Modifier.weight(1f))
            Switch(active, { active = it }, enabled = !isSelf, colors = SwitchDefaults.colors(checkedTrackColor = Plasmaco.colors.primary))
        }
        Spacer(Modifier.height(16.dp))
        PrimaryButton(
            "Save", { onSave(user.copy(username = username, displayName = name.trim(), roles = roles, active = active), pin, confirm) },
            loading = busy, enabled = username.isNotBlank() && name.isNotBlank() && roles.isNotEmpty() && (!isNew || pin.isNotEmpty()),
        )
    }
}
