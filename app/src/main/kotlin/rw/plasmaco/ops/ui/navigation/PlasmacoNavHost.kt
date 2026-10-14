package rw.plasmaco.ops.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import rw.plasmaco.ops.domain.security.Actor
import rw.plasmaco.ops.domain.security.Permission
import rw.plasmaco.ops.ui.admin.AuditScreen
import rw.plasmaco.ops.ui.admin.BackupScreen
import rw.plasmaco.ops.ui.admin.ImportScreen
import rw.plasmaco.ops.ui.admin.MasterDataScreen
import rw.plasmaco.ops.ui.admin.MasterSectionScreen
import rw.plasmaco.ops.ui.admin.MigrationScreen
import rw.plasmaco.ops.ui.admin.UsersScreen
import rw.plasmaco.ops.ui.analytics.AnalyticsScreen
import rw.plasmaco.ops.ui.approvals.ApprovalsScreen
import rw.plasmaco.ops.ui.components.EmptyState
import rw.plasmaco.ops.ui.dashboard.DashboardScreen
import rw.plasmaco.ops.ui.history.HistoryScreen
import rw.plasmaco.ops.ui.inventory.CountScreen
import rw.plasmaco.ops.ui.inventory.CountsScreen
import rw.plasmaco.ops.ui.inventory.InventoryScreen
import rw.plasmaco.ops.ui.inventory.ItemDetailScreen
import rw.plasmaco.ops.ui.inventory.MovementScreen
import rw.plasmaco.ops.ui.notifications.NotificationsScreen
import rw.plasmaco.ops.ui.production.ProductionScreen
import rw.plasmaco.ops.ui.production.detail.ReportDetailScreen
import rw.plasmaco.ops.ui.production.editor.ReportEditorScreen
import rw.plasmaco.ops.ui.reports.ReportBuilderScreen
import rw.plasmaco.ops.ui.reports.ReportsScreen
import rw.plasmaco.ops.ui.settings.SettingsScreen
import rw.plasmaco.ops.ui.settings.SyncScreen

fun NavController.navigateTop(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

private fun optional(name: String) = navArgument(name) { type = NavType.StringType; nullable = true; defaultValue = null }

/** Shows [content] only when the signed-in role has one of [any]; the server enforces the same rules. */
@Composable
private fun Guard(actor: Actor, vararg any: Permission, content: @Composable () -> Unit) {
    if (any.isEmpty() || any.any { actor.can(it) }) content()
    else EmptyState("No access", "Your role does not include this screen. Ask an administrator if you need it.", icon = Icons.Outlined.Lock)
}

@Composable
fun PlasmacoNavHost(nav: NavHostController, actor: Actor, modifier: Modifier = Modifier) {
    val navigator = remember(nav) {
        object : Navigator {
            override fun open(route: String) = nav.navigate(route) { launchSingleTop = true }
            override fun top(route: String) = nav.navigateTop(route)
            override fun back() {
                if (!nav.popBackStack()) nav.navigateTop(Routes.DASHBOARD)
            }
        }
    }

    NavHost(nav, startDestination = Routes.DASHBOARD, modifier = modifier) {
        composable(Routes.DASHBOARD) { Guard(actor, Permission.DASHBOARD_VIEW) { DashboardScreen(navigator) } }
        composable(Routes.ANALYTICS) { Guard(actor, Permission.ANALYTICS_VIEW) { AnalyticsScreen(navigator) } }

        composable(Routes.PRODUCTION_PATTERN, listOf(navArgument("new") { type = NavType.BoolType; defaultValue = false })) {
            Guard(actor, Permission.REPORT_CREATE) { ProductionScreen(navigator) }
        }
        composable(Routes.EDITOR_PATTERN, listOf(navArgument("id") { type = NavType.StringType }, navArgument("step") { type = NavType.IntType; defaultValue = 0 })) {
            Guard(actor, Permission.REPORT_CREATE, Permission.REPORT_EDIT_ANY_DRAFT) { ReportEditorScreen(navigator) }
        }
        composable(Routes.DETAIL_PATTERN, listOf(navArgument("id") { type = NavType.StringType })) {
            Guard(actor, Permission.REPORT_VIEW_OWN, Permission.REPORT_VIEW_ALL) { ReportDetailScreen(navigator, preview = false) }
        }
        composable(Routes.PREVIEW_PATTERN, listOf(navArgument("id") { type = NavType.StringType })) {
            Guard(actor, Permission.REPORT_VIEW_OWN, Permission.REPORT_VIEW_ALL) { ReportDetailScreen(navigator, preview = true) }
        }
        composable(Routes.HISTORY_PATTERN, listOf(optional("date"), optional("product"), optional("line"), optional("shift"))) {
            Guard(actor, Permission.REPORT_VIEW_OWN, Permission.REPORT_VIEW_ALL) { HistoryScreen(navigator) }
        }
        composable(Routes.APPROVALS) { Guard(actor, Permission.REPORT_APPROVE, Permission.ADJUSTMENT_APPROVE) { ApprovalsScreen(navigator) } }

        composable(Routes.INVENTORY_PATTERN, listOf(optional("tab"))) { Guard(actor, Permission.INVENTORY_VIEW) { InventoryScreen(navigator) } }
        composable(Routes.ITEM_PATTERN, listOf(navArgument("kind") { type = NavType.StringType }, navArgument("id") { type = NavType.StringType })) {
            Guard(actor, Permission.INVENTORY_VIEW) { ItemDetailScreen(navigator) }
        }
        composable(Routes.MOVEMENT_PATTERN, listOf(optional("kind"), optional("item"), optional("type"), optional("report"), optional("entry"))) {
            Guard(actor, Permission.INVENTORY_RECEIVE, Permission.INVENTORY_ISSUE, Permission.INVENTORY_DISPATCH, Permission.INVENTORY_TRANSFER, Permission.ADJUSTMENT_REQUEST) { MovementScreen(navigator) }
        }
        composable(Routes.COUNTS) { Guard(actor, Permission.STOCK_COUNT, Permission.ADJUSTMENT_APPROVE) { CountsScreen(navigator) } }
        composable(Routes.COUNT_PATTERN, listOf(navArgument("id") { type = NavType.StringType })) {
            Guard(actor, Permission.STOCK_COUNT, Permission.ADJUSTMENT_APPROVE) { CountScreen(navigator) }
        }

        composable(Routes.REPORTS) { Guard(actor, Permission.REPORTS_EXPORT, Permission.INVENTORY_VIEW) { ReportsScreen(navigator) } }
        composable(Routes.BUILDER_PATTERN, listOf(navArgument("type") { type = NavType.StringType }, optional("reportId"))) {
            Guard(actor, Permission.REPORTS_EXPORT, Permission.REPORT_VIEW_OWN, Permission.INVENTORY_VIEW) { ReportBuilderScreen(navigator) }
        }

        composable(Routes.NOTIFICATIONS) { NotificationsScreen(navigator) }
        composable(Routes.SETTINGS) { SettingsScreen(navigator) }
        composable(Routes.SYNC) { SyncScreen(navigator) }

        composable(Routes.MASTER) { Guard(actor, Permission.MASTER_DATA_MANAGE) { MasterDataScreen(navigator) } }
        composable(Routes.MASTER_SECTION_PATTERN, listOf(navArgument("section") { type = NavType.StringType })) {
            Guard(actor, Permission.MASTER_DATA_MANAGE) { MasterSectionScreen(navigator) }
        }
        composable(Routes.USERS) { Guard(actor, Permission.USERS_MANAGE) { UsersScreen(navigator) } }
        composable(Routes.IMPORT) { Guard(actor, Permission.IMPORT_RUN) { ImportScreen(navigator) } }
        composable(Routes.AUDIT) { Guard(actor, Permission.AUDIT_VIEW) { AuditScreen(navigator) } }
        composable(Routes.BACKUP) { Guard(actor, Permission.BACKUP_MANAGE) { BackupScreen(navigator) } }
        composable(Routes.MIGRATION) { Guard(actor, Permission.OPENING_BALANCE_MIGRATE) { MigrationScreen(navigator) } }
    }
}
