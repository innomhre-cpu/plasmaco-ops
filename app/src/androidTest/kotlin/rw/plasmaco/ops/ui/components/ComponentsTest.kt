package rw.plasmaco.ops.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import rw.plasmaco.ops.ui.theme.PlasmacoTheme
import kotlin.test.assertEquals

/** Shared components render their content and report taps (Compose UI test, device or emulator). */
@RunWith(AndroidJUnit4::class)
class ComponentsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun emptyStateShowsItsTextAndAction() {
        var taps = 0
        compose.setContent {
            PlasmacoTheme {
                EmptyState(title = "No reports yet", message = "Create the first report for this shift.", actionText = "New report", onAction = { taps++ })
            }
        }
        compose.onNodeWithText("No reports yet").assertIsDisplayed()
        compose.onNodeWithText("Create the first report for this shift.").assertIsDisplayed()
        compose.onNodeWithText("New report").performClick()
        assertEquals(1, taps)
    }

    @Test
    fun statusBadgeShowsItsLabel() {
        compose.setContent { PlasmacoTheme { StatusBadge("Pending approval", Tone.WARNING) } }
        compose.onNodeWithText("Pending approval").assertIsDisplayed()
    }
}
