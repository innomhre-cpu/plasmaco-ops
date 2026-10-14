@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch", "NOTHING_TO_INLINE", "UNUSED_TYPEALIAS_PARAMETER")

package androidx.compose.ui.graphics.drawscope

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.unit.Density

sealed class DrawStyle
object Fill : DrawStyle()
class Stroke(
    val width: Float = 0.0f,
    val miter: Float = 4.0f,
    val cap: StrokeCap = StrokeCap.Butt,
    val join: StrokeJoin = StrokeJoin.Miter,
    val pathEffect: PathEffect? = null,
) : DrawStyle() { companion object { const val HairlineWidth = 0.0f } }

interface DrawScope : Density {
    val size: Size
    val center: Offset
    fun drawLine(color: Color, start: Offset, end: Offset, strokeWidth: Float = Stroke.HairlineWidth, cap: StrokeCap = StrokeCap.Butt, pathEffect: PathEffect? = null, alpha: Float = 1.0f)
    fun drawRect(color: Color, topLeft: Offset = Offset.Zero, size: Size = this.size, alpha: Float = 1.0f, style: DrawStyle = Fill)
    fun drawRoundRect(color: Color, topLeft: Offset = Offset.Zero, size: Size = this.size, cornerRadius: CornerRadius = CornerRadius.Zero, style: DrawStyle = Fill, alpha: Float = 1.0f)
    fun drawCircle(color: Color, radius: Float = size.minDimension / 2.0f, center: Offset = this.center, alpha: Float = 1.0f, style: DrawStyle = Fill)
    fun drawArc(color: Color, startAngle: Float, sweepAngle: Float, useCenter: Boolean, topLeft: Offset = Offset.Zero, size: Size = this.size, alpha: Float = 1.0f, style: DrawStyle = Fill)
    fun drawPath(path: Path, color: Color, alpha: Float = 1.0f, style: DrawStyle = Fill)
}
