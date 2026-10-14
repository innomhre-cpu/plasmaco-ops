package rw.plasmaco.ops.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Factory
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import rw.plasmaco.ops.domain.master.ItemKind
import rw.plasmaco.ops.domain.reports.ReportType
import rw.plasmaco.ops.domain.security.Actor
import rw.plasmaco.ops.domain.security.Permission
import java.time.LocalDate

/** The seven main destinations (requirements §6), in drawer order. [inBottomBar] = shown in the phone bottom bar. */
enum class TopDestination(val route: String, val label: String, val icon: ImageVector, val inBottomBar: Boolean, val permission: Permission?) {
    DASHBOARD(Routes.DASHBOARD, "Dashboard", Icons.Outlined.Dashboard, true, Permission.DASHBOARD_VIEW),
    PRODUCTION(Routes.PRODUCTION, "Production", Icons.Outlined.Factory, true, Permission.REPORT_CREATE),
    HISTORY(Routes.HISTORY, "History", Icons.Outlined.History, true, null),
    INVENTORY(Routes.INVENTORY, "Inventory", Icons.Outlined.Inventory2, true, Permission.INVENTORY_VIEW),
    REPORTS(Routes.REPORTS, "Reports", Icons.Outlined.Assessment, true, Permission.REPORTS_EXPORT),
    NOTIFICATIONS(Routes.NOTIFICATIONS, "Notifications", Icons.Outlined.Notifications, false, null),
    SETTINGS(Routes.SETTINGS, "Settings", Icons.Outlined.Settings, false, null);

    fun visibleTo(actor: Actor?): Boolean = actor != null && (permission == null || actor.can(permission) ||
        (this == HISTORY && (actor.can(Permission.REPORT_VIEW_ALL) || actor.can(Permission.REPORT_VIEW_OWN))))

    val fullLabel: String
        get() = when (this) {
            PRODUCTION -> "Daily Production"
            HISTORY -> "Production History"
            REPORTS -> "Reports & Analytics"
            else -> label
        }
}

object Routes {
    const val SIGN_IN = "signin"
    const val SETUP = "setup"
    const val DASHBOARD = "dashboard"
    const val ANALYTICS = "analytics"
    const val PRODUCTION = "production"
    const val HISTORY = "history"
    const val APPROVALS = "approvals"
    const val INVENTORY = "inventory"
    const val COUNTS = "counts"
    const val REPORTS = "reports"
    const val NOTIFICATIONS = "notifications"
    const val SETTINGS = "settings"
    const val MASTER = "admin/master"
    const val USERS = "admin/users"
    const val IMPORT = "admin/import"
    const val AUDIT = "admin/audit"
    const val BACKUP = "admin/backup"
    const val SYNC = "admin/sync"
    const val MIGRATION = "admin/migration"

    const val PRODUCTION_PATTERN = "production?new={new}"
    const val EDITOR_PATTERN = "report/{id}/edit?step={step}"
    const val DETAIL_PATTERN = "report/{id}"
    const val PREVIEW_PATTERN = "report/{id}/preview"
    const val HISTORY_PATTERN = "history?date={date}&product={product}&line={line}&shift={shift}"
    const val ITEM_PATTERN = "item/{kind}/{id}"
    const val MOVEMENT_PATTERN = "movement?kind={kind}&item={item}&type={type}&report={report}&entry={entry}"
    const val COUNT_PATTERN = "count/{id}"
    const val BUILDER_PATTERN = "report-builder/{type}?reportId={reportId}"
    const val INVENTORY_PATTERN = "inventory?tab={tab}"
    const val MASTER_SECTION_PATTERN = "admin/master/{section}"

    fun production(openNew: Boolean = false) = "production?new=$openNew"
    fun editor(id: String, step: Int = 0) = "report/${enc(id)}/edit?step=$step"
    fun detail(id: String) = "report/${enc(id)}"
    fun preview(id: String) = "report/${enc(id)}/preview"
    fun history(date: LocalDate? = null, productId: String? = null, lineId: String? = null, shiftId: String? = null) =
        "history?date=${date ?: ""}&product=${enc(productId)}&line=${enc(lineId)}&shift=${enc(shiftId)}"
    fun item(kind: ItemKind, id: String) = "item/${kind.name}/${enc(id)}"
    fun movement(kind: ItemKind? = null, itemId: String? = null, type: String? = null, reportId: String? = null, entryId: String? = null) =
        "movement?kind=${kind?.name.orEmpty()}&item=${enc(itemId)}&type=${enc(type)}&report=${enc(reportId)}&entry=${enc(entryId)}"
    fun count(id: String) = "count/${enc(id)}"
    fun builder(type: ReportType, reportId: String? = null) = "report-builder/${type.name}?reportId=${enc(reportId)}"
    fun inventory(kind: ItemKind) = "inventory?tab=${kind.name}"
    fun masterSection(section: String) = "admin/master/${enc(section)}"

    /**
     * Percent-encodes an argument (UTF-8; everything but letters, digits and -._~), so ids that contain '/', '?', '&'
     * or spaces (codes typed by an administrator, imported item names) cannot break a route. Navigation decodes them.
     */
    fun enc(value: String?): String {
        if (value == null) return ""
        val out = StringBuilder()
        for (b in value.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            if (c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code || c == '-'.code || c == '.'.code || c == '_'.code || c == '~'.code) {
                out.append(c.toChar())
            } else {
                out.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 0xf])
            }
        }
        return out.toString()
    }
}

/** What screens use to move around; implemented over the NavController so screens stay testable. */
interface Navigator {
    /** Opens a screen on top of the current one. */
    fun open(route: String)
    /** Switches to a top-level section, keeping each section's own back stack. */
    fun top(route: String)
    fun back()
}
