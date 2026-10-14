package rw.plasmaco.ops.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import rw.plasmaco.ops.ui.theme.Plasmaco

/** Full-width teal action, as the template's "Invite Students" / "Export Report" buttons. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    fillWidth: Boolean = true,
) {
    val c = Plasmaco.colors
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        shape = Plasmaco.shapes.button,
        colors = ButtonDefaults.buttonColors(
            containerColor = c.primary, contentColor = c.onPrimary,
            disabledContainerColor = c.primary.copy(alpha = 0.38f), disabledContentColor = c.onPrimary.copy(alpha = 0.8f),
        ),
        contentPadding = PaddingValues(horizontal = 20.dp),
        modifier = modifier.then(if (fillWidth) Modifier.fillMaxWidth() else Modifier).height(Plasmaco.dimens.buttonHeight),
    ) { ButtonContent(text, icon, loading, c.onPrimary) }
}

/** Outlined companion to [PrimaryButton] for secondary actions (Save draft, Preview). */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    fillWidth: Boolean = true,
    destructive: Boolean = false,
) {
    val c = Plasmaco.colors
    val tint = if (destructive) c.danger else c.primary
    OutlinedButton(
        onClick = onClick,
        enabled = enabled && !loading,
        shape = Plasmaco.shapes.button,
        border = BorderStroke(1.dp, if (enabled) tint.copy(alpha = 0.5f) else c.border),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = tint, containerColor = c.surface),
        contentPadding = PaddingValues(horizontal = 18.dp),
        modifier = modifier.then(if (fillWidth) Modifier.fillMaxWidth() else Modifier).height(Plasmaco.dimens.buttonHeight),
    ) { ButtonContent(text, icon, loading, tint) }
}

/** Compact filled button ("+ Add Exam" in the template). Keeps a 48-dp touch area. */
@Composable
fun SmallButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true) {
    val c = Plasmaco.colors
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = Plasmaco.shapes.smallButton,
        colors = ButtonDefaults.buttonColors(containerColor = c.primary, contentColor = c.onPrimary),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
        modifier = modifier.defaultMinSize(minHeight = Plasmaco.dimens.smallButtonHeight).height(Plasmaco.dimens.smallButtonHeight),
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, style = Plasmaco.type.labelStrong)
    }
}

/** Underlined text link ("See All"). */
@Composable
fun TextLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onClick, modifier = modifier.semantics { role = Role.Button }, contentPadding = PaddingValues(horizontal = 4.dp)) {
        Text(text, style = Plasmaco.type.bodyStrong.copy(textDecoration = TextDecoration.Underline), color = Plasmaco.colors.primary)
    }
}

@Composable
private fun ButtonContent(text: String, icon: ImageVector?, loading: Boolean, tint: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(18.dp), color = tint, strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(Plasmaco.dimens.iconButtonGlyph))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = Plasmaco.type.button, maxLines = 1)
    }
}
