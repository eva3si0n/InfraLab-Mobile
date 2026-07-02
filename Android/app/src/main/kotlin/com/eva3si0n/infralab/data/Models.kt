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
    val cascadeMatch: String
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
    val cascadeSegments: List<CascadeSegmentCfg>? = null,
    val cascadeTrafficHosts: Map<String, String>? = null
)

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
