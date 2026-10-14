@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package androidx.compose.ui.test.junit4

interface ComposeContentTestRule : org.junit.rules.TestRule, androidx.compose.ui.test.SemanticsNodeInteractionsProvider {
    fun setContent(composable: @androidx.compose.runtime.Composable () -> Unit)
}
fun createComposeRule(): ComposeContentTestRule = TODO()
