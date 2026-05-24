package io.github.pandaakira.apppanda.ui.nav

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.SettingsRemote
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
import io.github.pandaakira.apppanda.ui.files.FilesScreen
import io.github.pandaakira.apppanda.ui.more.AppsScreen
import io.github.pandaakira.apppanda.ui.more.DisplaysScreen
import io.github.pandaakira.apppanda.ui.more.GamesScreen
import io.github.pandaakira.apppanda.ui.more.MediaScreen
import io.github.pandaakira.apppanda.ui.more.ModulesScreen
import io.github.pandaakira.apppanda.ui.more.NetworkScreen
import io.github.pandaakira.apppanda.ui.more.PowerScreen
import io.github.pandaakira.apppanda.ui.more.ServicesListScreen
import io.github.pandaakira.apppanda.ui.more.UpdatesScreen
import io.github.pandaakira.apppanda.ui.more.VpsScreen
import io.github.pandaakira.apppanda.ui.onboarding.OnboardingScreen
import io.github.pandaakira.apppanda.ui.status.MonitorScreen
import io.github.pandaakira.apppanda.ui.sudo.SudoApprovalOverlay

private sealed class Dest(
    val route: String,
    val label: String,
    val icon: ImageVector? = null,
) {
    data object Home : Dest("home", "Inicio", icon = Icons.Outlined.Dashboard)
    data object Monitor : Dest("monitor", "Monitor", icon = Icons.Outlined.Analytics)
    data object Control : Dest("control", "Control", icon = Icons.Outlined.SettingsRemote)
    data object Sistema : Dest("sistema", "Sistema", icon = Icons.Outlined.Dns)
}

private val tabs = listOf(Dest.Home, Dest.Monitor, Dest.Control, Dest.Sistema)

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
                            icon = {
                                dest.icon?.let {
                                    Icon(it, contentDescription = dest.label)
                                }
                            },
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
            composable(Dest.Monitor.route) { MonitorScreen(app = app) }
            composable(Dest.Control.route) {
                MediaTabScreen(app = app, onNavigate = { route -> navController.navigate(route) })
            }
            composable(Dest.Sistema.route) {
                ModulesScreen(onNavigate = { route -> navController.navigate(route) })
            }

            // Sub-pantallas (accesibles desde Control o Sistema)
            composable("media") { MediaScreen(app = app) }
            composable("input") {
                io.github.pandaakira.apppanda.ui.input.InputControlScreen(app = app)
            }
            composable("displays") { DisplaysScreen(app = app) }
            composable("apps") { AppsScreen(app = app) }
            composable("games") { GamesScreen(app = app) }

            composable("services") { ServicesListScreen(app = app) }
            composable("files") { FilesScreen(app = app) }
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

    // Overlay de sudo: vive a nivel del AppNav y se superpone sobre lo que sea
    // que esté visible (Home, Modules, settings, etc.) cuando llega un
    // sudo_request por SSE.
    SudoApprovalOverlay(app = app)
}
