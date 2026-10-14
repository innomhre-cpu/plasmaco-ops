@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch", "NOTHING_TO_INLINE", "UNUSED_TYPEALIAS_PARAMETER")

package androidx.compose.ui.text.input

@JvmInline value class KeyboardType(val value: Int) { companion object { val Unspecified = KeyboardType(0); val Text = KeyboardType(1); val Ascii = KeyboardType(2); val Number = KeyboardType(3); val Phone = KeyboardType(4); val Uri = KeyboardType(5); val Email = KeyboardType(6); val Password = KeyboardType(7); val NumberPassword = KeyboardType(8); val Decimal = KeyboardType(9) } }
@JvmInline value class ImeAction(val value: Int) { companion object { val Unspecified = ImeAction(-1); val Default = ImeAction(1); val None = ImeAction(0); val Go = ImeAction(2); val Search = ImeAction(3); val Send = ImeAction(4); val Previous = ImeAction(5); val Next = ImeAction(6); val Done = ImeAction(7) } }
fun interface VisualTransformation { fun filter(text: String): Any; companion object { val None: VisualTransformation = TODO() } }
class PasswordVisualTransformation(val mask: Char = '\u2022') : VisualTransformation { override fun filter(text: String): Any = TODO() }
