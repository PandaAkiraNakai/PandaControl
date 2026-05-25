package io.github.pandaakira.apppanda.ui.trends
import io.github.pandaakira.apppanda.ui.theme.LocalPandaColors

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import io.github.pandaakira.apppanda.PandaApp
import io.github.pandaakira.apppanda.data.models.MetricRow
import io.github.pandaakira.apppanda.data.models.MetricsResponse
import io.github.pandaakira.apppanda.ui.components.EmptyState
import io.github.pandaakira.apppanda.ui.components.PandaCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ranges = listOf("1h", "6h", "24h")

@Composable
fun TrendsScreen(app: PandaApp) {
    val api by app.repository.api.collectAsState()
    var range by remember { mutableStateOf("1h") }
    var data by remember { mutableStateOf<MetricsResponse?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(api, range) {
        api?.let {
            scope.launch {
                try {
                    data = withContext(Dispatchers.IO) { it.metrics(range) }
                    error = null
                } catch (e: Exception) {
                    error = e.message ?: e::class.simpleName
                }
            }
        }
    }

    if (api == null) {
        EmptyState("Configura el backend en Ajustes.")
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Text(
                "// TREND :: ${data?.rows?.size ?: 0} rows",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ranges.forEach { r ->
                    FilterChip(
                        selected = range == r,
                        onClick = { range = r },
                        label = { Text(r) },
                    )
                }
            }
        }

        error?.let {
            item {
                PandaCard(title = "ERROR", accent = MaterialTheme.colorScheme.error) {
                    Text(it.take(300), color = MaterialTheme.colorScheme.error)
                }
            }
        }

        val rows = data?.rows.orEmpty()
        if (rows.isEmpty() && error == null) {
            item { EmptyState("Sin datos todavía. El monitor escribe al SQLite cada `monitor.interval_s` (default 60 s).") }
        } else if (rows.isNotEmpty()) {
            item {
                PandaCard(title = "CPU / RAM / GPU (%)", accent = LocalPandaColors.current.yellow) {
                    Spacer(Modifier.height(8.dp))
                    LineChart(
                        rows = rows,
                        series = listOf(
                            Series("CPU", LocalPandaColors.current.yellow) { it.cpuPct },
                            Series("RAM", LocalPandaColors.current.cyan) { it.ramPct },
                            Series("GPU", LocalPandaColors.current.magenta) { it.gpuPct },
                            Series("Disco", LocalPandaColors.current.green) { it.diskPct },
                        ),
                        yMax = 100.0,
                    )
                }
            }
            item {
                PandaCard(title = "TEMPS (°C)", accent = LocalPandaColors.current.orange) {
                    Spacer(Modifier.height(8.dp))
                    LineChart(
                        rows = rows,
                        series = listOf(
                            Series("CPU", LocalPandaColors.current.yellow) { it.cpuTemp },
                            Series("GPU", LocalPandaColors.current.magenta) { it.gpuTemp },
                        ),
                        yMax = computeMaxTemp(rows),
                    )
                }
            }
        }
    }
}

private data class Series(val label: String, val color: Color, val extract: (MetricRow) -> Double?)

@Composable
private fun LineChart(rows: List<MetricRow>, series: List<Series>, yMax: Double) {
    val gridColor = LocalPandaColors.current.onSurfaceMuted
    Column(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 8.dp)) {
            series.forEach { s ->
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Canvas(modifier = Modifier.size(12.dp)) {
                        drawCircle(s.color)
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        s.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .background(MaterialTheme.colorScheme.background),
        ) {
            val w = size.width
            val h = size.height
            // grid horizontal
            for (i in 0..4) {
                val y = h * i / 4f
                drawLine(
                    gridColor.copy(alpha = 0.2f),
                    Offset(0f, y), Offset(w, y),
                    strokeWidth = 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)),
                )
            }
            if (rows.size < 2 || yMax <= 0) return@Canvas
            val tsMin = rows.first().ts
            val tsMax = rows.last().ts
            val tsRange = (tsMax - tsMin).coerceAtLeast(1).toFloat()

            series.forEach { s ->
                val path = Path()
                var started = false
                rows.forEach { r ->
                    val v = s.extract(r) ?: return@forEach
                    val x = ((r.ts - tsMin) / tsRange) * w
                    val y = h - (v / yMax).toFloat().coerceIn(0f, 1f) * h
                    if (!started) {
                        path.moveTo(x, y); started = true
                    } else {
                        path.lineTo(x, y)
                    }
                }
                drawPath(
                    path = path,
                    color = s.color,
                    style = Stroke(width = 2.5f, cap = StrokeCap.Round),
                )
            }
        }
    }
}

private fun computeMaxTemp(rows: List<MetricRow>): Double {
    val ts = rows.flatMap { listOfNotNull(it.cpuTemp, it.gpuTemp) }
    val m = ts.maxOrNull() ?: 100.0
    // Redondea al múltiplo de 10 más cercano hacia arriba, con headroom
    return ((m / 10).toInt() + 2) * 10.0
}
