package rw.plasmaco.ops.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.LocalShipping
import androidx.compose.material.icons.outlined.MoveToInbox
import androidx.compose.material.icons.outlined.NotificationImportant
import androidx.compose.material.icons.outlined.PendingActions
import androidx.compose.material.icons.outlined.PrecisionManufacturing
import androidx.compose.material.icons.outlined.Scale
import androidx.compose.material.icons.outlined.TimerOff
import androidx.compose.material.icons.outlined.TrackChanges
import androidx.compose.material.icons.outlined.Warehouse
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import rw.plasmaco.ops.domain.analytics.Alert
import rw.plasmaco.ops.domain.analytics.ChartSpec
import rw.plasmaco.ops.domain.analytics.DrillTarget
import rw.plasmaco.ops.domain.analytics.Kpi
import rw.plasmaco.ops.domain.metrics.MetricValue
import rw.plasmaco.ops.ui.components.KpiCard
import rw.plasmaco.ops.ui.components.PriorityText
import rw.plasmaco.ops.ui.components.SectionCard
import rw.plasmaco.ops.ui.components.Tone
import rw.plasmaco.ops.ui.components.charts.ChartView
import rw.plasmaco.ops.ui.components.toneColors
import rw.plasmaco.ops.ui.theme.Plasmaco

/** Icon and tone per KPI key (keys come from the domain's dashboard builder). */
fun kpiIcon(key: String): Pair<ImageVector, Tone> = when (key) {
    "gross_output" -> Icons.Outlined.PrecisionManufacturing to Tone.PRIMARY
    "target" -> Icons.Outlined.Flag to Tone.NEUTRAL
    "target_achievement" -> Icons.Outlined.TrackChanges to Tone.SUCCESS
    "good_output" -> Icons.Outlined.CheckCircle to Tone.SUCCESS
    "rejected_output" -> Icons.Outlined.Cancel to Tone.DANGER
    "downtime" -> Icons.Outlined.TimerOff to Tone.WARNING
    "rm_consumption" -> Icons.Outlined.Scale to Tone.PRIMARY
    "fg_receipts" -> Icons.Outlined.MoveToInbox to Tone.SUCCESS
    "fg_dispatches" -> Icons.Outlined.LocalShipping to Tone.INFO
    "fg_stock" -> Icons.Outlined.Inventory2 to Tone.PRIMARY
    "rm_stock" -> Icons.Outlined.Warehouse to Tone.PRIMARY
    "pending_approvals" -> Icons.Outlined.PendingActions to Tone.INFO
    "reorder_alerts" -> Icons.Outlined.NotificationImportant to Tone.WARNING
    else -> Icons.Outlined.Insights to Tone.PRIMARY
}

/** KPI tiles in an adaptive grid (2 columns on phones, up to 4 on tablets). No-data tiles say why instead of showing 0. */
@Composable
fun KpiGrid(kpis: List<Kpi>, onOpen: ((Kpi) -> Unit)?, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = (maxWidth / 170.dp).toInt().coerceIn(2, 4)
        Column(verticalArrangement = Arrangement.spacedBy(Plasmaco.dimens.cardGap)) {
            kpis.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(Plasmaco.dimens.cardGap)) {
                    row.forEach { k ->
                        val (icon, tone) = kpiIcon(k.metricKey)
                        val t = toneColors(tone)
                        val (value, unit) = Fmt.metric(k.value)
                        KpiCard(
                            label = k.label, value = value, unit = unit, modifier = Modifier.weight(1f),
                            icon = icon, iconTint = t.content, iconBackground = t.container,
                            noDataReason = (k.value as? MetricValue.NoData)?.reason,
                            onClick = onOpen?.let { open -> { open(k) } },
                        )
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
fun ChartCard(spec: ChartSpec, onDrill: ((DrillTarget) -> Unit)?, modifier: Modifier = Modifier, subtitle: String? = null) {
    SectionCard(spec.title, modifier, subtitle = subtitle ?: spec.unit.takeIf { it.isNotBlank() }?.let { "Unit: $it" }) {
        ChartView(spec, onDrill = onDrill)
    }
}

@Composable
fun AlertsCard(alerts: List<Alert>, onOpen: (Alert) -> Unit, onSeeAll: (() -> Unit)?, modifier: Modifier = Modifier, limit: Int = 5) {
    SectionCard("Alerts", modifier, subtitle = if (alerts.isEmpty()) "Nothing needs attention" else "${alerts.size} open", onOpen = onSeeAll, openLabel = "See all alerts") {
        if (alerts.isEmpty()) {
            Text("No alerts for the selected period.", style = Plasmaco.type.bodySmall, color = Plasmaco.colors.textSecondary)
        }
        alerts.take(limit).forEachIndexed { i, a ->
            if (i > 0) RowDivider()
            ListRow(
                title = a.title,
                subtitle = listOfNotNull(a.message, a.date?.let { Fmt.short(it) }).joinToString(" · "),
                onClick = if (a.route() != null) ({ onOpen(a) }) else null,
            ) { PriorityText(a.type.label, a.severity.tone()) }
        }
        if (alerts.size > limit) {
            Spacer(Modifier.height(4.dp))
            Text("+${alerts.size - limit} more", style = Plasmaco.type.label, color = Plasmaco.colors.textSecondary)
        }
    }
}
