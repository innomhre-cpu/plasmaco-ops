package rw.plasmaco.ops.ui.navigation

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Inventory
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.DrawerState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import rw.plasmaco.ops.R
import rw.plasmaco.ops.domain.security.Actor
import rw.plasmaco.ops.domain.security.Permission
import rw.plasmaco.ops.ui.components.CircleIconButton
import rw.plasmaco.ops.ui.components.OfflineBanner
import rw.plasmaco.ops.ui.theme.Plasmaco

data class ShellState(
    val actor: Actor?,
    val currentRoute: String?,
    val unreadNotifications: Int,
    val pendingSync: Int,
    val online: Boolean,
    val widthClass: WindowWidthSizeClass,
)

/**
 * The template's shell on every screen: hamburger + logo + circular action buttons in the top bar, and a drawer with
 * the full menu. Android adaptations: bottom bar on phones, navigation rail on tablets and landscape (discovery §7).
 */
@Composable
fun AppShell(
    state: ShellState,
    drawerState: DrawerState,
    snackbar: SnackbarHostState,
    onNavigate: (String) -> Unit,
    onNewReport: () -> Unit,
    onSignOut: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val compact = state.widthClass == WindowWidthSizeClass.Compact
    val visible = TopDestination.entries.filter { it.visibleTo(state.actor) }
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            AppDrawer(state, visible, onNavigate = { route -> scope.launch { drawerState.close() }; onNavigate(route) }, onSignOut = onSignOut)
        },
    ) {
        Row(Modifier.fillMaxSize().background(Plasmaco.colors.background)) {
            if (!compact) PlasmacoRail(state, visible, onNavigate, onNewReport)
            Scaffold(
                containerColor = Plasmaco.colors.background,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                snackbarHost = { SnackbarHost(snackbar) },
                topBar = {
                    Column(Modifier.background(Plasmaco.colors.background).statusBarsPadding()) {
                        PlasmacoTopBar(
                            onMenu = { scope.launch { drawerState.open() } },
                            unread = state.unreadNotifications,
                            pendingSync = state.pendingSync,
                            onNotifications = { onNavigate(Routes.NOTIFICATIONS) },
                            onSync = { onNavigate(Routes.SYNC) },
                        )
                        if (!state.online) OfflineBanner(state.pendingSync)
                    }
                },
                bottomBar = { if (compact) PlasmacoBottomBar(state, visible.filter { it.inBottomBar }, onNavigate) },
            ) { padding -> Box(Modifier.padding(padding).fillMaxSize()) { content(Modifier.fillMaxSize()) } }
        }
    }
}

@Composable
fun PlasmacoTopBar(onMenu: () -> Unit, unread: Int, pendingSync: Int, onNotifications: () -> Unit, onSync: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(Plasmaco.dimens.topBarHeight).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onMenu) { Icon(Icons.Outlined.Menu, contentDescription = "Open menu", tint = Plasmaco.colors.textPrimary) }
        Logo()
        Spacer(Modifier.weight(1f))
        CircleIconButton(Icons.Outlined.Notifications, if (unread > 0) "Notifications, $unread unread" else "Notifications", onNotifications, badge = unread)
        CircleIconButton(Icons.Outlined.Sync, if (pendingSync > 0) "Sync, $pendingSync waiting" else "Sync status", onSync, badge = pendingSync.takeIf { it > 0 })
    }
}

@Composable
fun Logo(modifier: Modifier = Modifier) {
    Row(modifier.semantics(mergeDescendants = true) { contentDescription = "Plasmaco" }, verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.size(36.dp))
        Text("Plasmaco", style = Plasmaco.type.cardTitle.copy(fontWeight = FontWeight.SemiBold), color = Plasmaco.colors.textPrimary)
    }
}

@Composable
private fun PlasmacoBottomBar(state: ShellState, items: List<TopDestination>, onNavigate: (String) -> Unit) {
    val c = Plasmaco.colors
    NavigationBar(containerColor = c.surface, tonalElevation = 0.dp) {
        items.forEach { d ->
            val selected = state.currentRoute?.substringBefore('?')?.substringBefore('/') == d.route
            NavigationBarItem(
                selected = selected,
                onClick = { onNavigate(d.route) },
                icon = { Icon(d.icon, contentDescription = null) },
                label = { Text(d.label, style = Plasmaco.type.caption, maxLines = 1) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = c.onPrimary, selectedTextColor = c.primary, indicatorColor = c.primary,
                    unselectedIconColor = c.textSecondary, unselectedTextColor = c.textSecondary,
                ),
            )
        }
    }
}

@Composable
private fun PlasmacoRail(state: ShellState, items: List<TopDestination>, onNavigate: (String) -> Unit, onNewReport: () -> Unit) {
    val c = Plasmaco.colors
    NavigationRail(
        containerColor = c.surface,
        modifier = Modifier.fillMaxHeight().statusBarsPadding(),
        header = {
            if (state.actor?.can(Permission.REPORT_CREATE) == true) {
                CircleIconButton(Icons.Outlined.AddCircleOutline, "New daily report", onNewReport, background = c.primary, tint = c.onPrimary)
            }
        },
    ) {
        items.forEach { d ->
            val selected = state.currentRoute?.substringBefore('?')?.substringBefore('/') == d.route
            NavigationRailItem(
                selected = selected,
                onClick = { onNavigate(d.route) },
                icon = { Icon(d.icon, contentDescription = null) },
                label = { Text(d.label, style = Plasmaco.type.caption) },
                colors = NavigationRailItemDefaults.colors(selectedIconColor = c.onPrimary, indicatorColor = c.primary, selectedTextColor = c.primary, unselectedIconColor = c.textSecondary, unselectedTextColor = c.textSecondary),
            )
        }
    }
}

private data class DrawerLink(val label: String, val route: String, val icon: ImageVector, val permission: Permission?)

@Composable
private fun AppDrawer(state: ShellState, visible: List<TopDestination>, onNavigate: (String) -> Unit, onSignOut: () -> Unit) {
    val c = Plasmaco.colors
    val actor = state.actor
    val more = listOf(
        DrawerLink("Analytics", Routes.ANALYTICS, Icons.Outlined.QueryStats, Permission.ANALYTICS_VIEW),
        DrawerLink("Approvals", Routes.APPROVALS, Icons.AutoMirrored.Outlined.FactCheck, Permission.REPORT_APPROVE),
        DrawerLink("Stock counts", Routes.COUNTS, Icons.Outlined.Inventory, Permission.STOCK_COUNT),
    ).filter { it.permission == null || actor?.can(it.permission) == true }
    val admin = listOf(
        DrawerLink("Master data", Routes.MASTER, Icons.Outlined.Category, Permission.MASTER_DATA_MANAGE),
        DrawerLink("Users and roles", Routes.USERS, Icons.Outlined.Group, Permission.USERS_MANAGE),
        DrawerLink("Excel import", Routes.IMPORT, Icons.Outlined.UploadFile, Permission.IMPORT_RUN),
        DrawerLink("Opening balances", Routes.MIGRATION, Icons.Outlined.AdminPanelSettings, Permission.OPENING_BALANCE_MIGRATE),
        DrawerLink("Audit log", Routes.AUDIT, Icons.AutoMirrored.Outlined.ManageSearch, Permission.AUDIT_VIEW),
        DrawerLink("Backup and restore", Routes.BACKUP, Icons.Outlined.Backup, Permission.BACKUP_MANAGE),
    ).filter { it.permission == null || actor?.can(it.permission) == true }
    ModalDrawerSheet(drawerContainerColor = c.surface, drawerShape = Plasmaco.shapes.card, modifier = Modifier.widthIn(max = 320.dp)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { Logo() }
            if (actor != null) {
                Text(actor.displayName, style = Plasmaco.type.cardTitle, color = c.textPrimary, modifier = Modifier.padding(horizontal = 20.dp))
                Text(actor.roles.joinToString { it.label }, style = Plasmaco.type.label, color = c.textSecondary, modifier = Modifier.padding(horizontal = 20.dp))
            }
            Spacer(Modifier.height(12.dp))
            visible.forEach { d -> DrawerItem(d.fullLabel, d.icon, state.currentRoute?.startsWith(d.route) == true) { onNavigate(d.route) } }
            if (more.isNotEmpty()) {
                SectionLabel("More")
                more.forEach { l -> DrawerItem(l.label, l.icon, state.currentRoute?.startsWith(l.route) == true) { onNavigate(l.route) } }
            }
            if (admin.isNotEmpty()) {
                SectionLabel("Administration")
                admin.forEach { l -> DrawerItem(l.label, l.icon, state.currentRoute?.startsWith(l.route) == true) { onNavigate(l.route) } }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = c.divider)
            DrawerItem("Sign out", Icons.AutoMirrored.Outlined.Logout, false, onSignOut)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), style = Plasmaco.type.caption, color = Plasmaco.colors.textSecondary, modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 4.dp).semantics { heading() })
}

@Composable
private fun DrawerItem(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val c = Plasmaco.colors
    NavigationDrawerItem(
        label = { Text(label, style = Plasmaco.type.bodyStrong) },
        icon = { Icon(icon, contentDescription = null) },
        selected = selected,
        onClick = onClick,
        shape = Plasmaco.shapes.button,
        colors = NavigationDrawerItemDefaults.colors(
            selectedContainerColor = c.primaryTint, selectedIconColor = c.primary, selectedTextColor = c.primary,
            unselectedContainerColor = c.surface, unselectedIconColor = c.textSecondary, unselectedTextColor = c.textPrimary,
        ),
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}
