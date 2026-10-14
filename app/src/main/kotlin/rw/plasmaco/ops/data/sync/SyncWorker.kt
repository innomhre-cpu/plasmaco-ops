package rw.plasmaco.ops.data.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import rw.plasmaco.ops.R
import rw.plasmaco.ops.data.repository.NotificationRepository
import rw.plasmaco.ops.domain.analytics.AlertSeverity
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Background sync: push, pull, then refresh alerts. Runs every 15 minutes with a network, and on demand. */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val sync: SyncRepository,
    private val notifications: NotificationRepository,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val report = sync.run()
        val fresh = runCatching { notifications.refresh() }.getOrDefault(emptyList())
        fresh.filter { it.severity == AlertSeverity.CRITICAL }.take(3).forEach { alert -> notify(alert.key.hashCode(), alert.title, alert.message) }
        return when {
            report.error == null -> Result.success()
            report.error.startsWith("No server") -> Result.success()
            runAttemptCount < 5 -> Result.retry()
            else -> Result.failure()
        }
    }

    private fun notify(id: Int, title: String, text: String) {
        val nm = applicationContext.getSystemService(NotificationManager::class.java) ?: return
        if (!nm.areNotificationsEnabled()) return
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERTS, applicationContext.getString(R.string.notification_channel_alerts), NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = applicationContext.getString(R.string.notification_channel_alerts_desc)
        })
        val n = NotificationCompat.Builder(applicationContext, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(id, n) }
    }

    companion object {
        const val CHANNEL_ALERTS = "alerts"
    }
}

@Singleton
class SyncScheduler @Inject constructor(@ApplicationContext private val context: Context) {
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("sync-periodic", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Triggered after local changes and by "Sync now"; coalesces with a sync already queued. */
    fun syncNow() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("sync-now", ExistingWorkPolicy.REPLACE, request)
    }

    /** Alerts are refreshed locally even in standalone mode (no network constraint). */
    fun refreshAlerts() {
        WorkManager.getInstance(context).enqueueUniqueWork("alerts-refresh", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<SyncWorker>().build())
    }
}
