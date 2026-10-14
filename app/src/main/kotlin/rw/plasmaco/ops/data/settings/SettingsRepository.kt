package rw.plasmaco.ops.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import rw.plasmaco.ops.domain.analytics.AlertThresholds
import rw.plasmaco.ops.domain.inventory.InventorySettings
import rw.plasmaco.ops.security.KeystoreCipher
import rw.plasmaco.ops.ui.theme.ThemeMode
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "plasmaco_settings")

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val serverUrl: String = "",
    val deviceId: String = "",
    val lastSyncAt: Long? = null,
    val syncCursor: String? = null,
    val inventoryCutoff: LocalDate? = null,
    /** Off by default: negative stock is blocked unless an administrator enables the audited override. */
    val allowNegativeOverride: Boolean = false,
    val minAchievementPct: Int = 85,
    val maxRejectionPct: Int = 5,
    val maxDowntimeMinutes: Int = 60,
    val workingDays: Set<DayOfWeek> = DayOfWeek.entries.toSet() - DayOfWeek.SUNDAY,
    val idleTimeoutMinutes: Int = 15,
    val auditRetentionDays: Int = 3650,
    val setupComplete: Boolean = false,
) {
    val inventorySettings: InventorySettings get() = InventorySettings(inventoryCutoff, allowNegativeOverride)
    val alertThresholds: AlertThresholds
        get() = AlertThresholds(BigDecimal(minAchievementPct), BigDecimal(maxRejectionPct), maxDowntimeMinutes.toLong(), 24, workingDays)
    val serverConfigured: Boolean get() = serverUrl.startsWith("https://")
}

/** Non-sensitive preferences in DataStore; the API token is encrypted with an Android Keystore key before storage. */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cipher: KeystoreCipher,
) {
    private object K {
        val theme = stringPreferencesKey("theme")
        val server = stringPreferencesKey("server_url")
        val device = stringPreferencesKey("device_id")
        val lastSync = longPreferencesKey("last_sync")
        val cursor = stringPreferencesKey("sync_cursor")
        val cutoff = stringPreferencesKey("inventory_cutoff")
        val negative = booleanPreferencesKey("allow_negative_override")
        val minAch = intPreferencesKey("min_achievement")
        val maxRej = intPreferencesKey("max_rejection")
        val maxDown = intPreferencesKey("max_downtime")
        val workDays = stringPreferencesKey("working_days")
        val idle = intPreferencesKey("idle_timeout")
        val retention = intPreferencesKey("audit_retention_days")
        val setup = booleanPreferencesKey("setup_complete")
        val token = stringPreferencesKey("api_token_encrypted")
        val tokenExpiry = longPreferencesKey("api_token_expiry")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            themeMode = p[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            serverUrl = p[K.server].orEmpty(),
            deviceId = p[K.device].orEmpty(),
            lastSyncAt = p[K.lastSync],
            syncCursor = p[K.cursor],
            inventoryCutoff = p[K.cutoff]?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            allowNegativeOverride = p[K.negative] ?: false,
            minAchievementPct = p[K.minAch] ?: 85,
            maxRejectionPct = p[K.maxRej] ?: 5,
            maxDowntimeMinutes = p[K.maxDown] ?: 60,
            workingDays = p[K.workDays]?.split(',')?.mapNotNull { d -> runCatching { DayOfWeek.valueOf(d) }.getOrNull() }?.toSet()
                ?: (DayOfWeek.entries.toSet() - DayOfWeek.SUNDAY),
            idleTimeoutMinutes = p[K.idle] ?: 15,
            auditRetentionDays = p[K.retention] ?: 3650,
            setupComplete = p[K.setup] ?: false,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    /** A stable random id for this installation, created on first use. */
    suspend fun deviceId(): String {
        val existing = context.dataStore.data.first()[K.device]
        if (!existing.isNullOrBlank()) return existing
        val id = UUID.randomUUID().toString()
        context.dataStore.edit { it[K.device] = id }
        return id
    }

    suspend fun setTheme(mode: ThemeMode) = context.dataStore.edit { it[K.theme] = mode.name }
    suspend fun setServerUrl(url: String) = context.dataStore.edit { it[K.server] = url.trim().trimEnd('/') }
    suspend fun setSyncState(at: Long, cursor: String?) = context.dataStore.edit { p -> p[K.lastSync] = at; if (cursor != null) p[K.cursor] = cursor }
    suspend fun setInventoryCutoff(date: LocalDate?) = context.dataStore.edit { p -> if (date == null) p.remove(K.cutoff) else p[K.cutoff] = date.toString() }
    suspend fun setAllowNegativeOverride(v: Boolean) = context.dataStore.edit { it[K.negative] = v }
    suspend fun setThresholds(minAchievement: Int, maxRejection: Int, maxDowntime: Int) = context.dataStore.edit {
        it[K.minAch] = minAchievement.coerceIn(1, 200); it[K.maxRej] = maxRejection.coerceIn(0, 100); it[K.maxDown] = maxDowntime.coerceIn(0, 1440)
    }
    suspend fun setWorkingDays(days: Set<DayOfWeek>) = context.dataStore.edit { it[K.workDays] = days.joinToString(",") { d -> d.name } }
    suspend fun setIdleTimeout(minutes: Int) = context.dataStore.edit { it[K.idle] = minutes.coerceIn(1, 240) }
    suspend fun setSetupComplete() = context.dataStore.edit { it[K.setup] = true }

    suspend fun saveToken(token: String, expiresAtMs: Long) = context.dataStore.edit {
        it[K.token] = cipher.encrypt(token); it[K.tokenExpiry] = expiresAtMs
    }

    suspend fun token(now: Long): String? {
        val p = context.dataStore.data.first()
        val expiry = p[K.tokenExpiry] ?: return null
        if (expiry <= now) return null
        return p[K.token]?.let { runCatching { cipher.decrypt(it) }.getOrNull() }
    }

    suspend fun clearToken() = context.dataStore.edit { it.remove(K.token); it.remove(K.tokenExpiry) }
}
