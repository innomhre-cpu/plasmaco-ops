package rw.plasmaco.ops.data.repository

import androidx.room.withTransaction
import rw.plasmaco.ops.data.local.PlasmacoDatabase
import rw.plasmaco.ops.data.settings.SettingsRepository
import rw.plasmaco.ops.domain.security.Permission
import java.net.URI
import java.time.DayOfWeek
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Settings that change how the whole device behaves (server, thresholds, security). Only roles with
 * [Permission.SETTINGS_MANAGE] may change them, and every change is written to the audit log.
 */
@Singleton
class AdminSettings @Inject constructor(
    private val settings: SettingsRepository,
    private val session: SessionManager,
    private val db: PlasmacoDatabase,
    private val recorder: ChangeRecorder,
) {
    private fun requireManage() = session.requireActor().also {
        if (!it.can(Permission.SETTINGS_MANAGE)) throw NotAllowedException("Only an administrator can change these settings")
    }

    private suspend fun audit(summary: String) {
        val actor = requireManage()
        db.withTransaction { recorder.audit(actor, "settings", "device", "SETTINGS", summary) }
    }

    /** HTTPS only; an empty address returns the device to standalone mode. */
    suspend fun setServerUrl(url: String) {
        requireManage()
        val trimmed = url.trim().trimEnd('/')
        if (trimmed.isNotEmpty()) {
            val ok = runCatching { URI(trimmed) }.getOrNull()?.let { it.scheme == "https" && !it.host.isNullOrBlank() } == true
            if (!ok) throw NotAllowedException("Enter the full server address, starting with https://")
        }
        settings.setServerUrl(trimmed)
        settings.clearToken()
        audit(if (trimmed.isEmpty()) "Server disconnected (standalone mode)" else "Server address set to $trimmed")
    }

    suspend fun setThresholds(minAchievement: Int, maxRejection: Int, maxDowntime: Int) {
        requireManage()
        if (minAchievement !in 1..200 || maxRejection !in 0..100 || maxDowntime !in 0..1440) throw NotAllowedException("Check the alert thresholds: percentages 0–100 (achievement up to 200), downtime 0–1440 minutes")
        settings.setThresholds(minAchievement, maxRejection, maxDowntime)
        audit("Alert thresholds: achievement below $minAchievement%, rejection above $maxRejection%, downtime above $maxDowntime min")
    }

    suspend fun setWorkingDays(days: Set<DayOfWeek>) {
        requireManage()
        if (days.isEmpty()) throw NotAllowedException("Choose at least one working day")
        settings.setWorkingDays(days)
        audit("Working days for missing-report alerts: ${days.sorted().joinToString { it.name.lowercase().replaceFirstChar(Char::uppercase).take(3) }}")
    }

    suspend fun setIdleTimeout(minutes: Int) {
        requireManage()
        settings.setIdleTimeout(minutes)
        audit("Automatic sign-out after $minutes idle minute(s)")
    }

    /** The negative-stock exception: off by default; when on, an authorised override with a note is still required. */
    suspend fun setAllowNegativeOverride(enabled: Boolean) {
        requireManage()
        settings.setAllowNegativeOverride(enabled)
        audit(if (enabled) "Negative-stock override ENABLED (manager override with a note, flagged in audit)" else "Negative-stock override disabled")
    }
}
