@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch", "NOTHING_TO_INLINE", "UNUSED_TYPEALIAS_PARAMETER")

package androidx.compose.ui.input.pointer

import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
interface PointerInputScope : Density { val size: IntSize }
fun Modifier.pointerInput(key1: Any?, block: suspend PointerInputScope.() -> Unit): Modifier = this
fun Modifier.pointerInput(key1: Any?, key2: Any?, block: suspend PointerInputScope.() -> Unit): Modifier = this
