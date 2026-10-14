@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch", "NOTHING_TO_INLINE", "UNUSED_TYPEALIAS_PARAMETER")

package androidx.compose.ui.graphics

import androidx.compose.runtime.Immutable

@Immutable
@JvmInline
value class Color(val value: ULong) {
    val red: Float get() = TODO()
    val green: Float get() = TODO()
    val blue: Float get() = TODO()
    val alpha: Float get() = TODO()
    fun copy(alpha: Float = this.alpha, red: Float = this.red, green: Float = this.green, blue: Float = this.blue): Color = TODO()
    companion object {
        val Black: Color = TODO(); val White: Color = TODO(); val Transparent: Color = TODO(); val Unspecified: Color = TODO()
        val LightGray: Color = TODO(); val Gray: Color = TODO(); val DarkGray: Color = TODO(); val Red: Color = TODO()
    }
}
fun Color(color: Long): Color = TODO()
fun Color(color: Int): Color = TODO()
fun Color(red: Float, green: Float, blue: Float, alpha: Float = 1f): Color = TODO()
fun Color.toArgb(): Int = TODO()
fun lerp(start: Color, stop: Color, fraction: Float): Color = TODO()

interface Shape
val RectangleShape: Shape = TODO()

class Path {
    fun moveTo(x: Float, y: Float) {}
    fun lineTo(x: Float, y: Float) {}
    fun close() {}
    fun reset() {}
}
interface PathEffect { companion object { fun dashPathEffect(intervals: FloatArray, phase: Float = 0f): PathEffect = TODO() } }

@JvmInline
value class StrokeCap(val value: Int) { companion object { val Butt = StrokeCap(0); val Round = StrokeCap(1); val Square = StrokeCap(2) } }
@JvmInline
value class StrokeJoin(val value: Int) { companion object { val Miter = StrokeJoin(0); val Round = StrokeJoin(1); val Bevel = StrokeJoin(2) } }
abstract class Brush
interface ColorFilter
