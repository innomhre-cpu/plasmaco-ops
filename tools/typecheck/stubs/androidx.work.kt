@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package androidx.work

import android.content.Context
import java.util.concurrent.TimeUnit
abstract class WorkerFactory
class WorkerParameters { }
abstract class ListenableWorker(appContext: Context, params: WorkerParameters) {
    val applicationContext: Context get() = TODO()
    val runAttemptCount: Int get() = TODO()
    abstract class Result { companion object { @JvmStatic fun success(): Result = TODO(); @JvmStatic fun retry(): Result = TODO(); @JvmStatic fun failure(): Result = TODO() } }
}
abstract class CoroutineWorker(appContext: Context, params: WorkerParameters) : ListenableWorker(appContext, params) {
    abstract suspend fun doWork(): Result
}
enum class NetworkType { NOT_REQUIRED, CONNECTED, UNMETERED }
enum class BackoffPolicy { EXPONENTIAL, LINEAR }
enum class ExistingPeriodicWorkPolicy { KEEP, REPLACE, UPDATE, CANCEL_AND_REENQUEUE }
enum class ExistingWorkPolicy { REPLACE, KEEP, APPEND, APPEND_OR_REPLACE }
class Constraints { class Builder { fun setRequiredNetworkType(networkType: NetworkType): Builder = this; fun build(): Constraints = TODO() } }
abstract class WorkRequest
class OneTimeWorkRequest : WorkRequest()
class PeriodicWorkRequest : WorkRequest()
abstract class WorkRequestBuilder<B, W : WorkRequest> {
    fun setConstraints(constraints: Constraints): B = TODO()
    fun setBackoffCriteria(backoffPolicy: BackoffPolicy, backoffDelay: Long, timeUnit: TimeUnit): B = TODO()
    fun build(): W = TODO()
}
class OneTimeWorkRequestBuilderImpl : WorkRequestBuilder<OneTimeWorkRequestBuilderImpl, OneTimeWorkRequest>()
class PeriodicWorkRequestBuilderImpl : WorkRequestBuilder<PeriodicWorkRequestBuilderImpl, PeriodicWorkRequest>()
inline fun <reified W : CoroutineWorker> OneTimeWorkRequestBuilder(): OneTimeWorkRequestBuilderImpl = TODO()
inline fun <reified W : CoroutineWorker> PeriodicWorkRequestBuilder(repeatInterval: Long, repeatIntervalTimeUnit: TimeUnit): PeriodicWorkRequestBuilderImpl = TODO()
abstract class WorkManager {
    abstract fun enqueueUniquePeriodicWork(uniqueWorkName: String, existingPeriodicWorkPolicy: ExistingPeriodicWorkPolicy, periodicWork: PeriodicWorkRequest): Any
    abstract fun enqueueUniqueWork(uniqueWorkName: String, existingWorkPolicy: ExistingWorkPolicy, work: OneTimeWorkRequest): Any
    companion object { @JvmStatic fun getInstance(context: Context): WorkManager = TODO() }
}
class Configuration {
    interface Provider { val workManagerConfiguration: Configuration }
    class Builder { fun setWorkerFactory(workerFactory: WorkerFactory): Builder = this; fun build(): Configuration = TODO() }
}
