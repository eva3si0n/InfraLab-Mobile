package com.eva3si0n.infralab.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.eva3si0n.infralab.ui.cascade.CascadeScreen
import com.eva3si0n.infralab.ui.cascade.PathsScreen
import com.eva3si0n.infralab.ui.homepage.InfraHomeScreen
import com.eva3si0n.infralab.ui.metrics.MetricsScreen
import com.eva3si0n.infralab.ui.monitors.MonitorsScreen
import com.eva3si0n.infralab.ui.settings.SettingsScreen

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("monitors", "Monitors", Icons.Default.Sensors),
    Tab("cascade", "Cascade", Icons.AutoMirrored.Filled.AltRoute),
    Tab("metrics", "Metrics", Icons.Default.BarChart),
    Tab("homepage", "InfraHome", Icons.Default.GridView),
    Tab("settings", "Settings", Icons.Default.Settings),
)

@Composable
fun AppNavigation(vm: AppViewModel) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),  // screen Scaffolds handle status/nav insets
        bottomBar = {
        NavigationBar {
            tabs.forEach { t ->
                NavigationBarItem(
                    selected = current == t.route,
                    onClick = {
                        nav.navigate(t.route) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    icon = { Icon(t.icon, contentDescription = t.label) },
                    label = { Text(t.label) }
                )
            }
        }
    }) { padding ->
        NavHost(nav, startDestination = "monitors", modifier = Modifier.padding(padding)) {
            composable("monitors") { MonitorsScreen(vm) }
            // Транзит — отдельный маршрут, а не шестая вкладка: нижняя панель на пять
            // пунктов и так плотная, а у экрана своя логика чтения (как и в вебе).
            composable("cascade") { CascadeScreen(vm, onOpenPaths = { nav.navigate("paths") }) }
            composable("paths") { PathsScreen(vm, onBack = { nav.popBackStack() }) }
            composable("metrics") { MetricsScreen(vm) }
            composable("homepage") { InfraHomeScreen(vm) }
            composable("settings") { SettingsScreen(vm) }
        }
    }
}
