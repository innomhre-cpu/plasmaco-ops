@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package androidx.compose.ui.test

class SemanticsNodeInteraction
fun SemanticsNodeInteraction.assertIsDisplayed(): SemanticsNodeInteraction = this
fun SemanticsNodeInteraction.performClick(): SemanticsNodeInteraction = this
interface SemanticsNodeInteractionsProvider
fun SemanticsNodeInteractionsProvider.onNodeWithText(text: String, substring: Boolean = false, ignoreCase: Boolean = false, useUnmergedTree: Boolean = false): SemanticsNodeInteraction = TODO()
