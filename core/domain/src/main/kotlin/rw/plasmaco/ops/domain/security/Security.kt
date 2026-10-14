package rw.plasmaco.ops.domain.security

import rw.plasmaco.ops.domain.common.Id
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

enum class Role(val label: String) {
    OPERATOR("Production Operator"),
    STOREKEEPER("Storekeeper"),
    SUPERVISOR("Production Supervisor"),
    MANAGER("Department Manager"),
    ADMIN("Administrator"),
}

enum class Permission {
    REPORT_CREATE, REPORT_EDIT_OWN_DRAFT, REPORT_EDIT_ANY_DRAFT, REPORT_SUBMIT, REPORT_VIEW_OWN, REPORT_VIEW_ALL,
    REPORT_APPROVE, REPORT_REJECT, REPORT_RETURN, REPORT_REOPEN, REPORT_DUPLICATE,
    INVENTORY_VIEW, INVENTORY_RECEIVE, INVENTORY_ISSUE, INVENTORY_DISPATCH, INVENTORY_TRANSFER,
    STOCK_COUNT, ADJUSTMENT_REQUEST, ADJUSTMENT_APPROVE, NEGATIVE_STOCK_OVERRIDE,
    DASHBOARD_VIEW, ANALYTICS_VIEW, REPORTS_EXPORT,
    MASTER_DATA_MANAGE, USERS_MANAGE, IMPORT_RUN, OPENING_BALANCE_MIGRATE, AUDIT_VIEW, SETTINGS_MANAGE, BACKUP_MANAGE,
}

/** The role matrix from the requirements, in one place for the app and the server. */
object RolePermissions {
    private val matrix: Map<Role, Set<Permission>> = mapOf(
        Role.OPERATOR to setOf(
            Permission.REPORT_CREATE, Permission.REPORT_EDIT_OWN_DRAFT, Permission.REPORT_SUBMIT,
            Permission.REPORT_VIEW_OWN, Permission.REPORT_DUPLICATE, Permission.DASHBOARD_VIEW,
        ),
        Role.STOREKEEPER to setOf(
            Permission.INVENTORY_VIEW, Permission.INVENTORY_RECEIVE, Permission.INVENTORY_ISSUE,
            Permission.INVENTORY_DISPATCH, Permission.INVENTORY_TRANSFER, Permission.STOCK_COUNT,
            Permission.ADJUSTMENT_REQUEST, Permission.DASHBOARD_VIEW, Permission.REPORT_VIEW_ALL,
        ),
        Role.SUPERVISOR to setOf(
            Permission.REPORT_CREATE, Permission.REPORT_EDIT_OWN_DRAFT, Permission.REPORT_EDIT_ANY_DRAFT,
            Permission.REPORT_SUBMIT, Permission.REPORT_VIEW_ALL, Permission.REPORT_APPROVE, Permission.REPORT_REJECT,
            Permission.REPORT_RETURN, Permission.REPORT_DUPLICATE, Permission.DASHBOARD_VIEW, Permission.ANALYTICS_VIEW,
            Permission.INVENTORY_VIEW, Permission.ADJUSTMENT_APPROVE, Permission.REPORTS_EXPORT,
        ),
        Role.MANAGER to setOf(
            Permission.REPORT_VIEW_ALL, Permission.REPORT_REOPEN, Permission.DASHBOARD_VIEW, Permission.ANALYTICS_VIEW,
            Permission.REPORTS_EXPORT, Permission.INVENTORY_VIEW, Permission.ADJUSTMENT_APPROVE,
            Permission.NEGATIVE_STOCK_OVERRIDE, Permission.AUDIT_VIEW,
        ),
        Role.ADMIN to Permission.entries.toSet(),
    )

    fun of(role: Role): Set<Permission> = matrix.getValue(role)
    fun of(roles: Collection<Role>): Set<Permission> = roles.flatMap { of(it) }.toSet()
}

/** The signed-in person as business rules see them. */
data class Actor(val userId: Id, val displayName: String, val roles: Set<Role>) {
    val permissions: Set<Permission> by lazy { RolePermissions.of(roles) }
    fun can(permission: Permission): Boolean = permission in permissions
    fun hasRole(role: Role): Boolean = role in roles
}

data class User(
    val id: Id,
    val username: String,
    val displayName: String,
    val roles: Set<Role>,
    val active: Boolean = true,
    val pinHash: String? = null,
) {
    fun toActor() = Actor(id, displayName, roles)
}

/**
 * PBKDF2-HMAC-SHA256 hashing for PINs and passwords stored on the device for offline sign-in.
 * Format: pbkdf2$<iterations>$<salt b64>$<hash b64>.
 */
object PinHasher {
    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256
    private val random = SecureRandom()

    fun hash(secret: CharArray, iterations: Int = ITERATIONS): String {
        val salt = ByteArray(16).also(random::nextBytes)
        val hash = derive(secret, salt, iterations)
        val b64 = Base64.getEncoder()
        return "pbkdf2\$$iterations\$${b64.encodeToString(salt)}\$${b64.encodeToString(hash)}"
    }

    fun verify(secret: CharArray, stored: String): Boolean {
        val parts = stored.split('$')
        if (parts.size != 4 || parts[0] != "pbkdf2") return false
        val iterations = parts[1].toIntOrNull() ?: return false
        val dec = Base64.getDecoder()
        val salt = runCatching { dec.decode(parts[2]) }.getOrNull() ?: return false
        val expected = runCatching { dec.decode(parts[3]) }.getOrNull() ?: return false
        return MessageDigest.isEqual(expected, derive(secret, salt, iterations))
    }

    private fun derive(secret: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(secret, salt, iterations, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** Minimum strength for a sign-in PIN or password. */
    fun strengthIssues(secret: String): List<String> = buildList {
        if (secret.length < 6) add("Use at least 6 characters")
        if (secret.all { it == secret.first() }) add("Avoid repeating one character")
        if (secret in setOf("123456", "654321", "000000", "password", "plasmaco")) add("Choose something less common")
    }
}

/** Idle-timeout rule for sessions on shared factory devices. */
data class SessionPolicy(val idleTimeoutMinutes: Long = 15, val maxOfflineDays: Long = 14) {
    fun isExpired(lastActivityEpochMs: Long, nowEpochMs: Long): Boolean = nowEpochMs - lastActivityEpochMs > idleTimeoutMinutes * 60_000
    fun offlineSignInAllowed(lastServerVerificationEpochMs: Long?, nowEpochMs: Long): Boolean =
        lastServerVerificationEpochMs != null && nowEpochMs - lastServerVerificationEpochMs <= maxOfflineDays * 86_400_000
}
