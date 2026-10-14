package rw.plasmaco.ops.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Plasmaco colour tokens, adapted from the Testora template (discovery report §5–6).
 * Text steps are darkened from the template so they pass WCAG AA; the template's bright green is kept for graphics only.
 */
@Immutable
data class PlasmacoColors(
    val primary: Color,
    val primaryPressed: Color,
    val onPrimary: Color,
    val primaryTint: Color,
    val heroDeep: Color,
    val accent: Color,
    val background: Color,
    val surface: Color,
    val surfaceInset: Color,
    val border: Color,
    val divider: Color,
    val track: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val success: Color,
    val successTint: Color,
    val warning: Color,
    val warningGraphic: Color,
    val warningTint: Color,
    val danger: Color,
    val dangerGraphic: Color,
    val dangerTint: Color,
    val info: Color,
    val infoTint: Color,
    val scrim: Color,
    val chart: ChartColors,
    val isDark: Boolean,
)

/** Chart palette: validated categorical order (teal, amber, blue, coral, violet), template context sage and teal ramp. */
@Immutable
data class ChartColors(
    val primary: Color,
    val context: Color,
    val target: Color,
    val negative: Color,
    val other: Color,
    val categorical: List<Color>,
    val heatRamp: List<Color>,
    val grid: Color,
    val axisText: Color,
    val tooltipBackground: Color,
    val tooltipText: Color,
)

val LightColors = PlasmacoColors(
    primary = Color(0xFF17504E),
    primaryPressed = Color(0xFF0E3D3D),
    onPrimary = Color.White,
    primaryTint = Color(0xFFE3EEED),
    heroDeep = Color(0xFF0E3D3D),
    accent = Color(0xFF00C953),
    background = Color(0xFFF1F2F4),
    surface = Color.White,
    surfaceInset = Color(0xFFF5F7F7),
    border = Color(0xFFE6E9EA),
    divider = Color(0xFFEEF0F1),
    track = Color(0xFFEEF1F1),
    textPrimary = Color(0xFF111414),
    textSecondary = Color(0xFF626B6B),
    textTertiary = Color(0xFF9AA3A3),
    success = Color(0xFF0B7F3D),
    successTint = Color(0xFFE0F6EA),
    warning = Color(0xFF8A5A00),
    warningGraphic = Color(0xFFEFB201),
    warningTint = Color(0xFFFFF2D6),
    danger = Color(0xFFC0362C),
    dangerGraphic = Color(0xFFE5484D),
    dangerTint = Color(0xFFFCE4E4),
    info = Color(0xFF2A5F94),
    infoTint = Color(0xFFE3EDF7),
    scrim = Color(0x66000000),
    chart = ChartColors(
        primary = Color(0xFF17504E),
        context = Color(0xFFC9D3D2),
        target = Color(0xFF111414),
        negative = Color(0xFFE5484D),
        other = Color(0xFF9AA3A3),
        categorical = listOf(Color(0xFF00876F), Color(0xFFC47D04), Color(0xFF2A75BA), Color(0xFFD45B3D), Color(0xFF795BBF)),
        heatRamp = listOf(Color(0xFFE3EAE9), Color(0xFFC5D3D1), Color(0xFF8FA9A7), Color(0xFF5F8684), Color(0xFF33605F), Color(0xFF17504E)),
        grid = Color(0xFFEEF0F1),
        axisText = Color(0xFF626B6B),
        tooltipBackground = Color(0xFF17504E),
        tooltipText = Color.White,
    ),
    isDark = false,
)

/** Dark theme: not shown in the template; derived from the same hues and validated separately (discovery report §6). */
val DarkColors = PlasmacoColors(
    primary = Color(0xFF1F6A67),
    primaryPressed = Color(0xFF175451),
    onPrimary = Color.White,
    primaryTint = Color(0xFF183634),
    heroDeep = Color(0xFF123331),
    accent = Color(0xFF2EDB7A),
    background = Color(0xFF0E1313),
    surface = Color(0xFF161D1D),
    surfaceInset = Color(0xFF1E2726),
    border = Color(0xFF2A3534),
    divider = Color(0xFF222C2B),
    track = Color(0xFF26302F),
    textPrimary = Color(0xFFE8EEED),
    textSecondary = Color(0xFFA3B0AF),
    textTertiary = Color(0xFF6F7C7B),
    success = Color(0xFF2EDB7A),
    successTint = Color(0xFF15301F),
    warning = Color(0xFFF5C542),
    warningGraphic = Color(0xFFF5C542),
    warningTint = Color(0xFF332A12),
    danger = Color(0xFFFF7A72),
    dangerGraphic = Color(0xFFFF7A72),
    dangerTint = Color(0xFF3A1D1C),
    info = Color(0xFF8DB8E8),
    infoTint = Color(0xFF1A2A3A),
    scrim = Color(0x99000000),
    chart = ChartColors(
        primary = Color(0xFF5FB3AD),
        context = Color(0xFF3A4847),
        target = Color(0xFFE8EEED),
        negative = Color(0xFFFF7A72),
        other = Color(0xFF6F7C7B),
        categorical = listOf(Color(0xFF1D9A8B), Color(0xFFC38300), Color(0xFF3A84CA), Color(0xFFD05F43), Color(0xFF8D73D1)),
        heatRamp = listOf(Color(0xFF1E2726), Color(0xFF23403E), Color(0xFF2C5A57), Color(0xFF3B7671), Color(0xFF4F938C), Color(0xFF6FB3AB)),
        grid = Color(0xFF222C2B),
        axisText = Color(0xFFA3B0AF),
        tooltipBackground = Color(0xFF2B4A48),
        tooltipText = Color.White,
    ),
    isDark = true,
)

val LocalPlasmacoColors = staticCompositionLocalOf { LightColors }
