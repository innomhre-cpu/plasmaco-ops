@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package androidx.compose.material3.windowsizeclass

import androidx.compose.runtime.Composable
@RequiresOptIn annotation class ExperimentalMaterial3WindowSizeClassApi
@JvmInline value class WindowWidthSizeClass(val value: Int) { companion object { val Compact = WindowWidthSizeClass(0); val Medium = WindowWidthSizeClass(1); val Expanded = WindowWidthSizeClass(2) } }
@JvmInline value class WindowHeightSizeClass(val value: Int) { companion object { val Compact = WindowHeightSizeClass(0); val Medium = WindowHeightSizeClass(1); val Expanded = WindowHeightSizeClass(2) } }
class WindowSizeClass { val widthSizeClass: WindowWidthSizeClass get() = TODO(); val heightSizeClass: WindowHeightSizeClass get() = TODO() }
@ExperimentalMaterial3WindowSizeClassApi @Composable fun calculateWindowSizeClass(activity: android.app.Activity): WindowSizeClass = TODO()
