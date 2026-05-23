package io.github.pandaakira.apppanda

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.pandaakira.apppanda.ui.nav.AppNav
import io.github.pandaakira.apppanda.ui.theme.PandaControlTheme

class MainActivity : ComponentActivity() {
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
        setContent {
            PandaControlTheme {
                AppNav(app = application as PandaApp)
            }
        }
    }
}
