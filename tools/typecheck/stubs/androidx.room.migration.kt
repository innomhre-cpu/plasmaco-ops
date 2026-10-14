@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package androidx.room.migration

abstract class Migration(@JvmField val startVersion: Int, @JvmField val endVersion: Int) {
    abstract fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase)
}
