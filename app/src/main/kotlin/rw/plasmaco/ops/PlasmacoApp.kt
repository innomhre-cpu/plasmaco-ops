package rw.plasmaco.ops

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import rw.plasmaco.ops.data.repository.BackupRepository
import rw.plasmaco.ops.data.sync.SyncScheduler
import javax.inject.Inject

@HiltAndroidApp
class PlasmacoApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var syncScheduler: SyncScheduler

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        // A staged backup restore must replace the database file before anything opens it.
        BackupRepository.applyPendingRestore(this)
        super.onCreate()
        syncScheduler.schedulePeriodic()
    }
}
