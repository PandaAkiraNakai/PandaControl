package io.github.pandaakira.apppanda.ui.nav

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.pandaakira.apppanda.PandaApp
import io.github.pandaakira.apppanda.ui.home.HomeScreen
import io.github.pandaakira.apppanda.ui.media.MediaTabScreen
import io.github.pandaakira.apppanda.ui.more.AppsScreen
import io.github.pandaakira.apppanda.ui.more.AudioScreen
import io.github.pandaakira.apppanda.ui.more.DisplaysScreen
import io.github.pandaakira.apppanda.ui.more.GamesScreen
import io.github.pandaakira.apppanda.ui.more.LogsScreen
import io.github.pandaakira.apppanda.ui.more.MediaScreen
import io.github.pandaakira.apppanda.ui.more.ModulesScreen
import io.github.pandaakira.apppanda.ui.more.NetworkScreen
import io.github.pandaakira.apppanda.ui.more.PowerScreen
import io.github.pandaakira.apppanda.ui.more.ProcessesScreen
import io.github.pandaakira.apppanda.ui.more.ServicesListScreen
import io.github.pandaakira.apppanda.ui.more.UpdatesScreen
import io.github.pandaakira.apppanda.ui.more.VpsScreen
import io.github.pandaakira.apppanda.ui.onboarding.OnboardingScreen
import io.github.pandaakira.apppanda.ui.status.StatusScreen
import io.github.pandaakira.apppanda.ui.trends.TrendsScreen

private sealed class Dest(val route: String, val label: String, val icon: ImageVector) {
    data object Home : Dest("home", "Home", Icons.Outlined.Dashboard)
    data object Status : Dest("status", "Status", Icons.Outlined.Analytics)
    data object Media : Dest("media-tab", "Media", Icons.Outlined.Bolt)
    data object Modules : Dest("modules", "Modules", Icons.Outlined.Apps)
}

private val tabs = listOf(Dest.Home, Dest.Status, Dest.Media, Dest.Modules)

@Composable
fun AppNav(app: PandaApp) {
    val navController = rememberNavController()
    val config by app.settings.config.collectAsState(initial = null)

    val startDestination = when {
        config == null -> Dest.Home.route
        config!!.isConfigured -> Dest.Home.route
        else -> "onboarding"
    }

    Scaffold(
        bottomBar = {
            val current = navController.currentBackStackEntryAsState().value?.destination
            val isTopLevel = tabs.any { d ->
                current?.hierarchy?.any { it.route == d.route } == true
            }
            if (isTopLevel) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    tabs.forEach { dest ->
                        val selected = current?.hierarchy?.any { it.route == dest.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(dest.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(dest.icon, contentDescription = dest.label) },
                            label = { Text(dest.label, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
            }
        },
    ) { padding: PaddingValues ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(padding),
        ) {
            composable(Dest.Home.route) {
                HomeScreen(
                    app = app,
                    onGoSetup = { navController.navigate("onboarding") },
                )
            }
            composable(Dest.Status.route) { StatusScreen(app = app) }
            composable(Dest.Media.route) {
                MediaTabScreen(onNavigate = { route -> navController.navigate(route) })
            }
            composable(Dest.Modules.route) {
                ModulesScreen(onNavigate = { route -> navController.navigate(route) })
            }

            // Sub-pantallas (accesibles desde Media o Modules)
            composable("media") { MediaScreen(app = app) }
            composable("audio") { AudioScreen(app = app) }
            composable("displays") { DisplaysScreen(app = app) }
            composable("apps") { AppsScreen(app = app) }
            composable("games") { GamesScreen(app = app) }

            composable("trends") { TrendsScreen(app = app) }
            composable("processes") { ProcessesScreen(app = app) }
            composable("services") { ServicesListScreen(app = app) }
            composable("logs") { LogsScreen(app = app) }
            composable("updates") { UpdatesScreen(app = app) }
            composable("network") { NetworkScreen(app = app) }
            composable("vps") { VpsScreen(app = app) }
            composable("power") { PowerScreen(app = app) }
            composable("settings") {
                io.github.pandaakira.apppanda.ui.settings.SettingsScreen(app = app)
            }

            composable("onboarding") {
                OnboardingScreen(
                    app = app,
                    onSaved = {
                        navController.navigate(Dest.Home.route) {
                            popUpTo("onboarding") { inclusive = true }
                        }
                    },
                )
            }
        }
    }
}
