package rw.plasmaco.ops.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import rw.plasmaco.ops.ui.theme.Plasmaco
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import androidx.compose.foundation.lazy.LazyColumn

/** Label placed above the field, as in the template's forms (never a floating label). */
@Composable
fun FieldLabel(text: String, required: Boolean = false, modifier: Modifier = Modifier) {
    Row(modifier.padding(bottom = 6.dp)) {
        Text(text, style = Plasmaco.type.labelStrong, color = Plasmaco.colors.textPrimary)
        if (required) Text(" *", style = Plasmaco.type.labelStrong, color = Plasmaco.colors.danger)
    }
}

@Composable
fun FieldError(message: String?) {
    if (message == null) return
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.ErrorOutline, null, tint = Plasmaco.colors.danger, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(message, style = Plasmaco.type.label, color = Plasmaco.colors.danger)
    }
}

@Composable
fun FieldHint(message: String?) {
    if (message == null) return
    Text(message, style = Plasmaco.type.label, color = Plasmaco.colors.textSecondary, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Plasmaco.colors.primary,
    unfocusedBorderColor = Plasmaco.colors.border,
    errorBorderColor = Plasmaco.colors.danger,
    focusedContainerColor = Plasmaco.colors.surface,
    unfocusedContainerColor = Plasmaco.colors.surface,
    disabledContainerColor = Plasmaco.colors.surfaceInset,
    disabledBorderColor = Plasmaco.colors.border,
    cursorColor = Plasmaco.colors.primary,
    focusedTextColor = Plasmaco.colors.textPrimary,
    unfocusedTextColor = Plasmaco.colors.textPrimary,
)

@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    error: String? = null,
    hint: String? = null,
    required: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    enabled: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    isPassword: Boolean = false,
) {
    Column(modifier.fillMaxWidth()) {
        FieldLabel(label, required)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().heightIn(min = Plasmaco.dimens.inputHeight).semantics { if (error != null) this.error(error) },
            placeholder = placeholder?.let { { Text(it, style = Plasmaco.type.body, color = Plasmaco.colors.textTertiary) } },
            isError = error != null,
            singleLine = singleLine,
            minLines = minLines,
            enabled = enabled,
            textStyle = Plasmaco.type.body,
            shape = Plasmaco.shapes.input,
            colors = fieldColors(),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
            visualTransformation = if (isPassword) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        )
        FieldError(error)
        if (error == null) FieldHint(hint)
    }
}

/**
 * Decimal input kept as text so partially typed values ("12.") survive recomposition; parsing to BigDecimal happens
 * in the ViewModel with the domain's validation. The unit is shown as a suffix so no figure is ever unit-less.
 */
@Composable
fun NumericField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    unit: String?,
    modifier: Modifier = Modifier,
    error: String? = null,
    hint: String? = null,
    required: Boolean = false,
    enabled: Boolean = true,
    imeAction: ImeAction = ImeAction.Next,
) {
    Column(modifier.fillMaxWidth()) {
        FieldLabel(label, required)
        OutlinedTextField(
            value = value,
            onValueChange = { new -> if (new.isEmpty() || new.matches(Regex("""-?\d{0,9}([.,]\d{0,3})?"""))) onValueChange(new.replace(',', '.')) },
            modifier = Modifier.fillMaxWidth().heightIn(min = Plasmaco.dimens.inputHeight).semantics { if (error != null) this.error(error) },
            isError = error != null,
            singleLine = true,
            enabled = enabled,
            textStyle = Plasmaco.type.number,
            shape = Plasmaco.shapes.input,
            colors = fieldColors(),
            suffix = unit?.let { { Text(it, style = Plasmaco.type.bodySmall, color = Plasmaco.colors.textSecondary) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = imeAction),
        )
        FieldError(error)
        if (error == null) FieldHint(hint)
    }
}

/** Read-only field that opens a picker; shared look for dropdowns, dates and times. */
@Composable
private fun PickerField(
    label: String,
    text: String?,
    placeholder: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier,
    error: String?,
    required: Boolean,
    enabled: Boolean,
) {
    val c = Plasmaco.colors
    Column(modifier.fillMaxWidth()) {
        FieldLabel(label, required)
        Row(
            Modifier.fillMaxWidth().height(Plasmaco.dimens.inputHeight).clip(Plasmaco.shapes.input)
                .border(BorderStroke(1.dp, if (error != null) c.danger else c.border), Plasmaco.shapes.input)
                .clickable(enabled = enabled, role = Role.DropdownList, onClick = onClick)
                .padding(horizontal = 14.dp)
                .semantics { contentDescription = "$label: ${text ?: "not set"}"; if (error != null) this.error(error) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text ?: placeholder, style = Plasmaco.type.body, color = if (text == null) c.textTertiary else if (enabled) c.textPrimary else c.textSecondary,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Icon(icon, null, tint = c.textSecondary, modifier = Modifier.size(20.dp))
        }
        FieldError(error)
    }
}

@Composable
fun <T> DropdownField(
    label: String,
    options: List<T>,
    selected: T?,
    onSelected: (T) -> Unit,
    itemLabel: (T) -> String,
    modifier: Modifier = Modifier,
    placeholder: String = "Choose…",
    error: String? = null,
    required: Boolean = false,
    enabled: Boolean = true,
    itemSupporting: ((T) -> String?)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        PickerField(label, selected?.let(itemLabel), placeholder, Icons.Outlined.ExpandMore, { expanded = true }, Modifier, error, required, enabled && options.isNotEmpty())
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 360.dp)) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(itemLabel(option), style = Plasmaco.type.body)
                            itemSupporting?.invoke(option)?.let { Text(it, style = Plasmaco.type.label, color = Plasmaco.colors.textSecondary) }
                        }
                    },
                    onClick = { onSelected(option); expanded = false },
                    modifier = Modifier.semantics { stateDescription = if (option == selected) "Selected" else "" },
                )
            }
        }
    }
}

/**
 * Picker for long lists (the product catalogue has 132 items): opens a sheet with a search box. Matching ignores
 * case and spaces, so "dn110pn10" finds "HDPE PIPE PE-PE100 DN110 PN10".
 */
@Composable
fun <T> SearchablePickerField(
    label: String,
    options: List<T>,
    selected: T?,
    onSelected: (T) -> Unit,
    itemLabel: (T) -> String,
    modifier: Modifier = Modifier,
    placeholder: String = "Choose…",
    error: String? = null,
    required: Boolean = false,
    enabled: Boolean = true,
    itemSupporting: ((T) -> String?)? = null,
) {
    var open by remember { mutableStateOf(false) }
    PickerField(label, selected?.let(itemLabel), placeholder, Icons.Outlined.Search, { open = true }, modifier, error, required, enabled && options.isNotEmpty())
    if (open) {
        var query by remember { mutableStateOf("") }
        val key = query.lowercase().replace(" ", "")
        val shown = if (key.isEmpty()) options else options.filter { o ->
            itemLabel(o).lowercase().replace(" ", "").contains(key) || itemSupporting?.invoke(o)?.lowercase()?.replace(" ", "")?.contains(key) == true
        }
        AppBottomSheet(label, onDismiss = { open = false }, scrollable = false) {
            SearchField(query, { query = it }, "Search")
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(shown.size) { i ->
                    val o = shown[i]
                    Column(
                        Modifier.fillMaxWidth().clickable(role = Role.Button) { onSelected(o); open = false }
                            .padding(vertical = 10.dp, horizontal = 4.dp)
                            .semantics { stateDescription = if (o == selected) "Selected" else "" },
                    ) {
                        Text(itemLabel(o), style = Plasmaco.type.body, color = if (o == selected) Plasmaco.colors.primary else Plasmaco.colors.textPrimary)
                        itemSupporting?.invoke(o)?.let { Text(it, style = Plasmaco.type.label, color = Plasmaco.colors.textSecondary) }
                    }
                }
            }
            if (shown.isEmpty()) Text("Nothing matches \"$query\"", style = Plasmaco.type.bodySmall, color = Plasmaco.colors.textSecondary)
        }
    }
}

private val dateText = DateTimeFormatter.ofPattern("EEE d MMM yyyy")
private val timeText = DateTimeFormatter.ofPattern("HH:mm")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(
    label: String,
    value: LocalDate?,
    onValueChange: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    maxDate: LocalDate? = null,
    error: String? = null,
    required: Boolean = false,
    enabled: Boolean = true,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    PickerField(label, value?.format(dateText), "Choose a date", Icons.Outlined.CalendarMonth, { open = true }, modifier, error, required, enabled)
    if (open) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = value?.atStartOfDay()?.toInstant(ZoneOffset.UTC)?.toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    maxDate == null || !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isAfter(maxDate)
            },
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onValueChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeField(
    label: String,
    value: LocalTime?,
    onValueChange: (LocalTime) -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
    required: Boolean = false,
    enabled: Boolean = true,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    PickerField(label, value?.format(timeText), "Choose a time", Icons.Outlined.Schedule, { open = true }, modifier, error, required, enabled)
    if (open) {
        val state = rememberTimePickerState(initialHour = value?.hour ?: 6, initialMinute = value?.minute ?: 0, is24Hour = true)
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = { TextButton(onClick = { onValueChange(LocalTime.of(state.hour, state.minute)); open = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
            title = { Text(label, style = Plasmaco.type.cardTitle) },
            text = { TimePicker(state = state) },
        )
    }
}

/** Template's date filter chip opens this range picker ("Monthly", "This week"). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateRangeDialog(start: LocalDate, end: LocalDate, onDismiss: () -> Unit, onConfirm: (LocalDate, LocalDate) -> Unit) {
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = start.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
        initialSelectedEndDateMillis = end.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = state.selectedStartDateMillis != null,
                onClick = {
                    val s = state.selectedStartDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() } ?: return@TextButton
                    val e = state.selectedEndDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() } ?: s
                    onConfirm(s, e)
                },
            ) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) { DateRangePicker(state = state, modifier = Modifier.heightIn(max = 520.dp)) }
}

/** Rounded search field with a leading magnifier (template: "Search exam"). */
@Composable
fun SearchField(query: String, onQueryChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, onSearch: (() -> Unit)? = null) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        placeholder = { Text(placeholder, style = Plasmaco.type.bodySmall, color = Plasmaco.colors.textTertiary) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = Plasmaco.colors.textSecondary) },
        trailingIcon = if (query.isNotEmpty()) ({ IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Outlined.Close, contentDescription = "Clear search") } }) else null,
        singleLine = true,
        textStyle = Plasmaco.type.body,
        shape = Plasmaco.shapes.input,
        colors = fieldColors(),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch?.invoke() }),
    )
}
