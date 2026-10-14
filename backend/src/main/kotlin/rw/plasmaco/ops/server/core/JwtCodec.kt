package rw.plasmaco.ops.server.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Minimal HS256 JSON Web Tokens: enough for the app's bearer tokens, with no third-party dependency to keep the
 * attack surface small. Only HS256 is accepted (the header is checked, so "alg: none" tokens fail), signatures are
 * compared in constant time, and expiry is enforced. Tokens carry the user id and device id only; roles are read
 * from the user record on every request so a role change or deactivation takes effect immediately.
 */
class JwtCodec(secret: ByteArray, private val now: () -> Instant) {
    init {
        require(secret.size >= 32) { "The token signing secret must be at least 32 bytes (256 bits)" }
    }

    data class Claims(val userId: String, val deviceId: String, val issuedAt: Instant, val expiresAt: Instant)

    private val key = SecretKeySpec(secret.copyOf(), "HmacSHA256")
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val header = b64.encodeToString("""{"alg":"HS256","typ":"JWT"}""".toByteArray())

    fun issue(userId: String, deviceId: String, ttl: Duration): Pair<String, Instant> {
        val iat = now()
        val exp = iat.plus(ttl)
        val payload = JsonObject(
            mapOf(
                "sub" to JsonPrimitive(userId),
                "dev" to JsonPrimitive(deviceId),
                "iat" to JsonPrimitive(iat.epochSecond),
                "exp" to JsonPrimitive(exp.epochSecond),
            ),
        )
        val body = b64.encodeToString(payload.toString().toByteArray())
        val signingInput = "$header.$body"
        return "$signingInput.${b64.encodeToString(sign(signingInput))}" to exp
    }

    /** The token's claims when the signature is valid and it has not expired; null otherwise. */
    fun verify(token: String): Claims? {
        val parts = token.split('.')
        if (parts.size != 3 || token.length > 4096) return null
        return try {
            val headerJson = Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(parts[0]))).jsonObject
            if (headerJson["alg"]?.jsonPrimitive?.content != "HS256") return null
            val expected = sign("${parts[0]}.${parts[1]}")
            if (!MessageDigest.isEqual(expected, Base64.getUrlDecoder().decode(parts[2]))) return null
            val claims = Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(parts[1]))).jsonObject
            val exp = Instant.ofEpochSecond(claims.getValue("exp").jsonPrimitive.long)
            if (!now().isBefore(exp)) return null
            Claims(
                userId = claims.getValue("sub").jsonPrimitive.content,
                deviceId = claims["dev"]?.jsonPrimitive?.content ?: "unknown",
                issuedAt = Instant.ofEpochSecond(claims.getValue("iat").jsonPrimitive.long),
                expiresAt = exp,
            )
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: NoSuchElementException) {
            null
        }
    }

    private fun sign(input: String): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(key)
        doFinal(input.toByteArray())
    }
}
