package com.jek.sq5monitor.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.jek.sq5monitor.AppContainer
import com.jek.sq5monitor.connection.ConnectionState
import com.jek.sq5monitor.ui.connect.ConnectScreen
import com.jek.sq5monitor.ui.dashboard.DashboardScreen
import com.jek.sq5monitor.ui.diagnose.DiagnoseScreen
import com.jek.sq5monitor.ui.live.LiveDataScreen
import com.jek.sq5monitor.ui.logs.LogsScreen
import com.jek.sq5monitor.ui.settings.SettingsScreen
import com.jek.sq5monitor.ui.vehicle.VehicleScreen

private object Routes {
    const val CONNECT = "connect"
    const val DASHBOARD = "dashboard"
    const val LIVE = "live"
    const val DIAGNOSE = "diagnose"
    const val VEHICLE = "vehicle"
    const val SETTINGS = "settings"
    const val LOGS = "logs"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.DASHBOARD, "Dashboard", Icons.Filled.Speed),
    Tab(Routes.LIVE, "Live data", Icons.AutoMirrored.Filled.List),
    Tab(Routes.DIAGNOSE, "Diagnose", Icons.Filled.MedicalServices),
    Tab(Routes.VEHICLE, "Vehicle", Icons.Filled.DirectionsCar),
)

@Composable
fun Sq5App(container: AppContainer) {
    val navController = rememberNavController()
    val connection by container.connection.state.collectAsStateWithLifecycle()
    val connected = connection is ConnectionState.Connected
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    // The connection drives top-level navigation: connected -> dashboard, disconnected -> connect screen.
    LaunchedEffect(connected) {
        val target = if (connected) Routes.DASHBOARD else Routes.CONNECT
        if (navController.currentDestination?.route != target) {
            navController.navigate(target) {
                popUpTo(navController.graph.id) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    val settingsAction: @Composable () -> Unit = {
        IconButton(onClick = { navController.navigate(Routes.SETTINGS) }) {
            Icon(Icons.Filled.Settings, contentDescription = "Settings")
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // Each screen's TopAppBar handles the status bar; the scaffold only keeps content above the nav bar.
        contentWindowInsets = WindowInsets.navigationBars,
        bottomBar = {
            if (connected && tabs.any { it.route == currentRoute }) {
                BottomBar(navController, currentRoute)
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            NavHost(navController, startDestination = Routes.CONNECT) {
                composable(Routes.CONNECT) { ConnectScreen(onOpenSettings = { navController.navigate(Routes.SETTINGS) }) }
                composable(Routes.DASHBOARD) { DashboardScreen(settingsAction) }
                composable(Routes.LIVE) { LiveDataScreen(settingsAction) }
                composable(Routes.DIAGNOSE) { DiagnoseScreen(settingsAction) }
                composable(Routes.VEHICLE) { VehicleScreen(settingsAction) }
                composable(Routes.SETTINGS) {
                    SettingsScreen(onBack = { navController.popBackStack() }, onOpenLogs = { navController.navigate(Routes.LOGS) })
                }
                composable(Routes.LOGS) { LogsScreen(onBack = { navController.popBackStack() }) }
            }
        }
    }
}

@Composable
private fun BottomBar(navController: NavHostController, currentRoute: String?) {
    NavigationBar {
        tabs.forEach { tab ->
            NavigationBarItem(
                selected = currentRoute == tab.route,
                onClick = {
                    navController.navigate(tab.route) {
                        // The dashboard is the root of the back stack while connected.
                        popUpTo(Routes.DASHBOARD) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(tab.label) },
            )
        }
    }
}
