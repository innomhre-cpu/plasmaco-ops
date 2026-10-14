@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package org.springframework.http

enum class HttpStatus(private val code: Int) : HttpStatusCode {
    OK(200), BAD_REQUEST(400), UNAUTHORIZED(401), FORBIDDEN(403), NOT_FOUND(404), CONFLICT(409), PAYLOAD_TOO_LARGE(413),
    UNPROCESSABLE_ENTITY(422), TOO_MANY_REQUESTS(429), INTERNAL_SERVER_ERROR(500);
    override fun value(): Int = code
}
interface HttpStatusCode { fun value(): Int }
enum class HttpMethod { GET, POST, PUT, DELETE }
class MediaType private constructor() {
    companion object { @JvmField val APPLICATION_JSON: MediaType = MediaType() }
}
class HttpHeaders {
    var contentType: MediaType? = null
    fun setBearerAuth(token: String) {}
}
open class HttpEntity<T>(body: T?, headers: HttpHeaders?) {
    open val body: T? get() = TODO()
}
open class ResponseEntity<T> : HttpEntity<T>(null, null) {
    val statusCode: HttpStatusCode get() = TODO()
    interface BodyBuilder { fun <T> body(body: T?): ResponseEntity<T> }
    companion object {
        @JvmStatic fun <T> ok(body: T?): ResponseEntity<T> = TODO()
        @JvmStatic fun status(status: HttpStatusCode): BodyBuilder = TODO()
    }
}
