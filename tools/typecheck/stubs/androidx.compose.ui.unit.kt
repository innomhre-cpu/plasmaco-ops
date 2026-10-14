@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch", "NOTHING_TO_INLINE", "UNUSED_TYPEALIAS_PARAMETER")

package androidx.compose.ui.unit

@JvmInline
value class Dp(val value: Float) : Comparable<Dp> {
    operator fun plus(other: Dp): Dp = Dp(value + other.value)
    operator fun minus(other: Dp): Dp = Dp(value - other.value)
    operator fun times(other: Float): Dp = Dp(value * other)
    operator fun times(other: Int): Dp = Dp(value * other)
    operator fun div(other: Float): Dp = Dp(value / other)
    operator fun div(other: Int): Dp = Dp(value / other)
    operator fun div(other: Dp): Float = value / other.value
    operator fun unaryMinus(): Dp = Dp(-value)
    override fun compareTo(other: Dp): Int = value.compareTo(other.value)
    companion object { val Unspecified: Dp = Dp(Float.NaN); val Hairline: Dp = Dp(0f); val Infinity: Dp = Dp(Float.POSITIVE_INFINITY) }
}
inline val Int.dp: Dp get() = Dp(this.toFloat())
inline val Float.dp: Dp get() = Dp(this)
inline val Double.dp: Dp get() = Dp(this.toFloat())
operator fun Float.times(other: Dp): Dp = Dp(this * other.value)
operator fun Int.times(other: Dp): Dp = Dp(this * other.value)
fun min(a: Dp, b: Dp): Dp = if (a < b) a else b
fun max(a: Dp, b: Dp): Dp = if (a > b) a else b

@JvmInline
value class TextUnit(val packedValue: Long) { companion object { val Unspecified: TextUnit = TextUnit(0) } }
val Int.sp: TextUnit get() = TODO()
val Float.sp: TextUnit get() = TODO()
val Double.sp: TextUnit get() = TODO()

@JvmInline
value class IntSize(val packedValue: Long) { val width: Int get() = TODO(); val height: Int get() = TODO() }

interface Density {
    val density: Float
    val fontScale: Float
    fun Dp.toPx(): Float = value * density
    fun Dp.roundToPx(): Int = toPx().toInt()
    fun Int.toDp(): Dp = Dp(this / density)
    fun Float.toDp(): Dp = Dp(this / density)
    fun TextUnit.toPx(): Float = TODO()
}
