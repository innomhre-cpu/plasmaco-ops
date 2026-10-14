@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package androidx.core.splashscreen

import android.app.Activity
class SplashScreen {
    fun interface KeepOnScreenCondition { fun shouldKeepOnScreen(): Boolean }
    fun setKeepOnScreenCondition(condition: KeepOnScreenCondition) {}
    companion object { fun Activity.installSplashScreen(): SplashScreen = TODO() }
}
