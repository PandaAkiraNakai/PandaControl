package io.github.pandaakira.apppanda.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import io.github.pandaakira.apppanda.PandaApp
import io.github.pandaakira.apppanda.data.AIMessage
import io.github.pandaakira.apppanda.data.AIRole
import io.github.pandaakira.apppanda.ui.components.ScreenHeader
import io.github.pandaakira.apppanda.ui.theme.PandaCyan
import io.github.pandaakira.apppanda.ui.theme.PandaGreen
import io.github.pandaakira.apppanda.ui.theme.PandaMagenta
import io.github.pandaakira.apppanda.ui.theme.PandaYellow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AIScreen(app: PandaApp) {
    val api by app.repository.api.collectAsState()
    val chat by app.repository.aiChat.collectAsState()
    val scope = rememberCoroutineScope()

    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Auto-scroll al fondo cuando llegan mensajes nuevos o el último crece.
    // scrollOffset = Int.MAX_VALUE clampa al máximo del contenido — así el
    // último mensaje queda anclado al BOTTOM del viewport (como un chat),
    // no al top (que es el default de animateScrollToItem y producía el
    // salto hacia arriba al enviar un mensaje corto).
    LaunchedEffect(chat.messages.size, chat.messages.lastOrNull()?.content?.length) {
        if (chat.messages.isNotEmpty()) {
            val target = chat.messages.size - 1
            listState.animateScrollToItem(target, scrollOffset = Int.MAX_VALUE)
        }
    }

    // Sincronizar el state del backend al entrar a la pantalla.
    LaunchedEffect(api) {
        val client = api ?: return@LaunchedEffect
        try {
            val state = withContext(Dispatchers.IO) { client.aiState() }
            app.repository.aiSyncFromState(
                busy = state.busy,
                sessionId = state.sessionId,
                model = state.model,
                turnId = state.turnId,
            )
        } catch (_: Exception) {
            // si falla la sincronización inicial seguimos con el estado local
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            // Empuja el contenido hacia arriba cuando aparece el teclado
            // — sin esto, el TextField y el botón Send quedan tapados.
            .imePadding(),
    ) {
        ScreenHeader(
            "IA",
            buildString {
                append("Claude Code · ")
                append(chat.model)
                if (chat.sessionId != null) append(" · resume")
                if (chat.busy) append(" · pensando…")
            },
        )

        Spacer(Modifier.height(8.dp))

        // Chips de acción.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                onClick = {
                    val client = api ?: return@AssistChip
                    scope.launch(Dispatchers.IO) {
                        try { client.aiReset() } catch (_: Exception) {}
                    }
                    app.repository.aiClearMessages()
                },
                enabled = !chat.busy,
                leadingIcon = {
                    Icon(Icons.Outlined.RestartAlt, null, Modifier.height(18.dp))
                },
                label = { Text("Nuevo") },
                colors = AssistChipDefaults.assistChipColors(labelColor = PandaCyan),
            )
            AssistChip(
                onClick = {
                    val client = api ?: return@AssistChip
                    scope.launch(Dispatchers.IO) {
                        try { client.aiCancel() } catch (_: Exception) {}
                    }
                },
                enabled = chat.busy,
                leadingIcon = {
                    Icon(Icons.Outlined.Stop, null, Modifier.height(18.dp))
                },
                label = { Text("Cancelar") },
                colors = AssistChipDefaults.assistChipColors(labelColor = PandaMagenta),
            )
        }

        Spacer(Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (chat.messages.isEmpty()) {
                item {
                    Text(
                        "Escribí algo abajo para hablar con Claude. " +
                            "Cada turno corre en /home/sergioc por default. " +
                            "El session_id queda persistido — los reinicios no rompen la conversación.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else {
                items(chat.messages, key = { it.id + "-" + it.role.name }) { msg ->
                    MessageBubble(msg)
                }
            }
            if (chat.busy && chat.messages.lastOrNull()?.role != AIRole.Assistant) {
                item {
                    Text(
                        "…",
                        modifier = Modifier.padding(start = 16.dp),
                        color = PandaYellow,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Pregúntale a Claude…") },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Default,
                ),
                maxLines = 6,
                enabled = !chat.busy,
            )
            FilledIconButton(
                onClick = {
                    val client = api ?: return@FilledIconButton
                    val text = input.trim()
                    if (text.isEmpty() || chat.busy) return@FilledIconButton
                    input = ""
                    scope.launch {
                        try {
                            val res = withContext(Dispatchers.IO) {
                                client.aiSend(text)
                            }
                            if (res.result == "ok") {
                                app.repository.aiAddUserMessage(text, res.turnId)
                            } else {
                                // Re-poner el texto si falló
                                input = text
                                app.repository.aiSetLastError(res.error ?: "error")
                            }
                        } catch (e: Exception) {
                            input = text
                            app.repository.aiSetLastError(
                                e.message?.take(80) ?: e::class.simpleName.orEmpty()
                            )
                        }
                    }
                },
                enabled = !chat.busy && input.isNotBlank() && api != null,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = PandaGreen.copy(alpha = 0.35f),
                    contentColor = PandaGreen,
                ),
            ) { Icon(Icons.Outlined.Send, contentDescription = "Enviar") }
        }

        chat.lastError?.let {
            Text(
                "✗ $it",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun MessageBubble(msg: AIMessage) {
    val isUser = msg.role == AIRole.User
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val bg = if (isUser) PandaCyan.copy(alpha = 0.15f)
             else MaterialTheme.colorScheme.surface
    val border = if (isUser) PandaCyan.copy(alpha = 0.6f)
                 else PandaYellow.copy(alpha = 0.5f)

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(bg)
                .border(1.dp, border, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (msg.tools.isNotEmpty()) {
                msg.tools.forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = PandaYellow,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
                if (msg.content.isNotEmpty()) Spacer(Modifier.height(4.dp))
            }
            if (msg.content.isNotEmpty()) {
                Text(
                    msg.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            msg.error?.let {
                Text(
                    "✗ $it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (msg.done && msg.role == AIRole.Assistant && msg.durationS != null) {
                Text(
                    "${"%.1f".format(msg.durationS)} s",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
