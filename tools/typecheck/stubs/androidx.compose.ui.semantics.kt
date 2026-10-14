@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch", "NOTHING_TO_INLINE", "UNUSED_TYPEALIAS_PARAMETER")

package androidx.compose.ui.semantics

import androidx.compose.ui.Modifier

interface SemanticsPropertyReceiver
fun Modifier.semantics(mergeDescendants: Boolean = false, properties: SemanticsPropertyReceiver.() -> Unit): Modifier = this
fun Modifier.clearAndSetSemantics(properties: SemanticsPropertyReceiver.() -> Unit): Modifier = this

var SemanticsPropertyReceiver.contentDescription: String get() = TODO(); set(value) {}
var SemanticsPropertyReceiver.stateDescription: String get() = TODO(); set(value) {}
var SemanticsPropertyReceiver.role: Role get() = TODO(); set(value) {}
var SemanticsPropertyReceiver.selected: Boolean get() = TODO(); set(value) {}
var SemanticsPropertyReceiver.liveRegion: LiveRegionMode get() = TODO(); set(value) {}
fun SemanticsPropertyReceiver.heading() {}
fun SemanticsPropertyReceiver.error(description: String) {}

@JvmInline
value class Role(val value: Int) {
    companion object { val Button = Role(0); val Checkbox = Role(1); val Switch = Role(2); val RadioButton = Role(3); val Tab = Role(4); val Image = Role(5); val DropdownList = Role(6) }
}
@JvmInline
value class LiveRegionMode(val value: Int) { companion object { val Polite = LiveRegionMode(0); val Assertive = LiveRegionMode(1) } }
