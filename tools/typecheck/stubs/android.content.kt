@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package android.content

import android.net.Uri
import android.database.Cursor
import java.io.File
import java.io.InputStream
import java.io.OutputStream

abstract class Context {
    open val packageName: String get() = TODO()
    open val cacheDir: File get() = TODO()
    open val filesDir: File get() = TODO()
    open val contentResolver: ContentResolver get() = TODO()
    open val applicationContext: Context get() = TODO()
    open fun getDatabasePath(name: String): File = TODO()
    open fun <T> getSystemService(serviceClass: Class<T>): T? = TODO()
    open fun getString(resId: Int): String = TODO()
    open fun startActivity(intent: Intent) {}
    open fun checkSelfPermission(permission: String): Int = TODO()
}

open class ContextWrapper : Context()

abstract class ContentResolver {
    fun openInputStream(uri: Uri): InputStream? = TODO()
    fun openOutputStream(uri: Uri, mode: String): OutputStream? = TODO()
    fun openOutputStream(uri: Uri): OutputStream? = TODO()
    fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? = TODO()
}

open class Intent(action: String?) {
    constructor() : this(null)
    fun putExtra(name: String, value: android.os.Parcelable?): Intent = this
    fun putExtra(name: String, value: String?): Intent = this
    fun addFlags(flags: Int): Intent = this
    var type: String? = null

    companion object {
        const val ACTION_SEND = "android.intent.action.SEND"
        const val EXTRA_STREAM = "android.intent.extra.STREAM"
        const val EXTRA_SUBJECT = "android.intent.extra.SUBJECT"
        const val FLAG_GRANT_READ_URI_PERMISSION = 1
        const val FLAG_ACTIVITY_NEW_TASK = 0x10000000
        @JvmStatic fun createChooser(target: Intent, title: CharSequence?): Intent = TODO()
    }
}

class ActivityNotFoundException : RuntimeException()
