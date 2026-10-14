package rw.plasmaco.ops.ui.components.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import rw.plasmaco.ops.ui.components.LegendItem
import rw.plasmaco.ops.ui.theme.Plasmaco
import kotlin.math.max

/** One slice of a share visual: label, value and colour. */
data class Share(val label: String, val value: Double, val color: Color)

private fun describe(shares: List<Share>, unit: String): String {
    val total = shares.sumOf { it.value }.takeIf { it > 0 } ?: return "No data"
    return shares.joinToString { "${it.label} ${"%.0f".format(it.value / total * 100)}% (${"%.0f".format(it.value)} $unit)" }
}

/**
 * Segmented semi-circle gauge with gaps (template's "Most Interested Subjects" gauge), centre total and a legend with
 * bar markers. Used for "output by product family" style shares. Maximum five segments; the rest folds into Other.
 */
@Composable
fun SegmentedGauge(shares: List<Share>, centerLabel: String, centerValue: String, unit: String, modifier: Modifier = Modifier) {
    val total = shares.sumOf { it.value }
    Column(modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "$centerLabel $centerValue. ${describe(shares, unit)}" }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth(0.8f).aspectRatio(2f), contentAlignment = Alignment.BottomCenter) {
            Canvas(Modifier.fillMaxWidth().aspectRatio(2f)) {
                val stroke = size.width * 0.11f
                val d = size.width - stroke
                val topLeft = Offset(stroke / 2, stroke / 2)
                val gap = 3f
                var start = 180f
                if (total <= 0) drawArc(Color.LightGray, 180f, 180f, false, topLeft, Size(d, d), style = Stroke(stroke))
                shares.forEach { s ->
                    val sweep = (s.value / total * 180).toFloat()
                    if (sweep > gap) drawArc(s.color, start + gap / 2, sweep - gap, false, topLeft, Size(d, d), style = Stroke(stroke, cap = StrokeCap.Butt))
                    start += sweep
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 4.dp)) {
                Text(centerLabel, style = Plasmaco.type.label, color = Plasmaco.colors.textSecondary)
                Text(centerValue, style = Plasmaco.type.kpiValue, color = Plasmaco.colors.textPrimary)
            }
        }
        Spacer(Modifier.height(12.dp))
        shares.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                pair.forEach { s -> LegendItem(s.color, "${s.label} — ${if (total > 0) "%.0f".format(s.value / total * 100) else "0"}%", Modifier.weight(1f)) }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * Proportion bar made of thin vertical stripes (template's "Comfortable Question Pattern"), with the share and label
 * printed above each part. Used for good vs rejected output.
 */
@Composable
fun StripeShareBar(shares: List<Share>, unit: String, modifier: Modifier = Modifier) {
    val total = shares.sumOf { it.value }
    Column(modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = describe(shares, unit) }) {
        Row(Modifier.fillMaxWidth()) {
            shares.forEach { s ->
                val w = if (total > 0) (s.value / total).toFloat() else 0f
                if (w > 0f) Column(Modifier.weight(max(w, 0.0001f))) {
                    Text("%.0f%%".format(w * 100), style = Plasmaco.type.kpiValueSmall, color = Plasmaco.colors.textPrimary)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Canvas(Modifier.fillMaxWidth().height(36.dp)) {
            if (total <= 0) return@Canvas
            val step = 5.dp.toPx()
            val stroke = 2.dp.toPx()
            var x0 = 0f
            shares.forEach { s ->
                val w = (s.value / total * size.width).toFloat()
                var x = x0 + stroke
                while (x < x0 + w - 1f) {
                    drawLine(s.color, Offset(x, 0f), Offset(x, size.height), stroke, cap = StrokeCap.Round)
                    x += step
                }
                x0 += w
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { shares.forEach { LegendItem(it.color, it.label) } }
    }
}

/**
 * Track with two filled segments (template's "Upgrade Conversion at a Glance"): e.g. finished-goods stock split into
 * in-stock and below-minimum metres, or receipts vs dispatches.
 */
@Composable
fun SplitProgressBar(parts: List<Share>, total: Double, modifier: Modifier = Modifier, unit: String = "") {
    val c = Plasmaco.colors
    Column(modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = describe(parts, unit) }) {
        Canvas(Modifier.fillMaxWidth().height(36.dp)) {
            val r = CornerRadius(10.dp.toPx())
            drawRoundRect(c.track, Offset.Zero, size, r)
            if (total <= 0) return@Canvas
            var end = size.width
            parts.reversed().forEach { p ->
                val w = (p.value / total * size.width).toFloat().coerceAtMost(end)
                if (w > 0) drawRoundRect(p.color, Offset(end - w, 0f), Size(w, size.height), r)
                end -= w + 3f
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            parts.forEach { p -> Row(verticalAlignment = Alignment.CenterVertically) { LegendItem(p.color, p.label); Spacer(Modifier.width(4.dp)); Text("%,.0f".format(p.value), style = Plasmaco.type.bodyStrong, color = c.textPrimary) } }
        }
    }
}

/** Heatmap of rounded cells on the template's six-step teal ramp (rows × columns, values 0..1). */
@Composable
fun HeatmapGrid(rows: List<String>, columns: List<String>, values: List<List<Double?>>, modifier: Modifier = Modifier, describeCell: (Int, Int) -> String) {
    val ramp = Plasmaco.colors.chart.heatRamp
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.forEachIndexed { r, label ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = Plasmaco.type.label, color = Plasmaco.colors.textPrimary, modifier = Modifier.weight(0.3f), maxLines = 1)
                Row(Modifier.weight(0.7f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    columns.indices.forEach { c ->
                        val v = values.getOrNull(r)?.getOrNull(c)
                        Canvas(Modifier.weight(1f).height(18.dp).semantics { contentDescription = describeCell(r, c) }) {
                            val color = if (v == null) Plasmaco_EMPTY else ramp[(v.coerceIn(0.0, 1.0) * (ramp.size - 1)).toInt()]
                            drawRoundRect(color, Offset.Zero, size, CornerRadius(4.dp.toPx()))
                        }
                    }
                }
            }
        }
    }
}

private val Plasmaco_EMPTY = Color(0x14000000)
