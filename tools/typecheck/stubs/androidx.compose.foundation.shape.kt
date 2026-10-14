@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch", "NOTHING_TO_INLINE")

package androidx.compose.foundation.shape

import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
abstract class CornerBasedShape : Shape
class RoundedCornerShape(topStart: Any, topEnd: Any, bottomEnd: Any, bottomStart: Any) : CornerBasedShape()
fun RoundedCornerShape(size: Dp): RoundedCornerShape = TODO()
fun RoundedCornerShape(percent: Int): RoundedCornerShape = TODO()
fun RoundedCornerShape(topStart: Dp = 0.dp, topEnd: Dp = 0.dp, bottomEnd: Dp = 0.dp, bottomStart: Dp = 0.dp): RoundedCornerShape = TODO()
val CircleShape: RoundedCornerShape = TODO()
