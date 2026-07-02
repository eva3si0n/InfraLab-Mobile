package com.eva3si0n.infralab.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.eva3si0n.infralab.data.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import java.net.URLEncoder

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = AppPrefs(application)
    private val secure = SecurePrefs(application)
    private val api = ApiClient()

    // Settings
    var kumaBaseURL by mutableStateOf(prefs.kumaBaseURL); private set
    var kumaSlug by mutableStateOf(prefs.kumaSlug); private set
    var grafanaBaseURL by mutableStateOf(prefs.grafanaBaseURL); private set
    var grafanaDatasourceUID by mutableStateOf(prefs.grafanaDatasourceUID); private set
    var homePageBaseURL by mutableStateOf(prefs.homePageBaseURL); private set
    var refreshIntervalSecs by mutableStateOf(prefs.refreshIntervalSecs); private set

    fun updateKumaBaseURL(v: String) { kumaBaseURL = v; prefs.kumaBaseURL = v }
    fun updateKumaSlug(v: String) { kumaSlug = v; prefs.kumaSlug = v }
    fun updateGrafanaBaseURL(v: String) { grafanaBaseURL = v; prefs.grafanaBaseURL = v }
    fun updateGrafanaDatasourceUID(v: String) { grafanaDatasourceUID = v; prefs.grafanaDatasourceUID = v }
    fun updateHomePageBaseURL(v: String) { homePageBaseURL = v; prefs.homePageBaseURL = v }
    fun updateRefreshInterval(v: Long) { refreshIntervalSecs = v; prefs.refreshIntervalSecs = v }
    fun setKumaAPIKey(v: String) { secure.set("kumaAPIKey", v) }
    fun setGrafanaToken(v: String) { secure.set("grafanaToken", v) }
    fun hasKumaAPIKey() = secure.has("kumaAPIKey")
    fun hasGrafanaToken() = secure.has("grafanaToken")
    fun grafanaToken() = secure.get("grafanaToken")

    // Runtime
    var monitors by mutableStateOf<List<MonitorStatus>>(emptyList()); private set
    var monitorsLoading by mutableStateOf(false); private set
    var monitorsError by mutableStateOf<String?>(null); private set

    var dashboards by mutableStateOf<List<DashboardInfo>>(emptyList()); private set
    var dashboardsLoading by mutableStateOf(false); private set
    var dashboardsError by mutableStateOf<String?>(null); private set

    // VPN Cascade layout — from bundled assets/seed.json; placeholder defaults for public builds.
    var cascadeSegments: List<CascadeSegmentCfg> = listOf(
        CascadeSegmentCfg("node-a", "Wired · FQDN хоста", "Node A", "node-a"),
        CascadeSegmentCfg("node-b", "Mobile · FQDN хоста", "Node B", "node-b")
    ); private set
    var cascadeTrafficHosts: Map<String, String> = emptyMap(); private set

    private var refreshJob: Job? = null

    init { seedFromAssetsIfNeeded(); loadCascadeConfig() }

    // Pre-fill Settings from a bundled assets/seed.json on first launch (personal builds).
    // The file is gitignored — never committed; absent in public clones (then no-op).
    private fun seedFromAssetsIfNeeded() {
        if (kumaBaseURL.isNotEmpty() || grafanaBaseURL.isNotEmpty() || homePageBaseURL.isNotEmpty()) return
        runCatching {
            val txt = getApplication<Application>().assets.open("seed.json").bufferedReader().use { it.readText() }
            val s = api.json.decodeFromString<SeedConfig>(txt)
            s.kumaBaseURL?.let { updateKumaBaseURL(it) }
            s.kumaSlug?.let { updateKumaSlug(it) }
            s.grafanaBaseURL?.let { updateGrafanaBaseURL(it) }
            s.grafanaDatasourceUID?.takeIf { it.isNotEmpty() }?.let { updateGrafanaDatasourceUID(it) }
            s.homePageBaseURL?.let { updateHomePageBaseURL(it) }
            s.kumaAPIKey?.takeIf { it.isNotEmpty() }?.let { setKumaAPIKey(it) }
            s.grafanaToken?.takeIf { it.isNotEmpty() }?.let { setGrafanaToken(it) }
        }
    }

    // Cascade layout from the bundled seed.json (real node/group names only there, gitignored).
    private fun loadCascadeConfig() {
        runCatching {
            val txt = getApplication<Application>().assets.open("seed.json").bufferedReader().use { it.readText() }
            val s = api.json.decodeFromString<SeedConfig>(txt)
            s.cascadeSegments?.takeIf { it.isNotEmpty() }?.let { cascadeSegments = it }
            s.cascadeTrafficHosts?.let { cascadeTrafficHosts = it }
        }
    }

    // MARK: Orchestration

    fun refreshAll() {
        viewModelScope.launch {
            val jobs = buildList {
                if (kumaBaseURL.isNotEmpty() && kumaSlug.isNotEmpty()) add(launch { refreshMonitors() })
                if (grafanaBaseURL.isNotEmpty()) add(launch { loadDashboards() })
            }
            jobs.forEach { it.join() }
        }
    }

    fun startAutoRefresh() {
        refreshJob?.cancel()
        val interval = refreshIntervalSecs
        refreshJob = viewModelScope.launch {
            while (isActive) {
                delay(interval * 1000L)
                if (isActive && kumaBaseURL.isNotEmpty() && kumaSlug.isNotEmpty()) refreshMonitors()
            }
        }
    }

    fun stopAutoRefresh() { refreshJob?.cancel(); refreshJob = null }

    // MARK: Kuma

    suspend fun refreshMonitors() {
        if (kumaBaseURL.isEmpty() || kumaSlug.isEmpty()) return
        monitorsLoading = true; monitorsError = null
        try {
            val base = kumaBaseURL.trimEnd('/')
            val token = secure.get("kumaAPIKey")
            val page = api.decode<KumaStatusPageResponse>(api.get("$base/api/status-page/$kumaSlug", token))
            val beats = api.decode<KumaHeartbeatResponse>(api.get("$base/api/status-page/heartbeat/$kumaSlug", token))
            monitors = page.publicGroupList.flatMap { group ->
                group.monitorList.map { m ->
                    val hb = beats.heartbeatList[m.id.toString()] ?: emptyList()
                    MonitorStatus(
                        id = m.id, name = m.name, groupName = group.name,
                        isUp = hb.lastOrNull()?.status == 1,
                        latency = hb.lastOrNull()?.ping?.let { Math.round(it).toInt() },
                        uptime24h = beats.uptimeList["${m.id}_24"] ?: 0.0,
                        recentBeats = hb.takeLast(25)
                    )
                }
            }
        } catch (e: Exception) {
            monitorsError = e.message ?: "Unknown error"
        } finally { monitorsLoading = false }
    }

    // MARK: Grafana dashboards

    suspend fun loadDashboards() {
        if (grafanaBaseURL.isEmpty()) return
        dashboardsLoading = true; dashboardsError = null
        try {
            val base = grafanaBaseURL.trimEnd('/')
            dashboards = api.decode<List<DashboardInfo>>(
                api.get("$base/api/search?type=dash-db&limit=200", grafanaToken())
            ).sortedBy { it.title.lowercase() }
        } catch (e: Exception) {
            dashboardsError = e.message ?: "Unknown error"
        } finally { dashboardsLoading = false }
    }

    suspend fun fetchPanels(uid: String): List<PanelDef> {
        val base = grafanaBaseURL.trimEnd('/')
        val resp = api.decode<GDashResponse>(api.get("$base/api/dashboards/uid/$uid", grafanaToken()))
        val out = mutableListOf<PanelDef>()
        fun add(p: GPanel) {
            if (panelKind(p.type) == PanelKind.ROW) {
                out += PanelDef(p.title ?: "", PanelKind.ROW, "", emptyList())
                p.panels?.forEach(::add)
                return
            }
            val targets = (p.targets ?: emptyList()).mapNotNull { t ->
                t.expr?.takeIf { it.isNotBlank() }?.let { it to (t.legendFormat ?: "") }
            }
            if (targets.isEmpty()) return
            out += PanelDef(p.title ?: "", panelKind(p.type), p.fieldConfig?.defaults?.unit ?: "", targets)
        }
        resp.dashboard.panels.forEach(::add)
        return out
    }

    private fun panelKind(type: String): PanelKind = when (type) {
        "row" -> PanelKind.ROW
        "timeseries", "graph", "barchart", "state-timeline" -> PanelKind.TIMESERIES
        "stat", "singlestat" -> PanelKind.STAT
        "gauge" -> PanelKind.GAUGE
        "bargauge" -> PanelKind.BARGAUGE
        "table", "table-old" -> PanelKind.TABLE
        else -> PanelKind.UNSUPPORTED
    }

    // MARK: Prometheus (via Grafana datasource proxy)

    suspend fun promRange(expr: String, legend: String): List<MetricSeries> {
        val end = System.currentTimeMillis() / 1000
        val start = end - 6 * 3600
        val url = proxy("query_range") +
            "?query=${enc(expr)}&start=$start&end=$end&step=300"
        val resp = api.decode<PromResponse>(api.get(url, grafanaToken()))
        return resp.data.result.mapNotNull { s ->
            val pts = s.values.mapNotNull { pair ->
                if (pair.size != 2) return@mapNotNull null
                val t = pair[0].toDoubleOrNull() ?: return@mapNotNull null
                val v = pair[1].toDoubleOrNull() ?: return@mapNotNull null
                if (!v.isFinite()) return@mapNotNull null
                MetricPoint((t * 1000).toLong(), v)
            }
            if (pts.isEmpty()) null else MetricSeries(seriesName(s.metric, legend), pts)
        }
    }

    suspend fun promInstant(expr: String, legend: String): List<InstantRow> {
        val url = proxy("query") + "?query=${enc(expr)}"
        val resp = api.decode<PromInstantResponse>(api.get(url, grafanaToken()))
        return resp.data.result.mapNotNull { s ->
            if (s.value.size != 2) return@mapNotNull null
            val v = s.value[1].toDoubleOrNull() ?: return@mapNotNull null
            if (!v.isFinite()) return@mapNotNull null
            InstantRow(seriesName(s.metric, legend), v, s.metric)
        }
    }

    private fun proxy(path: String): String {
        val base = grafanaBaseURL.trimEnd('/')
        val uid = grafanaDatasourceUID.ifEmpty { "prometheus" }
        return "$base/api/datasources/proxy/uid/$uid/api/v1/$path"
    }

    // URLEncoder turns spaces into '+', which Prometheus reads literally (→ PromQL syntax
    // error). Real '+' are already %2B, so swapping the remaining '+' to %20 is safe.
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun seriesName(metric: Map<String, String>, legend: String): String {
        if (legend.isNotEmpty()) {
            var out = legend
            for ((k, v) in metric) {
                out = out.replace("{{$k}}", v).replace("{{ $k }}", v)
            }
            out = out.replace(Regex("\\{\\{[^}]*\\}\\}"), "").trim()
            if (out.isNotEmpty()) return out
        }
        return metric["host"] ?: metric["instance"] ?: metric["name"]
            ?: metric.values.firstOrNull() ?: "value"
    }
}
