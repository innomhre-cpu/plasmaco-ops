@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package androidx.activity

import androidx.lifecycle.ViewModel
open class ComponentActivity : android.app.Activity(), androidx.lifecycle.LifecycleOwner {
    protected open fun onCreate(savedInstanceState: android.os.Bundle?) {}
}
fun ComponentActivity.enableEdgeToEdge() {}
inline fun <reified VM : ViewModel> ComponentActivity.viewModels(): Lazy<VM> = TODO()
