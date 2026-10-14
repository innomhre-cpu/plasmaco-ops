@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package androidx.core.content

import android.content.Context
import android.net.Uri
import java.io.File
object ContextCompat { @JvmStatic fun checkSelfPermission(context: Context, permission: String): Int = TODO() }
open class FileProvider { companion object { @JvmStatic fun getUriForFile(context: Context, authority: String, file: File): Uri = TODO() } }
