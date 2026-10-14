package rw.plasmaco.ops.server.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.filter.OncePerRequestFilter
import rw.plasmaco.ops.api.ApiError
import rw.plasmaco.ops.api.LoginRequest
import rw.plasmaco.ops.api.LoginResponse
import rw.plasmaco.ops.api.PullResponse
import rw.plasmaco.ops.api.PushRequest
import rw.plasmaco.ops.api.PushResponse
import rw.plasmaco.ops.api.SaveUserRequest
import rw.plasmaco.ops.api.UserDto
import rw.plasmaco.ops.api.WireEntity
import rw.plasmaco.ops.api.WireJson
import rw.plasmaco.ops.domain.common.Outcome
import rw.plasmaco.ops.domain.common.TimeSource
import rw.plasmaco.ops.domain.security.Permission
import rw.plasmaco.ops.server.core.AuthService
import rw.plasmaco.ops.server.core.LoginResult
import rw.plasmaco.ops.server.core.RequestPrincipal
import rw.plasmaco.ops.server.core.ServerStore
import rw.plasmaco.ops.server.core.SyncService

const val PRINCIPAL = "plasmaco.principal"

/** Largest request body accepted (a push carries at most 200 operations); larger declared bodies are refused before parsing. */
const val MAX_BODY_BYTES = 8L * 1024 * 1024

/**
 * Bearer-token check for every /api/v1 call except sign-in and health. The token's user is re-read on each request
 * (roles and deactivation apply at once). Failures return 401 with a JSON error and never echo the token.
 */
@Component
class TokenFilter(private val auth: AuthService) : OncePerRequestFilter() {
    private val open = setOf("/api/v1/auth/login", "/api/v1/health")

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val path = request.requestURI.removePrefix(request.contextPath)
        if (!path.startsWith("/api/")) {
            chain.doFilter(request, response)
            return
        }
        if (request.contentLengthLong > MAX_BODY_BYTES) {
            response.fail(HttpStatus.PAYLOAD_TOO_LARGE, ApiError("too_large", "The request is too large; sync in smaller batches"))
            return
        }
        if (path in open) {
            chain.doFilter(request, response)
            return
        }
        val header = request.getHeader("Authorization").orEmpty()
        val principal = header.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim()?.let(auth::authenticate)
        if (principal == null) {
            response.setHeader("WWW-Authenticate", "Bearer")
            response.fail(HttpStatus.UNAUTHORIZED, ApiError("unauthorized", "Sign in again"))
            return
        }
        request.setAttribute(PRINCIPAL, principal)
        chain.doFilter(request, response)
    }

    private fun HttpServletResponse.fail(status: HttpStatus, error: ApiError) {
        this.status = status.value()
        contentType = "application/json"
        writer.write(WireJson.encodeToString(ApiError.serializer(), error))
    }
}

@Serializable
data class Health(val status: String, val api: String)

/** An error answer with a status and a JSON body. */
class ApiException(val status: HttpStatus, val error: ApiError) : RuntimeException(error.message)

@RestController
@RequestMapping("/api/v1")
class ApiController(private val auth: AuthService, private val sync: SyncService, private val store: ServerStore, private val time: TimeSource) {

    @GetMapping("/health")
    fun health(): Health = Health("ok", "v1")

    /** 200 with a token; 401 for wrong credentials (same message whether or not the user exists); 403 while locked. */
    @PostMapping("/auth/login")
    fun login(@RequestBody body: LoginRequest): LoginResponse = when (val r = auth.login(body)) {
        is LoginResult.Success -> r.response
        is LoginResult.Denied -> throw ApiException(HttpStatus.UNAUTHORIZED, ApiError("invalid_credentials", r.message))
        is LoginResult.Locked -> throw ApiException(
            HttpStatus.FORBIDDEN,
            ApiError("account_locked", "Too many failed attempts. Try again after ${r.until.atZone(time.zone()).toLocalTime().withNano(0)}."),
        )
    }

    /** Applies the device's queued operations in order; each gets its own result (APPLIED, DUPLICATE, CONFLICT, REJECTED). */
    @PostMapping("/sync/push")
    fun push(@RequestAttribute(PRINCIPAL) principal: RequestPrincipal, @RequestBody body: PushRequest): PushResponse = sync.push(principal, body)

    /** Changes after the cursor that the caller may see. */
    @GetMapping("/sync/pull")
    fun pull(
        @RequestAttribute(PRINCIPAL) principal: RequestPrincipal,
        @RequestParam(name = "since", required = false) since: String?,
        @RequestParam(name = "limit", required = false, defaultValue = "500") limit: Int,
    ): PullResponse = sync.pull(principal.actor, since, limit)

    @GetMapping("/admin/users")
    fun users(@RequestAttribute(PRINCIPAL) principal: RequestPrincipal): List<UserDto> {
        if (!principal.actor.can(Permission.USERS_MANAGE)) throw ApiException(HttpStatus.FORBIDDEN, ApiError("forbidden", "Only an administrator can manage users"))
        return store.records(WireEntity.USER).mapNotNull { r -> r.payload?.let { WireJson.decodeFromString(UserDto.serializer(), it) } }
    }

    @PostMapping("/admin/users")
    fun saveUser(@RequestAttribute(PRINCIPAL) principal: RequestPrincipal, @RequestBody body: SaveUserRequest): UserDto =
        when (val o = auth.saveUser(principal, body.user, body.password)) {
            is Outcome.Ok -> o.value
            is Outcome.Refused -> {
                val first = o.issues.first()
                throw ApiException(
                    if (first.code == "users.forbidden") HttpStatus.FORBIDDEN else HttpStatus.UNPROCESSABLE_ENTITY,
                    ApiError(first.code, first.message, o.issues.map { it.message }),
                )
            }
        }
}

/** Errors as JSON. Internal details stay in the server log (by type only); clients get a short message. */
@RestControllerAdvice
class ApiErrors {
    private val log = LoggerFactory.getLogger(ApiErrors::class.java)

    @ExceptionHandler(ApiException::class)
    fun api(e: ApiException): ResponseEntity<ApiError> = ResponseEntity.status(e.status).body(e.error)

    @ExceptionHandler(IllegalArgumentException::class, SerializationException::class)
    fun badRequest(e: Exception): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiError("bad_request", e.message?.take(300) ?: "The request is not valid"))

    @ExceptionHandler(Exception::class)
    fun unexpected(e: Exception): ResponseEntity<ApiError> {
        log.error("Unhandled {} in API call", e.javaClass.name)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiError("internal", "Unexpected server error"))
    }
}
