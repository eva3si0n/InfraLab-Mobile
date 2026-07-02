package com.eva3si0n.infralab.ui.cascade

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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

// VPN Cascade — per-segment egress state + Kuma cascade health + egress-leg traffic + migration history.
// Data: Prometheus via Grafana proxy (vm.promInstant) + Kuma status page (vm.monitors).
private val STO = Color(0xFF5CDD8B)
private val AMS = Color(0xFFF8A532)
private val FI = Color(0xFFDC3D46)
private val DOWN = Color(0xFFDC3D46)
private val PENDING = Color(0xFFF8A532)
private val MAINT = Color(0xFF459BFF)
private fun legColor(l: String) = when (l) { "sto" -> STO; "ams" -> AMS; "fi" -> FI; else -> Color.Gray }

private const val CASCADE_HINT ="up — активное плечо STO/AMS (чистый Vultr-egress); down — деградация на FI (оба Vultr-плеча недоступны) или несвежий handshake."
private const val EGRESS_HINT = "Лимит Vultr 2 ТБ на инстанс (STO и AMS отдельно), считается outbound (tx), сброс 1-го числа. FI — cold standby, квота не отслеживается."

private data class Seg(
    val host: String, val title: String, val activeLeg: String, val activeSeconds: Double,
    val rtt: Map<String, Double>, val txBps: Double?, val rxBps: Double?,
    val healthy: Boolean, val cascade: MonitorStatus?
)
private data class Leg(val leg: String, val homeRtt: Double?, val txBytes: Double?, val limitBytes: Double?)
private data class Migration(val host: String, val label: String, val from: String, val to: String, val epoch: Long)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CascadeScreen(vm: AppViewModel) {
    var segs by remember { mutableStateOf<List<Seg>>(emptyList()) }
    var legs by remember { mutableStateOf<List<Leg>>(emptyList()) }
    var history by remember { mutableStateOf<List<Migration>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        if (vm.grafanaBaseURL.isEmpty()) return
        loading = true
        try {
            if (vm.monitors.isEmpty()) vm.refreshMonitors()
            val active = vm.promInstant("vpn_egress_active_leg == 1", "")
            val durQ = vm.promInstant("vpn_egress_active_seconds", "")
            val rtt = vm.promInstant("vpn_leg_rtt_ms", "")
            val txbps = vm.promInstant("sum by (host) (rate(wireguard_sent_bytes[5m]))", "")
            val rxbps = vm.promInstant("sum by (host) (rate(wireguard_received_bytes[5m]))", "")
            val home = vm.promInstant("home_node_rtt_ms", "")
            val tx = vm.promInstant("vds_month_tx_bytes", "")
            val lim = vm.promInstant("vds_month_limit_bytes", "")
            val sw = vm.promInstant("vpn_egress_switch_time", "")

            segs = vm.cascadeSegments.map { cfg ->
                val al = active.firstOrNull { it.labels["host"] == cfg.host }?.labels?.get("leg") ?: "—"
                val ds = durQ.firstOrNull { it.labels["host"] == cfg.host }?.value ?: 0.0
                val rm = rtt.filter { it.labels["host"] == cfg.host }
                    .mapNotNull { r -> r.labels["leg"]?.let { it to r.value } }.toMap()
                // Healthy = node reachability (Ping + SSH). Feature/cascade checks (FI handshake,
                // Geo Routing, services) are shown separately and don't gate node health — FI is
                // cold-standby, so its dead-man monitors are expected down while on STO/AMS.
                val reach = vm.monitors.filter { it.groupName == cfg.kumaGroup && (it.name == "Ping" || it.name == "SSH") }
                val healthy = reach.isNotEmpty() && reach.all { it.isUp }
                val casc = vm.monitors.firstOrNull { it.groupName == "VPN Cascade" && it.name.contains(cfg.cascadeMatch) }
                Seg(cfg.host, cfg.title, al, ds, rm,
                    txbps.firstOrNull { it.labels["host"] == cfg.host }?.value,
                    rxbps.firstOrNull { it.labels["host"] == cfg.host }?.value,
                    healthy, casc)
            }
            legs = listOf("sto", "ams", "fi").map { l ->
                val host = vm.cascadeTrafficHosts[l] ?: ""
                Leg(l, home.firstOrNull { it.labels["node"] == l }?.value,
                    if (host.isEmpty()) null else tx.firstOrNull { it.labels["host"] == host }?.value,
                    if (host.isEmpty()) null else lim.firstOrNull { it.labels["host"] == host }?.value)
            }
            history = sw.mapNotNull { r ->
                val f = r.labels["from"]; val t = r.labels["to"]; val h = r.labels["host"]
                if (f == null || t == null || h == null) null else {
                    val label = vm.cascadeSegments.firstOrNull { it.host == h }?.title?.substringBefore(" · ") ?: h
                    Migration(h, label, f, t, r.value.toLong())
                }
            }.sortedByDescending { it.epoch }
            error = null
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
                vm.grafanaBaseURL.isEmpty() -> EmptyState("Grafana Not Configured", "Set Grafana URL in Settings")
                segs.isEmpty() && loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                segs.isEmpty() && error != null -> EmptyState("Failed to Load", error!!)
                else -> PullToRefreshBox(isRefreshing = loading, onRefresh = { scope.launch { load() } }) {
                    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(segs) { SegCard(it) }
                        item(key = "egress") { EgressCard(legs) }
                        item(key = "history") { HistoryCard(history) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SegCard(s: Seg) {
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
                KV("Throughput WG", "↑ ${fmtBps(s.txBps)}  ↓ ${fmtBps(s.rxBps)}")
            }
            KV("RTT STO", s.rtt["sto"]?.let { "${it.toInt()} ms" } ?: "—")
            KV("RTT AMS", s.rtt["ams"]?.let { "${it.toInt()} ms" } ?: "—")
            KV("RTT FI", s.rtt["fi"]?.let { "${it.toInt()} ms" } ?: "—")
            CascadeCard(s)
        }
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
                    }
                }
            }
            Text(EGRESS_HINT, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HistoryCard(history: List<Migration>) {
    val fmt = remember { SimpleDateFormat("MMM d, HH:mm", Locale.US) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("History · primary-leg migrations", style = MaterialTheme.typography.titleSmall)
            if (history.isEmpty()) {
                Text("No migrations recorded", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                history.forEach { m ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(m.label,
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(48.dp))
                        Pill(m.from.uppercase(), legColor(m.from))
                        Text("→", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Pill(m.to.uppercase(), legColor(m.to))
                        Spacer(Modifier.weight(1f))
                        Text(fmt.format(Date(m.epoch * 1000)), style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
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
