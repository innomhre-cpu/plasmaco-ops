@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch", "NOTHING_TO_INLINE", "UNUSED_TYPEALIAS_PARAMETER")

package androidx.compose.ui.geometry

@JvmInline
value class Offset(val packedValue: Long) {
    val x: Float get() = TODO()
    val y: Float get() = TODO()
    operator fun plus(other: Offset): Offset = TODO()
    operator fun minus(other: Offset): Offset = TODO()
    companion object { val Zero: Offset = TODO(); val Unspecified: Offset = TODO() }
}
fun Offset(x: Float, y: Float): Offset = TODO()

@JvmInline
value class Size(val packedValue: Long) {
    val width: Float get() = TODO()
    val height: Float get() = TODO()
    val minDimension: Float get() = TODO()
    companion object { val Zero: Size = TODO(); val Unspecified: Size = TODO() }
}
fun Size(width: Float, height: Float): Size = TODO()

@JvmInline
value class CornerRadius(val packedValue: Long) { companion object { val Zero: CornerRadius = TODO() } }
fun CornerRadius(x: Float, y: Float = x): CornerRadius = TODO()
