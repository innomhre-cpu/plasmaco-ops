package rw.plasmaco.ops

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import rw.plasmaco.ops.domain.security.User
import rw.plasmaco.ops.ui.auth.SetupScreen
import rw.plasmaco.ops.ui.auth.SignInScreen
import rw.plasmaco.ops.ui.navigation.AppShell
import rw.plasmaco.ops.ui.navigation.PlasmacoNavHost
import rw.plasmaco.ops.ui.navigation.Routes
import rw.plasmaco.ops.ui.navigation.ShellState
import rw.plasmaco.ops.ui.navigation.navigateTop
import rw.plasmaco.ops.ui.theme.Plasmaco
import rw.plasmaco.ops.ui.theme.PlasmacoTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition { vm.state.value.loading }
        enableEdgeToEdge()
        setContent {
            val state by vm.state.collectAsStateWithLifecycle()
            PlasmacoTheme(state.themeMode) {
                val widthClass = calculateWindowSizeClass(this).widthSizeClass
                PlasmacoRoot(state, widthClass, vm)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.onResume()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        vm.touch()
    }
}

@Composable
private fun PlasmacoRoot(state: MainState, widthClass: WindowWidthSizeClass, vm: MainViewModel) {
    val user = state.user
    when {
        state.loading -> Box(Modifier.fillMaxSize().background(Plasmaco.colors.background))
        user == null && !state.hasUsers && !state.serverConfigured -> SetupScreen()
        user == null -> SignInScreen()
        else -> SignedInApp(state, user, widthClass, vm)
    }
}

@Composable
private fun SignedInApp(state: MainState, user: User, widthClass: WindowWidthSizeClass, vm: MainViewModel) {
    // Signing out leaves this composable, so the next person always starts with a fresh navigation graph.
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val snackbar = remember { SnackbarHostState() }
    val actor = remember(user) { user.toActor() }

    LaunchedEffect(Unit) { vm.messages.messages.collect { snackbar.showSnackbar(it) } }
    NotificationPermissionRequest()

    AppShell(
        state = ShellState(actor, entry?.destination?.route, state.unread, state.pendingSync, state.online, widthClass),
        drawerState = drawerState,
        snackbar = snackbar,
        onNavigate = { route -> nav.navigateTop(route) },
        onNewReport = { nav.navigateTop(Routes.production(openNew = true)) },
        onSignOut = vm::signOut,
    ) { modifier ->
        PlasmacoNavHost(nav, actor, modifier)
    }
}

/** Android 13+ asks once for permission to show critical alerts (low stock, missing reports) as notifications. */
@Composable
private fun NotificationPermissionRequest() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
