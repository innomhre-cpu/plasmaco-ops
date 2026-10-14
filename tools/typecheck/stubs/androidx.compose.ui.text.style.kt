@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch", "NOTHING_TO_INLINE", "UNUSED_TYPEALIAS_PARAMETER")

package androidx.compose.ui.text.style

@JvmInline value class TextAlign(val value: Int) { companion object { val Left = TextAlign(1); val Right = TextAlign(2); val Center = TextAlign(3); val Justify = TextAlign(4); val Start = TextAlign(5); val End = TextAlign(6); val Unspecified = TextAlign(0) } }
@JvmInline value class TextOverflow(val value: Int) { companion object { val Clip = TextOverflow(1); val Ellipsis = TextOverflow(2); val Visible = TextOverflow(3) } }
class TextDecoration(val mask: Int) { companion object { val None = TextDecoration(0); val Underline = TextDecoration(1); val LineThrough = TextDecoration(2) } }
