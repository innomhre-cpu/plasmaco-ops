@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch", "NOTHING_TO_INLINE", "UNUSED_TYPEALIAS_PARAMETER")

package androidx.compose.ui

import androidx.compose.runtime.Stable

@Stable
interface Modifier {
    infix fun then(other: Modifier): Modifier = other
    companion object : Modifier
}

@Stable
fun interface Alignment {
    fun align(size: Long, space: Long): Long
    fun interface Horizontal { fun align(size: Int, space: Int): Int }
    fun interface Vertical { fun align(size: Int, space: Int): Int }
    companion object {
        val TopStart: Alignment = TODO()
        val TopCenter: Alignment = TODO()
        val TopEnd: Alignment = TODO()
        val CenterStart: Alignment = TODO()
        val Center: Alignment = TODO()
        val CenterEnd: Alignment = TODO()
        val BottomStart: Alignment = TODO()
        val BottomCenter: Alignment = TODO()
        val BottomEnd: Alignment = TODO()
        val Top: Vertical = TODO()
        val CenterVertically: Vertical = TODO()
        val Bottom: Vertical = TODO()
        val Start: Horizontal = TODO()
        val CenterHorizontally: Horizontal = TODO()
        val End: Horizontal = TODO()
    }
}
