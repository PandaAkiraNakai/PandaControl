package io.github.pandaakira.apppanda.ui.themes

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.random.Random

/** Glifos que caen: katakana de medio ancho (el look clásico) + dígitos y unas
 *  pocas letras/símbolos. La typeface por defecto de Android los cubre. */
private val MATRIX_GLYPHS =
    ("ｱｲｳｴｵｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄﾅﾆﾇﾈﾉﾊﾋﾌﾍﾎﾏﾐﾑﾒﾓﾔﾕﾖﾗﾘﾚﾛﾜﾝ" +
        "0123456789ABCDEFGHJKLMNPRZ:.=*+<>").toCharArray()

/** Estado mutable de una columna de lluvia, persistente entre frames. */
private class RainColumn(
    var head: Float,   // fila de la cabeza (puede ser negativa = aún por entrar)
    var speed: Float,  // filas por segundo
    var len: Int,      // largo de la estela
)

private fun newColumn(rows: Int) = RainColumn(
    head = -Random.nextInt(rows + 1).toFloat(),
    speed = 6f + Random.nextFloat() * 16f,
    len = 6 + Random.nextInt((rows / 2).coerceAtLeast(6)),
)

/**
 * Lluvia digital estilo Matrix: columnas de glifos que caen, con la cabeza
 * brillante y una estela que se desvanece. Animada con `withFrameMillis`
 * (independiente del reloj de recomposición) y dibujada en una sola pasada de
 * Canvas. El grid se recalcula si cambia el tamaño.
 */
@Composable
fun MatrixRain(
    head: Color,
    trail: Color,
    background: Color,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    BoxWithConstraints(modifier.background(background)) {
        val cell = with(density) { 15.dp.toPx() }
        val cols = (constraints.maxWidth / cell).toInt().coerceAtLeast(1)
        val rows = (constraints.maxHeight / cell).toInt().coerceAtLeast(1)

        val columns = remember(cols, rows) { List(cols) { newColumn(rows) } }
        val paint = remember {
            Paint().apply {
                isAntiAlias = true
                typeface = Typeface.MONOSPACE
            }
        }
        val headArgb = remember(head) { head.toArgb() }

        // Reloj de frames. Avanza las cabezas y dispara el redibujo leyendo `tick`.
        var tick by remember { mutableStateOf(0L) }
        LaunchedEffect(cols, rows) {
            var last = 0L
            while (true) {
                withFrameMillis { t ->
                    val dt = if (last == 0L) 0f else ((t - last) / 1000f).coerceAtMost(0.1f)
                    last = t
                    columns.forEach { c ->
                        c.head += c.speed * dt
                        if (c.head - c.len > rows) {
                            c.head = -Random.nextInt(rows / 3 + 1).toFloat()
                            c.speed = 6f + Random.nextFloat() * 16f
                            c.len = 6 + Random.nextInt((rows / 2).coerceAtLeast(6))
                        }
                    }
                    tick = t
                }
            }
        }

        Canvas(Modifier.fillMaxSize()) {
            tick // leer el estado: redibuja cada frame
            paint.textSize = cell * 0.92f
            // Cambia los glifos ~8 veces por segundo para el "shimmer".
            val phase = (tick / 120L).toInt()
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                columns.forEachIndexed { ci, col ->
                    val x = ci * cell
                    val headRow = col.head.toInt()
                    for (k in 0 until col.len) {
                        val row = headRow - k
                        if (row < 0 || row > rows) continue
                        // Glifo pseudo-aleatorio estable por celda, que rota con la fase.
                        val gi = ((ci * 31 + row * 17 + phase + k) % MATRIX_GLYPHS.size +
                            MATRIX_GLYPHS.size) % MATRIX_GLYPHS.size
                        paint.color = if (k == 0) {
                            headArgb
                        } else {
                            val a = (1f - k.toFloat() / col.len).coerceIn(0.05f, 1f)
                            trail.copy(alpha = a).toArgb()
                        }
                        native.drawText(
                            MATRIX_GLYPHS[gi].toString(),
                            x,
                            row * cell + cell,
                            paint,
                        )
                    }
                }
            }
        }
    }
}
