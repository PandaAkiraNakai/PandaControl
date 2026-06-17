package io.github.pandaakira.apppanda.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.sharp.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.vector.ImageVector

/** Estilo de iconos del tema. Cada tema elige uno y se aplica a TODA la app
 *  vía [PandaIcons], que resuelve la variante correcta de cada icono. */
enum class IconStyle { Outlined, Filled, Rounded, Sharp }

val LocalIconStyle = staticCompositionLocalOf { IconStyle.Outlined }

/**
 * Catálogo de iconos de la app. Cada propiedad devuelve la variante (outlined /
 * filled / rounded / sharp) según [LocalIconStyle]. Las pantallas usan
 * `PandaIcons.xxx` en vez de `Icons.Outlined.Xxx`, así un cambio de tema
 * reestiliza todos los iconos de una.
 */
object PandaIcons {
    @Composable
    @ReadOnlyComposable
    private fun pick(
        outlined: ImageVector,
        filled: ImageVector,
        rounded: ImageVector,
        sharp: ImageVector,
    ): ImageVector = when (LocalIconStyle.current) {
        IconStyle.Outlined -> outlined
        IconStyle.Filled -> filled
        IconStyle.Rounded -> rounded
        IconStyle.Sharp -> sharp
    }

    val analytics: ImageVector
        @Composable @ReadOnlyComposable get() = pick(
            Icons.Outlined.Analytics, Icons.Filled.Analytics,
            Icons.Rounded.Analytics, Icons.Sharp.Analytics)
    val apps: ImageVector
        @Composable @ReadOnlyComposable get() = pick(
            Icons.Outlined.Apps, Icons.Filled.Apps,
            Icons.Rounded.Apps, Icons.Sharp.Apps)
    val aspectRatio: ImageVector
        @Composable @ReadOnlyComposable get() = pick(
            Icons.Outlined.AspectRatio, Icons.Filled.AspectRatio,
            Icons.Rounded.AspectRatio, Icons.Sharp.AspectRatio)
    val bolt: ImageVector
        @Composable @ReadOnlyComposable get() = pick(
            Icons.Outlined.Bolt, Icons.Filled.Bolt,
            Icons.Rounded.Bolt, Icons.Sharp.Bolt)
    val checkCircle: ImageVector
        @Composable @ReadOnlyComposable get() = pick(
            Icons.Outlined.CheckCircle, Icons.Filled.CheckCircle,
            Icons.Rounded.CheckCircle, Icons.Sharp.CheckCircle)
    val chevronLeft: ImageVector
        @Composable @ReadOnlyComposable get() = pick(
            Icons.Outlined.ChevronLeft, Icons.Filled.ChevronLeft,
            Icons.Rounded.ChevronLeft, Icons.Sharp.ChevronLeft)
    val chevronRight: ImageVector
        @Composable @ReadOnlyComposable get() = pick(
            Icons.Outlined.ChevronRight, Icons.Filled.ChevronRight,
            Icons.Rounded.ChevronRight, Icons.Sharp.ChevronRight)
    val add: ImageVector
        @Composable @ReadOnlyComposable get() = pick(
            Icons.Outlined.Add, Icons.Filled.Add,
            Icons.Rounded.Add, Icons.Sharp.Add)
    val calendarMonth: ImageVector
        @Composable @ReadOnlyComposable get() = pick(
            Icons.Outlined.CalendarMonth, Icons.Filled.CalendarMonth,
            Icons.Rounded.CalendarMonth, Icons.Sharp.CalendarMonth)
    val checklist: ImageVector
        @Composable @ReadOnlyComposable get() = pick(
            Icons.Outlined.Checklist, Icons.Filled.Checklist,
            Icons.Rounded.Checklist, Icons.Sharp.Checklist)
    val deleteIcon: ImageVector
        @Composable @ReadOnlyComposable get() = pick(
            Icons.Outlined.Delete, Icons.Filled.Delete,
            Icons.Rounded.Delete, Icons.Sharp.Delete)
    val notifications: ImageVector
        @Composable @ReadOnlyComposable get() = pick(
            Icons.Outlined.Notifications, Icons.Filled.Notifications,
            Icons.Rounded.Notifications, Icons.Sharp.Notifications)
    val close: ImageVector
        @Composable @ReadOnlyComposable get() = pick(
            Icons.Outlined.Close, Icons.Filled.Close,
            Icons.Rounded.Close, Icons.Sharp.Close)
    val inventory2: ImageVector
        @Composable @ReadOnlyComposable get() = pick(
            Icons.Outlined.Inventory2, Icons.Filled.Inventory2,
            Icons.Rounded.Inventory2, Icons.Sharp.Inventory2)
    val cloud: ImageVector
        @Composable @ReadOnlyComposable get() = pick(
            Icons.Outlined.Cloud, Icons.Filled.Cloud,
            Icons.Rounded.Cloud, Icons.Sharp.Cloud)
    val dashboard: ImageVector
        @Composable @ReadOnlyComposable get() = pick(
            Icons.Outlined.Dashboard, Icons.Filled.Dashboard,
            Icons.Rounded.Dashboard, Icons.Sharp.Dashboard)
    val dns: ImageVector
        @Composable @ReadOnlyComposable get() = pick(
            Icons.Outlined.Dns, Icons.Filled.Dns,
            Icons.Rounded.Dns, Icons.Sharp.Dns)
    val folder: ImageVector
        @Composable @ReadOnlyComposable get() = pick(
            Icons.Outlined.Folder, Icons.Filled.Folder,
            Icons.Rounded.Folder, Icons.Sharp.Folder)
    val fullscreen: ImageVector
        @Composable @ReadOnlyComposable get() = pick(
            Icons.Outlined.Fullscreen, Icons.Filled.Fullscreen,
            Icons.Rounded.Fullscreen, Icons.Sharp.Fullscreen)
    val gridView: ImageVector
        @Composable @ReadOnlyComposable get() = pick(
            Icons.Outlined.GridView, Icons.Filled.GridView,
            Icons.Rounded.GridView, Icons.Sharp.GridView)
    val keyboardArrowDown: ImageVector
        @Composable @ReadOnlyComposable get() = pick(
            Icons.Outlined.KeyboardArrowDown, Icons.Filled.KeyboardArrowDown,
            Icons.Rounded.KeyboardArrowDown, Icons.Sharp.KeyboardArrowDown)
    val keyboardArrowUp: ImageVector
        @Composable @ReadOnlyComposable get() = pick(
            Icons.Outlined.KeyboardArrowUp, Icons.Filled.KeyboardArrowUp,
            Icons.Rounded.KeyboardArrowUp, Icons.Sharp.KeyboardArrowUp)
    val mouse: ImageVector
        @Composable @ReadOnlyComposable get() = pick(
            Icons.Outlined.Mouse, Icons.Filled.Mouse,
            Icons.Rounded.Mouse, Icons.Sharp.Mouse)
    val myLocation: ImageVector
        @Composable @ReadOnlyComposable get() = pick(
            Icons.Outlined.MyLocation, Icons.Filled.MyLocation,
            Icons.Rounded.MyLocation, Icons.Sharp.MyLocation)
    val palette: ImageVector
        @Composable @ReadOnlyComposable get() = pick(
            Icons.Outlined.Palette, Icons.Filled.Palette,
            Icons.Rounded.Palette, Icons.Sharp.Palette)
    val powerSettingsNew: ImageVector
        @Composable @ReadOnlyComposable get() = pick(
            Icons.Outlined.PowerSettingsNew, Icons.Filled.PowerSettingsNew,
            Icons.Rounded.PowerSettingsNew, Icons.Sharp.PowerSettingsNew)
    val psychology: ImageVector
        @Composable @ReadOnlyComposable get() = pick(
            Icons.Outlined.Psychology, Icons.Filled.Psychology,
            Icons.Rounded.Psychology, Icons.Sharp.Psychology)
    val settings: ImageVector
        @Composable @ReadOnlyComposable get() = pick(
            Icons.Outlined.Settings, Icons.Filled.Settings,
            Icons.Rounded.Settings, Icons.Sharp.Settings)
    val settingsRemote: ImageVector
        @Composable @ReadOnlyComposable get() = pick(
            Icons.Outlined.SettingsRemote, Icons.Filled.SettingsRemote,
            Icons.Rounded.SettingsRemote, Icons.Sharp.SettingsRemote)
    val sportsEsports: ImageVector
        @Composable @ReadOnlyComposable get() = pick(
            Icons.Outlined.SportsEsports, Icons.Filled.SportsEsports,
            Icons.Rounded.SportsEsports, Icons.Sharp.SportsEsports)
    val systemUpdate: ImageVector
        @Composable @ReadOnlyComposable get() = pick(
            Icons.Outlined.SystemUpdate, Icons.Filled.SystemUpdate,
            Icons.Rounded.SystemUpdate, Icons.Sharp.SystemUpdate)
    val tune: ImageVector
        @Composable @ReadOnlyComposable get() = pick(
            Icons.Outlined.Tune, Icons.Filled.Tune,
            Icons.Rounded.Tune, Icons.Sharp.Tune)
    val tv: ImageVector
        @Composable @ReadOnlyComposable get() = pick(
            Icons.Outlined.Tv, Icons.Filled.Tv,
            Icons.Rounded.Tv, Icons.Sharp.Tv)
    val wifi: ImageVector
        @Composable @ReadOnlyComposable get() = pick(
            Icons.Outlined.Wifi, Icons.Filled.Wifi,
            Icons.Rounded.Wifi, Icons.Sharp.Wifi)
}

/** Convierte el string del tema ("outlined"/"filled"/...) a [IconStyle]. */
fun iconStyleFromName(name: String): IconStyle = when (name.trim().lowercase()) {
    "filled" -> IconStyle.Filled
    "rounded" -> IconStyle.Rounded
    "sharp" -> IconStyle.Sharp
    else -> IconStyle.Outlined
}
