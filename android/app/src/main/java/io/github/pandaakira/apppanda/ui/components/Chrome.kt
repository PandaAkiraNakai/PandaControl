package io.github.pandaakira.apppanda.ui.components

import androidx.compose.runtime.Composable
import io.github.pandaakira.apppanda.ui.theme.LocalPandaChrome

/**
 * Prefija un titular con `// ` solo cuando el tema activo usa chrome de
 * terminal (Cyberpunk, Matrix, Synthwave). En temas sans/serif (Nord, Soft)
 * devuelve el título limpio, para que la interfaz respete su estética en vez de
 * verse siempre cyberpunk. Ver [LocalPandaChrome].
 */
@Composable
fun pandaDeco(title: String): String =
    if (LocalPandaChrome.current) "// $title" else title
