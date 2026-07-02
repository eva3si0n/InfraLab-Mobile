import SwiftUI

/// VPN Cascade — per-segment egress state + Kuma cascade health + egress-leg traffic + migration history.
/// Data: Prometheus via Grafana proxy + Kuma status page. RootView wraps this in a NavigationStack.
struct CascadeView: View {
    @EnvironmentObject var appState: AppState

    struct Seg: Identifiable {
        let id = UUID()
        let host, title, activeLeg: String
        let activeSeconds: Double
        let rtt: [String: Double]
        let txBps, rxBps: Double?
        let healthy: Bool
        let cascade: MonitorStatus?
    }
    struct Leg: Identifiable { let id = UUID(); let leg: String; let homeRTT, txBytes, limitBytes: Double? }
    struct Migration: Identifiable { let id = UUID(); let host, from, to: String; let time: Date; let reason: String }

    @State private var segs: [Seg] = []
    @State private var legs: [Leg] = []
    @State private var history: [Migration] = []
    @State private var loading = false
    @State private var errText: String?

    private let cascadeHint = "up — активное плечо STO/AMS (чистый Vultr-egress); down — деградация на FI (оба Vultr-плеча недоступны) или несвежий handshake."
    private let egressHint = "Лимит Vultr 2 ТБ на инстанс (STO и AMS отдельно), считается outbound (tx), сброс 1-го числа. FI — cold standby, квота не отслеживается."

    var body: some View {
        Group {
            if appState.grafanaBaseURL.isEmpty {
                ContentUnavailableView("Grafana Not Configured", systemImage: "arrow.triangle.branch",
                    description: Text("Set Grafana URL in Settings"))
            } else if segs.isEmpty && loading {
                ProgressView("Loading…").frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if let e = errText, segs.isEmpty {
                ContentUnavailableView("Failed to Load", systemImage: "exclamationmark.triangle", description: Text(e))
            } else { list }
        }
        .navigationTitle("VPN Cascade")
        .toolbar { if loading { ToolbarItem(placement: .topBarTrailing) { ProgressView() } } }
        .task { await load() }
    }

    private var list: some View {
        List {
            decisionSection
            ForEach(segs) { segmentSection($0) }
            egressSection
            historySection
        }
        .listStyle(.insetGrouped)
        .refreshable { await load() }
    }

    @ViewBuilder private func segmentSection(_ s: Seg) -> some View {
        Section(s.title) {
            HStack(spacing: 6) {
                Text("Active leg").font(.subheadline)
                Spacer(minLength: 4)
                pill(s.activeLeg.uppercased(), legColor(s.activeLeg))
                pill(s.healthy ? "Healthy" : "Unhealthy", s.healthy ? .green : .red)
                pill(s.activeLeg == "sto" ? "Primary" : "Secondary", s.activeLeg == "sto" ? .green : .orange)
                Text(fmtDur(s.activeSeconds)).font(.caption).foregroundStyle(.secondary)
            }
            if let tx = s.txBps, let rx = s.rxBps {
                row("Throughput WG", "↑ \(fmtBps(tx))  ↓ \(fmtBps(rx))")
            }
            row("RTT STO", s.rtt["sto"].map { "\(Int($0.rounded())) ms" } ?? "—")
            row("RTT AMS", s.rtt["ams"].map { "\(Int($0.rounded())) ms" } ?? "—")
            row("RTT FI",  s.rtt["fi"].map  { "\(Int($0.rounded())) ms" } ?? "—")
            cascadeCard(s)
        }
    }

    @ViewBuilder private func cascadeCard(_ s: Seg) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Circle().fill((s.cascade?.isUp ?? false) ? Color.green : Color.red).frame(width: 8, height: 8)
                Text("Cascade — \(segLabel(s.host))")
                    .font(.subheadline)
                Spacer()
            }
            if let beats = s.cascade?.recentBeats, !beats.isEmpty {
                KumaHeartbeatBar(beats: beats).frame(height: 20)
            }
            Text(s.cascade.map { String(format: "%.2f%% · 24h", $0.uptime24h * 100) } ?? "no data")
                .font(.caption).foregroundStyle(.secondary)
            Text(cascadeHint).font(.caption2).foregroundStyle(.secondary)
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 10).fill(Color(.secondarySystemGroupedBackground)))
        .listRowInsets(EdgeInsets(top: 6, leading: 12, bottom: 6, trailing: 12))
    }

    private var egressSection: some View {
        Section("Egress legs · from home / monthly traffic") {
            ForEach(legs) { lg in
                VStack(alignment: .leading, spacing: 6) {
                    HStack {
                        pill(lg.leg.uppercased(), legColor(lg.leg))
                        Spacer()
                        if let h = lg.homeRTT {
                            Text("home → \(Int(h.rounded())) ms").font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                        }
                    }
                    if let tx = lg.txBytes, let lim = lg.limitBytes, lim > 0 {
                        ProgressView(value: min(tx / lim, 1)) {
                            HStack {
                                Text("\(fmtBytes(tx)) / \(fmtBytes(lim))").font(.caption2)
                                Spacer()
                                Text(String(format: "%.1f%%", tx / lim * 100)).font(.caption2.monospacedDigit())
                            }
                        }
                        .tint(tx / lim > 0.85 ? .red : (tx / lim > 0.6 ? .orange : .green))
                    }
                }
                .padding(.vertical, 2)
            }
            Text(egressHint).font(.caption2).foregroundStyle(.secondary)
        }
    }

    @ViewBuilder private var historySection: some View {
        Section("History · primary-leg migrations") {
            if history.isEmpty {
                Text("No migrations recorded").font(.subheadline).foregroundStyle(.secondary)
            } else {
                ForEach(history) { m in
                    HStack(spacing: 8) {
                        Text(segLabel(m.host))
                            .font(.caption).foregroundStyle(.secondary).frame(width: 52, alignment: .leading)
                        pill(m.from.uppercased(), legColor(m.from))
                        Image(systemName: "arrow.right").font(.caption2).foregroundStyle(.secondary)
                        pill(m.to.uppercased(), legColor(m.to))
                        if !m.reason.isEmpty { pill(reasonText(m.reason), reasonColor(m.reason)) }
                        Spacer()
                        Text(fmtTime(m.time)).font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                    }
                }
                Text("stale HS — хэндшейк протух; no route — нет прохода через плечо; link down — линк упал; failback — возврат на приоритетное плечо; boot — старт.")
                    .font(.caption2).foregroundStyle(.secondary)
            }
        }
    }

    private func row(_ k: String, _ v: String) -> some View {
        HStack { Text(k).font(.subheadline); Spacer(); Text(v).font(.subheadline.monospacedDigit()) }
    }

    // Top summary: current route decision per segment, swipeable left/right (Wired / Mobile).
    @ViewBuilder private var decisionSection: some View {
        Section {
            TabView {
                ForEach(segs) { s in decisionPage(s).tag(s.id) }
            }
            .tabViewStyle(.page(indexDisplayMode: .always))
            .frame(height: 210)
            .listRowInsets(EdgeInsets())
        } header: {
            Text("Cascade decision")
        } footer: {
            Text("Primary healthy — маршрут на приоритетном STO; Failover from STO — ушли с primary (справа причина); Both Vultr legs down — оба Vultr-плеча недоступны, работаем на FI.")
        }
    }

    private func decisionPage(_ s: Seg) -> some View {
        let order = ["sto", "ams", "fi"]
        let cur = s.activeLeg
        let curRtt = s.rtt[cur]
        let alt = order.first { $0 != cur && $0 != "fi" }
        let cold: String? = cur != "fi" ? "fi" : nil
        func delta(_ leg: String?) -> String {
            guard let leg, let r = s.rtt[leg], let c = curRtt else { return "" }
            let d = Int((r - c).rounded()); return (d >= 0 ? "+\(d)" : "\(d)") + " ms"
        }
        return VStack(alignment: .leading, spacing: 6) {
            Text("Route").font(.caption).foregroundStyle(.secondary)
            decisionRow("Current", cur, nil)
            HStack(spacing: 8) {
                Text("Reason").font(.subheadline).frame(width: 110, alignment: .leading)
                Text(decisionReason(s)).font(.subheadline); Spacer()
            }
            if let alt { decisionRow("Alternative", alt, delta(alt)) }
            if let cold { decisionRow("Cold standby", cold, delta(cold)) }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal)
    }

    @ViewBuilder private func decisionRow(_ label: String, _ leg: String, _ delta: String?) -> some View {
        HStack(spacing: 8) {
            Text(label).font(.subheadline).frame(width: 110, alignment: .leading)
            pill(leg.uppercased(), legColor(leg))
            if let delta, !delta.isEmpty { Text(delta).font(.caption.monospacedDigit()).foregroundStyle(.secondary) }
            Spacer()
        }
    }

    private func decisionReason(_ s: Seg) -> String {
        let cur = s.activeLeg
        if cur == "sto" { return s.healthy ? "Primary healthy" : "On primary (degraded)" }
        if cur == "fi" { return "Both Vultr legs down" }
        let last = history.filter { $0.host == s.host && $0.to == cur }.max { $0.time < $1.time }?.reason
        if let last, ["stale_handshake", "unreachable", "link_down"].contains(last) {
            return "Failover from STO · \(reasonText(last))"
        }
        return "Failover from STO"
    }

    @ViewBuilder private func pill(_ text: String, _ c: Color) -> some View {
        Text(text).font(.caption2.weight(.semibold))
            .padding(.horizontal, 7).padding(.vertical, 2)
            .background(c.opacity(0.18)).foregroundStyle(c).clipShape(Capsule())
    }
    private func legColor(_ l: String) -> Color { l == "sto" ? .green : (l == "ams" ? .orange : (l == "fi" ? .red : .secondary)) }

    // Migration reason (vpn_egress_switch_time reason label): precise on new lines, coarse on legacy.
    private func reasonText(_ r: String) -> String {
        switch r {
        case "stale_handshake": return "stale HS"
        case "unreachable": return "no route"
        case "link_down": return "link down"
        case "failback": return "failback"
        case "failover": return "failover"
        case "initial": return "boot"
        default: return r
        }
    }
    private func reasonColor(_ r: String) -> Color {
        switch r {
        case "failback": return .green
        case "initial": return .blue
        case "failover": return .orange
        case "stale_handshake", "unreachable", "link_down": return .red
        default: return .secondary
        }
    }

    // Short segment label (part of the title before " · "), resolved from config by host.
    private func segLabel(_ host: String) -> String {
        guard let title = appState.cascadeSegments.first(where: { $0.host == host })?.title else { return host }
        return title.components(separatedBy: " · ").first ?? title
    }

    private func fmtDur(_ s: Double) -> String {
        let t = Int(s), h = t / 3600, m = (t % 3600) / 60
        return h > 0 ? "\(h)h \(m)m" : "\(m)m"
    }
    private func fmtBps(_ bytesPerSec: Double) -> String {
        var v = bytesPerSec * 8; let u = ["bps", "Kbps", "Mbps", "Gbps"]; var i = 0
        while v >= 1000 && i < u.count - 1 { v /= 1000; i += 1 }
        return String(format: "%.1f %@", v, u[i])
    }
    private func fmtBytes(_ b: Double) -> String {
        var v = b; let u = ["B", "KB", "MB", "GB", "TB"]; var i = 0
        while v >= 1000 && i < u.count - 1 { v /= 1000; i += 1 }
        return String(format: "%.1f %@", v, u[i])
    }
    private func fmtTime(_ d: Date) -> String {
        let f = DateFormatter(); f.dateFormat = "MMM d, HH:mm"; return f.string(from: d)
    }

    private func load() async {
        guard !appState.grafanaBaseURL.isEmpty else { return }
        loading = true; defer { loading = false }
        if appState.monitors.isEmpty { await appState.refreshMonitors() }
        do {
            let active = try await appState.promInstant("vpn_egress_active_leg == 1", legend: "")
            let durQ  = try await appState.promInstant("vpn_egress_active_seconds", legend: "")
            let rtt   = try await appState.promInstant("vpn_leg_rtt_ms", legend: "")
            let txbps = try await appState.promInstant("sum by (host) (rate(wireguard_sent_bytes[5m]))", legend: "")
            let rxbps = try await appState.promInstant("sum by (host) (rate(wireguard_received_bytes[5m]))", legend: "")
            let home  = try await appState.promInstant("home_node_rtt_ms", legend: "")
            let tx    = try await appState.promInstant("vds_month_tx_bytes", legend: "")
            let lim   = try await appState.promInstant("vds_month_limit_bytes", legend: "")
            let sw    = try await appState.promInstant("vpn_egress_switch_time", legend: "")

            segs = appState.cascadeSegments.map { cfg in
                let al = active.first { $0.labels["host"] == cfg.host }?.labels["leg"] ?? "—"
                let ds = durQ.first { $0.labels["host"] == cfg.host }?.value ?? 0
                var rm: [String: Double] = [:]
                for r in rtt where r.labels["host"] == cfg.host { if let l = r.labels["leg"] { rm[l] = r.value } }
                // Healthy = node reachability (Ping + SSH). Feature/cascade checks (FI handshake,
                // Geo Routing, services) are shown separately and don't gate node health — FI is
                // cold-standby, so its dead-man monitors are expected down while on STO/AMS.
                let reach = appState.monitors.filter { $0.groupName == cfg.kumaGroup && ($0.name == "Ping" || $0.name == "SSH") }
                let healthy = !reach.isEmpty && reach.allSatisfy { $0.isUp }
                let casc = appState.monitors.first { $0.groupName == "VPN Cascade" && $0.name.contains(cfg.cascadeMatch) }
                return Seg(host: cfg.host, title: cfg.title, activeLeg: al, activeSeconds: ds, rtt: rm,
                           txBps: txbps.first { $0.labels["host"] == cfg.host }?.value,
                           rxBps: rxbps.first { $0.labels["host"] == cfg.host }?.value,
                           healthy: healthy, cascade: casc)
            }
            legs = ["sto", "ams", "fi"].map { l in
                let host = appState.cascadeTrafficHosts[l] ?? ""
                return Leg(leg: l, homeRTT: home.first { $0.labels["node"] == l }?.value,
                           txBytes: host.isEmpty ? nil : tx.first { $0.labels["host"] == host }?.value,
                           limitBytes: host.isEmpty ? nil : lim.first { $0.labels["host"] == host }?.value)
            }
            history = sw.compactMap { r -> Migration? in
                guard let f = r.labels["from"], let t = r.labels["to"], let h = r.labels["host"] else { return nil }
                return Migration(host: h, from: f, to: t, time: Date(timeIntervalSince1970: r.value), reason: r.labels["reason"] ?? "")
            }.sorted { $0.time > $1.time }
            errText = nil
        } catch let e {
            errText = e.localizedDescription
        }
    }
}
