package rw.plasmaco.ops.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import rw.plasmaco.ops.data.repository.SessionManager
import rw.plasmaco.ops.data.repository.SignInResult
import rw.plasmaco.ops.data.repository.UserRepository
import rw.plasmaco.ops.data.settings.SettingsRepository
import java.net.URI
import javax.inject.Inject

data class AuthState(
    val serverUrl: String = "",
    val serverConfigured: Boolean = false,
    val hasUsers: Boolean = false,
    val working: Boolean = false,
    val error: String? = null,
)

/** Sign-in, first-administrator creation and the server address used on first run. */
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val session: SessionManager,
    private val settings: SettingsRepository,
    users: UserRepository,
) : ViewModel() {
    private val working = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)

    val state: StateFlow<AuthState> = combine(settings.settings, users.users.map { it.isNotEmpty() }, working, error) { s, has, w, e ->
        AuthState(s.serverUrl, s.serverConfigured, has, w, e)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AuthState())

    fun clearError() {
        error.value = null
    }

    fun signIn(username: String, secret: String) {
        if (username.isBlank() || secret.isEmpty()) {
            error.value = "Enter your username and PIN"
            return
        }
        attempt { session.signIn(username, secret.toCharArray()) }
    }

    fun createAdministrator(username: String, displayName: String, secret: String, confirm: String) {
        if (secret != confirm) {
            error.value = "The two PIN entries do not match"
            return
        }
        attempt { session.createFirstAdministrator(username, displayName, secret.toCharArray()) }
    }

    /** Only HTTPS addresses are accepted; credentials and production data never travel unencrypted. */
    fun saveServer(url: String) {
        val trimmed = url.trim().trimEnd('/')
        val valid = runCatching { URI(trimmed) }.getOrNull()?.let { it.scheme == "https" && !it.host.isNullOrBlank() } == true
        if (!valid) {
            error.value = "Enter the full server address, starting with https://"
            return
        }
        viewModelScope.launch {
            settings.setServerUrl(trimmed)
            error.value = null
        }
    }

    /** Allowed only while no account exists on this device (to correct a mistyped address on first run). */
    fun clearServer() {
        viewModelScope.launch {
            if (!state.value.hasUsers) settings.setServerUrl("")
        }
    }

    private fun attempt(block: suspend () -> SignInResult) {
        if (working.value) return
        viewModelScope.launch {
            working.value = true
            error.value = null
            val result = runCatching { block() }.getOrElse { SignInResult.Failure("Sign-in failed (${it.javaClass.simpleName})") }
            if (result is SignInResult.Failure) error.value = result.message
            working.value = false
        }
    }
}
