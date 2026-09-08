package io.github.pandaakira.apppanda.ui.themes

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.sin

/**
 * Pantalla de la Pokédex de la primera generación del anime: una matriz de
 * puntos de LCD verde que se enciende al paso de una línea de escaneo, las
 * franjas horizontales del cristal, y las luces del frente del aparato (la
 * lámpara azul grande y los tres pilotos rojo/amarillo/verde) parpadeando cada
 * una a su ritmo.
 *
 * Se anima con `withFrameMillis` (independiente del reloj de recomposición) y
 * se pinta en una sola pasada de Canvas; la rejilla se calcula sobre el tamaño
 * real, así que se adapta a cualquier pantalla.
 */
@Composable
fun PokedexScan(
    screen: Color,
    lamp: Color,
    red: Color,
    yellow: Color,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val cell = with(density) { 18.dp.toPx() }
    val dotR = with(density) { 1.2.dp.toPx() }
    val lineGap = with(density) { 4.dp.toPx() }
    val lampR = with(density) { 26.dp.toPx() }
    val lampX = with(density) { 56.dp.toPx() }
    val lampY = with(density) { 92.dp.toPx() }
    val pilotR = with(density) { 5.dp.toPx() }
    val pilotGap = with(density) { 16.dp.toPx() }

    // Reloj de frames: dispara el redibujo sin depender de la recomposición.
    var tick by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameMillis { tick = it }
        }
    }

    Canvas(modifier) {
        val t = tick / 1000f  // leer el estado: redibuja cada frame
        if (size.width <= 0f || size.height <= 0f) return@Canvas

        // Línea de escaneo: baja en bucle con una estela suave por delante.
        val period = 5.4f
        val scanY = size.height * ((t % period) / period)
        val bandH = size.height * 0.26f
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.Transparent,
                    screen.copy(alpha = 0.06f),
                    screen.copy(alpha = 0.13f),
                    Color.Transparent,
                ),
                startY = scanY - bandH,
                endY = scanY + bandH * 0.12f,
            ),
            topLeft = Offset(0f, scanY - bandH),
            size = Size(size.width, bandH * 1.12f),
        )
        drawLine(
            color = screen.copy(alpha = 0.35f),
            start = Offset(0f, scanY),
            end = Offset(size.width, scanY),
            strokeWidth = 1.5f,
        )

        // Matriz de puntos del LCD: base tenue + brillo donde pasa el escaneo.
        val rows = (size.height / cell).toInt()
        val cols = (size.width / cell).toInt()
        for (r in 0..rows) {
            val y = r * cell
            val d = abs(y - scanY)
            val glow = if (d < bandH) (1f - d / bandH) * 0.30f else 0f
            val alpha = 0.045f + glow
            for (c in 0..cols) {
                drawCircle(
                    color = screen.copy(alpha = alpha),
                    radius = dotR,
                    center = Offset(c * cell, y),
                )
            }
        }

        // Franjas del cristal de la pantalla.
        var y = 0f
        while (y < size.height) {
            drawLine(
                color = Color.Black.copy(alpha = 0.14f),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1f,
            )
            y += lineGap
        }

        // Lámpara azul del frente, con su halo latiendo y el reflejo del vidrio.
        val pulse = (1f + sin(t * 2.1f)) / 2f
        val center = Offset(lampX, lampY)
        drawCircle(lamp.copy(alpha = 0.05f + 0.05f * pulse), lampR * 2.3f, center)
        drawCircle(lamp.copy(alpha = 0.16f + 0.10f * pulse), lampR, center)
        drawCircle(
            color = Color.White.copy(alpha = 0.10f + 0.06f * pulse),
            radius = lampR * 0.34f,
            center = Offset(lampX - lampR * 0.32f, lampY - lampR * 0.32f),
        )

        // Los tres pilotos, cada uno con su propio ritmo de parpadeo.
        listOf(red, yellow, screen).forEachIndexed { i, c ->
            val blink = (1f + sin(t * (1.4f + i * 0.6f) + i)) / 2f
            drawCircle(
                color = c.copy(alpha = 0.12f + 0.22f * blink),
                radius = pilotR,
                center = Offset(lampX + lampR * 1.9f + i * pilotGap, lampY + lampR * 0.55f),
            )
        }
    }
}
