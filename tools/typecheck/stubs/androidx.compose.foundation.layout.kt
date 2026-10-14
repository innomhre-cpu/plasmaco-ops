@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch", "NOTHING_TO_INLINE")

package androidx.compose.foundation.layout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object Arrangement {
    interface Horizontal
    interface Vertical
    interface HorizontalOrVertical : Horizontal, Vertical
    val Start: Horizontal = TODO()
    val End: Horizontal = TODO()
    val Top: Vertical = TODO()
    val Bottom: Vertical = TODO()
    val Center: HorizontalOrVertical = TODO()
    val SpaceBetween: HorizontalOrVertical = TODO()
    val SpaceAround: HorizontalOrVertical = TODO()
    val SpaceEvenly: HorizontalOrVertical = TODO()
    fun spacedBy(space: Dp): HorizontalOrVertical = TODO()
    fun spacedBy(space: Dp, alignment: Alignment.Horizontal): Horizontal = TODO()
    fun spacedBy(space: Dp, alignment: Alignment.Vertical): Vertical = TODO()
}

@Stable interface RowScope {
    fun Modifier.weight(weight: Float, fill: Boolean = true): Modifier = this
    fun Modifier.align(alignment: Alignment.Vertical): Modifier = this
}
@Stable interface ColumnScope {
    fun Modifier.weight(weight: Float, fill: Boolean = true): Modifier = this
    fun Modifier.align(alignment: Alignment.Horizontal): Modifier = this
}
@Stable interface BoxScope {
    fun Modifier.align(alignment: Alignment): Modifier = this
    fun Modifier.matchParentSize(): Modifier = this
}
@Stable interface BoxWithConstraintsScope : BoxScope {
    val minWidth: Dp
    val maxWidth: Dp
    val minHeight: Dp
    val maxHeight: Dp
}

@Composable inline fun Row(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalAlignment: Alignment.Vertical = Alignment.Top,
    content: @Composable RowScope.() -> Unit,
) {}
@Composable inline fun Column(
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) {}
@Composable inline fun Box(
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.TopStart,
    propagateMinConstraints: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {}
@Composable fun Box(modifier: Modifier) {}
@Composable fun Spacer(modifier: Modifier) {}
@Composable fun BoxWithConstraints(
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.TopStart,
    propagateMinConstraints: Boolean = false,
    content: @Composable BoxWithConstraintsScope.() -> Unit,
) {}

interface PaddingValues
fun PaddingValues(all: Dp): PaddingValues = TODO()
fun PaddingValues(horizontal: Dp = 0.dp, vertical: Dp = 0.dp): PaddingValues = TODO()
fun PaddingValues(start: Dp = 0.dp, top: Dp = 0.dp, end: Dp = 0.dp, bottom: Dp = 0.dp): PaddingValues = TODO()

interface WindowInsets { companion object }
fun WindowInsets(left: Int = 0, top: Int = 0, right: Int = 0, bottom: Int = 0): WindowInsets = TODO()
fun WindowInsets(left: Dp = 0.dp, top: Dp = 0.dp, right: Dp = 0.dp, bottom: Dp = 0.dp): WindowInsets = TODO()

fun Modifier.padding(all: Dp): Modifier = this
fun Modifier.padding(horizontal: Dp = 0.dp, vertical: Dp = 0.dp): Modifier = this
fun Modifier.padding(start: Dp = 0.dp, top: Dp = 0.dp, end: Dp = 0.dp, bottom: Dp = 0.dp): Modifier = this
fun Modifier.padding(paddingValues: PaddingValues): Modifier = this
fun Modifier.fillMaxWidth(fraction: Float = 1f): Modifier = this
fun Modifier.fillMaxHeight(fraction: Float = 1f): Modifier = this
fun Modifier.fillMaxSize(fraction: Float = 1f): Modifier = this
fun Modifier.width(width: Dp): Modifier = this
fun Modifier.height(height: Dp): Modifier = this
fun Modifier.size(size: Dp): Modifier = this
fun Modifier.size(width: Dp, height: Dp): Modifier = this
fun Modifier.heightIn(min: Dp = Dp.Unspecified, max: Dp = Dp.Unspecified): Modifier = this
fun Modifier.widthIn(min: Dp = Dp.Unspecified, max: Dp = Dp.Unspecified): Modifier = this
fun Modifier.defaultMinSize(minWidth: Dp = Dp.Unspecified, minHeight: Dp = Dp.Unspecified): Modifier = this
fun Modifier.aspectRatio(ratio: Float, matchHeightConstraintsFirst: Boolean = false): Modifier = this
fun Modifier.imePadding(): Modifier = this
fun Modifier.navigationBarsPadding(): Modifier = this
fun Modifier.statusBarsPadding(): Modifier = this
fun Modifier.safeDrawingPadding(): Modifier = this
fun Modifier.offset(x: Dp = 0.dp, y: Dp = 0.dp): Modifier = this
