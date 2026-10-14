@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package android.os

interface Parcelable
class Bundle
object Build {
    object VERSION { @JvmField val SDK_INT: Int = 0 }
    object VERSION_CODES { const val TIRAMISU = 33; const val S = 31; const val O = 26 }
}
