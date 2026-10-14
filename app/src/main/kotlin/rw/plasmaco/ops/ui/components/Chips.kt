package rw.plasmaco.ops.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import rw.plasmaco.ops.ui.theme.Plasmaco

/** Semantic tones shared by badges, banners and alerts. Each always pairs with text. */
enum class Tone { NEUTRAL, PRIMARY, SUCCESS, WARNING, DANGER, INFO }

data class ToneColors(val content: Color, val container: Color)

@Composable
fun toneColors(tone: Tone): ToneColors {
    val c = Plasmaco.colors
    return when (tone) {
        Tone.NEUTRAL -> ToneColors(c.textSecondary, c.surfaceInset)
        Tone.PRIMARY -> ToneColors(c.primary, c.primaryTint)
        Tone.SUCCESS -> ToneColors(c.success, c.successTint)
        Tone.WARNING -> ToneColors(c.warning, c.warningTint)
        Tone.DANGER -> ToneColors(c.danger, c.dangerTint)
        Tone.INFO -> ToneColors(c.info, c.infoTint)
    }
}

/** Filled status badge (template's "Crisis" / "Action" chips). */
@Composable
fun StatusBadge(text: String, tone: Tone, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val t = toneColors(tone)
    Row(
        modifier.clip(Plasmaco.shapes.badge).background(t.container).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, tint = t.content, modifier = Modifier.size(13.dp)); Spacer(Modifier.width(4.dp)) }
        Text(text, style = Plasmaco.type.labelStrong, color = t.content, maxLines = 1)
    }
}

/** Outlined small badge (template's "Class-09"). */
@Composable
fun OutlineBadge(text: String, modifier: Modifier = Modifier) {
    val c = Plasmaco.colors
    Text(
        text, style = Plasmaco.type.label, color = c.textSecondary,
        modifier = modifier.clip(Plasmaco.shapes.badge).background(c.primaryTint.copy(alpha = 0.5f))
            .border(1.dp, c.textTertiary.copy(alpha = 0.6f), Plasmaco.shapes.badge).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** Selectable filter chip: selected = teal outline + teal text + tint, as the template's "MCQ" chip. */
@Composable
fun AppFilterChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val c = Plasmaco.colors
    val shape = Plasmaco.shapes.chip
    Row(
        modifier.heightIn(min = 40.dp).clip(shape)
            .background(if (selected) c.primaryTint else c.surface)
            .border(BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) c.primary else c.border), shape)
            .clickable(role = Role.Checkbox, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics { this.selected = selected },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, tint = if (selected) c.primary else c.textSecondary, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, style = Plasmaco.type.chip, color = if (selected) c.primary else c.textPrimary, maxLines = 1)
    }
}

@Composable
fun FilterChipRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { content() }
}

/** Calendar-icon dropdown chip (template's "Monthly ⌄"). */
@Composable
fun DropdownChip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector = Icons.Outlined.CalendarMonth, description: String = text) {
    val c = Plasmaco.colors
    Row(
        modifier.heightIn(min = 40.dp).clip(Plasmaco.shapes.chip).background(c.surface)
            .border(1.dp, c.border, Plasmaco.shapes.chip).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp).semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = c.textSecondary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = Plasmaco.type.label, color = c.textPrimary, maxLines = 1)
        Spacer(Modifier.width(2.dp))
        Icon(Icons.Outlined.ExpandMore, null, tint = c.textSecondary, modifier = Modifier.size(16.dp))
    }
}

/** Coloured priority word with a dot (template's Urgent / High / Medium / Low). */
@Composable
fun PriorityText(text: String, tone: Tone, modifier: Modifier = Modifier) {
    val t = toneColors(tone)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(Plasmaco.shapes.circle).background(t.content))
        Spacer(Modifier.width(6.dp))
        Text(text, style = Plasmaco.type.labelStrong, color = t.content)
    }
}

/** Legend item: short bar marker + name (template's gauge legend "Chemistry — 45%"). */
@Composable
fun LegendItem(color: Color, label: String, modifier: Modifier = Modifier, line: Boolean = false) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = if (line) 14.dp else 10.dp, height = if (line) 2.dp else 10.dp).clip(Plasmaco.shapes.badge).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = Plasmaco.type.label, color = Plasmaco.colors.textPrimary)
    }
}
