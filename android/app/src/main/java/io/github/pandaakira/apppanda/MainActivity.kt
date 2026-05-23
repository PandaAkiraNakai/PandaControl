package io.github.pandaakira.apppanda

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.pandaakira.apppanda.ui.nav.AppNav
import io.github.pandaakira.apppanda.ui.theme.AppPandaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppPandaTheme {
                AppNav(app = application as PandaApp)
            }
        }
    }
}
