@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package androidx.test.runner

open class AndroidJUnitRunner {
    open fun newApplication(cl: ClassLoader?, className: String?, context: android.content.Context?): android.app.Application = TODO()
}
