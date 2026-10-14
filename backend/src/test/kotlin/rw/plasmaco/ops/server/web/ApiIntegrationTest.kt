package rw.plasmaco.ops.server.web

import org.junit.BeforeClass
import org.junit.runner.RunWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.junit4.SpringRunner
import rw.plasmaco.ops.api.ApiError
import rw.plasmaco.ops.api.LineDto
import rw.plasmaco.ops.api.LoginRequest
import rw.plasmaco.ops.api.LoginResponse
import rw.plasmaco.ops.api.PullResponse
import rw.plasmaco.ops.api.PushOperation
import rw.plasmaco.ops.api.PushRequest
import rw.plasmaco.ops.api.PushResponse
import rw.plasmaco.ops.api.PushStatus
import rw.plasmaco.ops.api.SaveUserRequest
import rw.plasmaco.ops.api.UserDto
import rw.plasmaco.ops.api.WireEntity
import rw.plasmaco.ops.api.WireJson
import rw.plasmaco.ops.api.toDto
import rw.plasmaco.ops.domain.TestData
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The HTTP API end to end: Spring Boot on a random port, PostgreSQL in a container, the bootstrap administrator from
 * configuration. Requests and responses are exchanged as JSON text and decoded with the shared wire settings, exactly
 * as the app does. Skipped when Docker is not available.
 */
@RunWith(SpringRunner::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiIntegrationTest {
    companion object {
        // Generated per run: no credential or signing key is stored in the repository.
        private val adminPassword = "Adm-" + UUID.randomUUID()
        private val jwtSecret = Base64.getEncoder().encodeToString(ByteArray(48).also(SecureRandom()::nextBytes))

        @JvmStatic
        @BeforeClass
        fun docker() = PostgresTestDatabase.assumeDocker()

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { PostgresTestDatabase.jdbcUrl }
            registry.add("spring.datasource.username") { PostgresTestDatabase.username }
            registry.add("spring.datasource.password") { PostgresTestDatabase.password }
            registry.add("plasmaco.jwt-secret") { jwtSecret }
            registry.add("plasmaco.bootstrap-admin-username") { "admin" }
            registry.add("plasmaco.bootstrap-admin-password") { adminPassword }
        }
    }

    @Autowired
    private lateinit var http: TestRestTemplate

    private data class Answer(val status: Int, val body: String)

    private fun call(method: HttpMethod, path: String, json: String? = null, token: String? = null): Answer {
        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_JSON
        if (token != null) headers.setBearerAuth(token)
        val r = http.exchange("/api/v1$path", method, HttpEntity(json, headers), String::class.java)
        return Answer(r.statusCode.value(), r.body.orEmpty())
    }

    private fun error(a: Answer): ApiError = WireJson.decodeFromString(ApiError.serializer(), a.body)

    private fun login(username: String, password: String): Answer =
        call(HttpMethod.POST, "/auth/login", WireJson.encodeToString(LoginRequest.serializer(), LoginRequest(username, password, "test-device")))

    private fun token(username: String, password: String): String {
        val a = login(username, password)
        assertEquals(200, a.status, a.body)
        return WireJson.decodeFromString(LoginResponse.serializer(), a.body).token
    }

    @Test
    fun `health needs no token, everything else does`() {
        assertEquals(200, call(HttpMethod.GET, "/health").status)
        val denied = call(HttpMethod.GET, "/sync/pull")
        assertEquals(401, denied.status)
        assertEquals("unauthorized", error(denied).code)
        assertEquals(401, call(HttpMethod.GET, "/sync/pull", token = "not-a-token").status)
    }

    @Test
    fun `wrong credentials get one generic answer`() {
        val wrong = login("admin", "wrong-password")
        assertEquals(401, wrong.status)
        val unknown = login("nobody-${UUID.randomUUID()}", "wrong-password")
        assertEquals(401, unknown.status)
        assertEquals(error(wrong).message, error(unknown).message)
    }

    @Test
    fun `an administrator adds a user, who signs in and sees synced master data but cannot manage users`() {
        val admin = token("admin", adminPassword)
        val username = "op-" + UUID.randomUUID().toString().take(8)
        val operatorPassword = "Op-" + UUID.randomUUID()
        val user = UserDto(UUID.randomUUID().toString(), username, "Test Operator", listOf("OPERATOR"), true)
        val saved = call(HttpMethod.POST, "/admin/users", WireJson.encodeToString(SaveUserRequest.serializer(), SaveUserRequest(user, operatorPassword)), admin)
        assertEquals(200, saved.status, saved.body)

        val line = TestData.LINE.copy(id = "line-" + UUID.randomUUID(), code = "IT")
        val push = PushRequest("test-device", listOf(PushOperation("op-1", "key-" + UUID.randomUUID(), WireEntity.LINE, line.id, "UPSERT", null,
            WireJson.encodeToJsonElement(LineDto.serializer(), line.toDto()))))
        val pushed = call(HttpMethod.POST, "/sync/push", WireJson.encodeToString(PushRequest.serializer(), push), admin)
        assertEquals(200, pushed.status, pushed.body)
        assertEquals(PushStatus.APPLIED, WireJson.decodeFromString(PushResponse.serializer(), pushed.body).results.single().status)

        val operator = token(username, operatorPassword)
        val pulled = call(HttpMethod.GET, "/sync/pull?limit=1000", token = operator)
        assertEquals(200, pulled.status, pulled.body)
        assertTrue(WireJson.decodeFromString(PullResponse.serializer(), pulled.body).changes.any { it.entityId == line.id })
        assertEquals(403, call(HttpMethod.GET, "/admin/users", token = operator).status)
    }
}
