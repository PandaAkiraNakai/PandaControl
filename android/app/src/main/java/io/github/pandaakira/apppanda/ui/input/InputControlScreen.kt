package io.github.pandaakira.apppanda.ui.input

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.pandaakira.apppanda.PandaApp
import io.github.pandaakira.apppanda.data.MouseDeltaSource
import io.github.pandaakira.apppanda.data.PandaApi
import io.github.pandaakira.apppanda.ui.components.PandaCard
import io.github.pandaakira.apppanda.ui.components.ScreenHeader
import io.github.pandaakira.apppanda.ui.theme.PandaCyan
import io.github.pandaakira.apppanda.ui.theme.PandaGreen
import io.github.pandaakira.apppanda.ui.theme.PandaMagenta
import io.github.pandaakira.apppanda.ui.theme.PandaYellow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Sensibilidad del touchpad (px de cursor por px de dedo).
private const val SENSITIVITY = 1.6f
// Píxeles de desplazamiento vertical de dos dedos por cada "notch" de scroll.
private const val SCROLL_STEP = 18f

/** Acumulador de movimiento thread-safe que conserva la fracción sub-pixel:
 *  los movimientos lentos no se pierden por el redondeo a entero. Implementa
 *  [MouseDeltaSource] para alimentar el stream de mouse. */
private class MouseAccumulator : MouseDeltaSource {
    private var x = 0f
    private var y = 0f
    private val lock = Any()

    fun add(dx: Float, dy: Float) = synchronized(lock) {
        x += dx; y += dy
    }

    /** Devuelve la parte entera acumulada y conserva el resto fraccional. */
    override fun take(): Pair<Int, Int> = synchronized(lock) {
        val ix = x.toInt()
        val iy = y.toInt()
        x -= ix; y -= iy
        ix to iy
    }
}

@Composable
fun InputControlScreen(app: PandaApp) {
    val api by app.repository.api.collectAsState()
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    val acc = remember { MouseAccumulator() }
    var typeText by remember { mutableStateOf("") }

    // Stream de mouse: una sola conexión persistente que emite los deltas a
    // cadencia fija sin esperar acks (frecuencia regular, suave). Vive SOLO
    // mientras esta pantalla está en primer plano (RESUMED): al salir del
    // módulo o mandar la app a background se cierra — así no consume batería
    // manteniendo el socket abierto cuando no estás controlando el mouse. Al
    // volver, reconecta. Si la conexión se cae sola, reintenta tras 500 ms.
    LaunchedEffect(api, lifecycleOwner) {
        val current = api ?: return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                try {
                    current.mouseStream(acc)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // red caída: reintenta mientras sigamos en RESUMED
                }
                delay(500)
            }
        }
    }

    fun fire(block: suspend (PandaApi) -> Unit) {
        val current = api ?: return
        scope.launch(Dispatchers.IO) {
            try { block(current) } catch (_: Exception) {}
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        ScreenHeader("CONTROL REMOTO", "mouse · teclado")
        Spacer(Modifier.height(12.dp))

        // ─── Touchpad ─────────────────────────────────────────────────────
        PandaCard(title = "TOUCHPAD", accent = PandaGreen) {
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(460.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(PandaGreen.copy(alpha = 0.07f))
                    .border(1.dp, PandaGreen.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                    // Gesto EXCLUSIVO: consume cada evento desde el `down`, así
                    // el verticalScroll del Column padre nunca participa y el
                    // movimiento es libre en cualquier dirección. Un dedo: drag
                    // = mover, toque corto = clic. Dos dedos: scroll vertical.
                    .pointerInput(Unit) {
                        val slop = viewConfiguration.touchSlop
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            var travel = 0f
                            var isDrag = false
                            var maxFingers = 1
                            var scrollAcc = 0f
                            while (true) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                val fingers = pressed.size
                                if (fingers > maxFingers) maxFingers = fingers

                                if (fingers == 0) {
                                    event.changes.forEach { it.consume() }
                                    // Toque corto de un dedo sin arrastre → clic.
                                    if (!isDrag && maxFingers == 1) {
                                        fire { it.mouseClick("left") }
                                    }
                                    break
                                }

                                // Leer los deltas ANTES de consumir: positionChange()
                                // devuelve cero una vez consumido el evento.
                                if (fingers >= 2) {
                                    // Dos dedos → scroll: acumula el movimiento
                                    // vertical promedio y manda un notch cada
                                    // SCROLL_STEP px.
                                    val dy = pressed.map { it.positionChange().y }
                                        .average().toFloat()
                                    scrollAcc += dy
                                    while (scrollAcc <= -SCROLL_STEP) {
                                        fire { it.mouseScroll("up") }
                                        scrollAcc += SCROLL_STEP
                                    }
                                    while (scrollAcc >= SCROLL_STEP) {
                                        fire { it.mouseScroll("down") }
                                        scrollAcc -= SCROLL_STEP
                                    }
                                } else if (maxFingers < 2) {
                                    // Un dedo (y nunca hubo dos en este gesto, para
                                    // no mover el cursor al soltar un dedo del
                                    // scroll) → mover.
                                    val ch = event.changes.firstOrNull { it.id == down.id }
                                    val d = ch?.positionChange()
                                    if (d != null) {
                                        travel += kotlin.math.hypot(d.x, d.y)
                                        if (travel > slop) isDrag = true
                                        if (isDrag && (d.x != 0f || d.y != 0f)) {
                                            acc.add(d.x * SENSITIVITY, d.y * SENSITIVITY)
                                        }
                                    }
                                }
                                event.changes.forEach { it.consume() }
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Un dedo: mover · toca para clic\n" +
                        "Dos dedos: scroll · botones: clic medio/derecho",
                    style = MaterialTheme.typography.bodySmall,
                    color = PandaGreen.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(12.dp))

            // Botones de clic
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ClickBtn("Izq", Modifier.weight(2f), PandaGreen, api != null) {
                    fire { it.mouseClick("left") }
                }
                ClickBtn("Med", Modifier.weight(1f), PandaCyan, api != null) {
                    fire { it.mouseClick("middle") }
                }
                ClickBtn("Der", Modifier.weight(2f), PandaYellow, api != null) {
                    fire { it.mouseClick("right") }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Scroll
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Scroll",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 10.dp),
                )
                OutlinedButton(
                    onClick = { fire { it.mouseScroll("up") } },
                    enabled = api != null,
                    modifier = Modifier.size(44.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    shape = CircleShape,
                ) {
                    Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = null, tint = PandaGreen)
                }
                Spacer(Modifier.padding(horizontal = 6.dp))
                OutlinedButton(
                    onClick = { fire { it.mouseScroll("down") } },
                    enabled = api != null,
                    modifier = Modifier.size(44.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    shape = CircleShape,
                ) {
                    Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null, tint = PandaGreen)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ─── Teclado ──────────────────────────────────────────────────────
        PandaCard(title = "TECLADO", accent = PandaMagenta) {
            Spacer(Modifier.height(8.dp))

            val enabled = api != null

            Text("Navegación", style = MaterialTheme.typography.labelMedium, color = PandaMagenta)
            Spacer(Modifier.height(6.dp))
            KeyRow(enabled,
                "Esc" to "Escape", "Tab" to "Tab", "Enter" to "Return",
                "⌫" to "BackSpace", "Del" to "Delete",
            ) { fire { api -> api.keyPress(it) } }

            Spacer(Modifier.height(6.dp))
            KeyRow(enabled,
                "←" to "Left", "→" to "Right", "↑" to "Up", "↓" to "Down",
            ) { fire { api -> api.keyPress(it) } }

            Spacer(Modifier.height(6.dp))
            KeyRow(enabled,
                "Home" to "Home", "End" to "End", "PgUp" to "Page_Up", "PgDn" to "Page_Down",
            ) { fire { api -> api.keyPress(it) } }

            Spacer(Modifier.height(12.dp))
            Text("Atajos", style = MaterialTheme.typography.labelMedium, color = PandaMagenta)
            Spacer(Modifier.height(6.dp))

            KeyRow(enabled,
                "C+C" to "ctrl+c", "C+V" to "ctrl+v", "C+Z" to "ctrl+z",
                "C+X" to "ctrl+x", "C+A" to "ctrl+a",
            ) { fire { api -> api.keyPress(it) } }

            Spacer(Modifier.height(6.dp))
            KeyRow(enabled,
                "C+↩" to "ctrl+Return", "A+Tab" to "alt+Tab",
                "A+F4" to "alt+F4", "Super" to "super",
            ) { fire { api -> api.keyPress(it) } }

            Spacer(Modifier.height(12.dp))

            // Escribir texto libre
            Text("Escribir texto", style = MaterialTheme.typography.labelMedium, color = PandaMagenta)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = typeText,
                    onValueChange = { typeText = it },
                    placeholder = { Text("texto a escribir en el PC") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        val t = typeText
                        if (t.isNotBlank()) {
                            typeText = ""
                            fire { it.typeText(t) }
                        }
                    },
                    enabled = typeText.isNotBlank() && enabled,
                    modifier = Modifier.padding(start = 8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PandaMagenta.copy(alpha = 0.3f),
                        contentColor = PandaMagenta,
                    ),
                ) { Text("Escribir") }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ClickBtn(
    label: String,
    modifier: Modifier,
    color: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = color.copy(alpha = 0.2f),
            contentColor = color,
        ),
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun KeyRow(
    enabled: Boolean,
    vararg keys: Pair<String, String>,
    onKey: (String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        keys.forEach { (label, key) ->
            OutlinedButton(
                onClick = { onKey(key) },
                enabled = enabled,
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 2.dp, vertical = 6.dp,
                ),
                shape = RoundedCornerShape(6.dp),
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            }
        }
    }
}
