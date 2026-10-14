package rw.plasmaco.ops.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TrendingDown
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.NorthEast
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import rw.plasmaco.ops.ui.theme.Plasmaco

/** White, flat, 20-dp-radius card on the grey canvas — the template's basic surface. */
@Composable
fun PlasmacoCard(
    modifier: Modifier = Modifier,
    padding: Dp = Plasmaco.dimens.cardPadding,
    onClick: (() -> Unit)? = null,
    color: Color = Plasmaco.colors.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = Plasmaco.shapes.card
    Surface(
        shape = shape,
        color = color,
        modifier = modifier.fillMaxWidth().clip(shape).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) { Column(Modifier.padding(padding), content = content) }
}

/** Light grey card nested inside a white card (template's exam list items, table blocks). */
@Composable
fun InsetCard(modifier: Modifier = Modifier, padding: Dp = Plasmaco.dimens.md, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = Plasmaco.shapes.inset
    Surface(
        shape = shape,
        color = Plasmaco.colors.surfaceInset,
        modifier = modifier.fillMaxWidth().clip(shape).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) { Column(Modifier.padding(padding), content = content) }
}

/** Title, date line and one full-width primary action ("Good Morning, James" + "Invite Students"). */
@Composable
fun PageHeaderCard(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    actionIcon: ImageVector? = null,
    onAction: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    PlasmacoCard(modifier, padding = Plasmaco.dimens.headerCardPadding) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(title, style = Plasmaco.type.screenTitle, color = Plasmaco.colors.textPrimary, modifier = Modifier.semantics { heading() })
                if (subtitle != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(subtitle, style = Plasmaco.type.body, color = Plasmaco.colors.textSecondary)
                }
            }
            trailing?.invoke()
        }
        if (actionText != null && onAction != null) {
            Spacer(Modifier.height(Plasmaco.dimens.lg))
            PrimaryButton(actionText, onAction, icon = actionIcon)
        }
    }
}

/** Section card with a title and the template's circular ↗ drill-down button. */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onOpen: (() -> Unit)? = null,
    openLabel: String = "Open $title",
    trailing: @Composable (RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    PlasmacoCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = Plasmaco.type.sectionTitle, color = Plasmaco.colors.textPrimary, modifier = Modifier.semantics { heading() })
                if (subtitle != null) Text(subtitle, style = Plasmaco.type.label, color = Plasmaco.colors.textSecondary)
            }
            trailing?.invoke(this)
            if (onOpen != null) ArrowButton(onOpen, openLabel)
        }
        Spacer(Modifier.height(Plasmaco.dimens.md))
        content()
    }
}

/** 40-dp circle with an outlined glyph, inside a 48-dp touch target (template's bell and chat buttons). */
@Composable
fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    background: Color = Plasmaco.colors.surface,
    tint: Color = Plasmaco.colors.textPrimary,
    badge: Int? = null,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        IconButton(onClick = onClick, modifier = Modifier.size(Plasmaco.dimens.touchTarget)) {
            Box(Modifier.size(Plasmaco.dimens.iconButton).clip(CircleShape).background(background), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(Plasmaco.dimens.iconButtonGlyph))
            }
        }
        if (badge != null && badge > 0) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 4.dp).size(18.dp).clip(CircleShape).background(Plasmaco.colors.dangerGraphic),
                contentAlignment = Alignment.Center,
            ) { Text(if (badge > 9) "9+" else badge.toString(), style = Plasmaco.type.caption, color = Color.White) }
        }
    }
}

@Composable
fun ArrowButton(onClick: () -> Unit, contentDescription: String, onDark: Boolean = false) {
    val c = Plasmaco.colors
    CircleIconButton(
        Icons.Outlined.NorthEast, contentDescription, onClick,
        background = if (onDark) Color.White else c.surfaceInset, tint = if (onDark) c.primary else c.textPrimary,
    )
}

/** Circular tinted icon used in KPI cards (template: green and orange tints). */
@Composable
fun IconBadge(icon: ImageVector, tint: Color, background: Color, size: Dp = Plasmaco.dimens.iconBadge) {
    Box(Modifier.size(size).clip(CircleShape).background(background), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.55f))
    }
}

enum class Trend { UP_GOOD, UP_BAD, DOWN_GOOD, DOWN_BAD, FLAT }

/** "+3.7% ↗" delta. Colour comes from whether the change is good, with the arrow carrying direction (never colour alone). */
@Composable
fun DeltaText(text: String, trend: Trend, modifier: Modifier = Modifier) {
    val c = Plasmaco.colors
    val color = when (trend) {
        Trend.UP_GOOD, Trend.DOWN_GOOD -> c.success
        Trend.UP_BAD, Trend.DOWN_BAD -> c.danger
        Trend.FLAT -> c.textSecondary
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = Plasmaco.type.delta, color = color)
        val icon = when (trend) {
            Trend.UP_GOOD, Trend.UP_BAD -> Icons.AutoMirrored.Outlined.TrendingUp
            Trend.DOWN_GOOD, Trend.DOWN_BAD -> Icons.AutoMirrored.Outlined.TrendingDown
            Trend.FLAT -> null
        }
        if (icon != null) { Spacer(Modifier.width(2.dp)); Icon(icon, null, tint = color, modifier = Modifier.size(14.dp)) }
    }
}

/** KPI card: grey label, tinted icon top-right, large value with a small unit, optional delta or "No data" reason. */
@Composable
fun KpiCard(
    label: String,
    value: String?,
    unit: String?,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTint: Color = Plasmaco.colors.success,
    iconBackground: Color = Plasmaco.colors.successTint,
    delta: (@Composable () -> Unit)? = null,
    noDataReason: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val c = Plasmaco.colors
    val description = if (value == null) "$label: no data. ${noDataReason.orEmpty()}" else "$label: $value ${unit.orEmpty()}"
    PlasmacoCard(modifier.widthIn(min = Plasmaco.dimens.kpiCardMinWidth).semantics(mergeDescendants = true) { contentDescription = description }, onClick = onClick, padding = 14.dp) {
        Row(verticalAlignment = Alignment.Top) {
            Text(label, style = Plasmaco.type.label, color = c.textSecondary, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (icon != null) IconBadge(icon, iconTint, iconBackground)
        }
        Spacer(Modifier.height(10.dp))
        if (value == null) {
            Text("No data", style = Plasmaco.type.kpiValueSmall, color = c.textTertiary)
            if (noDataReason != null) Text(noDataReason, style = Plasmaco.type.caption, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        } else {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, style = Plasmaco.type.kpiValue, color = c.textPrimary, maxLines = 1)
                if (unit != null) {
                    Spacer(Modifier.width(4.dp))
                    Text(unit, style = Plasmaco.type.kpiUnit, color = c.textPrimary, modifier = Modifier.padding(bottom = 4.dp))
                }
                if (delta != null) { Spacer(Modifier.width(8.dp)); Box(Modifier.padding(bottom = 6.dp)) { delta() } }
            }
        }
    }
}

/** Ring progress (template's "43 min Left"): white track, bright green arc, value in the middle. */
@Composable
fun RingProgress(
    fraction: Float?,
    centerText: String,
    caption: String?,
    modifier: Modifier = Modifier,
    size: Dp = 76.dp,
    trackColor: Color = Color.White,
    progressColor: Color = Plasmaco.colors.accent,
    textColor: Color = Color.White,
) {
    Box(modifier.size(size).semantics { contentDescription = "$centerText ${caption.orEmpty()}" }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = 7.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(trackColor, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            if (fraction != null) drawArc(progressColor, -90f, 360f * fraction.coerceIn(0f, 1f), false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(centerText, style = Plasmaco.type.bodyStrong, color = textColor)
            if (caption != null) Text(caption, style = Plasmaco.type.caption, color = textColor)
        }
    }
}

/** Dark teal hero card (template's live-exam card) used for the current shift. */
@Composable
fun HeroStatusCard(
    title: String,
    statusText: String,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    metaLines: List<Pair<ImageVector, String>> = emptyList(),
    ring: (@Composable () -> Unit)? = null,
    onOpen: (() -> Unit)? = null,
) {
    val c = Plasmaco.colors
    Surface(shape = Plasmaco.shapes.card, color = c.primary, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(Plasmaco.dimens.cardPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (leadingIcon != null) IconBadge(leadingIcon, Color.White, c.heroDeep, 40.dp)
                Spacer(Modifier.weight(1f))
                if (onOpen != null) ArrowButton(onOpen, "Open $title", onDark = true)
            }
            Spacer(Modifier.height(Plasmaco.dimens.lg))
            Text(title, style = Plasmaco.type.cardTitle, color = Color.White)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(c.accent))
                Spacer(Modifier.width(6.dp))
                Text(statusText, style = Plasmaco.type.bodySmall, color = Color.White)
            }
            Spacer(Modifier.height(Plasmaco.dimens.md))
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    metaLines.forEach { (icon, text) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(icon, null, tint = Color.White, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(text, style = Plasmaco.type.bodySmall, color = Color.White)
                        }
                    }
                }
                ring?.invoke()
            }
        }
    }
}

/** Thin outlined card border for items that must stand out on an inset background. */
@Composable
fun outlinedBorder(): BorderStroke = BorderStroke(1.dp, Plasmaco.colors.border)
