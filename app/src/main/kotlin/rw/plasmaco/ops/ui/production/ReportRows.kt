package rw.plasmaco.ops.ui.production

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.outlined.AssignmentLate
import androidx.compose.material.icons.outlined.AssignmentTurnedIn
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import rw.plasmaco.ops.domain.master.MasterData
import rw.plasmaco.ops.domain.production.ProductionReport
import rw.plasmaco.ops.domain.production.ReportStatus
import rw.plasmaco.ops.domain.units.UnitOfMeasure
import rw.plasmaco.ops.ui.common.Fmt
import rw.plasmaco.ops.ui.common.ListRow
import rw.plasmaco.ops.ui.common.tone
import rw.plasmaco.ops.ui.components.StatusBadge
import rw.plasmaco.ops.ui.components.Tone
import rw.plasmaco.ops.domain.common.sumDecimal

/** One production report in a list: reference, line · shift · date, good output and status. */
@Composable
fun ReportRow(report: ProductionReport, master: MasterData, onClick: () -> Unit, modifier: Modifier = Modifier, operatorName: String? = null) {
    val line = master.line(report.lineId)?.name ?: "Unknown line"
    val shift = master.shift(report.shiftId)?.name ?: "Unknown shift"
    val good = report.entries.filter { it.unit == UnitOfMeasure.METRE }.map { it.good }.sumDecimal()
    val icon = when (report.status) {
        ReportStatus.DRAFT -> Icons.Outlined.EditNote
        ReportStatus.RETURNED, ReportStatus.REOPENED, ReportStatus.REJECTED -> Icons.Outlined.AssignmentLate
        ReportStatus.APPROVED -> Icons.Outlined.AssignmentTurnedIn
        ReportStatus.SUBMITTED -> Icons.AutoMirrored.Outlined.Assignment
    }
    ListRow(
        title = report.reference,
        subtitle = buildString {
            append("$line · $shift · ${Fmt.short(report.date)}")
            if (report.entries.isNotEmpty()) append("\n${report.entries.size} product(s) · good ${Fmt.qty(good, UnitOfMeasure.METRE)}")
            if (operatorName != null) append(" · $operatorName")
        },
        icon = icon,
        iconTone = report.status.tone().takeIf { it != Tone.NEUTRAL } ?: Tone.PRIMARY,
        onClick = onClick,
        modifier = modifier,
    ) { StatusBadge(report.status.label, report.status.tone()) }
}
