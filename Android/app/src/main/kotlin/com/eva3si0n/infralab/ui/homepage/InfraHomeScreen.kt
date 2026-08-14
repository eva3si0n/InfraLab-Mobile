package com.eva3si0n.infralab.ui.homepage

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eva3si0n.infralab.data.*
import com.eva3si0n.infralab.ui.AppViewModel
import kotlinx.coroutines.launch

// InfraHome — нативная витрина лаборатории вместо прежнего web-view на gethomepage.
//
// Почему нативно: снаружи дома InfraHome закрыт Cloudflare Access, и встроенный браузер
// требовал бы логина на каждом запуске. Экран ходит по /api/home с тем же service token,
// что и каскад, и работает из любой сети.
//
// Порядок на телефоне НЕ повторяет веб: там наверху плитки, а здесь сначала то, ради чего
// в дашборд заглядывают с телефона — активные алерты и сводка мониторов.

private val OK = Color(0xFF5CDD8B)
private val WARN = Color(0xFFF8A532)
private val CRIT = Color(0xFFFF4D4D)

private fun statusColor(s: String?): Color = when (s) {
    "crit", "critical", "down" -> CRIT
    "warn", "warning" -> WARN
    "ok", "up" -> OK
    else -> Color.Gray
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfraHomeScreen(vm: AppViewModel) {
    var data by remember { mutableStateOf<InfraHomePayload?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Явно раскрытые группы. Свёрнутость по умолчанию берём с веба, а этот набор её
    // перекрывает и переживает автообновление.
    val expanded = remember { mutableStateListOf<String>() }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        if (vm.homePageBaseURL.isEmpty()) return
        loading = true
        val p = vm.fetchInfraHome()
        if (p == null) error = "InfraHome недоступен" else { data = p; error = null }
        loading = false
    }

    LaunchedEffect(Unit) { load() }

    Scaffold(topBar = { TopAppBar(title = { Text("InfraHome") }) }) { padding ->
        Box(Modifier.padding(padding)) {
            when {
                vm.homePageBaseURL.isEmpty() ->
                    Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Add InfraHome URL in Settings") }
                data == null && loading ->
                    Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                data == null ->
                    Box(Modifier.fillMaxSize(), Alignment.Center) { Text(error ?: "Failed to Load") }
                else -> PullToRefreshBox(isRefreshing = loading, onRefresh = { scope.launch { load() } }) {
                    LazyColumn(
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val d = data!!
                        val al = d.alerts
                        if (al != null && (al.firing > 0 || al.pending > 0)) item(key = "alerts") {
                            ElevatedCard {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("Алерты", style = MaterialTheme.typography.titleSmall)
                                    al.list.forEach { a ->
                                        Row(verticalAlignment = Alignment.Top) {
                                            Box(
                                                Modifier.padding(top = 5.dp).size(8.dp)
                                                    .background(statusColor(a.severity), CircleShape)
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Column {
                                                Text(a.name ?: "—", style = MaterialTheme.typography.bodyMedium)
                                                if (!a.summary.isNullOrEmpty()) Text(
                                                    a.summary, style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        d.kuma?.let { k ->
                            item(key = "kuma") {
                                ElevatedCard {
                                    Row(
                                        Modifier.padding(12.dp).fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Counter("up", k.up, OK); Counter("down", k.down, CRIT)
                                        Counter("pending", k.pending, WARN)
                                        Spacer(Modifier.weight(1f))
                                        Text("всего ${k.total}", style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }

                        items(d.groups) { g ->
                            val key = g.name ?: ""
                            val open = if (expanded.contains(key)) true else !g.collapsed
                            ElevatedCard {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(
                                        Modifier.fillMaxWidth().clickable {
                                            if (open) expanded.remove(key) else expanded.add(key)
                                        },
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(if (open) "▾" else "▸", style = MaterialTheme.typography.labelSmall)
                                        Spacer(Modifier.width(6.dp))
                                        Text(g.name ?: "—", style = MaterialTheme.typography.titleSmall)
                                        Spacer(Modifier.weight(1f))
                                        Text("${g.tiles.size}", style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (open) g.tiles.forEach { TileRow(it) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Counter(label: String, n: Int, c: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp)) {
        Box(Modifier.size(7.dp).background(if (n > 0) c else Color.Gray, CircleShape))
        Spacer(Modifier.width(4.dp))
        Text("$n", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(3.dp))
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TileRow(t: HomeTile) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(statusColor(t.status), CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(t.name ?: "—", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            if (!t.badge.isNullOrEmpty()) Text(
                t.badge, style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 1.dp)
            )
        }
        if (!t.description.isNullOrEmpty()) Text(
            t.description, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // Показываем ТОЛЬКО метрики не в порядке: полный список из семи строк на плитку
        // превращает экран в простыню, а смысл витрины — заметить отклонение.
        t.metrics.filter { (it.state ?: "ok") != "ok" }.forEach { m ->
            Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(5.dp).background(statusColor(m.state), CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(m.label ?: "—", style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.weight(1f))
                Text(m.text ?: "", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
