package io.github.pandaakira.apppanda.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.pandaakira.apppanda.PandaApp
import io.github.pandaakira.apppanda.data.models.CalEvent
import io.github.pandaakira.apppanda.data.models.CalEventReq
import io.github.pandaakira.apppanda.data.models.CalendarResponse
import io.github.pandaakira.apppanda.ui.components.ConfirmDialog
import io.github.pandaakira.apppanda.ui.components.EmptyState
import io.github.pandaakira.apppanda.ui.components.ErrorCard
import io.github.pandaakira.apppanda.ui.components.PandaCard
import io.github.pandaakira.apppanda.ui.components.ScreenHeader
import io.github.pandaakira.apppanda.ui.components.rememberActionExecutor
import io.github.pandaakira.apppanda.ui.theme.LocalPandaColors
import io.github.pandaakira.apppanda.ui.theme.PandaIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.util.Locale

private val ES = Locale("es")
private val WEEKDAYS = listOf("L", "M", "M", "J", "V", "S", "D")
private fun ymd(d: LocalDate) = "%04d-%02d-%02d".format(d.year, d.monthValue, d.dayOfMonth)

@Composable
fun CalendarScreen(app: PandaApp) {
    val api by app.repository.api.collectAsState()
    var month by remember { mutableStateOf(YearMonth.now()) }
    var selected by remember { mutableStateOf(LocalDate.now()) }
    var data by remember { mutableStateOf<CalendarResponse?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf<CalEvent?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val exec = rememberActionExecutor { api }

    // Trae el mes visible (con margen para los días de meses vecinos en grilla).
    LaunchedEffect(api, month, refresh) {
        val current = api ?: return@LaunchedEffect
        val from = ymd(month.atDay(1).minusDays(7))
        val to = ymd(month.atEndOfMonth().plusDays(7))
        try {
            data = withContext(Dispatchers.IO) { current.calendario(from, to) }
            error = null
        } catch (e: Exception) {
            error = e.message ?: e::class.simpleName
        }
    }

    val eventos = data?.eventos ?: emptyList()
    val porFecha = remember(eventos) { eventos.groupBy { it.fecha } }
    val delDia = (porFecha[ymd(selected)] ?: emptyList())
        .sortedWith(compareBy({ !it.todoElDia }, { it.hora }))

    Scaffold(
        containerColor = Color.Transparent,
        floatingActionButton = {
            FloatingActionButton(
                onClick = { editing = null; showEditor = true },
                containerColor = LocalPandaColors.current.cyan,
            ) { Icon(PandaIcons.add, contentDescription = "Nuevo evento") }
        },
    ) { pad ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().padding(pad),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        ScreenHeader(
                            "AGENDA",
                            "${month.month.getDisplayName(TextStyle.FULL, ES)
                                .replaceFirstChar { it.uppercase() }} ${month.year}",
                        )
                    }
                    TextButton(onClick = {
                        month = YearMonth.now(); selected = LocalDate.now()
                    }) { Text("hoy") }
                    IconButton(onClick = { month = month.minusMonths(1) }) {
                        Icon(PandaIcons.chevronLeft, contentDescription = "Mes anterior")
                    }
                    IconButton(onClick = { month = month.plusMonths(1) }) {
                        Icon(PandaIcons.chevronRight, contentDescription = "Mes siguiente")
                    }
                }
            }

            error?.let { item { ErrorCard(it) } }

            item {
                MonthGrid(
                    month = month,
                    selected = selected,
                    diasConEventos = porFecha.keys,
                    onSelect = { selected = it },
                )
            }

            item {
                Text(
                    selected.format(
                        java.time.format.DateTimeFormatter
                            .ofPattern("EEEE d 'de' MMMM", ES),
                    ).replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.titleSmall,
                    color = LocalPandaColors.current.cyan,
                )
            }

            if (delDia.isEmpty()) {
                item { EmptyState("Sin eventos este día. Tocá + para agregar uno.") }
            } else {
                items(delDia, key = { it.id }) { ev ->
                    EventoCard(ev) { editing = ev; showEditor = true }
                }
            }

            item {
                io.github.pandaakira.apppanda.ui.components.ActionResultBanner(exec)
            }
        }
    }

    if (showEditor) {
        EventEditorDialog(
            evento = editing,
            fechaInicial = selected,
            onDismiss = { showEditor = false },
            onSave = { req ->
                val ed = editing
                if (ed == null) exec.run("crear") { it.calendarioCreate(req) }
                else exec.run("guardar") { it.calendarioUpdate(ed.id, req) }
                scope.launch { kotlinx.coroutines.delay(500); refresh++ }
                showEditor = false
            },
            onDelete = { ed ->
                exec.run("borrar") { it.calendarioDelete(ed.id) }
                scope.launch { kotlinx.coroutines.delay(500); refresh++ }
                showEditor = false
            },
        )
    }
}

@Composable
private fun MonthGrid(
    month: YearMonth,
    selected: LocalDate,
    diasConEventos: Set<String>,
    onSelect: (LocalDate) -> Unit,
) {
    val first = month.atDay(1)
    val offset = first.dayOfWeek.value - 1 // lunes = 0
    val total = month.lengthOfMonth()
    val today = LocalDate.now()

    Column {
        Row(Modifier.fillMaxWidth()) {
            WEEKDAYS.forEach { d ->
                Text(
                    d, textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        var day = 1
        val rows = ((offset + total + 6) / 7)
        repeat(rows) { r ->
            Row(Modifier.fillMaxWidth()) {
                repeat(7) { c ->
                    val cellIndex = r * 7 + c
                    if (cellIndex < offset || day > total) {
                        Box(Modifier.weight(1f).aspectRatio(1f))
                    } else {
                        val date = month.atDay(day)
                        DayCell(
                            day = date.dayOfMonth,
                            isToday = date == today,
                            isSelected = date == selected,
                            hasEvents = ymd(date) in diasConEventos,
                            onClick = { onSelect(date) },
                            modifier = Modifier.weight(1f),
                        )
                        day++
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    day: Int,
    isToday: Boolean,
    isSelected: Boolean,
    hasEvents: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cyan = LocalPandaColors.current.cyan
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .padding(2.dp)
            .clip(CircleShape)
            .then(
                if (isSelected) Modifier.background(cyan.copy(alpha = 0.25f))
                else Modifier,
            )
            .then(
                if (isToday) Modifier.border(1.5.dp, cyan, CircleShape)
                else Modifier,
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                day.toString(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (hasEvents) {
                Box(
                    Modifier.size(5.dp).clip(CircleShape)
                        .background(LocalPandaColors.current.magenta),
                )
            }
        }
    }
}

@Composable
private fun EventoCard(ev: CalEvent, onClick: () -> Unit) {
    PandaCard(
        title = if (ev.todoElDia) "TODO EL DÍA" else ev.hora,
        accent = LocalPandaColors.current.cyan,
        modifier = Modifier.clickable { onClick() },
    ) {
        Text(
            ev.titulo,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (ev.ubicacion.isNotBlank()) {
            Text(
                "📍 ${ev.ubicacion}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (ev.descripcion.isNotBlank()) {
            Text(
                ev.descripcion,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (ev.recordatorioMin != null) {
            Text(
                "🔔 ${reminderLabel(ev.recordatorioMin)}",
                style = MaterialTheme.typography.labelSmall,
                color = LocalPandaColors.current.yellow,
            )
        }
    }
}

private val REMINDERS = listOf(
    null to "sin", 0 to "al momento", 10 to "10 min",
    30 to "30 min", 60 to "1 h", 1440 to "1 día",
)

private fun reminderLabel(min: Int?): String =
    REMINDERS.firstOrNull { it.first == min }?.second
        ?: "${min} min antes"

@OptIn(
    ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)
@Composable
private fun EventEditorDialog(
    evento: CalEvent?,
    fechaInicial: LocalDate,
    onDismiss: () -> Unit,
    onSave: (CalEventReq) -> Unit,
    onDelete: (CalEvent) -> Unit,
) {
    var titulo by remember { mutableStateOf(evento?.titulo ?: "") }
    var descripcion by remember { mutableStateOf(evento?.descripcion ?: "") }
    var ubicacion by remember { mutableStateOf(evento?.ubicacion ?: "") }
    var todoElDia by remember { mutableStateOf(evento?.todoElDia ?: false) }
    var fecha by remember {
        mutableStateOf(evento?.fecha?.let { LocalDate.parse(it) } ?: fechaInicial)
    }
    var hora by remember { mutableStateOf(evento?.hora?.takeIf { it.isNotBlank() } ?: "09:00") }
    var recordatorio by remember { mutableStateOf(evento?.recordatorioMin) }
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                if (evento == null) "Nuevo evento" else "Editar evento",
                color = MaterialTheme.colorScheme.primary,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = titulo, onValueChange = { titulo = it },
                    label = { Text("Título") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Todo el día", Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurface)
                    Switch(checked = todoElDia, onCheckedChange = { todoElDia = it })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showDate = true }, modifier = Modifier.weight(1f)) {
                        Text(ymd(fecha))
                    }
                    if (!todoElDia) {
                        OutlinedButton(onClick = { showTime = true }, modifier = Modifier.weight(1f)) {
                            Text(hora)
                        }
                    }
                }
                OutlinedTextField(
                    value = ubicacion, onValueChange = { ubicacion = it },
                    label = { Text("Ubicación") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = descripcion, onValueChange = { descripcion = it },
                    label = { Text("Descripción") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Recordatorio", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    REMINDERS.forEach { (min, label) ->
                        FilterChip(
                            selected = recordatorio == min,
                            onClick = { recordatorio = min },
                            label = { Text(label) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (titulo.isBlank()) return@TextButton
                    val inicio = if (todoElDia) ymd(fecha) else "${ymd(fecha)}T$hora"
                    onSave(
                        CalEventReq(
                            titulo = titulo.trim(),
                            inicio = inicio,
                            descripcion = descripcion.trim(),
                            ubicacion = ubicacion.trim(),
                            todoElDia = todoElDia,
                            recordatorioMin = recordatorio,
                        ),
                    )
                },
            ) { Text("Guardar") }
        },
        dismissButton = {
            Row {
                if (evento != null) {
                    TextButton(onClick = { confirmDelete = true }) {
                        Text("Eliminar", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("Cancelar") }
            }
        },
    )

    if (showDate) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = fecha.atStartOfDay(ZoneOffset.UTC)
                .toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        fecha = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                    }
                    showDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("Cancelar") } },
        ) { DatePicker(state = state) }
    }

    if (showTime) {
        val parts = hora.split(":")
        val state = rememberTimePickerState(
            initialHour = parts.getOrNull(0)?.toIntOrNull() ?: 9,
            initialMinute = parts.getOrNull(1)?.toIntOrNull() ?: 0,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { showTime = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Hora", color = MaterialTheme.colorScheme.primary) },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    hora = "%02d:%02d".format(state.hour, state.minute)
                    showTime = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showTime = false }) { Text("Cancelar") } },
        )
    }

    if (confirmDelete && evento != null) {
        ConfirmDialog(
            title = "Eliminar evento",
            message = "¿Borrar \"${evento.titulo}\"?",
            confirmLabel = "Eliminar",
            onConfirm = { confirmDelete = false; onDelete(evento) },
            onDismiss = { confirmDelete = false },
        )
    }
}
