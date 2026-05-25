package io.github.pandaakira.apppanda

import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import io.github.pandaakira.apppanda.data.models.ThemeDef
import io.github.pandaakira.apppanda.ui.nav.AppNav
import io.github.pandaakira.apppanda.ui.theme.BuiltInTheme
import io.github.pandaakira.apppanda.ui.theme.PandaControlTheme
import io.github.pandaakira.apppanda.ui.theme.toPandaTheme
import io.github.pandaakira.apppanda.ui.themes.ThemedBackground
import kotlinx.serialization.json.Json

class MainActivity : FragmentActivity() {
    private val themeJson = Json { ignoreUnknownKeys = true }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Habilitar mostrar sobre el lockscreen y encender la pantalla — el
        // sudo modal vive dentro de esta misma Activity, así que cuando una
        // notif sudo full-screen-intent dispara MainActivity, necesita
        // pisar el keyguard y prender la pantalla.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        enableEdgeToEdge()
        val app = application as PandaApp
        setContent {
            // El tema seleccionado se guarda resuelto en DataStore, así se
            // aplica al instante al arrancar (sin esperar al backend) y en
            // vivo cuando el usuario lo cambia desde la pantalla Temas.
            val selected by app.settings.selectedTheme.collectAsState(initial = null)
            val theme = remember(selected?.specJson) {
                val json = selected?.specJson
                if (json.isNullOrBlank()) {
                    BuiltInTheme
                } else {
                    runCatching {
                        themeJson.decodeFromString(ThemeDef.serializer(), json).toPandaTheme()
                    }.getOrDefault(BuiltInTheme)
                }
            }
            PandaControlTheme(theme = theme) {
                ThemedBackground(app = app, theme = theme) {
                    AppNav(app = app, theme = theme)
                }
            }
        }
    }
}
