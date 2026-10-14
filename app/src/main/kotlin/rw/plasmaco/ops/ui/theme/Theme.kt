package rw.plasmaco.ops.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Spacing scale (4-dp base) and component sizes measured from the template (discovery report §5–6). */
@Immutable
data class PlasmacoDimens(
    val xxs: Dp = 2.dp,
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 20.dp,
    val xxl: Dp = 24.dp,
    val xxxl: Dp = 32.dp,
    val gutter: Dp = 16.dp,
    val cardPadding: Dp = 16.dp,
    val headerCardPadding: Dp = 20.dp,
    val cardGap: Dp = 12.dp,
    val buttonHeight: Dp = 48.dp,
    val smallButtonHeight: Dp = 36.dp,
    val touchTarget: Dp = 48.dp,
    val iconButton: Dp = 40.dp,
    val iconBadge: Dp = 32.dp,
    val topBarHeight: Dp = 64.dp,
    val bottomBarHeight: Dp = 72.dp,
    val inputHeight: Dp = 52.dp,
    val kpiCardMinWidth: Dp = 164.dp,
    val iconSmall: Dp = 18.dp,
    val iconButtonGlyph: Dp = 20.dp,
    val iconBar: Dp = 24.dp,
    val maxContentWidth: Dp = 1080.dp,
)

/** Corner radii: cards 20, inset 16, buttons 14, small buttons/chips 10, inputs 12, badges 6. */
@Immutable
data class PlasmacoShapes(
    val card: RoundedCornerShape = RoundedCornerShape(20.dp),
    val inset: RoundedCornerShape = RoundedCornerShape(16.dp),
    val button: RoundedCornerShape = RoundedCornerShape(14.dp),
    val smallButton: RoundedCornerShape = RoundedCornerShape(10.dp),
    val chip: RoundedCornerShape = RoundedCornerShape(10.dp),
    val input: RoundedCornerShape = RoundedCornerShape(12.dp),
    val badge: RoundedCornerShape = RoundedCornerShape(6.dp),
    val tooltip: RoundedCornerShape = RoundedCornerShape(8.dp),
    val sheet: RoundedCornerShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    val pill: RoundedCornerShape = RoundedCornerShape(50),
    val circle: androidx.compose.ui.graphics.Shape = CircleShape,
)

val LocalPlasmacoDimens = staticCompositionLocalOf { PlasmacoDimens() }
val LocalPlasmacoShapes = staticCompositionLocalOf { PlasmacoShapes() }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Single access point for tokens: `Plasmaco.colors.primary`, `Plasmaco.type.kpiValue`, `Plasmaco.dimens.gutter`. */
object Plasmaco {
    val colors: PlasmacoColors @Composable @ReadOnlyComposable get() = LocalPlasmacoColors.current
    val type: PlasmacoType @Composable @ReadOnlyComposable get() = LocalPlasmacoType.current
    val dimens: PlasmacoDimens @Composable @ReadOnlyComposable get() = LocalPlasmacoDimens.current
    val shapes: PlasmacoShapes @Composable @ReadOnlyComposable get() = LocalPlasmacoShapes.current
}

@Composable
fun PlasmacoTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val c = if (dark) DarkColors else LightColors
    val type = PlasmacoType()
    val scheme = if (dark) darkColorScheme(
        primary = c.primary, onPrimary = c.onPrimary, primaryContainer = c.primaryTint, onPrimaryContainer = c.textPrimary,
        secondary = c.chart.primary, onSecondary = c.onPrimary, background = c.background, onBackground = c.textPrimary,
        surface = c.surface, onSurface = c.textPrimary, surfaceVariant = c.surfaceInset, onSurfaceVariant = c.textSecondary,
        surfaceContainer = c.surface, surfaceContainerHigh = c.surfaceInset, surfaceContainerHighest = c.surfaceInset,
        outline = c.border, outlineVariant = c.divider, error = c.danger, onError = c.onPrimary, errorContainer = c.dangerTint, scrim = c.scrim,
    ) else lightColorScheme(
        primary = c.primary, onPrimary = c.onPrimary, primaryContainer = c.primaryTint, onPrimaryContainer = c.primary,
        secondary = c.primary, onSecondary = c.onPrimary, background = c.background, onBackground = c.textPrimary,
        surface = c.surface, onSurface = c.textPrimary, surfaceVariant = c.surfaceInset, onSurfaceVariant = c.textSecondary,
        surfaceContainer = c.surface, surfaceContainerHigh = c.surface, surfaceContainerHighest = c.surfaceInset, surfaceContainerLow = c.surface,
        outline = c.border, outlineVariant = c.divider, error = c.danger, onError = c.onPrimary, errorContainer = c.dangerTint, scrim = c.scrim,
    )
    val shapes = PlasmacoShapes()
    CompositionLocalProvider(
        LocalPlasmacoColors provides c,
        LocalPlasmacoType provides type,
        LocalPlasmacoDimens provides PlasmacoDimens(),
        LocalPlasmacoShapes provides shapes,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = materialTypography(type),
            shapes = Shapes(small = shapes.chip, medium = shapes.input, large = shapes.card, extraLarge = shapes.card),
            content = content,
        )
    }
}
