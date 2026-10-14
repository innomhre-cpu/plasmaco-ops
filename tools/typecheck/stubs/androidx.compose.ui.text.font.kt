@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch", "NOTHING_TO_INLINE", "UNUSED_TYPEALIAS_PARAMETER")

package androidx.compose.ui.text.font

class FontWeight(val weight: Int) {
    companion object {
        val Normal = FontWeight(400); val Medium = FontWeight(500); val SemiBold = FontWeight(600); val Bold = FontWeight(700); val Light = FontWeight(300)
    }
}
abstract class FontFamily { companion object { val Default: FontFamily = TODO(); val SansSerif: FontFamily = TODO() } }
interface Font
@JvmInline value class FontStyle(val value: Int) { companion object { val Normal = FontStyle(0); val Italic = FontStyle(1) } }
fun FontFamily(vararg fonts: Font): FontFamily = TODO()
fun Font(resId: Int, weight: FontWeight = FontWeight.Normal, style: FontStyle = FontStyle.Normal): Font = TODO()
