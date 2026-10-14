@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch", "NOTHING_TO_INLINE", "UNUSED_TYPEALIAS_PARAMETER")

package androidx.compose.runtime.saveable

import androidx.compose.runtime.Composable
interface Saver<Original, Saveable : Any>
fun <T> autoSaver(): Saver<T, Any> = TODO()
@Composable fun <T : Any> rememberSaveable(vararg inputs: Any?, saver: Saver<T, out Any> = autoSaver(), key: String? = null, init: () -> T): T = init()
