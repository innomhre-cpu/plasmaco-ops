@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package androidx.lifecycle

import kotlinx.coroutines.CoroutineScope
abstract class ViewModel { protected open fun onCleared() {} }
val ViewModel.viewModelScope: CoroutineScope get() = TODO()
class SavedStateHandle {
    operator fun <T> get(key: String): T? = TODO()
    operator fun <T> set(key: String, value: T?) {}
    fun contains(key: String): Boolean = TODO()
}
interface LifecycleOwner
