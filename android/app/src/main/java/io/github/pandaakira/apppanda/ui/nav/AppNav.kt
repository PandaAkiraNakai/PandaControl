package io.github.pandaakira.apppanda.ui.nav

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
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
import io.github.pandaakira.apppanda.ui.theme.PandaIcons
import io.github.pandaakira.apppanda.ui.theme.PandaTheme
import io.github.pandaakira.apppanda.ui.themes.ThemesScreen

private sealed class Dest(
    val route: String,
    val label: String,
) {
    data object Home : Dest("home", "Inicio")
    data object Monitor : Dest("monitor", "Monitor")
    data object Control : Dest("control", "Control")
    data object Sistema : Dest("sistema", "Sistema")
}

/** Icono de cada tab, resuelto en composición para que siga el estilo del
 *  tema activo (outlined/filled/rounded/sharp). */
@Composable
private fun tabIcon(dest: Dest): ImageVector = when (dest) {
    Dest.Home -> PandaIcons.dashboard
    Dest.Monitor -> PandaIcons.analytics
    Dest.Control -> PandaIcons.settingsRemote
    Dest.Sistema -> PandaIcons.dns
}

private val tabs = listOf(Dest.Home, Dest.Monitor, Dest.Control, Dest.Sistema)

@Composable
fun AppNav(app: PandaApp, theme: PandaTheme) {
    val navController = rememberNavController()
    val config by app.settings.config.collectAsState(initial = null)

    val startDestination = when {
        config == null -> Dest.Home.route
        config!!.isConfigured -> Dest.Home.route
        else -> "onboarding"
    }

    Scaffold(
        // Transparente para que el fondo del tema (color o imagen) se vea
        // detrás del contenido; lo pinta ThemedBackground.
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
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
                                Icon(tabIcon(dest), contentDescription = dest.label)
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
            composable("temas") { ThemesScreen(app = app) }
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
    SudoApprovalOverlay(app = app, theme = theme)
}
