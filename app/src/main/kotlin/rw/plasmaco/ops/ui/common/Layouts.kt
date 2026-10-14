package rw.plasmaco.ops.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import rw.plasmaco.ops.domain.common.Issue
import rw.plasmaco.ops.domain.common.Severity
import rw.plasmaco.ops.ui.components.CircleIconButton
import rw.plasmaco.ops.ui.components.IconBadge
import rw.plasmaco.ops.ui.components.NoticeBanner
import rw.plasmaco.ops.ui.components.PlasmacoCard
import rw.plasmaco.ops.ui.components.Tone
import rw.plasmaco.ops.ui.components.toneColors
import rw.plasmaco.ops.ui.theme.Plasmaco

/**
 * Standard scrolling screen body: template gutters, 12-dp gaps between cards, and a centred maximum width so
 * tablets and landscape phones show a readable column rather than stretched cards.
 */
@Composable
fun ScreenList(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    bottomPadding: androidx.compose.ui.unit.Dp = 24.dp,
    content: LazyListScope.() -> Unit,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            state = state,
            modifier = Modifier.widthIn(max = Plasmaco.dimens.maxContentWidth).fillMaxSize(),
            contentPadding = PaddingValues(start = Plasmaco.dimens.gutter, end = Plasmaco.dimens.gutter, top = 4.dp, bottom = bottomPadding),
            verticalArrangement = Arrangement.spacedBy(Plasmaco.dimens.cardGap),
            content = content,
        )
    }
}

/** Non-lazy variant for forms (inputs keep focus and state reliably inside a plain scrolling column). */
@Composable
fun ScreenForm(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = Plasmaco.dimens.maxContentWidth).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = Plasmaco.dimens.gutter).padding(top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(Plasmaco.dimens.cardGap),
            content = content,
        )
    }
}

/** Header for screens below the top level: circular back button (template style), title, optional actions. */
@Composable
fun DetailHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        CircleIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack)
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Plasmaco.type.screenTitle, color = Plasmaco.colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
            if (subtitle != null) Text(subtitle, style = Plasmaco.type.bodySmall, color = Plasmaco.colors.textSecondary)
        }
        actions()
    }
}

/** Template list row: icon badge, title and supporting text, trailing content, chevron when clickable. */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTone: Tone = Tone.PRIMARY,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    val t = toneColors(iconTone)
    Row(
        modifier.fillMaxWidth().heightIn(min = Plasmaco.dimens.touchTarget)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            IconBadge(icon, t.content, t.container, 36.dp)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = Plasmaco.type.bodyStrong, color = Plasmaco.colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = Plasmaco.type.label, color = Plasmaco.colors.textSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
        if (onClick != null) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = Plasmaco.colors.textTertiary)
    }
}

@Composable
fun RowDivider() = HorizontalDivider(color = Plasmaco.colors.divider)

@Composable
fun KeyValueRow(label: String, value: String, modifier: Modifier = Modifier, emphasise: Boolean = false) {
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Text(label, style = Plasmaco.type.bodySmall, color = Plasmaco.colors.textSecondary, modifier = Modifier.weight(0.45f))
        Text(
            value, style = if (emphasise) Plasmaco.type.number else Plasmaco.type.body, color = Plasmaco.colors.textPrimary,
            modifier = Modifier.weight(0.55f),
        )
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = Plasmaco.type.sectionTitle, color = Plasmaco.colors.textPrimary, modifier = modifier.padding(top = 8.dp).semantics { heading() })
}

/** Validation findings: errors first, each with its message; [onOpen] lets the user jump to the place to fix it. */
@Composable
fun IssueList(issues: List<Issue>, modifier: Modifier = Modifier, onOpen: ((Issue) -> Unit)? = null) {
    if (issues.isEmpty()) return
    val sorted = issues.sortedBy { it.severity.ordinal }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        sorted.forEach { i ->
            val tone = if (i.severity == Severity.ERROR) Tone.DANGER else if (i.severity == Severity.WARNING) Tone.WARNING else Tone.INFO
            Box(Modifier.fillMaxWidth().then(if (onOpen != null) Modifier.clickable(role = Role.Button) { onOpen(i) } else Modifier)) {
                NoticeBanner(i.message, tone)
            }
        }
    }
}

/** Sticky action area at the bottom of editors and detail screens. */
@Composable
fun ActionBar(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Column(modifier.fillMaxWidth().background(Plasmaco.colors.surface)) {
        HorizontalDivider(color = Plasmaco.colors.divider)
        Row(
            Modifier.fillMaxWidth().widthIn(max = Plasmaco.dimens.maxContentWidth).padding(horizontal = Plasmaco.dimens.gutter, vertical = 10.dp).navigationBarsPadding(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/** A white card holding [ListRow]s separated by dividers. */
@Composable
fun <T> ListCard(items: List<T>, modifier: Modifier = Modifier, row: @Composable (T) -> Unit) {
    PlasmacoCard(modifier, padding = 12.dp) {
        items.forEachIndexed { i, item ->
            if (i > 0) RowDivider()
            row(item)
        }
    }
}
