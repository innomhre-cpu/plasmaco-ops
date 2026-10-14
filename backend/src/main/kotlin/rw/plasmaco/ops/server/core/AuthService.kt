package rw.plasmaco.ops.server.core

import rw.plasmaco.ops.api.LoginRequest
import rw.plasmaco.ops.api.LoginResponse
import rw.plasmaco.ops.api.UserDto
import rw.plasmaco.ops.api.WireEntity
import rw.plasmaco.ops.api.WireJson
import rw.plasmaco.ops.api.toDomain
import rw.plasmaco.ops.api.toDto
import rw.plasmaco.ops.domain.common.Outcome
import rw.plasmaco.ops.domain.common.TimeSource
import rw.plasmaco.ops.domain.common.newId
import rw.plasmaco.ops.domain.security.Actor
import rw.plasmaco.ops.domain.security.Permission
import rw.plasmaco.ops.domain.security.PinHasher
import rw.plasmaco.ops.domain.security.Role
import rw.plasmaco.ops.domain.security.User
import java.time.Duration
import java.time.Instant

/** The person behind a request: who they are and which device they signed in on. */
data class RequestPrincipal(val actor: Actor, val deviceId: String)

sealed interface LoginResult {
    data class Success(val response: LoginResponse) : LoginResult
    data class Denied(val message: String) : LoginResult
    data class Locked(val until: Instant) : LoginResult
}

/**
 * Sign-in, token checks and user administration. Passwords and PINs are stored only as PBKDF2 hashes (the same
 * PinHasher the app uses for offline sign-in); repeated failures lock the account for a while; error messages do
 * not reveal whether a username exists.
 */
class AuthService(
    private val store: ServerStore,
    private val jwt: JwtCodec,
    private val time: TimeSource,
    private val policy: Policy = Policy(),
) {
    data class Policy(val maxFailures: Int = 5, val lockFor: Duration = Duration.ofMinutes(15), val tokenTtl: Duration = Duration.ofHours(12))

    /** Verified against when the username is unknown, so both cases take the same time. */
    private val decoyHash: String by lazy { PinHasher.hash("decoy-${newId()}".toCharArray()) }

    fun login(request: LoginRequest): LoginResult = store.transaction {
        val now = time.now()
        val creds = store.credentials(request.username.trim())
        if (creds == null) {
            PinHasher.verify(request.password.toCharArray(), decoyHash)
            return@transaction LoginResult.Denied(GENERIC_DENIAL)
        }
        creds.lockedUntil?.takeIf { it.isAfter(now) }?.let { return@transaction LoginResult.Locked(it) }
        val user = user(creds.userId)
        val ok = PinHasher.verify(request.password.toCharArray(), creds.passwordHash)
        if (!ok) {
            val failures = creds.failedAttempts + 1
            val locked = if (failures >= policy.maxFailures) now.plus(policy.lockFor) else null
            store.saveCredentials(creds.copy(failedAttempts = if (locked != null) 0 else failures, lockedUntil = locked))
            store.audit(AuditEntry(newId(), now, creds.userId, creds.username, request.deviceId.take(64), WireEntity.USER, creds.userId, "LOGIN_FAILED",
                if (locked != null) "Sign-in failed; account locked until $locked" else "Sign-in failed"))
            return@transaction if (locked != null) LoginResult.Locked(locked) else LoginResult.Denied(GENERIC_DENIAL)
        }
        if (user == null || !user.active) return@transaction LoginResult.Denied(GENERIC_DENIAL)
        if (creds.failedAttempts != 0 || creds.lockedUntil != null) store.saveCredentials(creds.copy(failedAttempts = 0, lockedUntil = null))
        val (token, expires) = jwt.issue(user.id, request.deviceId.take(64), policy.tokenTtl)
        store.audit(AuditEntry(newId(), now, user.id, user.displayName, request.deviceId.take(64), WireEntity.USER, user.id, "LOGIN", "Signed in"))
        val record = store.record(WireEntity.USER, user.id)
        LoginResult.Success(LoginResponse(token, expires.toString(), user.toDto(record?.version ?: 0)))
    }

    /** The principal for a bearer token, or null when the token is invalid or the user is gone or deactivated. */
    fun authenticate(token: String): RequestPrincipal? {
        val claims = jwt.verify(token) ?: return null
        val user = user(claims.userId)?.takeIf { it.active } ?: return null
        return RequestPrincipal(user.toActor(), claims.deviceId)
    }

    /** Creates or updates a user. Only [Permission.USERS_MANAGE]; an administrator cannot remove their own admin role. */
    fun saveUser(admin: RequestPrincipal, dto: UserDto, password: String?): Outcome<UserDto> = store.transaction {
        if (!admin.actor.can(Permission.USERS_MANAGE)) return@transaction Outcome.Refused("actor", "users.forbidden", "Only an administrator can manage users")
        val user = dto.toDomain()
        if (user.username.isBlank() || user.displayName.isBlank()) return@transaction Outcome.Refused("username", "users.required", "Username and name are required")
        if (user.roles.isEmpty()) return@transaction Outcome.Refused("roles", "users.roles", "Give the user at least one role")
        if (user.id == admin.actor.userId && Role.ADMIN !in user.roles) return@transaction Outcome.Refused("roles", "users.self_demote", "You cannot remove your own administrator role")
        val clash = store.credentials(user.username)
        if (clash != null && clash.userId != user.id) return@transaction Outcome.Refused("username", "users.taken", "Username ${user.username} is already used")
        val existing = store.credentialsById(user.id)
        if (existing == null && password == null) return@transaction Outcome.Refused("password", "users.password", "A new user needs a password or PIN")
        password?.let { p -> PinHasher.strengthIssues(p).takeIf { it.isNotEmpty() }?.let { return@transaction Outcome.Refused("password", "users.password", it.joinToString(". ")) } }
        val now = time.now()
        val record = store.write(WireEntity.USER, user.id, WireJson.encodeToString(UserDto.serializer(), user.toDto((store.record(WireEntity.USER, user.id)?.version ?: 0) + 1)), false, admin.actor.userId, admin.deviceId, now)
        store.saveCredentials(
            Credentials(
                userId = user.id,
                username = user.username,
                passwordHash = password?.let { PinHasher.hash(it.toCharArray()) } ?: existing!!.passwordHash,
                failedAttempts = 0,
                lockedUntil = null,
            ),
        )
        store.audit(AuditEntry(newId(), now, admin.actor.userId, admin.actor.displayName, admin.deviceId, WireEntity.USER, user.id, if (existing == null) "CREATE" else "EDIT",
            "${if (existing == null) "Created" else "Updated"} user ${user.username} (${user.roles.joinToString { it.label }})${if (password != null) "; password set" else ""}${if (!user.active) "; deactivated" else ""}"))
        Outcome.Ok(user.toDto(record.version))
    }

    /**
     * First start of an empty server: creates the administrator from configuration (environment variables), so no
     * default password ever exists. Does nothing once any user exists.
     */
    fun bootstrapAdministrator(username: String, displayName: String, password: String): Boolean = store.transaction {
        if (store.records(WireEntity.USER).isNotEmpty()) return@transaction false
        val issues = PinHasher.strengthIssues(password)
        require(issues.isEmpty()) { "Bootstrap administrator password: ${issues.joinToString(". ")}" }
        val user = User(newId(), username.trim(), displayName.trim(), setOf(Role.ADMIN), true)
        val now = time.now()
        store.write(WireEntity.USER, user.id, WireJson.encodeToString(UserDto.serializer(), user.toDto(1)), false, "system", "server", now)
        store.saveCredentials(Credentials(user.id, user.username, PinHasher.hash(password.toCharArray())))
        store.audit(AuditEntry(newId(), now, "system", "System", "server", WireEntity.USER, user.id, "CREATE", "Bootstrap administrator ${user.username} created"))
        true
    }

    fun user(id: String): User? = store.record(WireEntity.USER, id)?.takeIf { !it.deleted }?.payload
        ?.let { WireJson.decodeFromString(UserDto.serializer(), it).toDomain() }

    companion object {
        const val GENERIC_DENIAL = "Username or password is not correct"
    }
}
