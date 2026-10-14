@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch", "NOTHING_TO_INLINE")

package androidx.compose.foundation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp

@Immutable
class BorderStroke(val width: Dp, val color: Color)

fun Modifier.background(color: Color, shape: Shape = RectangleShape): Modifier = this
fun Modifier.border(width: Dp, color: Color, shape: Shape = RectangleShape): Modifier = this
fun Modifier.border(border: BorderStroke, shape: Shape = RectangleShape): Modifier = this
fun Modifier.clickable(enabled: Boolean = true, onClickLabel: String? = null, role: Role? = null, onClick: () -> Unit): Modifier = this

class ScrollState(initial: Int) { val value: Int get() = TODO(); val maxValue: Int get() = TODO() }
@Composable fun rememberScrollState(initial: Int = 0): ScrollState = TODO()
fun Modifier.horizontalScroll(state: ScrollState, enabled: Boolean = true, reverseScrolling: Boolean = false): Modifier = this
fun Modifier.verticalScroll(state: ScrollState, enabled: Boolean = true, reverseScrolling: Boolean = false): Modifier = this

@Composable fun Canvas(modifier: Modifier, onDraw: DrawScope.() -> Unit) {}
@Composable fun Image(painter: Painter, contentDescription: String?, modifier: Modifier = Modifier, alignment: Alignment = Alignment.Center, alpha: Float = 1.0f) {}
@Composable fun isSystemInDarkTheme(): Boolean = TODO()
