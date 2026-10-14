package rw.plasmaco.ops.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import rw.plasmaco.ops.data.local.PlasmacoDatabase
import rw.plasmaco.ops.domain.common.SystemTimeSource
import rw.plasmaco.ops.domain.common.TimeSource
import java.time.ZoneId
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): PlasmacoDatabase =
        Room.databaseBuilder(context, PlasmacoDatabase::class.java, PlasmacoDatabase.NAME)
            .addMigrations(*PlasmacoDatabase.MIGRATIONS)
            .build()

    /** Plant time zone: all business dates (reporting day, shifts) are Kigali time regardless of device settings. */
    @Provides @Singleton
    fun timeSource(): TimeSource = SystemTimeSource(ZoneId.of("Africa/Kigali"))
}
