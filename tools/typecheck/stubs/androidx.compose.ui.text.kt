@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch", "NOTHING_TO_INLINE", "UNUSED_TYPEALIAS_PARAMETER")

package androidx.compose.ui.text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit

@Immutable
class TextStyle(
    val color: Color = Color.Unspecified,
    val fontSize: TextUnit = TextUnit.Unspecified,
    val fontWeight: FontWeight? = null,
    val fontFamily: FontFamily? = null,
    val fontFeatureSettings: String? = null,
    val letterSpacing: TextUnit = TextUnit.Unspecified,
    val textDecoration: TextDecoration? = null,
    val textAlign: TextAlign = TextAlign.Unspecified,
    val lineHeight: TextUnit = TextUnit.Unspecified,
) {
    fun copy(
        color: Color = this.color,
        fontSize: TextUnit = this.fontSize,
        fontWeight: FontWeight? = this.fontWeight,
        fontFamily: FontFamily? = this.fontFamily,
        fontFeatureSettings: String? = this.fontFeatureSettings,
        letterSpacing: TextUnit = this.letterSpacing,
        textDecoration: TextDecoration? = this.textDecoration,
        textAlign: TextAlign = this.textAlign,
        lineHeight: TextUnit = this.lineHeight,
    ): TextStyle = TODO()
    companion object { val Default: TextStyle = TextStyle() }
}

class TextLayoutResult { val size: IntSize get() = TODO() }
class TextMeasurer {
    fun measure(text: String, style: TextStyle = TextStyle.Default, overflow: TextOverflow = TextOverflow.Clip, softWrap: Boolean = true, maxLines: Int = Int.MAX_VALUE): TextLayoutResult = TODO()
}
@Composable fun rememberTextMeasurer(cacheSize: Int = 8): TextMeasurer = TODO()
fun DrawScope.drawText(textLayoutResult: TextLayoutResult, color: Color = Color.Unspecified, topLeft: Offset = Offset.Zero, alpha: Float = Float.NaN) {}
