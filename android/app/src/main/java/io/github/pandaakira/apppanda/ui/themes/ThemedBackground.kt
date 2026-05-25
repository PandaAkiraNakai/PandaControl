package io.github.pandaakira.apppanda.ui.themes

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import io.github.pandaakira.apppanda.PandaApp
import io.github.pandaakira.apppanda.ui.theme.PandaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fondo del tema. Pinta siempre el color de fondo; si el tema trae una imagen
 * (archivo en la carpeta del PC), la baja por el backend autenticado, la
 * dibuja recortada a pantalla completa y le pone un velo del color de fondo
 * para que el contenido siga legible. Sin imagen o sin conexión, queda el
 * color sólido — degradación elegante.
 */
@Composable
fun ThemedBackground(
    app: PandaApp,
    theme: PandaTheme,
    content: @Composable () -> Unit,
) {
    val api by app.repository.api.collectAsState()
    val name = theme.backgroundImage
    var image by remember(name) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(name, api) {
        if (name.isBlank()) {
            image = null
            return@LaunchedEffect
        }
        val current = api ?: return@LaunchedEffect
        image = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = current.themeImage(name)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        }
    }

    Box(Modifier.fillMaxSize().background(theme.palette.background)) {
        when {
            // Efecto animado del tema (la lluvia de código del Matrix). Se dibuja
            // por encima del color de fondo y debajo del contenido, con un velo
            // tenue para que el texto siga legible.
            theme.backgroundEffect == "matrixRain" -> {
                MatrixRain(
                    head = theme.palette.onSurface,
                    trail = theme.palette.green,
                    background = theme.palette.background,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(theme.palette.background.copy(alpha = 0.30f)),
                )
            }
            // Ecualizador animado estilo equipo de música. Si el tema trae
            // imagen (p. ej. el cassette), va detrás con su velo; las barras de
            // LEDs se dibujan encima, ancladas abajo.
            theme.backgroundEffect == "equalizer" -> {
                image?.let { img ->
                    Image(
                        bitmap = img,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(theme.palette.background.copy(alpha = 0.55f)),
                )
                EqualizerBars(
                    low = theme.palette.green,
                    mid = theme.palette.yellow,
                    high = theme.palette.red,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // Imagen de fondo del tema (archivo del PC), recortada a pantalla.
            image != null -> {
                Image(
                    bitmap = image!!,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(theme.palette.background.copy(alpha = 0.55f)),
                )
            }
        }
        content()
    }
}
