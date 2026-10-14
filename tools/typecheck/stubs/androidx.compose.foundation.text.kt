@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch", "NOTHING_TO_INLINE")

package androidx.compose.foundation.text

import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
class KeyboardOptions(
    val autoCorrectEnabled: Boolean? = null,
    val keyboardType: KeyboardType = KeyboardType.Unspecified,
    val imeAction: ImeAction = ImeAction.Unspecified,
) { companion object { val Default = KeyboardOptions() } }
interface KeyboardActionScope
class KeyboardActions(
    val onDone: (KeyboardActionScope.() -> Unit)? = null,
    val onGo: (KeyboardActionScope.() -> Unit)? = null,
    val onNext: (KeyboardActionScope.() -> Unit)? = null,
    val onPrevious: (KeyboardActionScope.() -> Unit)? = null,
    val onSearch: (KeyboardActionScope.() -> Unit)? = null,
    val onSend: (KeyboardActionScope.() -> Unit)? = null,
) { companion object { val Default = KeyboardActions() } }
