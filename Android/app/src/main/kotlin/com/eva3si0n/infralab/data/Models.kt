package com.eva3si0n.infralab.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive

// MARK: Kuma

@Serializable
data class KumaStatusPageResponse(
    val config: KumaPageConfig,
    val publicGroupList: List<KumaGroup>
)

@Serializable
data class KumaPageConfig(val title: String, val slug: String)

@Serializable
data class KumaGroup(val id: Int, val name: String, val monitorList: List<KumaMonitorInfo>)

@Serializable
data class KumaMonitorInfo(val id: Int, val name: String, val type: String? = null)

@Serializable
data class KumaHeartbeatResponse(
    val heartbeatList: Map<String, List<KumaHeartbeat>>,
    val uptimeList: Map<String, Double>
)

@Serializable
data class KumaHeartbeat(
    val status: Int,            // 1=up, 0=down, 2=pending, 3=maintenance
    val ping: Double? = null,   // ms, fractional
    val time: String
)

data class MonitorStatus(
    val id: Int,
    val name: String,
    val groupName: String,
    val isUp: Boolean,
    val latency: Int?,
    val uptime24h: Double,
    val recentBeats: List<KumaHeartbeat>
)

// MARK: Grafana dashboards

@Serializable
data class DashboardInfo(val uid: String, val title: String)

@Serializable
data class GDashResponse(val dashboard: GDash)

@Serializable
data class GDash(val title: String, val panels: List<GPanel> = emptyList())

@Serializable
data class GPanel(
    val id: Int? = null,
    val type: String,
    val title: String? = null,
    val targets: List<GTarget>? = null,
    val panels: List<GPanel>? = null,
    val fieldConfig: GFieldConfig? = null
)

@Serializable
data class GTarget(val expr: String? = null, val legendFormat: String? = null)

@Serializable
data class GFieldConfig(val defaults: GFieldDefaults? = null)

@Serializable
data class GFieldDefaults(val unit: String? = null)

enum class PanelKind { TIMESERIES, STAT, GAUGE, BARGAUGE, TABLE, ROW, UNSUPPORTED }

// Optional local pre-fill (assets/seed.json) for personal builds — gitignored, not in repo.
// VPN Cascade segment config — real host / Kuma-group names live only in the personal
// (gitignored) assets/seed.json; public code / seed.example.json carry placeholders.
@Serializable
data class CascadeSegmentCfg(
    val host: String,
    val title: String,
    val kumaGroup: String,
    val cascadeMatch: String,
    // Дек, в который попадает сегмент: "rkn" (входы под блокировками) или "udm"/null
    // (домашний каскад). Тем же полем веб делит «Cascade decision» и историю миграций.
    val group: String? = null
)

@Serializable
data class SeedConfig(
    val kumaBaseURL: String? = null,
    val kumaSlug: String? = null,
    val kumaAPIKey: String? = null,
    val grafanaBaseURL: String? = null,
    val grafanaDatasourceUID: String? = null,
    val grafanaToken: String? = null,
    val homePageBaseURL: String? = null,
    val vpncascadeBaseURL: String? = null,
    val switchToken: String? = null,
    val cfAccessClientId: String? = null,
    val cfAccessClientSecret: String? = null,
    val cascadeSegments: List<CascadeSegmentCfg>? = null,
    val cascadeTrafficHosts: Map<String, String>? = null,
    val cascadeTrafficNet: Map<String, NetTarget>? = null
)

// node_exporter interface (host+device) for month-to-date traffic on legs without a
// provider limit (e.g. FI) — mirrors vpncascade's cascadeTrafficNet.
@Serializable
data class NetTarget(val host: String = "", val device: String = "")

// Result of POST /api/switch on the vpncascade service (manual leg-switch).
@Serializable
data class SwitchResult(
    val ok: Boolean = false,
    val override: String? = null,
    val active: String? = null,
    val error: String? = null
)

// Полный ответ vpncascade GET /api/cascade.
//
// Зачем. Раньше экран каскада собирался в приложении сам — десятком запросов PromQL через
// Grafana-прокси. Ровно ту же логику считает сервис, и каждая новая возможность веб-панели
// требовала отдельной реализации на трёх платформах и расходилась с вебом. Теперь клиент
// тонкий: одна выборка, один источник правды.
//
// ⚠️ Поля опциональны намеренно: сервис добавляет их по ходу (paths приехали 13.08.2026),
// и старая сборка приложения не должна падать на новом payload.
@Serializable
data class ApiBeat(val status: Int? = null, val ping: Double? = null, val time: String? = null)

@Serializable
data class ApiMonitorBlock(
    val isUp: Boolean? = null,
    val uptime24h: Double? = null,
    val recentBeats: List<ApiBeat> = emptyList()
)

@Serializable
data class ApiSegment(
    val host: String,
    val title: String? = null,
    val label: String? = null,
    val activeLeg: String? = null,
    val activeSeconds: Double? = null,
    val rtt: Map<String, Double> = emptyMap(),
    val txBps: Double? = null,
    val rxBps: Double? = null,
    val healthy: Boolean? = null,
    val cascade: ApiMonitorBlock? = null,
    val cascadeMonitored: Boolean? = null,
    val override: String? = null,
    val switchLegs: List<String> = emptyList(),
    val primaryLeg: String? = null,
    val manual: Boolean = false,
    val inputUp: Boolean? = null,
    val inputAge: Double? = null,
    val txSeries: List<Double> = emptyList(),
    val rxSeries: List<Double> = emptyList(),
    // "rkn" | "udm" — деки и история в вебе делятся по этому признаку
    val group: String? = null,
    val fqdn: String? = null,
    val ip: String? = null
)

@Serializable
data class ApiLeg(
    val leg: String,
    val homeRTT: Double? = null,
    val txBytes: Double? = null,
    val limitBytes: Double? = null
)

@Serializable
data class ApiMigration(
    val host: String? = null,
    val label: String? = null,
    val group: String? = null,
    val from: String? = null,
    val to: String? = null,
    val time: Double? = null,
    val reason: String? = null
)

// Транзит: через какие AS идёт путь до эндпоинта плеча (появился 13.08.2026).
@Serializable
data class ApiASN(
    val num: String,
    val name: String? = null,
    // страна, где реально стоят ХОПЫ этой AS, а не где она зарегистрирована
    val cc: String? = null
)

@Serializable
data class ApiPathChange(val time: Double? = null, val asPath: String? = null)

@Serializable
data class ApiPathInfo(
    val host: String,
    val leg: String,
    val asns: List<ApiASN> = emptyList(),
    val hops: Int? = null,
    val changedAt: Double? = null,
    val changes: List<ApiPathChange> = emptyList(),
    // зонд молчит — показывать обязательно, иначе читается как «путь стабилен»
    val stale: Boolean = false
)

@Serializable
data class CascadePayload(
    val segments: List<ApiSegment> = emptyList(),
    val hiddenSegments: List<ApiSegment> = emptyList(),
    val legs: List<ApiLeg> = emptyList(),
    val history: List<ApiMigration> = emptyList(),
    val paths: List<ApiPathInfo> = emptyList(),
    val error: String? = null,
    val fetchedAt: Double? = null
)

// View of GET /api/cascade — manual-override state + WG throughput history per segment.
@Serializable
data class OverrideSeg(
    val host: String,
    val override: String? = null,
    val manual: Boolean = false,
    val txSeries: List<Double> = emptyList(),
    val rxSeries: List<Double> = emptyList()
)

@Serializable
data class OverridePayload(val segments: List<OverrideSeg> = emptyList())

// Per-segment extras consumed by CascadeScreen (override state + WG sparkline series).
data class SegAux(val override: String, val manual: Boolean, val tx: List<Double>, val rx: List<Double>)

data class PanelDef(
    val title: String,
    val kind: PanelKind,
    val unit: String,
    val targets: List<Pair<String, String>>   // expr, legendFormat
)

// MARK: Prometheus query results

@Serializable
data class PromResponse(val status: String, val data: PromData)

@Serializable
data class PromData(val resultType: String, val result: List<PromSeries>)

@Serializable
data class PromSeries(
    val metric: Map<String, String> = emptyMap(),
    val values: List<List<JsonElement>> = emptyList()
)

@Serializable
data class PromInstantResponse(val data: PromInstantData)

@Serializable
data class PromInstantData(val resultType: String, val result: List<PromInstantSeries>)

@Serializable
data class PromInstantSeries(
    val metric: Map<String, String> = emptyMap(),
    val value: List<JsonElement> = emptyList()
)

// Rendered data
data class MetricPoint(val timeMs: Long, val value: Double)
data class MetricSeries(val name: String, val points: List<MetricPoint>)
data class InstantRow(val name: String, val value: Double, val labels: Map<String, String>)

fun JsonElement.toDoubleOrNull(): Double? = this.jsonPrimitive.content.toDoubleOrNull()

// Ответ InfraHome GET /api/home — тот же источник, что рисует веб-дашборд.
// Раньше раздел был web-view на gethomepage: внутри встроенного браузера Cloudflare
// Access просил логин, а на телефоне это неудобно. Теперь экран нативный и ходит по API
// с service token, как и каскад.
// ⚠️ Поля опциональны: состав плиток задаётся конфигом сервиса и меняется.
@Serializable
data class HomeMetric(
    val label: String? = null,
    val text: String? = null,
    val ok: Boolean? = null,
    val group: String? = null,
    val note: String? = null,
    val state: String? = null          // "ok" | "warn" | "crit"
)

@Serializable
data class HomeTile(
    val name: String? = null,
    val href: String? = null,
    val description: String? = null,
    val accent: String? = null,
    val badge: String? = null,
    val status: String? = null,        // "ok" | "warn" | "crit"
    val metrics: List<HomeMetric> = emptyList()
)

@Serializable
data class HomeGroup(
    val name: String? = null,
    val framed: Boolean? = null,
    // свёрнутость берём с веба, чтобы длинный дашборд не разворачивался на телефоне целиком
    val collapsed: Boolean = false,
    val tiles: List<HomeTile> = emptyList()
)

@Serializable
data class HomeKuma(
    val up: Int = 0, val down: Int = 0, val pending: Int = 0,
    val maintenance: Int = 0, val total: Int = 0
)

@Serializable
data class HomeAlert(
    val name: String? = null,
    val severity: String? = null,
    val state: String? = null,
    val summary: String? = null
)

@Serializable
data class HomeAlerts(
    val firing: Int = 0,
    val pending: Int = 0,
    val list: List<HomeAlert> = emptyList()
)

@Serializable
data class InfraHomePayload(
    val generatedAt: Double? = null,
    val title: String? = null,
    val groups: List<HomeGroup> = emptyList(),
    val kuma: HomeKuma? = null,
    val alerts: HomeAlerts? = null
)
