package com.eva3si0n.infralab.ui.cascade

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eva3si0n.infralab.data.KumaHeartbeat
import com.eva3si0n.infralab.data.MonitorStatus
import com.eva3si0n.infralab.ui.AppViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape

// VPN Cascade — per-segment egress state + Kuma cascade health + egress-leg traffic + migration history.
// Data: единственный источник — vpncascade GET /api/cascade (тонкий клиент).
// Раньше экран собирался из десяти PromQL-выборок через Grafana-прокси и расходился
// с веб-панелью при каждой её доработке.
private val STO = Color(0xFF5CDD8B)
private val AMS = Color(0xFFF8A532)
private val FI = Color(0xFFDC3D46)
private val DOWN = Color(0xFFDC3D46)
private val PENDING = Color(0xFFF8A532)
private val MAINT = Color(0xFF459BFF)
private fun legColor(l: String) = when (l) { "sto" -> STO; "ams" -> AMS; "fi" -> FI; else -> Color.Gray }

// Migration reason (from vpn_egress_switch_time reason label): precise on new lines, coarse on legacy.
private fun reasonText(r: String) = when (r) {
    "stale_handshake" -> "stale HS"
    "unreachable" -> "no route"
    "link_down" -> "link down"
    "failback" -> "failback"
    "failover" -> "failover"
    "initial" -> "boot"
    else -> r
}
private fun reasonColor(r: String) = when (r) {
    "failback" -> STO
    "initial" -> MAINT
    "failover" -> PENDING
    "stale_handshake", "unreachable", "link_down" -> DOWN
    else -> Color.Gray
}

private const val CASCADE_HINT ="up — активное плечо STO/AMS (чистый Vultr-egress); down — деградация на FI (оба Vultr-плеча недоступны) или несвежий handshake."
private const val EGRESS_HINT = "Лимит Vultr 2 ТБ на инстанс (STO и AMS отдельно), считается outbound (tx), сброс 1-го числа. FI — cold standby, квота не отслеживается."

private data class Seg(
    val host: String, val title: String, val activeLeg: String, val activeSeconds: Double,
    val rtt: Map<String, Double>, val txBps: Double?, val rxBps: Double?,
    val healthy: Boolean, val cascade: MonitorStatus?
)
private data class Leg(val leg: String, val homeRtt: Double?, val txBytes: Double?, val limitBytes: Double?)
private data class Migration(val host: String, val label: String, val from: String, val to: String, val epoch: Long, val reason: String = "", val group: String = "udm")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CascadeScreen(vm: AppViewModel, onOpenPaths: () -> Unit = {}) {
    var segs by remember { mutableStateOf<List<Seg>>(emptyList()) }
    var legs by remember { mutableStateOf<List<Leg>>(emptyList()) }
    var history by remember { mutableStateOf<List<Migration>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<Triple<String, String, String>?>(null) }  // seg, leg, label
    var switching by remember { mutableStateOf(false) }
    var switchNote by remember { mutableStateOf<String?>(null) }
    var manual by remember { mutableStateOf<Map<String, String>>(emptyMap()) }  // host → forced leg
    var series by remember { mutableStateOf<Map<String, Pair<List<Double>, List<Double>>>>(emptyMap()) }  // host → tx,rx
    val showForce = vm.hasSwitchToken()
    val scope = rememberCoroutineScope()

    suspend fun load() {
        // Один запрос вместо десяти PromQL-выборок: экран стал тонким клиентом над
        // vpncascade. Логика (активное плечо, RTT, throughput, месячный трафик, история,
        // здоровье узла) живёт в сервисе — том же, что рисует веб-панель, поэтому
        // приложение и веб больше не расходятся при каждой доработке.
        if (vm.vpncascadeBaseURL.isEmpty()) return
        loading = true
        try {
            val p = vm.fetchCascadePayload()
            if (p == null) { error = "vpncascade недоступен"; return }
            error = p.error

            // Порядок как на веб-странице: сначала «РКН Ingress», затем домашний каскад.
            // sortedBy стабильна, поэтому внутри группы порядок сервиса сохраняется.
            segs = p.segments.sortedBy { if (it.group == "rkn") 0 else 1 }.map { sg ->
                // Вложенный монитор приезжает готовым блоком; собираем MonitorStatus,
                // чтобы не трогать вёрстку карточки.
                val mon = sg.cascade?.let { c ->
                    MonitorStatus(
                        id = 0, name = "Cascade", groupName = "VPN Cascade",
                        isUp = c.isUp ?: false, latency = null,
                        uptime24h = c.uptime24h ?: 0.0,
                        recentBeats = c.recentBeats.map {
                            KumaHeartbeat(it.status ?: 0, it.ping, it.time ?: "")
                        }
                    )
                }
                Seg(sg.host, sg.title ?: sg.host, sg.activeLeg ?: "—", sg.activeSeconds ?: 0.0,
                    sg.rtt, sg.txBps, sg.rxBps, sg.healthy ?: false, mon)
            }

            legs = p.legs.map { Leg(it.leg, it.homeRTT, it.txBytes, it.limitBytes) }

            history = p.history.mapNotNull { m ->
                val h = m.host; val f = m.from; val t = m.to; val ts = m.time
                if (h == null || f == null || t == null || ts == null) null
                else Migration(h, m.label ?: h, f, t, ts.toLong(), m.reason ?: "", m.group ?: "udm")
            }.sortedByDescending { it.epoch }

            // Ручной пин и график throughput — из тех же сегментов, отдельная выборка
            // fetchCascadeAux больше не нужна.
            manual = p.segments.filter { it.manual && !it.override.isNullOrEmpty() && it.override != "auto" }
                .associate { it.host to (it.override ?: "") }
            series = p.segments.associate { it.host to (it.txSeries to it.rxSeries) }
        } catch (e: Exception) {
            error = e.message ?: "error"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) { load() }

    Scaffold(topBar = { TopAppBar(title = { Text("VPN Cascade") }) }) { padding ->
        Box(Modifier.padding(padding)) {
            when {
                // Экран больше не зависит от Grafana: всё приезжает из vpncascade.
                vm.vpncascadeBaseURL.isEmpty() -> EmptyState("VPN Cascade Not Configured", "Set VPN Cascade URL in Settings")
                segs.isEmpty() && loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                segs.isEmpty() && error != null -> EmptyState("Failed to Load", error!!)
                else -> BoxWithConstraints {
                    // On the unfolded / tablet width show the two segment cards side by side at ~folded
                    // width (capped), instead of stretching each across the whole screen.
                    val wide = maxWidth >= 600.dp
                    val cardW = ((maxWidth - 36.dp) / 2).coerceAtMost(400.dp)
                    val groupW = cardW * 2 + 12.dp
                    PullToRefreshBox(isRefreshing = loading, onRefresh = { scope.launch { load() } }) {
                        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            // Транзит — отдельным экраном, а не секцией здесь: у него своя
                            // логика чтения, и мешать её с оперативной картиной каскада незачем.
                            item(key = "pathsLink") {
                                ElevatedCard(onClick = onOpenPaths, modifier = Modifier.fillMaxWidth()) {
                                    Row(
                                        Modifier.padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text("Транзит — AS-путь до плеч",
                                                style = MaterialTheme.typography.titleSmall)
                                            Text("через какие AS идёт путь до каждого плеча",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Spacer(Modifier.weight(1f))
                                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                                    }
                                }
                            }
                            if (manual.isNotEmpty()) item(key = "manual") {
                                val txt = manual.entries.joinToString("; ") { (h, leg) ->
                                    val lbl = vm.cascadeSegments.firstOrNull { it.host == h }?.title?.substringBefore(" · ") ?: h
                                    "$lbl → ${leg.uppercase()}"
                                }
                                Card(
                                    Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = AMS.copy(alpha = 0.18f))
                                ) {
                                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("🔴", style = MaterialTheme.typography.bodyMedium)
                                        Text("Ручной режим — $txt. Верни Auto, когда не нужно.",
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                            item(key = "decision") {
                                if (wide) Box(Modifier.fillMaxWidth(), Alignment.Center) { Box(Modifier.width(groupW)) { DecisionCard(segs, history) } }
                                else DecisionCard(segs, history)
                            }
                            if (wide) {
                                item(key = "segrow") {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
                                        segs.forEach { Box(Modifier.width(cardW)) { SegCard(it, showForce, series[it.host]) { seg, leg, label -> pending = Triple(seg, leg, label) } } }
                                    }
                                }
                            } else {
                                items(segs) { SegCard(it, showForce, series[it.host]) { seg, leg, label -> pending = Triple(seg, leg, label) } }
                            }
                            item(key = "egress") {
                                if (wide) Box(Modifier.fillMaxWidth(), Alignment.Center) { Box(Modifier.width(groupW)) { EgressCard(legs) } }
                                else EgressCard(legs)
                            }
                            item(key = "history") {
                                if (wide) Box(Modifier.fillMaxWidth(), Alignment.Center) { Box(Modifier.width(groupW)) { HistoryCard(history) } }
                                else HistoryCard(history)
                            }
                        }
                    }
                }
            }
        }

        // Two-step confirm for a manual leg-switch.
        pending?.let { (seg, leg, label) ->
            AlertDialog(
                onDismissRequest = { pending = null },
                title = { Text("Переключить активное плечо?") },
                text = { Text(label) },
                confirmButton = {
                    TextButton(enabled = !switching, onClick = {
                        pending = null; switching = true
                        scope.launch {
                            try {
                                val r = vm.switchLeg(seg, leg)
                                switchNote = "✓ $label: активно ${(r.active ?: "").uppercase()} (override ${r.override ?: "—"})"
                                kotlinx.coroutines.delay(1000); load()
                            } catch (e: Exception) {
                                switchNote = "✗ ${e.message}"
                            } finally { switching = false }
                        }
                    }) { Text("Переключить") }
                },
                dismissButton = { TextButton(onClick = { pending = null }) { Text("Отмена") } }
            )
        }
        switchNote?.let { note ->
            AlertDialog(
                onDismissRequest = { switchNote = null },
                title = { Text("VPN Cascade") },
                text = { Text(note) },
                confirmButton = { TextButton(onClick = { switchNote = null }) { Text("OK") } }
            )
        }
    }
}

// Top summary: current route decision per segment, swipeable left/right (Wired / Mobile).
@Composable
private fun DecisionCard(segs: List<Seg>, history: List<Migration>) {
    if (segs.isEmpty()) return
    val pager = rememberPagerState(pageCount = { segs.size })
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Cascade decision", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                Text(segs.getOrNull(pager.currentPage)?.title?.substringBefore(" · ") ?: "",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalPager(state = pager) { page -> DecisionPage(segs[page], history) }
            if (segs.size > 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Text(segs.indices.joinToString(" ") { if (it == pager.currentPage) "●" else "○" },
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "Primary healthy — маршрут на приоритетном STO; Failover from STO — ушли с primary (справа причина); " +
                    "Both Vultr legs down — оба Vultr-плеча недоступны, работаем на FI.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DecisionPage(s: Seg, history: List<Migration>) {
    val order = listOf("sto", "ams", "fi")
    val cur = s.activeLeg
    val curRtt = s.rtt[cur]
    val alt = order.firstOrNull { it != cur && it != "fi" }
    val cold = if (cur != "fi") "fi" else null
    fun delta(leg: String?): String {
        val r = leg?.let { s.rtt[it] }
        if (r == null || curRtt == null) return ""
        val d = (r - curRtt).toInt(); return (if (d >= 0) "+$d" else "$d") + " ms"
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Route", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        DecisionRow("Current", cur, null)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Reason", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(104.dp))
            Text(decisionReason(s, history), style = MaterialTheme.typography.bodyMedium)
        }
        if (alt != null) DecisionRow("Alternative", alt, delta(alt))
        if (cold != null) DecisionRow("Cold standby", cold, delta(cold))
    }
}

@Composable
private fun DecisionRow(label: String, leg: String, delta: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(104.dp))
        Pill(leg.uppercase(), legColor(leg))
        if (!delta.isNullOrEmpty()) Text(delta, style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// Reason the route currently sits on its active leg.
private fun decisionReason(s: Seg, history: List<Migration>): String {
    val cur = s.activeLeg
    if (cur == "sto") return if (s.healthy) "Primary healthy" else "On primary (degraded)"
    if (cur == "fi") return "Both Vultr legs down"
    val last = history.filter { it.host == s.host && it.to == cur }.maxByOrNull { it.epoch }?.reason
    return if (last in listOf("stale_handshake", "unreachable", "link_down"))
        "Failover from STO · ${reasonText(last!!)}" else "Failover from STO"
}

@Composable
private fun SegCard(
    s: Seg,
    showForce: Boolean = false,
    spark: Pair<List<Double>, List<Double>>? = null,
    onForce: (String, String, String) -> Unit = { _, _, _ -> }
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(s.title, style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Active leg", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill(s.activeLeg.uppercase(), legColor(s.activeLeg))
                    Pill(if (s.healthy) "Healthy" else "Unhealthy", if (s.healthy) STO else DOWN)
                    Pill(if (s.activeLeg == "sto") "Primary" else "Secondary", if (s.activeLeg == "sto") STO else AMS)
                    Text(fmtDur(s.activeSeconds), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (s.txBps != null && s.rxBps != null) {
                KV("Throughput WG · rate 2m", "↑ ${fmtBps(s.txBps)}  ↓ ${fmtBps(s.rxBps)}")
            }
            spark?.let { (tx, rx) ->
                if (tx.size > 1) {
                    Sparkline(tx, rx, Modifier.fillMaxWidth().height(34.dp))
                    Text("↑ tx · ↓ rx · 1h", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            KV("RTT STO", s.rtt["sto"]?.let { "${it.toInt()} ms" } ?: "—")
            KV("RTT AMS", s.rtt["ams"]?.let { "${it.toInt()} ms" } ?: "—")
            KV("RTT FI", s.rtt["fi"]?.let { "${it.toInt()} ms" } ?: "—")
            if (showForce) {
                val segLabel = s.title.substringBefore(" · ")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Force leg", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    listOf("sto" to "STO", "ams" to "AMS", "auto" to "Auto").forEach { (leg, txt) ->
                        val on = s.activeLeg == leg
                        OutlinedButton(
                            onClick = { onForce(s.host, leg, "$segLabel → $txt") },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            modifier = Modifier.padding(start = 4.dp),
                            colors = if (on) ButtonDefaults.outlinedButtonColors(contentColor = STO)
                                     else ButtonDefaults.outlinedButtonColors()
                        ) { Text(txt, style = MaterialTheme.typography.labelMedium) }
                    }
                }
            }
            CascadeCard(s)
        }
    }
}

// Compact WG throughput sparkline: tx (green, ↑) + rx (blue, ↓), shared scale.
@Composable
private fun Sparkline(tx: List<Double>, rx: List<Double>, modifier: Modifier = Modifier) {
    val mx = ((tx + rx).maxOrNull() ?: 1.0).coerceAtLeast(1.0)
    Canvas(modifier) {
        fun draw(data: List<Double>, color: Color) {
            if (data.size < 2) return
            val path = Path()
            data.forEachIndexed { i, v ->
                val x = size.width * i / (data.size - 1)
                val y = size.height * (1f - (v.coerceAtLeast(0.0) / mx).toFloat())
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color, style = Stroke(width = 2.5f))
        }
        draw(tx, STO)
        draw(rx, Color(0xFF0A84FF))
    }
}

@Composable
private fun CascadeCard(s: Seg) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(8.dp)) { drawCircle(if (s.cascade?.isUp == true) STO else DOWN) }
                Spacer(Modifier.width(8.dp))
                Text("Cascade — ${s.title.substringBefore(" · ")}",
                    style = MaterialTheme.typography.bodyMedium)
            }
            s.cascade?.recentBeats?.takeIf { it.isNotEmpty() }?.let {
                HeartbeatBar(it, Modifier.fillMaxWidth().height(20.dp))
            }
            Text(s.cascade?.let { "%.2f%% · 24h".format(it.uptime24h * 100) } ?: "no data",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(CASCADE_HINT, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EgressCard(legs: List<Leg>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Egress legs · from home / monthly traffic", style = MaterialTheme.typography.titleSmall)
            legs.forEach { lg ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Pill(lg.leg.uppercase(), legColor(lg.leg))
                        Spacer(Modifier.weight(1f))
                        lg.homeRtt?.let {
                            Text("home → ${it.toInt()} ms", style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (lg.txBytes != null && lg.limitBytes != null && lg.limitBytes > 0) {
                        val frac = (lg.txBytes / lg.limitBytes).coerceIn(0.0, 1.0)
                        LinearProgressIndicator(progress = { frac.toFloat() }, modifier = Modifier.fillMaxWidth(),
                            color = if (frac > 0.85) FI else if (frac > 0.6) AMS else STO)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${fmtBytes(lg.txBytes)} / ${fmtBytes(lg.limitBytes)}",
                                style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                            Text("%.1f%%".format(frac * 100), style = MaterialTheme.typography.labelSmall)
                        }
                    } else if (lg.txBytes != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${fmtBytes(lg.txBytes)} · this month",
                                style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                            Text("no limit", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Text(EGRESS_HINT, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HistoryCard(history: List<Migration>) {
    val fmt = remember {
        SimpleDateFormat("MMM d, HH:mm", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Asia/Yekaterinburg")   // MSK+2 (UTC+5), not device-local
        }
    }
    // История разнесена по группам и листается влево-вправо — как на веб-странице.
    // Это два несвязанных каскада: у домашнего входа плечи sto/ams/fi, у РКН-входа своя
    // схема с транзитом через соседа. В общем списке их переключения перемешивались по
    // времени и читались как один поток событий.
    val pages = remember(history) {
        listOf("rkn" to "РКН Ingress", "udm" to "UDM Pro · домашний каскад")
            .map { (key, title) -> Triple(key, title, history.filter { m -> (m.group == "rkn") == (key == "rkn") }) }
            .filter { it.third.isNotEmpty() }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("History · primary-leg migrations", style = MaterialTheme.typography.titleSmall)
            if (pages.isEmpty()) {
                Text("No migrations recorded", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val pager = rememberPagerState(pageCount = { pages.size })
                HorizontalPager(state = pager) { page ->
                    val (key, title, rows) = pages[page]
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            title.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (key == "rkn") MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.primary
                        )
                        rows.forEach { m ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(m.label,
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(48.dp))
                                Pill(m.from.uppercase(), legColor(m.from))
                                Text("→", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Pill(m.to.uppercase(), legColor(m.to))
                                if (m.reason.isNotEmpty()) Pill(reasonText(m.reason), reasonColor(m.reason))
                                Spacer(Modifier.weight(1f))
                                Text(fmt.format(Date(m.epoch * 1000)), style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                // Точки-индикаторы рисуем только когда листать есть что.
                if (pages.size > 1) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            repeat(pages.size) { i ->
                                Box(
                                    Modifier.size(if (i == pager.currentPage) 8.dp else 6.dp)
                                        .background(
                                            if (i == pager.currentPage) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .35f),
                                            CircleShape
                                        )
                                )
                            }
                        }
                    }
                }
                Text(
                    "stale HS — хэндшейк протух; no route — нет прохода через плечо; " +
                        "link down — линк упал; failback — возврат на приоритетное плечо; boot — старт.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun KV(k: String, v: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(k, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(v, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun Pill(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.18f), shape = RoundedCornerShape(999.dp)) {
        Text(text, color = color, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp))
    }
}

@Composable
private fun HeartbeatBar(beats: List<KumaHeartbeat>, modifier: Modifier) {
    Canvas(modifier) {
        if (beats.isEmpty()) return@Canvas
        val gap = 3f
        val bw = (size.width - gap * (beats.size - 1)) / beats.size
        beats.forEachIndexed { i, b ->
            val c = when (b.status) { 1 -> STO; 0 -> DOWN; 2 -> PENDING; 3 -> MAINT; else -> Color.Gray }
            drawRoundRect(c, Offset(i * (bw + gap), 0f), Size(bw, size.height), CornerRadius(2.5f, 2.5f))
        }
    }
}

@Composable
private fun EmptyState(title: String, msg: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(msg, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun fmtDur(s: Double): String {
    val t = s.toInt(); val h = t / 3600; val m = (t % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
private fun fmtBps(bytesPerSec: Double): String {
    var v = bytesPerSec * 8; val u = listOf("bps", "Kbps", "Mbps", "Gbps"); var i = 0
    while (v >= 1000 && i < u.size - 1) { v /= 1000; i++ }
    return "%.1f %s".format(v, u[i])
}
private fun fmtBytes(b: Double): String {
    var v = b; val u = listOf("B", "KB", "MB", "GB", "TB"); var i = 0
    while (v >= 1000 && i < u.size - 1) { v /= 1000; i++ }
    return "%.1f %s".format(v, u[i])
}
