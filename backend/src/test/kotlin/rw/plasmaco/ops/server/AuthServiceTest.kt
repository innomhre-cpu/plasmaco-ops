package rw.plasmaco.ops.server

import rw.plasmaco.ops.api.LoginRequest
import rw.plasmaco.ops.api.UserDto
import rw.plasmaco.ops.domain.common.FixedTimeSource
import rw.plasmaco.ops.domain.common.Outcome
import rw.plasmaco.ops.domain.security.Role
import rw.plasmaco.ops.server.core.AuthService
import rw.plasmaco.ops.server.core.JwtCodec
import rw.plasmaco.ops.server.core.LoginResult
import rw.plasmaco.ops.server.core.RequestPrincipal
import java.time.Duration
import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthServiceTest {
    private val time = FixedTimeSource(Instant.parse("2026-10-08T08:00:00Z"))
    private val secret = ByteArray(32) { (it * 7 + 3).toByte() }
    private val jwt = JwtCodec(secret) { time.now() }
    private val store = InMemoryStore()
    private val auth = AuthService(store, jwt, time)

    private fun login(user: String, pw: String) = auth.login(LoginRequest(user, pw, "tablet-1"))
    private fun adminPrincipal(): RequestPrincipal {
        val ok = login("admin", "Str0ng-admin") as LoginResult.Success
        return auth.authenticate(ok.response.token)!!
    }

    @Test
    fun `tokens are signed, expire, and refuse the none algorithm`() {
        val (token, exp) = jwt.issue("u1", "dev", Duration.ofHours(1))
        assertEquals("u1", jwt.verify(token)?.userId)
        assertEquals(exp, jwt.verify(token)?.expiresAt)
        val parts = token.split('.')
        val enc = Base64.getUrlEncoder().withoutPadding()
        // Altered payload with the original signature.
        val forged = parts[0] + "." + enc.encodeToString("""{"sub":"admin","dev":"x","iat":0,"exp":9999999999}""".toByteArray()) + "." + parts[2]
        assertNull(jwt.verify(forged))
        // Unsigned token.
        val none = enc.encodeToString("""{"alg":"none","typ":"JWT"}""".toByteArray()) + "." + parts[1] + "."
        assertNull(jwt.verify(none))
        time.instant = time.instant.plus(Duration.ofHours(2))
        assertNull(jwt.verify(token))
        assertFailsWith<IllegalArgumentException> { JwtCodec(ByteArray(16)) { time.now() } }
    }

    @Test
    fun `the bootstrap administrator is created once and can sign in`() {
        assertTrue(auth.bootstrapAdministrator("admin", "Plant Administrator", "Str0ng-admin"))
        assertFalse(auth.bootstrapAdministrator("admin2", "Second", "Str0ng-admin"))
        val p = adminPrincipal()
        assertTrue(p.actor.hasRole(Role.ADMIN))
        assertEquals("tablet-1", p.deviceId)
        assertFailsWith<IllegalArgumentException> { AuthService(InMemoryStore(), jwt, time).bootstrapAdministrator("a", "A", "123456") }
    }

    @Test
    fun `wrong passwords lock the account and do not reveal which usernames exist`() {
        auth.bootstrapAdministrator("admin", "Plant Administrator", "Str0ng-admin")
        val unknown = login("nobody", "whatever1")
        val wrong = login("admin", "wrong-pass")
        assertEquals(AuthService.GENERIC_DENIAL, (unknown as LoginResult.Denied).message)
        assertEquals(AuthService.GENERIC_DENIAL, (wrong as LoginResult.Denied).message)
        repeat(3) { login("admin", "wrong-pass") }
        assertIs<LoginResult.Locked>(login("admin", "wrong-pass"))
        // Even the right password is refused while locked.
        assertIs<LoginResult.Locked>(login("admin", "Str0ng-admin"))
        time.instant = time.instant.plus(Duration.ofMinutes(16))
        assertIs<LoginResult.Success>(login("admin", "Str0ng-admin"))
        assertTrue(store.auditLog.any { it.action == "LOGIN_FAILED" })
    }

    @Test
    fun `users are managed by administrators and deactivation takes effect at once`() {
        auth.bootstrapAdministrator("admin", "Plant Administrator", "Str0ng-admin")
        val admin = adminPrincipal()
        val created = auth.saveUser(admin, UserDto("u-op", "jdoe", "Jean Operator", listOf("OPERATOR"), true), "Op3rator-pin")
        assertIs<Outcome.Ok<UserDto>>(created)
        val opToken = (login("jdoe", "Op3rator-pin") as LoginResult.Success).response.token
        val operator = auth.authenticate(opToken)
        assertNotNull(operator)
        // An operator cannot create users.
        assertIs<Outcome.Refused>(auth.saveUser(operator, UserDto("u-x", "x", "X", listOf("ADMIN"), true), "Str0ng-pass"))
        // Weak passwords and self-demotion are refused.
        assertIs<Outcome.Refused>(auth.saveUser(admin, UserDto("u-y", "y", "Y", listOf("OPERATOR"), true), "111111"))
        assertIs<Outcome.Refused>(auth.saveUser(admin, UserDto(admin.actor.userId, "admin", "Plant Administrator", listOf("SUPERVISOR"), true), null))
        // Deactivating the operator invalidates their existing token immediately.
        auth.saveUser(admin, UserDto("u-op", "jdoe", "Jean Operator", listOf("OPERATOR"), false), null)
        assertNull(auth.authenticate(opToken))
        assertIs<LoginResult.Denied>(login("jdoe", "Op3rator-pin"))
    }
}
