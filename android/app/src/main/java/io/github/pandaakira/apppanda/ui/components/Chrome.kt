package io.github.pandaakira.apppanda.ui.components

import androidx.compose.runtime.Composable
import io.github.pandaakira.apppanda.ui.theme.LocalPandaChrome

/**
 * Prefija un titular con el chrome del tema activo — el cursor `▸ ` de la
 * Pokédex incluida, o lo que declare el tema en su campo `chrome`. Si el tema
 * no lleva chrome (sans/serif, o `"chrome": "none"`), devuelve el título
 * limpio, para que la interfaz respete su estética. Ver [LocalPandaChrome].
 */
@Composable
fun pandaDeco(title: String): String {
    val chrome = LocalPandaChrome.current
    return if (chrome.isEmpty()) title else chrome + title
}
