@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package androidx.sqlite.db

interface SupportSQLiteDatabase {
    fun execSQL(sql: String)
    fun execSQL(sql: String, bindArgs: Array<out Any?>)
    fun query(query: String): android.database.Cursor
}
interface SupportSQLiteOpenHelper { val writableDatabase: SupportSQLiteDatabase; val readableDatabase: SupportSQLiteDatabase }
