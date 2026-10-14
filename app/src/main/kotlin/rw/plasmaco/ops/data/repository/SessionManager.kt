package rw.plasmaco.ops.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import retrofit2.HttpException
import rw.plasmaco.ops.data.local.PlasmacoDatabase
import rw.plasmaco.ops.data.local.toDomain
import rw.plasmaco.ops.data.local.toEntity
import rw.plasmaco.ops.data.remote.ApiProvider
import rw.plasmaco.ops.data.remote.serverMessage
import rw.plasmaco.ops.api.LoginRequest
import rw.plasmaco.ops.api.toDomain
import rw.plasmaco.ops.data.settings.SettingsRepository
import rw.plasmaco.ops.domain.common.TimeSource
import rw.plasmaco.ops.domain.common.newId
import rw.plasmaco.ops.domain.security.Actor
import rw.plasmaco.ops.domain.security.PinHasher
import rw.plasmaco.ops.domain.security.Role
import rw.plasmaco.ops.domain.security.SessionPolicy
import rw.plasmaco.ops.domain.security.User
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SignInResult {
    data class Success(val user: User, val offline: Boolean) : SignInResult
    data class Failure(val message: String) : SignInResult
}

/**
 * Who is signed in. With a server configured, sign-in is verified online and the secret's PBKDF2 hash is cached so the
 * same person can sign in offline for [SessionPolicy.maxOfflineDays]. In standalone mode, local accounts only.
 */
@Singleton
class SessionManager @Inject constructor(
    private val db: PlasmacoDatabase,
    private val settings: SettingsRepository,
    private val apiProvider: ApiProvider,
    private val time: TimeSource,
) {
    private val _user = MutableStateFlow<User?>(null)
    val user: StateFlow<User?> = _user.asStateFlow()
    private val _lastActivity = MutableStateFlow(0L)
    private val policy get() = SessionPolicy()

    val actor: Actor? get() = _user.value?.toActor()

    fun requireActor(): Actor = actor ?: throw NotAllowedException("Your session has ended. Sign in again.")

    suspend fun hasUsers(): Boolean = db.users().count() > 0

    suspend fun signIn(username: String, secret: CharArray): SignInResult {
        val now = time.now().toEpochMilli()
        val local = db.users().byUsername(username.trim())
        val api = apiProvider.api()
        if (api != null) {
            try {
                val res = api.login(LoginRequest(username.trim(), String(secret), settings.deviceId()))
                settings.saveToken(res.token, Instant.parse(res.expiresAt).toEpochMilli())
                val user = res.user.toDomain().copy(pinHash = PinHasher.hash(secret))
                db.users().upsert(user.toEntity(now, lastVerified = now))
                return started(user, offline = false)
            } catch (e: HttpException) {
                // 401 wrong credentials, 403 account locked: never fall back to offline sign-in, which would bypass the lock.
                if (e.code() == 401 || e.code() == 403) return SignInResult.Failure(e.serverMessage() ?: "Username or PIN is not correct")
                // Server error: fall through to offline sign-in.
            } catch (_: IOException) {
                // No connection: fall through to offline sign-in.
            }
        }
        if (local == null || !local.active) return SignInResult.Failure("Username or PIN is not correct")
        val hash = local.pinHash ?: return SignInResult.Failure("This account has not signed in on this device yet; connect to the server once")
        if (!PinHasher.verify(secret, hash)) return SignInResult.Failure("Username or PIN is not correct")
        if (api != null && !policy.offlineSignInAllowed(local.lastServerVerificationAt, now)) {
            return SignInResult.Failure("Offline sign-in has expired; connect to the server to sign in")
        }
        return started(local.toDomain(), offline = api != null)
    }

    private fun started(user: User, offline: Boolean): SignInResult {
        _user.value = user
        touch()
        return SignInResult.Success(user, offline)
    }

    fun signOut() {
        _user.value = null
    }

    fun touch() {
        _lastActivity.value = time.now().toEpochMilli()
    }

    /** Called when the app returns to the foreground: shared factory devices lock after the idle timeout. */
    suspend fun enforceIdleTimeout() {
        val minutes = settings.current().idleTimeoutMinutes.toLong()
        if (_user.value != null && SessionPolicy(idleTimeoutMinutes = minutes).isExpired(_lastActivity.value, time.now().toEpochMilli())) signOut()
    }

    /** First run on a device without a server: creates the administrator who then sets everything else up. */
    suspend fun createFirstAdministrator(username: String, displayName: String, secret: CharArray): SignInResult {
        if (hasUsers()) return SignInResult.Failure("An administrator already exists")
        val issues = PinHasher.strengthIssues(String(secret))
        if (issues.isNotEmpty()) return SignInResult.Failure(issues.joinToString(". "))
        if (username.isBlank() || displayName.isBlank()) return SignInResult.Failure("Enter a username and a display name")
        val now = time.now().toEpochMilli()
        val user = User(newId(), username.trim(), displayName.trim(), setOf(Role.ADMIN), true, PinHasher.hash(secret))
        db.withTransaction { db.users().upsert(user.toEntity(now, lastVerified = null)) }
        return started(user, offline = true)
    }
}
