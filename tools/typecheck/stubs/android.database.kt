@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package android.database

interface Cursor : java.io.Closeable {
    fun moveToFirst(): Boolean
    fun getString(columnIndex: Int): String?
    fun getLong(columnIndex: Int): Long
    fun isNull(columnIndex: Int): Boolean
}
