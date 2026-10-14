package rw.plasmaco.ops.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import rw.plasmaco.ops.ui.theme.Plasmaco

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Plasmaco.colors.surface,
        shape = Plasmaco.shapes.card,
        title = { Text(title, style = Plasmaco.type.sectionTitle) },
        text = { Text(message, style = Plasmaco.type.body, color = Plasmaco.colors.textSecondary) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmText, style = Plasmaco.type.button, color = if (destructive) Plasmaco.colors.danger else Plasmaco.colors.primary) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", style = Plasmaco.type.button, color = Plasmaco.colors.textSecondary) } },
    )
}

/** Reject / return / reopen: the action is only enabled once a reason of [minLength] characters is typed. */
@Composable
fun ReasonDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    minLength: Int = 5,
    destructive: Boolean = false,
) {
    var reason by rememberSaveable { mutableStateOf("") }
    val tooShort = reason.trim().length < minLength
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Plasmaco.colors.surface,
        shape = Plasmaco.shapes.card,
        title = { Text(title, style = Plasmaco.type.sectionTitle) },
        text = {
            Column {
                Text(message, style = Plasmaco.type.body, color = Plasmaco.colors.textSecondary)
                Spacer(Modifier.height(12.dp))
                AppTextField(
                    reason, { reason = it }, "Reason", required = true, singleLine = false, minLines = 3, imeAction = ImeAction.Default,
                    hint = "At least $minLength characters; saved in the approval history",
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(reason.trim()) }, enabled = !tooShort) {
                Text(confirmText, style = Plasmaco.type.button, color = if (tooShort) Plasmaco.colors.textTertiary else if (destructive) Plasmaco.colors.danger else Plasmaco.colors.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", style = Plasmaco.type.button, color = Plasmaco.colors.textSecondary) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBottomSheet(title: String, onDismiss: () -> Unit, scrollable: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        shape = Plasmaco.shapes.sheet,
        containerColor = Plasmaco.colors.surface,
    ) {
        Column(
            Modifier.fillMaxWidth().then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(horizontal = Plasmaco.dimens.gutter).padding(bottom = 16.dp).navigationBarsPadding().imePadding(),
        ) {
            Text(title, style = Plasmaco.type.sectionTitle, color = Plasmaco.colors.textPrimary)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}
