package rw.plasmaco.ops.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Factory
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import rw.plasmaco.ops.domain.analytics.Analytics
import rw.plasmaco.ops.domain.common.DateRange
import rw.plasmaco.ops.domain.master.MasterData
import rw.plasmaco.ops.ui.components.AppFilterChip
import rw.plasmaco.ops.ui.components.DateRangeDialog
import rw.plasmaco.ops.ui.components.DropdownChip
import rw.plasmaco.ops.ui.components.FilterChipRow
import rw.plasmaco.ops.ui.theme.Plasmaco
import java.time.LocalDate

/** A chip that opens a menu; "All" clears the choice. */
@Composable
fun <T> MenuChip(
    label: String,
    allLabel: String,
    options: List<T>,
    selected: T?,
    itemLabel: (T) -> String,
    onSelect: (T?) -> Unit,
    icon: ImageVector,
    includeAll: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        DropdownChip(selected?.let(itemLabel) ?: allLabel, { open = true }, icon = icon, description = "$label: ${selected?.let(itemLabel) ?: allLabel}. Change")
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Plasmaco.colors.surface) {
            if (includeAll) DropdownMenuItem(text = { Text(allLabel, style = Plasmaco.type.body) }, onClick = { open = false; onSelect(null) })
            options.forEach { o ->
                DropdownMenuItem(text = { Text(itemLabel(o), style = Plasmaco.type.body) }, onClick = { open = false; onSelect(o) })
            }
        }
    }
}

/**
 * The template's filter row ("Monthly ⌄" chip and filter chips), extended with the requirement's dimensions:
 * period, line, product and shift, plus an approved-only switch. Scrolls horizontally on narrow screens.
 */
@Composable
fun FiltersBar(
    selection: FilterSelection,
    master: MasterData,
    today: LocalDate,
    onChange: (FilterSelection) -> Unit,
    modifier: Modifier = Modifier,
    showProduction: Boolean = true,
) {
    var customOpen by remember { mutableStateOf(false) }
    val range = selection.range(today)
    FilterChipRow(modifier) {
        MenuChip(
            "Period", selection.period.label, Period.entries, selection.period,
            { p -> if (p == Period.CUSTOM && selection.period == Period.CUSTOM) "${Fmt.short(range.start)} – ${Fmt.short(range.end)}" else p.label },
            { p ->
                when (p) {
                    null -> Unit
                    Period.CUSTOM -> customOpen = true
                    else -> onChange(selection.copy(period = p))
                }
            },
            Icons.Outlined.CalendarMonth, includeAll = false,
        )
        if (showProduction) {
            MenuChip("Line", "All lines", master.lines.filter { it.active }, master.lines.firstOrNull { it.id == selection.lineId }, { it.name }, { onChange(selection.copy(lineId = it?.id)) }, Icons.Outlined.Factory)
            MenuChip("Shift", "All shifts", master.shifts.filter { it.active }, master.shifts.firstOrNull { it.id == selection.shiftId }, { it.name }, { onChange(selection.copy(shiftId = it?.id)) }, Icons.Outlined.Schedule)
        }
        MenuChip(
            "Product", "All products", master.products.filter { it.active }, master.products.firstOrNull { it.id == selection.productId },
            { Analytics.shortName(it.name) }, { onChange(selection.copy(productId = it?.id)) }, Icons.Outlined.Category,
        )
        if (showProduction) {
            AppFilterChip("Approved only", selection.approvedOnly, { onChange(selection.copy(approvedOnly = !selection.approvedOnly)) }, icon = Icons.Outlined.Verified)
        }
    }
    if (customOpen) {
        DateRangeDialog(range.start, range.end, onDismiss = { customOpen = false }) { s, e ->
            customOpen = false
            onChange(selection.copy(period = Period.CUSTOM, custom = DateRange(s, e)))
        }
    }
}
