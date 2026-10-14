@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package androidx.compose.material3

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

@RequiresOptIn
annotation class ExperimentalMaterial3Api

// ------------------------------------------------------------------ theme
class ColorScheme
fun lightColorScheme(
    primary: Color = Color.Unspecified, onPrimary: Color = Color.Unspecified, primaryContainer: Color = Color.Unspecified, onPrimaryContainer: Color = Color.Unspecified,
    inversePrimary: Color = Color.Unspecified, secondary: Color = Color.Unspecified, onSecondary: Color = Color.Unspecified, secondaryContainer: Color = Color.Unspecified,
    onSecondaryContainer: Color = Color.Unspecified, tertiary: Color = Color.Unspecified, onTertiary: Color = Color.Unspecified, tertiaryContainer: Color = Color.Unspecified,
    onTertiaryContainer: Color = Color.Unspecified, background: Color = Color.Unspecified, onBackground: Color = Color.Unspecified, surface: Color = Color.Unspecified,
    onSurface: Color = Color.Unspecified, surfaceVariant: Color = Color.Unspecified, onSurfaceVariant: Color = Color.Unspecified, surfaceTint: Color = Color.Unspecified,
    inverseSurface: Color = Color.Unspecified, inverseOnSurface: Color = Color.Unspecified, error: Color = Color.Unspecified, onError: Color = Color.Unspecified,
    errorContainer: Color = Color.Unspecified, onErrorContainer: Color = Color.Unspecified, outline: Color = Color.Unspecified, outlineVariant: Color = Color.Unspecified,
    scrim: Color = Color.Unspecified, surfaceBright: Color = Color.Unspecified, surfaceContainer: Color = Color.Unspecified, surfaceContainerHigh: Color = Color.Unspecified,
    surfaceContainerHighest: Color = Color.Unspecified, surfaceContainerLow: Color = Color.Unspecified, surfaceContainerLowest: Color = Color.Unspecified, surfaceDim: Color = Color.Unspecified,
): ColorScheme = TODO()
fun darkColorScheme(
    primary: Color = Color.Unspecified, onPrimary: Color = Color.Unspecified, primaryContainer: Color = Color.Unspecified, onPrimaryContainer: Color = Color.Unspecified,
    inversePrimary: Color = Color.Unspecified, secondary: Color = Color.Unspecified, onSecondary: Color = Color.Unspecified, secondaryContainer: Color = Color.Unspecified,
    onSecondaryContainer: Color = Color.Unspecified, tertiary: Color = Color.Unspecified, onTertiary: Color = Color.Unspecified, tertiaryContainer: Color = Color.Unspecified,
    onTertiaryContainer: Color = Color.Unspecified, background: Color = Color.Unspecified, onBackground: Color = Color.Unspecified, surface: Color = Color.Unspecified,
    onSurface: Color = Color.Unspecified, surfaceVariant: Color = Color.Unspecified, onSurfaceVariant: Color = Color.Unspecified, surfaceTint: Color = Color.Unspecified,
    inverseSurface: Color = Color.Unspecified, inverseOnSurface: Color = Color.Unspecified, error: Color = Color.Unspecified, onError: Color = Color.Unspecified,
    errorContainer: Color = Color.Unspecified, onErrorContainer: Color = Color.Unspecified, outline: Color = Color.Unspecified, outlineVariant: Color = Color.Unspecified,
    scrim: Color = Color.Unspecified, surfaceBright: Color = Color.Unspecified, surfaceContainer: Color = Color.Unspecified, surfaceContainerHigh: Color = Color.Unspecified,
    surfaceContainerHighest: Color = Color.Unspecified, surfaceContainerLow: Color = Color.Unspecified, surfaceContainerLowest: Color = Color.Unspecified, surfaceDim: Color = Color.Unspecified,
): ColorScheme = TODO()

class Typography(
    val displayLarge: TextStyle = TextStyle.Default, val displayMedium: TextStyle = TextStyle.Default, val displaySmall: TextStyle = TextStyle.Default,
    val headlineLarge: TextStyle = TextStyle.Default, val headlineMedium: TextStyle = TextStyle.Default, val headlineSmall: TextStyle = TextStyle.Default,
    val titleLarge: TextStyle = TextStyle.Default, val titleMedium: TextStyle = TextStyle.Default, val titleSmall: TextStyle = TextStyle.Default,
    val bodyLarge: TextStyle = TextStyle.Default, val bodyMedium: TextStyle = TextStyle.Default, val bodySmall: TextStyle = TextStyle.Default,
    val labelLarge: TextStyle = TextStyle.Default, val labelMedium: TextStyle = TextStyle.Default, val labelSmall: TextStyle = TextStyle.Default,
)
class Shapes(
    val extraSmall: CornerBasedShape = TODO(), val small: CornerBasedShape = TODO(), val medium: CornerBasedShape = TODO(),
    val large: CornerBasedShape = TODO(), val extraLarge: CornerBasedShape = TODO(),
)
@Composable fun MaterialTheme(colorScheme: ColorScheme = TODO(), shapes: Shapes = TODO(), typography: Typography = TODO(), content: @Composable () -> Unit) {}
object MaterialTheme {
    val colorScheme: ColorScheme @Composable get() = TODO()
    val typography: Typography @Composable get() = TODO()
    val shapes: Shapes @Composable get() = TODO()
}

// ------------------------------------------------------------------ text and icons
@Composable fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = TextStyle.Default,
) {}
@Composable fun Icon(imageVector: ImageVector, contentDescription: String?, modifier: Modifier = Modifier, tint: Color = Color.Unspecified) {}
@Composable fun Icon(painter: Painter, contentDescription: String?, modifier: Modifier = Modifier, tint: Color = Color.Unspecified) {}

// ------------------------------------------------------------------ buttons
class ButtonColors
class IconButtonColors
interface ButtonElevation
object ButtonDefaults {
    val ContentPadding: PaddingValues get() = TODO()
    val TextButtonContentPadding: PaddingValues get() = TODO()
    val shape: Shape @Composable get() = TODO()
    @Composable fun buttonColors(containerColor: Color = Color.Unspecified, contentColor: Color = Color.Unspecified, disabledContainerColor: Color = Color.Unspecified, disabledContentColor: Color = Color.Unspecified): ButtonColors = TODO()
    @Composable fun outlinedButtonColors(containerColor: Color = Color.Unspecified, contentColor: Color = Color.Unspecified, disabledContainerColor: Color = Color.Unspecified, disabledContentColor: Color = Color.Unspecified): ButtonColors = TODO()
    @Composable fun textButtonColors(containerColor: Color = Color.Unspecified, contentColor: Color = Color.Unspecified, disabledContainerColor: Color = Color.Unspecified, disabledContentColor: Color = Color.Unspecified): ButtonColors = TODO()
    @Composable fun buttonElevation(defaultElevation: Dp = 2.dp, pressedElevation: Dp = 2.dp, focusedElevation: Dp = 2.dp, hoveredElevation: Dp = 2.dp, disabledElevation: Dp = 0.dp): ButtonElevation = TODO()
}
@Composable fun Button(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, shape: Shape = TODO(), colors: ButtonColors = TODO(), elevation: ButtonElevation? = null, border: BorderStroke? = null, contentPadding: PaddingValues = ButtonDefaults.ContentPadding, content: @Composable RowScope.() -> Unit) {}
@Composable fun OutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, shape: Shape = TODO(), colors: ButtonColors = TODO(), elevation: ButtonElevation? = null, border: BorderStroke? = null, contentPadding: PaddingValues = ButtonDefaults.ContentPadding, content: @Composable RowScope.() -> Unit) {}
@Composable fun TextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, shape: Shape = TODO(), colors: ButtonColors = TODO(), elevation: ButtonElevation? = null, border: BorderStroke? = null, contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding, content: @Composable RowScope.() -> Unit) {}
@Composable fun IconButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, colors: IconButtonColors = TODO(), content: @Composable () -> Unit) {}

// ------------------------------------------------------------------ selection controls
class CheckboxColors
object CheckboxDefaults {
    @Composable fun colors(checkedColor: Color = Color.Unspecified, uncheckedColor: Color = Color.Unspecified, checkmarkColor: Color = Color.Unspecified, disabledCheckedColor: Color = Color.Unspecified, disabledUncheckedColor: Color = Color.Unspecified, disabledIndeterminateColor: Color = Color.Unspecified): CheckboxColors = TODO()
}
@Composable fun Checkbox(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true, colors: CheckboxColors = CheckboxDefaults.colors()) {}
class SwitchColors
object SwitchDefaults {
    @Composable fun colors(
        checkedThumbColor: Color = Color.Unspecified, checkedTrackColor: Color = Color.Unspecified, checkedBorderColor: Color = Color.Unspecified, checkedIconColor: Color = Color.Unspecified,
        uncheckedThumbColor: Color = Color.Unspecified, uncheckedTrackColor: Color = Color.Unspecified, uncheckedBorderColor: Color = Color.Unspecified, uncheckedIconColor: Color = Color.Unspecified,
    ): SwitchColors = TODO()
}
@Composable fun Switch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, thumbContent: (@Composable () -> Unit)? = null, enabled: Boolean = true, colors: SwitchColors = SwitchDefaults.colors()) {}

// ------------------------------------------------------------------ containers and feedback
@Composable fun Surface(modifier: Modifier = Modifier, shape: Shape = TODO(), color: Color = Color.Unspecified, contentColor: Color = Color.Unspecified, tonalElevation: Dp = 0.dp, shadowElevation: Dp = 0.dp, border: BorderStroke? = null, content: @Composable () -> Unit) {}
@Composable fun Surface(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, shape: Shape = TODO(), color: Color = Color.Unspecified, contentColor: Color = Color.Unspecified, tonalElevation: Dp = 0.dp, shadowElevation: Dp = 0.dp, border: BorderStroke? = null, content: @Composable () -> Unit) {}
@Composable fun HorizontalDivider(modifier: Modifier = Modifier, thickness: Dp = 1.dp, color: Color = Color.Unspecified) {}
@Composable fun CircularProgressIndicator(modifier: Modifier = Modifier, color: Color = Color.Unspecified, strokeWidth: Dp = 4.dp, trackColor: Color = Color.Unspecified, strokeCap: StrokeCap = StrokeCap.Butt) {}
@Composable fun AlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    shape: Shape = TODO(),
    containerColor: Color = Color.Unspecified,
    iconContentColor: Color = Color.Unspecified,
    titleContentColor: Color = Color.Unspecified,
    textContentColor: Color = Color.Unspecified,
    tonalElevation: Dp = 6.dp,
) {}

class SnackbarHostState {
    suspend fun showSnackbar(message: String, actionLabel: String? = null, withDismissAction: Boolean = false, duration: SnackbarDuration = SnackbarDuration.Short): SnackbarResult = TODO()
}
enum class SnackbarDuration { Short, Long, Indefinite }
enum class SnackbarResult { Dismissed, ActionPerformed }
@Composable fun SnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {}

@Composable fun Scaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    containerColor: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified,
    contentWindowInsets: WindowInsets = TODO(),
    content: @Composable (PaddingValues) -> Unit,
) {}

// ------------------------------------------------------------------ menus and text fields
@Composable fun DropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = TODO(),
    containerColor: Color = Color.Unspecified,
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 0.dp,
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit,
) {}
@Composable fun DropdownMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
) {}

class TextFieldColors
object OutlinedTextFieldDefaults {
    @Composable fun colors(
        focusedTextColor: Color = Color.Unspecified, unfocusedTextColor: Color = Color.Unspecified, disabledTextColor: Color = Color.Unspecified, errorTextColor: Color = Color.Unspecified,
        focusedContainerColor: Color = Color.Unspecified, unfocusedContainerColor: Color = Color.Unspecified, disabledContainerColor: Color = Color.Unspecified, errorContainerColor: Color = Color.Unspecified,
        cursorColor: Color = Color.Unspecified, errorCursorColor: Color = Color.Unspecified,
        focusedBorderColor: Color = Color.Unspecified, unfocusedBorderColor: Color = Color.Unspecified, disabledBorderColor: Color = Color.Unspecified, errorBorderColor: Color = Color.Unspecified,
        focusedLeadingIconColor: Color = Color.Unspecified, unfocusedLeadingIconColor: Color = Color.Unspecified, disabledLeadingIconColor: Color = Color.Unspecified, errorLeadingIconColor: Color = Color.Unspecified,
        focusedTrailingIconColor: Color = Color.Unspecified, unfocusedTrailingIconColor: Color = Color.Unspecified, disabledTrailingIconColor: Color = Color.Unspecified, errorTrailingIconColor: Color = Color.Unspecified,
        focusedLabelColor: Color = Color.Unspecified, unfocusedLabelColor: Color = Color.Unspecified, disabledLabelColor: Color = Color.Unspecified, errorLabelColor: Color = Color.Unspecified,
        focusedPlaceholderColor: Color = Color.Unspecified, unfocusedPlaceholderColor: Color = Color.Unspecified, disabledPlaceholderColor: Color = Color.Unspecified, errorPlaceholderColor: Color = Color.Unspecified,
        focusedSupportingTextColor: Color = Color.Unspecified, unfocusedSupportingTextColor: Color = Color.Unspecified, disabledSupportingTextColor: Color = Color.Unspecified, errorSupportingTextColor: Color = Color.Unspecified,
        focusedPrefixColor: Color = Color.Unspecified, unfocusedPrefixColor: Color = Color.Unspecified, disabledPrefixColor: Color = Color.Unspecified, errorPrefixColor: Color = Color.Unspecified,
        focusedSuffixColor: Color = Color.Unspecified, unfocusedSuffixColor: Color = Color.Unspecified, disabledSuffixColor: Color = Color.Unspecified, errorSuffixColor: Color = Color.Unspecified,
    ): TextFieldColors = TODO()
}
@Composable fun OutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = TextStyle.Default,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    prefix: @Composable (() -> Unit)? = null,
    suffix: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    shape: Shape = TODO(),
    colors: TextFieldColors = TODO(),
) {}

// ------------------------------------------------------------------ pickers
interface SelectableDates {
    fun isSelectableDate(utcTimeMillis: Long): Boolean = true
    fun isSelectableYear(year: Int): Boolean = true
}
class DatePickerState { val selectedDateMillis: Long? get() = TODO() }
class DateRangePickerState { val selectedStartDateMillis: Long? get() = TODO(); val selectedEndDateMillis: Long? get() = TODO() }
class TimePickerState { val hour: Int get() = TODO(); val minute: Int get() = TODO() }
@ExperimentalMaterial3Api @Composable fun rememberDatePickerState(initialSelectedDateMillis: Long? = null, initialDisplayedMonthMillis: Long? = initialSelectedDateMillis, yearRange: IntRange = 1900..2100, selectableDates: SelectableDates = object : SelectableDates {}): DatePickerState = TODO()
@ExperimentalMaterial3Api @Composable fun rememberDateRangePickerState(initialSelectedStartDateMillis: Long? = null, initialSelectedEndDateMillis: Long? = null, initialDisplayedMonthMillis: Long? = initialSelectedStartDateMillis, yearRange: IntRange = 1900..2100, selectableDates: SelectableDates = object : SelectableDates {}): DateRangePickerState = TODO()
@ExperimentalMaterial3Api @Composable fun rememberTimePickerState(initialHour: Int = 0, initialMinute: Int = 0, is24Hour: Boolean = true): TimePickerState = TODO()
@ExperimentalMaterial3Api @Composable fun DatePickerDialog(onDismissRequest: () -> Unit, confirmButton: @Composable () -> Unit, modifier: Modifier = Modifier, dismissButton: @Composable (() -> Unit)? = null, shape: Shape = TODO(), tonalElevation: Dp = 6.dp, content: @Composable ColumnScope.() -> Unit) {}
@ExperimentalMaterial3Api @Composable fun DatePicker(state: DatePickerState, modifier: Modifier = Modifier, showModeToggle: Boolean = true) {}
@ExperimentalMaterial3Api @Composable fun DateRangePicker(state: DateRangePickerState, modifier: Modifier = Modifier, showModeToggle: Boolean = true) {}
@ExperimentalMaterial3Api @Composable fun TimePicker(state: TimePickerState, modifier: Modifier = Modifier) {}

// ------------------------------------------------------------------ bottom sheet
class SheetState
@ExperimentalMaterial3Api @Composable fun rememberModalBottomSheetState(skipPartiallyExpanded: Boolean = false): SheetState = TODO()
@ExperimentalMaterial3Api @Composable fun ModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = TODO(),
    sheetMaxWidth: Dp = 640.dp,
    shape: Shape = TODO(),
    containerColor: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified,
    tonalElevation: Dp = 0.dp,
    scrimColor: Color = Color.Unspecified,
    dragHandle: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {}

// ------------------------------------------------------------------ navigation
enum class DrawerValue { Closed, Open }
class DrawerState(initialValue: DrawerValue) {
    val isOpen: Boolean get() = TODO()
    suspend fun open() {}
    suspend fun close() {}
}
@Composable fun rememberDrawerState(initialValue: DrawerValue, confirmStateChange: (DrawerValue) -> Boolean = { true }): DrawerState = TODO()
@Composable fun ModalNavigationDrawer(
    drawerContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    drawerState: DrawerState = rememberDrawerState(DrawerValue.Closed),
    gesturesEnabled: Boolean = true,
    scrimColor: Color = Color.Unspecified,
    content: @Composable () -> Unit,
) {}
@Composable fun ModalDrawerSheet(
    modifier: Modifier = Modifier,
    drawerShape: Shape = TODO(),
    drawerContainerColor: Color = Color.Unspecified,
    drawerContentColor: Color = Color.Unspecified,
    drawerTonalElevation: Dp = 0.dp,
    windowInsets: WindowInsets = TODO(),
    content: @Composable ColumnScope.() -> Unit,
) {}
class NavigationDrawerItemColors
object NavigationDrawerItemDefaults {
    @Composable fun colors(
        selectedContainerColor: Color = Color.Unspecified, unselectedContainerColor: Color = Color.Unspecified, selectedIconColor: Color = Color.Unspecified,
        unselectedIconColor: Color = Color.Unspecified, selectedTextColor: Color = Color.Unspecified, unselectedTextColor: Color = Color.Unspecified,
        selectedBadgeColor: Color = Color.Unspecified, unselectedBadgeColor: Color = Color.Unspecified,
    ): NavigationDrawerItemColors = TODO()
}
@Composable fun NavigationDrawerItem(
    label: @Composable () -> Unit,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
    badge: (@Composable () -> Unit)? = null,
    shape: Shape = TODO(),
    colors: NavigationDrawerItemColors = NavigationDrawerItemDefaults.colors(),
) {}
@Composable fun NavigationBar(modifier: Modifier = Modifier, containerColor: Color = Color.Unspecified, contentColor: Color = Color.Unspecified, tonalElevation: Dp = 3.dp, windowInsets: WindowInsets = TODO(), content: @Composable RowScope.() -> Unit) {}
class NavigationBarItemColors
object NavigationBarItemDefaults {
    @Composable fun colors(
        selectedIconColor: Color = Color.Unspecified, selectedTextColor: Color = Color.Unspecified, indicatorColor: Color = Color.Unspecified,
        unselectedIconColor: Color = Color.Unspecified, unselectedTextColor: Color = Color.Unspecified,
        disabledIconColor: Color = Color.Unspecified, disabledTextColor: Color = Color.Unspecified,
    ): NavigationBarItemColors = TODO()
}
@Composable fun RowScope.NavigationBarItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: @Composable (() -> Unit)? = null,
    alwaysShowLabel: Boolean = true,
    colors: NavigationBarItemColors = NavigationBarItemDefaults.colors(),
) {}
@Composable fun NavigationRail(
    modifier: Modifier = Modifier,
    containerColor: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified,
    header: @Composable (ColumnScope.() -> Unit)? = null,
    windowInsets: WindowInsets = TODO(),
    content: @Composable ColumnScope.() -> Unit,
) {}
class NavigationRailItemColors
object NavigationRailItemDefaults {
    @Composable fun colors(
        selectedIconColor: Color = Color.Unspecified, selectedTextColor: Color = Color.Unspecified, indicatorColor: Color = Color.Unspecified,
        unselectedIconColor: Color = Color.Unspecified, unselectedTextColor: Color = Color.Unspecified,
        disabledIconColor: Color = Color.Unspecified, disabledTextColor: Color = Color.Unspecified,
    ): NavigationRailItemColors = TODO()
}
@Composable fun NavigationRailItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: @Composable (() -> Unit)? = null,
    alwaysShowLabel: Boolean = true,
    colors: NavigationRailItemColors = NavigationRailItemDefaults.colors(),
) {}
