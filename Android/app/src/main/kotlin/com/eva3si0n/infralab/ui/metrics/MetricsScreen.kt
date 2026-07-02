package com.eva3si0n.infralab.ui.metrics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eva3si0n.infralab.data.*
import com.eva3si0n.infralab.ui.AppViewModel
import kotlin.math.abs
import kotlin.math.roundToInt

private val PALETTE = listOf(
    Color(0xFF82B4FF), Color(0xFF5CDD8B), Color(0xFFF8A532), Color(0xFFE57DD0),
    Color(0xFF59C2C9), Color(0xFFFFD166), Color(0xFFB084F5), Color(0xFFFF8A65)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetricsScreen(vm: AppViewModel) {
    var selected by remember { mutableStateOf<DashboardInfo?>(null) }

    if (vm.grafanaBaseURL.isEmpty()) {
        Scaffold(topBar = { TopAppBar(title = { Text("Metrics") }) }) { p ->
            Box(Modifier.padding(p).fillMaxSize(), Alignment.Center) {
                Text("Grafana not configured — add URL and token in Settings",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    val sel = selected
    if (sel == null) {
        LaunchedEffect(Unit) { if (vm.dashboards.isEmpty()) vm.loadDashboards() }
        Scaffold(topBar = { TopAppBar(title = { Text("Metrics") }) }) { p ->
            Box(Modifier.padding(p)) {
                when {
                    vm.dashboards.isEmpty() && vm.dashboardsLoading ->
                        Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                    !vm.dashboardsError.isNullOrEmpty() && vm.dashboards.isEmpty() ->
                        Box(Modifier.fillMaxSize(), Alignment.Center) { Text(vm.dashboardsError ?: "") }
                    else -> LazyColumn {
                        items(vm.dashboards, key = { it.uid }) { d ->
                            ListItem(
                                headlineContent = { Text(d.title) },
                                leadingContent = { Icon(Icons.Default.BarChart, null) },
                                modifier = Modifier.clickable { selected = d }
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    } else {
        DashboardDetail(vm, sel) { selected = null }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashboardDetail(vm: AppViewModel, dashboard: DashboardInfo, onBack: () -> Unit) {
    var panels by remember { mutableStateOf<List<PanelDef>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(dashboard.uid) {
        loading = true; error = null
        try { panels = vm.fetchPanels(dashboard.uid) }
        catch (e: Exception) { error = e.message }
        loading = false
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(dashboard.title, maxLines = 1) },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }
        )
    }) { p ->
        Box(Modifier.padding(p)) {
            when {
                loading && panels.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                error != null && panels.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) { Text(error ?: "") }
                else -> LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(panels.size) { i ->
                        val panel = panels[i]
                        if (panel.kind == PanelKind.ROW) {
                            Text(panel.title.uppercase(), style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp))
                        } else {
                            NativePanel(vm, panel)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NativePanel(vm: AppViewModel, panel: PanelDef) {
    var series by remember(panel) { mutableStateOf<List<MetricSeries>>(emptyList()) }
    var rows by remember(panel) { mutableStateOf<List<InstantRow>>(emptyList()) }
    var loading by remember(panel) { mutableStateOf(true) }
    var error by remember(panel) { mutableStateOf<String?>(null) }

    LaunchedEffect(panel) {
        loading = true; error = null
        try {
            if (panel.kind == PanelKind.TIMESERIES) {
                series = panel.targets.flatMap { vm.promRange(it.first, it.second) }
            } else {
                rows = panel.targets.flatMap { vm.promInstant(it.first, it.second) }
            }
        } catch (e: Exception) { error = e.message }
        loading = false
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(panel.title.ifEmpty { " " }, style = MaterialTheme.typography.titleSmall)
            when {
                loading && series.isEmpty() && rows.isEmpty() ->
                    Box(Modifier.fillMaxWidth().height(80.dp), Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
                error != null && series.isEmpty() && rows.isEmpty() ->
                    Text(error ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> when (panel.kind) {
                    PanelKind.TIMESERIES -> TimeseriesChart(series)
                    PanelKind.STAT -> StatView(rows, panel.unit)
                    PanelKind.GAUGE -> GaugeView(rows, panel.unit)
                    PanelKind.BARGAUGE -> BarGaugeView(rows, panel.unit)
                    PanelKind.TABLE -> TableView(rows, panel.unit)
                    else -> Text("Unsupported panel", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun TimeseriesChart(series: List<MetricSeries>) {
    if (series.isEmpty()) {
        Text("No data", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val allPts = series.flatMap { it.points }
    val tMin = allPts.minOf { it.timeMs }; val tMax = allPts.maxOf { it.timeMs }
    val vMin = allPts.minOf { it.value }; val vMax = allPts.maxOf { it.value }
    val tSpan = (tMax - tMin).coerceAtLeast(1).toFloat()
    val vSpan = (vMax - vMin).let { if (it <= 0.0) 1.0 else it }.toFloat()

    Canvas(Modifier.fillMaxWidth().height(150.dp)) {
        series.forEachIndexed { si, s ->
            val color = PALETTE[si % PALETTE.size]
            val pts = s.points.map { p ->
                Offset(
                    x = (p.timeMs - tMin) / tSpan * size.width,
                    y = size.height - ((p.value - vMin).toFloat() / vSpan) * size.height
                )
            }
            for (i in 1 until pts.size) {
                drawLine(color, pts[i - 1], pts[i], strokeWidth = 3f)
            }
        }
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        series.forEachIndexed { i, s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(8.dp)) { drawCircle(PALETTE[i % PALETTE.size]) }
                Spacer(Modifier.width(4.dp))
                Text(s.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun StatView(rows: List<InstantRow>, unit: String) {
    if (rows.size <= 1) {
        Text(rows.firstOrNull()?.let { GUnit.format(it.value, unit) } ?: "—",
            style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary)
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            rows.forEach { r ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(r.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, modifier = Modifier.weight(1f))
                    Text(GUnit.format(r.value, unit), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun GaugeView(rows: List<InstantRow>, unit: String) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { r ->
            if (unit == "percent" || unit == "percentunit") {
                val pct = (if (unit == "percentunit") r.value * 100 else r.value).coerceIn(0.0, 100.0)
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(r.name, style = MaterialTheme.typography.labelMedium)
                        Text("${pct.roundToInt()}%", style = MaterialTheme.typography.labelMedium)
                    }
                    LinearProgressIndicator(progress = { (pct / 100).toFloat() }, modifier = Modifier.fillMaxWidth())
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(r.name, style = MaterialTheme.typography.bodyMedium)
                    Text(GUnit.format(r.value, unit), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun BarGaugeView(rows: List<InstantRow>, unit: String) {
    val maxVal = (rows.maxOfOrNull { it.value } ?: 1.0).coerceAtLeast(1e-6)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { r ->
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(r.name, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    Text(GUnit.format(r.value, unit), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LinearProgressIndicator(progress = { (r.value / maxVal).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun TableView(rows: List<InstantRow>, unit: String) {
    Column {
        rows.forEachIndexed { i, r ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(r.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.weight(1f))
                Text(GUnit.format(r.value, unit), style = MaterialTheme.typography.bodySmall)
            }
            if (i < rows.size - 1) HorizontalDivider()
        }
    }
}

object GUnit {
    fun format(v: Double, unit: String): String = when (unit) {
        "percent" -> pct(v)
        "percentunit" -> pct(v * 100)
        "bytes", "decbytes", "bytes_iec" -> bytes(v)
        "celsius" -> "%.0f°C".format(v)
        "bps" -> rate(v, "bps")
        "Bps", "binBps" -> rate(v, "B/s")
        "s", "seconds" -> "%.1fs".format(v)
        else -> short(v)
    }
    private fun pct(v: Double) = if (v >= 10) "%.0f%%".format(v) else "%.1f%%".format(v)
    private fun short(v: Double): String {
        val a = abs(v)
        return when {
            a >= 1e9 -> "%.1fB".format(v / 1e9)
            a >= 1e6 -> "%.1fM".format(v / 1e6)
            a >= 1e3 -> "%.1fK".format(v / 1e3)
            a < 10 -> "%.2f".format(v)
            else -> "%.0f".format(v)
        }
    }
    private fun rate(v: Double, s: String): String {
        val a = abs(v)
        return when {
            a >= 1e9 -> "%.1f G%s".format(v / 1e9, s)
            a >= 1e6 -> "%.1f M%s".format(v / 1e6, s)
            a >= 1e3 -> "%.1f K%s".format(v / 1e3, s)
            else -> "%.0f %s".format(v, s)
        }
    }
    private fun bytes(v: Double): String {
        val a = abs(v)
        return when {
            a >= 1L shl 30 -> "%.1f GiB".format(v / (1L shl 30))
            a >= 1L shl 20 -> "%.1f MiB".format(v / (1L shl 20))
            a >= 1L shl 10 -> "%.1f KiB".format(v / (1L shl 10))
            else -> "%.0f B".format(v)
        }
    }
}
