package io.github.pandaakira.apppanda.ui.themes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.exp
import kotlin.random.Random

/** Una columna del ecualizador: persigue un nivel objetivo con suavizado, y
 *  cada cierto tiempo elige un nuevo objetivo, dando el rebote del VU. */
private class EqBar(
    var level: Float,
    var target: Float,
    var nextChangeMs: Long,
)

/**
 * Ecualizador gráfico estilo equipo de música ochentero: columnas de LEDs
 * ancladas abajo que suben y bajan "al ritmo". Cada barra persigue un nivel
 * objetivo aleatorio con suavizado exponencial (rebote tipo espectro/VU) y se
 * dibuja por segmentos coloreados verde→ámbar→rojo según la altura, el look
 * clásico de un hi-fi. Animado con `withFrameMillis` (independiente del reloj de
 * recomposición) y pintado en una sola pasada de Canvas; el grid se recalcula
 * si cambia el ancho.
 */
@Composable
fun EqualizerBars(
    low: Color,
    mid: Color,
    high: Color,
    modifier: Modifier = Modifier,
    heightFraction: Float = 0.45f,
) {
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        val barW = with(density) { 13.dp.toPx() }
        val gap = with(density) { 7.dp.toPx() }
        val segGap = with(density) { 3.dp.toPx() }
        val segments = 16
        val cols = ((constraints.maxWidth + gap) / (barW + gap)).toInt().coerceAtLeast(1)

        val bars = remember(cols) {
            List(cols) { EqBar(Random.nextFloat() * 0.4f + 0.1f, Random.nextFloat(), 0L) }
        }

        // Reloj de frames: avanza los niveles y dispara el redibujo vía `tick`.
        var tick by remember { mutableStateOf(0L) }
        LaunchedEffect(cols) {
            var last = 0L
            while (true) {
                withFrameMillis { t ->
                    val dt = if (last == 0L) 0f else ((t - last) / 1000f).coerceAtMost(0.05f)
                    last = t
                    bars.forEach { b ->
                        if (t >= b.nextChangeMs) {
                            b.target = 0.12f + Random.nextFloat() * 0.88f
                            b.nextChangeMs = t + (110 + Random.nextInt(360)).toLong()
                        }
                        // Suavizado exponencial hacia el objetivo (rebote musical).
                        b.level += (b.target - b.level) * (1f - exp(-dt * 9f))
                    }
                    tick = t
                }
            }
        }

        Canvas(Modifier.fillMaxSize()) {
            tick // leer el estado: redibuja cada frame
            val totalH = size.height * heightFraction
            val segH = (totalH - segGap * (segments - 1)) / segments
            if (segH <= 0f) return@Canvas
            val contentW = cols * (barW + gap) - gap
            val startX = (size.width - contentW) / 2f
            bars.forEachIndexed { i, b ->
                val x = startX + i * (barW + gap)
                val lit = (b.level * segments).toInt().coerceIn(0, segments)
                for (s in 0 until lit) {
                    val frac = s.toFloat() / (segments - 1)
                    val c = when {
                        frac > 0.82f -> high
                        frac > 0.55f -> mid
                        else -> low
                    }
                    val y = size.height - (s + 1) * segH - s * segGap
                    drawRect(
                        color = c.copy(alpha = 0.88f),
                        topLeft = Offset(x, y),
                        size = Size(barW, segH),
                    )
                }
            }
        }
    }
}
