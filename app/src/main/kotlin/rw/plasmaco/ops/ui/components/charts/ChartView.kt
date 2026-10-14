package rw.plasmaco.ops.ui.components.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.TableRows
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import rw.plasmaco.ops.domain.analytics.ChartKind
import rw.plasmaco.ops.domain.analytics.ChartSpec
import rw.plasmaco.ops.domain.analytics.DrillTarget
import rw.plasmaco.ops.domain.analytics.Series
import rw.plasmaco.ops.domain.analytics.SeriesRole
import rw.plasmaco.ops.domain.units.Formatting
import rw.plasmaco.ops.ui.components.LegendItem
import rw.plasmaco.ops.ui.components.TextLink
import rw.plasmaco.ops.ui.theme.ChartColors
import rw.plasmaco.ops.ui.theme.Plasmaco
import java.math.BigDecimal
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

fun ChartColors.forRole(role: SeriesRole): Color = when (role) {
    SeriesRole.PRIMARY -> primary
    SeriesRole.CONTEXT -> context
    SeriesRole.TARGET -> target
    SeriesRole.NEGATIVE -> negative
    SeriesRole.CATEGORY_1 -> categorical[0]
    SeriesRole.CATEGORY_2 -> categorical[1]
    SeriesRole.CATEGORY_3 -> categorical[2]
    SeriesRole.CATEGORY_4 -> categorical[3]
    SeriesRole.CATEGORY_5 -> categorical[4]
    SeriesRole.OTHER -> other
}

private fun fmt(v: BigDecimal, unit: String): String = when (unit) {
    "%" -> Formatting.number(v, 1) + "%"
    "min" -> Formatting.number(v, 0) + " min"
    else -> Formatting.number(v, if (v.abs() >= BigDecimal(100)) 0 else 1) + " " + unit
}

/**
 * Renders any [ChartSpec] in the template's chart language: soft sage context bars with the selected bar in teal,
 * a dark teal tooltip pill, light grey axis text, no heavy gridlines. Tapping selects a category; "View records" drills
 * into the data behind it. A table view of the same values is one tap away for screen-reader and print use.
 */
@Composable
fun ChartView(spec: ChartSpec, modifier: Modifier = Modifier, onDrill: ((DrillTarget) -> Unit)? = null, height: androidx.compose.ui.unit.Dp = 220.dp) {
    var asTable by rememberSaveable(spec.id) { mutableStateOf(false) }
    var selected by remember(spec) { mutableStateOf<Int?>(null) }
    val colors = Plasmaco.colors.chart
    Column(modifier.fillMaxWidth()) {
        if (spec.isEmpty) {
            Box(
                Modifier.fillMaxWidth().height(120.dp).clip(Plasmaco.shapes.inset).background(Plasmaco.colors.surfaceInset)
                    .semantics { contentDescription = spec.accessibleSummary() },
                contentAlignment = Alignment.Center,
            ) { Text(spec.emptyReason ?: "No data", style = Plasmaco.type.bodySmall, color = Plasmaco.colors.textSecondary) }
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (spec.series.size > 1 || spec.reference != null) {
                    spec.series.forEach { s -> LegendItem(colors.forRole(s.role), s.name, line = s.asLine) }
                    spec.referenceLabel?.let { LegendItem(colors.target, "$it (${Formatting.number(spec.reference!!, 0)}${spec.unit})", line = true) }
                }
            }
            Row(
                Modifier.clip(Plasmaco.shapes.chip).clickable(role = Role.Switch) { asTable = !asTable }.padding(8.dp)
                    .semantics { contentDescription = if (asTable) "Show as chart" else "Show as table" },
                verticalAlignment = Alignment.CenterVertically,
            ) { Icon(if (asTable) Icons.Outlined.BarChart else Icons.Outlined.TableRows, null, tint = Plasmaco.colors.textSecondary) }
        }
        Spacer(Modifier.height(8.dp))
        if (asTable) ChartTable(spec) else when (spec.kind) {
            ChartKind.HBAR, ChartKind.STACKED_HBAR -> HorizontalBars(spec, selected, { selected = it })
            else -> VerticalChart(spec, height, selected, { selected = it })
        }
        val sel = selected
        if (!asTable && sel != null && sel in spec.xLabels.indices) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(spec.xLabels[sel], style = Plasmaco.type.labelStrong, color = Plasmaco.colors.textPrimary)
                    spec.series.forEach { s ->
                        val v = s.values.getOrNull(sel)
                        Text("${s.name}: ${v?.let { fmt(it, spec.unit) } ?: "no data"}", style = Plasmaco.type.label, color = Plasmaco.colors.textSecondary)
                    }
                }
                val drill = spec.drill.getOrNull(sel)
                if (onDrill != null && drill != null) TextLink("View records", { onDrill(drill) })
            }
        }
    }
}

@Composable
private fun ChartTable(spec: ChartSpec) {
    Column(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
        Row(Modifier.fillMaxWidth().background(Plasmaco.colors.primaryTint).padding(8.dp)) {
            Text("Category", style = Plasmaco.type.tableHeader, color = Plasmaco.colors.primary, modifier = Modifier.weight(1.4f))
            spec.series.forEach { Text("${it.name} (${spec.unit})", style = Plasmaco.type.tableHeader, color = Plasmaco.colors.primary, modifier = Modifier.weight(1f), maxLines = 2) }
        }
        Column(Modifier.fillMaxWidth().heightIn(max = 280.dp).then(Modifier)) {
            spec.xLabels.forEachIndexed { i, label ->
                if (spec.series.all { it.values.getOrNull(i) == null }) return@forEachIndexed
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
                    Text(label, style = Plasmaco.type.tableCell, modifier = Modifier.weight(1.4f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    spec.series.forEach { s -> Text(s.values.getOrNull(i)?.let { Formatting.number(it, 1) } ?: "—", style = Plasmaco.type.tableCell, modifier = Modifier.weight(1f)) }
                }
            }
        }
    }
}

private fun niceTicks(lo0: Double, hi0: Double, count: Int = 4): List<Double> {
    val lo = min(0.0, lo0)
    val hi = if (hi0 <= lo) lo + 1 else hi0
    val raw = (hi - lo) / count
    val mag = 10.0.pow(floor(log10(raw)))
    val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * mag }.first { it >= raw }
    val start = floor(lo / step) * step
    val end = ceil(hi / step) * step
    return generateSequence(start) { it + step }.takeWhile { it <= end + step / 2 }.toList()
}

@Composable
private fun VerticalChart(spec: ChartSpec, height: androidx.compose.ui.unit.Dp, selected: Int?, onSelect: (Int?) -> Unit) {
    val colors = Plasmaco.colors.chart
    val axisStyle = Plasmaco.type.caption.copy(color = colors.axisText)
    val measurer = rememberTextMeasurer()
    val n = spec.xLabels.size
    val bars = spec.series.filter { !it.asLine }
    val lines = spec.series.filter { it.asLine }
    val stacked = spec.kind == ChartKind.STACKED_BAR
    val diverging = spec.kind == ChartKind.DIVERGING_BAR
    val singleBarSeries = bars.size == 1 && !stacked
    val allValues = buildList {
        for (i in 0 until n) {
            if (stacked) add(bars.sumOf { it.values[i]?.toDouble()?.coerceAtLeast(0.0) ?: 0.0 }) else bars.forEach { s -> s.values[i]?.let { add(it.toDouble()) } }
            lines.forEach { s -> s.values[i]?.let { add(it.toDouble()) } }
        }
        spec.reference?.let { add(it.toDouble()) }
    }
    val ticks = niceTicks(allValues.minOrNull() ?: 0.0, allValues.maxOrNull() ?: 1.0)
    val lo = ticks.first()
    val hi = ticks.last()

    val currentSelected by rememberUpdatedState(selected)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val minSlot = 18.dp
        val axisW = 40.dp
        val needed = minSlot * n + axisW
        val scroll = needed > maxWidth
        val chartWidth = if (scroll) needed else maxWidth
        val scrollState = rememberScrollState()
        Box(Modifier.fillMaxWidth().then(if (scroll) Modifier.horizontalScroll(scrollState) else Modifier)) {
            Canvas(
                Modifier.width(chartWidth).height(height)
                    .semantics { contentDescription = spec.accessibleSummary() }
                    .pointerInput(spec) {
                        detectTapGestures { pos ->
                            val left = axisW.toPx()
                            val slot = (size.width - left) / max(1, n)
                            val i = ((pos.x - left) / slot).toInt()
                            onSelect(if (i in 0 until n && i != currentSelected) i else null)
                        }
                    },
            ) {
                val left = axisW.toPx()
                val top = 8.dp.toPx()
                val bottom = size.height - 22.dp.toPx()
                val slot = (size.width - left) / max(1, n)
                fun y(v: Double) = (bottom - (v - lo) / (hi - lo) * (bottom - top)).toFloat()
                // Grid and y ticks
                ticks.forEach { t ->
                    val yy = y(t)
                    drawLine(if (t == 0.0) colors.axisText.copy(alpha = 0.5f) else colors.grid, Offset(left, yy), Offset(size.width, yy), strokeWidth = 1f)
                    val label = measurer.measure(Formatting.compact(BigDecimal.valueOf(t)) + if (spec.unit == "%") "%" else "", axisStyle)
                    drawText(label, topLeft = Offset(left - label.size.width - 6.dp.toPx(), yy - label.size.height / 2))
                }
                // Bars
                val groupW = slot * 0.62f
                val barW = if (stacked || diverging || bars.size <= 1) groupW else groupW / bars.size
                val radius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                for (i in 0 until n) {
                    val cx = left + slot * i + slot / 2
                    val isSel = selected == i
                    if (stacked) {
                        var acc = 0.0
                        bars.forEach { s ->
                            val v = s.values[i]?.toDouble() ?: return@forEach
                            if (v <= 0) return@forEach
                            val yTop = y(acc + v); val yBot = y(acc)
                            drawRoundRect(colors.forRole(s.role).copy(alpha = if (selected == null || isSel) 1f else 0.45f), Offset(cx - barW / 2, yTop), Size(barW, max(1f, yBot - yTop - 2f)), radius)
                            acc += v
                        }
                    } else {
                        bars.forEachIndexed { k, s ->
                            val v = s.values[i]?.toDouble() ?: return@forEachIndexed
                            val a = y(0.0); val b = y(v)
                            val x = if (diverging) cx - barW / 2 else cx - groupW / 2 + k * barW
                            // Template treatment: one series = sage context bars with the selected one in teal.
                            val color = if (singleBarSeries && s.role == SeriesRole.PRIMARY) (if (selected == null || isSel) colors.primary else colors.context) else colors.forRole(s.role).copy(alpha = if (selected == null || isSel) 1f else 0.45f)
                            drawRoundRect(color, Offset(x + 1f, min(a, b)), Size(max(1f, barW - 2f), max(1f, abs(a - b))), radius)
                        }
                    }
                }
                // Lines (target dashed)
                lines.forEach { s ->
                    val path = Path()
                    var drawing = false
                    for (i in 0 until n) {
                        val v = s.values[i]
                        if (v == null) { drawing = false; continue }
                        val px = left + slot * i + slot / 2
                        val py = y(v.toDouble())
                        if (drawing) path.lineTo(px, py) else path.moveTo(px, py)
                        drawing = true
                    }
                    val dashed = s.role == SeriesRole.TARGET
                    drawPath(path, colors.forRole(s.role), style = Stroke(width = if (dashed) 1.5.dp.toPx() else 2.dp.toPx(), cap = StrokeCap.Round, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null))
                    if (n <= 40) for (i in 0 until n) s.values[i]?.let { v -> drawCircle(colors.forRole(s.role), 3.dp.toPx(), Offset(left + slot * i + slot / 2, y(v.toDouble()))) }
                }
                spec.reference?.let { ref -> drawLine(colors.target, Offset(left, y(ref.toDouble())), Offset(size.width, y(ref.toDouble())), 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))) }
                // X labels, thinned to avoid collisions; the selected one is underlined (template's "Aug").
                val maxLabels = max(1f, (size.width - left) / 44.dp.toPx())
                val every = ceil(n / maxLabels.toDouble()).toInt().coerceAtLeast(1)
                for (i in 0 until n) {
                    if (i % every != 0 && selected != i) continue
                    val style = if (selected == i) axisStyle.copy(color = colors.primary) else axisStyle
                    val label = measurer.measure(spec.xLabels[i], style)
                    val lx = left + slot * i + slot / 2 - label.size.width / 2
                    drawText(label, topLeft = Offset(lx, bottom + 6.dp.toPx()))
                    if (selected == i) drawLine(colors.primary, Offset(lx, bottom + 6.dp.toPx() + label.size.height + 1f), Offset(lx + label.size.width, bottom + 6.dp.toPx() + label.size.height + 1f), 2f)
                }
                selected?.let { tooltip(this, spec, it, left + slot * it + slot / 2, top, colors, measurer, axisStyle) }
            }
        }
    }
}

private fun tooltip(scope: DrawScope, spec: ChartSpec, i: Int, x: Float, top: Float, colors: ChartColors, measurer: androidx.compose.ui.text.TextMeasurer, base: TextStyle) {
    with(scope) {
        val lines = spec.series.mapNotNull { s -> s.values.getOrNull(i)?.let { "${s.name}: ${Formatting.compact(it)}" } }
        if (lines.isEmpty()) return
        val style = base.copy(color = colors.tooltipText)
        val layouts = lines.map { measurer.measure(it, style) }
        val pad = 8.dp.toPx()
        val w = layouts.maxOf { it.size.width } + pad * 2
        val h = layouts.sumOf { it.size.height } + pad * 2
        val bx = (x - w / 2).coerceIn(0f, size.width - w)
        drawRoundRect(colors.tooltipBackground, Offset(bx, top), Size(w, h.toFloat()), CornerRadius(8.dp.toPx()))
        var ty = top + pad
        layouts.forEach { drawText(it, topLeft = Offset(bx + pad, ty)); ty += it.size.height }
    }
}

/** Horizontal bars as composables, so long product names wrap and each row is a proper touch target. */
@Composable
private fun HorizontalBars(spec: ChartSpec, selected: Int?, onSelect: (Int?) -> Unit) {
    val colors = Plasmaco.colors.chart
    val stacked = spec.kind == ChartKind.STACKED_HBAR
    val totals = spec.xLabels.indices.map { i -> if (stacked) spec.series.sumOf { it.values[i]?.toDouble() ?: 0.0 } else spec.series.maxOf { it.values[i]?.toDouble() ?: 0.0 } }
    val maxV = max(totals.maxOrNull() ?: 1.0, spec.reference?.toDouble() ?: 0.0).coerceAtLeast(1e-9)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        spec.xLabels.forEachIndexed { i, label ->
            val total = totals[i]
            val desc = "$label: " + spec.series.joinToString { s -> "${s.name} ${s.values[i]?.let { fmt(it, spec.unit) } ?: "no data"}" }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 40.dp).clip(Plasmaco.shapes.chip)
                    .background(if (selected == i) Plasmaco.colors.primaryTint else Color.Transparent)
                    .clickable(role = Role.Button) { onSelect(if (selected == i) null else i) }
                    .padding(horizontal = 6.dp, vertical = 4.dp)
                    .semantics(mergeDescendants = true) { contentDescription = desc },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, style = Plasmaco.type.label, color = Plasmaco.colors.textPrimary, modifier = Modifier.weight(0.38f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Canvas(Modifier.weight(0.47f).height(14.dp)) {
                    var acc = 0.0
                    val r = CornerRadius(4.dp.toPx())
                    drawRoundRect(colors.grid, Offset.Zero, Size(size.width, size.height), r)
                    spec.series.forEach { s ->
                        val v = s.values[i]?.toDouble() ?: return@forEach
                        val start = if (stacked) acc else 0.0
                        val x0 = (start / maxV * size.width).toFloat()
                        val w = (v / maxV * size.width).toFloat()
                        drawRoundRect(colors.forRole(s.role), Offset(x0, 0f), Size(max(2f, w - if (stacked) 2f else 0f), size.height), r)
                        if (stacked) acc += v
                    }
                    spec.reference?.let { ref ->
                        val x = (ref.toDouble() / maxV * size.width).toFloat()
                        drawLine(colors.target, Offset(x, -4f), Offset(x, size.height + 4f), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f)))
                    }
                }
                Text(fmt(BigDecimal.valueOf(total), spec.unit), style = Plasmaco.type.label, color = Plasmaco.colors.textSecondary, modifier = Modifier.weight(0.15f).padding(start = 6.dp), maxLines = 1)
            }
        }
    }
}
