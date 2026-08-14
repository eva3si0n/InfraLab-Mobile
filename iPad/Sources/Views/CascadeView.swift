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
    struct Migration: Identifiable { let id = UUID(); let host, from, to: String; let time: Date; let reason: String; let group: String }

    struct PendingSwitch: Identifiable { let id = UUID(); let seg, leg, label: String }

    @State private var segs: [Seg] = []
    @State private var legs: [Leg] = []
    @State private var history: [Migration] = []
    @State private var loading = false
    @State private var errText: String?
    @State private var pending: PendingSwitch?
    @State private var switching = false
    @State private var switchNote: String?
    @State private var manual: [String: String] = [:]   // host → forced leg while in manual mode
    @State private var series: [String: (tx: [Double], rx: [Double])] = [:]
    @State private var paths: [CascadeAPI.PathInfo] = []  // host → WG history

    private let cascadeHint = "up — активное плечо STO/AMS (чистый Vultr-egress); down — деградация на FI (оба Vultr-плеча недоступны) или несвежий handshake."
    private let egressHint = "Лимит Vultr 2 ТБ на инстанс (STO и AMS отдельно), считается outbound (tx), сброс 1-го числа. FI — cold standby, квота не отслеживается."

    var body: some View {
        Group {
            // Экран больше не зависит от Grafana: всё приезжает из vpncascade.
            if appState.vpncascadeBaseURL.isEmpty {
                ContentUnavailableView("VPN Cascade Not Configured", systemImage: "arrow.triangle.branch",
                    description: Text("Set VPN Cascade URL in Settings"))
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

    /// Транзит — отдельным экраном, а не секцией в общей ленте: у него своя логика
    /// чтения, и мешать её с оперативной картиной каскада незачем (так же сделано в
    /// вебе). Шестой вкладкой не делаем — на iPhone она уехала бы в «More».
    /// 🪤 Отдельным СВОЙСТВОМ, а не строкой внутри egressSection: тело вычисляемого
    /// свойства не @ViewBuilder, и два выражения подряд там не компилируются.
    private var pathsSection: some View {
        Section {
            NavigationLink { CascadePathsView(paths: paths) } label: {
                HStack {
                    Image(systemName: "point.topleft.down.curvedto.point.bottomright.up")
                        .foregroundStyle(.secondary)
                    VStack(alignment: .leading, spacing: 1) {
                        Text("Транзит — AS-путь до плеч")
                        Text(paths.isEmpty ? "нет данных" : "\(paths.count) плеч(а) под наблюдением")
                            .font(.caption2).foregroundStyle(.secondary)
                    }
                }
            }
            .disabled(paths.isEmpty)
        }
    }

    private var list: some View {
        List {
            if !manual.isEmpty { manualBanner }
            decisionSection
            ForEach(segs) { segmentSection($0) }
            egressSection
            pathsSection
            historySection
        }
        .listStyle(.insetGrouped)
        .refreshable { await load() }
        .confirmationDialog("Переключить активное плечо?", isPresented:
            Binding(get: { pending != nil }, set: { if !$0 { pending = nil } }), presenting: pending) { ps in
            Button("Переключить: \(ps.label)", role: .destructive) { Task { await doSwitch(ps) } }
            Button("Отмена", role: .cancel) { pending = nil }
        } message: { ps in Text(ps.label) }
        .alert("VPN Cascade", isPresented: Binding(get: { switchNote != nil }, set: { if !$0 { switchNote = nil } })) {
            Button("OK", role: .cancel) { switchNote = nil }
        } message: { Text(switchNote ?? "") }
    }

    private var manualBanner: some View {
        Section {
            HStack(alignment: .top, spacing: 8) {
                Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.orange)
                Text("Ручной режим — " + manual.map { "\(segLabel($0.key)) → \($0.value.uppercased())" }.joined(separator: "; ")
                    + ". Верни Auto, когда не нужно.").font(.footnote)
            }
        }
        .listRowBackground(Color.orange.opacity(0.15))
    }

    // Per-segment STO/AMS/Auto force controls (shown only when a switch token is configured).
    @ViewBuilder private func switchControls(_ s: Seg) -> some View {
        if !appState.switchToken.isEmpty {
            HStack(spacing: 6) {
                Text("Force leg").font(.subheadline)
                Spacer(minLength: 4)
                swBtn(s, "sto", "STO"); swBtn(s, "ams", "AMS"); swBtn(s, "auto", "Auto")
            }
        }
    }

    private func swBtn(_ s: Seg, _ leg: String, _ label: String) -> some View {
        Button(label) { pending = PendingSwitch(seg: s.host, leg: leg, label: "\(segLabel(s.host)) → \(label)") }
            .buttonStyle(.bordered).controlSize(.small)
            .tint(s.activeLeg == leg ? .green : .secondary)
            .disabled(switching)
    }

    private func doSwitch(_ ps: PendingSwitch) async {
        pending = nil; switching = true; defer { switching = false }
        do {
            let r = try await appState.switchLeg(segment: ps.seg, leg: ps.leg)
            switchNote = "✓ \(ps.label): активно \((r.active ?? "").uppercased()) (override \(r.override ?? "—"))"
            try? await Task.sleep(for: .seconds(1))
            await load()
        } catch { switchNote = "✗ \(error.localizedDescription)" }
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
                row("Throughput WG · rate 2m", "↑ \(fmtBps(tx))  ↓ \(fmtBps(rx))")
            }
            if let ser = series[s.host], ser.tx.count > 1 {
                Sparkline(tx: ser.tx, rx: ser.rx)
                Text("↑ tx · ↓ rx · 1h").font(.caption2).foregroundStyle(.secondary)
            }
            row("RTT STO", s.rtt["sto"].map { "\(Int($0.rounded())) ms" } ?? "—")
            row("RTT AMS", s.rtt["ams"].map { "\(Int($0.rounded())) ms" } ?? "—")
            row("RTT FI",  s.rtt["fi"].map  { "\(Int($0.rounded())) ms" } ?? "—")
            switchControls(s)
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
                    } else if let tx = lg.txBytes {
                        HStack {
                            Text("\(fmtBytes(tx)) · this month").font(.caption2)
                            Spacer()
                            Text("no limit").font(.caption2.monospacedDigit()).foregroundStyle(.secondary)
                        }
                    }
                }
                .padding(.vertical, 2)
            }
            Text(egressHint).font(.caption2).foregroundStyle(.secondary)
        }
    }

    /// История разнесена по группам и листается влево-вправо — как на веб-странице.
    /// Это два несвязанных каскада: у домашнего входа плечи sto/ams/fi, у РКН-входа своя
    /// схема с транзитом через соседа. В общем списке их переключения перемешивались по
    /// времени и читались как один поток событий.
    /// Отдельный тип, а не кортеж: у кортежей в Swift нет key path, а он нужен
    /// и для `ForEach(id:)`, и для `map(\.rows)`.
    struct HistPage: Identifiable { let id: String; let title: String; let rows: [Migration] }

    private var historyPages: [HistPage] {
        [HistPage(id: "rkn", title: "РКН Ingress", rows: history.filter { $0.group == "rkn" }),
         HistPage(id: "udm", title: "UDM Pro · домашний каскад", rows: history.filter { $0.group != "rkn" })]
            .filter { !$0.rows.isEmpty }
    }

    @ViewBuilder private func historyRow(_ m: Migration) -> some View {
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

    @ViewBuilder private var historySection: some View {
        Section {
            if history.isEmpty {
                Text("No migrations recorded").font(.subheadline).foregroundStyle(.secondary)
            } else {
                // Высота под самую длинную страницу: у TabView с .page она фиксированная,
                // а страницы разной длины — иначе короткая обрезала бы длинную.
                let maxRows = historyPages.map { $0.rows.count }.max() ?? 0
                TabView {
                    ForEach(historyPages) { page in
                        VStack(alignment: .leading, spacing: 6) {
                            Text(page.title.uppercased())
                                .font(.caption2.weight(.semibold))
                                .foregroundStyle(page.id == "rkn" ? Color.red : Color.blue)
                            ForEach(page.rows) { historyRow($0) }
                            Spacer(minLength: 0)
                        }
                        .padding(.horizontal, 16).padding(.top, 4)
                        .tag(page.id)
                    }
                }
                .tabViewStyle(.page(indexDisplayMode: historyPages.count > 1 ? .always : .never))
                .frame(height: CGFloat(maxRows) * 26 + 54)
                .listRowInsets(EdgeInsets())
                Text("stale HS — хэндшейк протух; no route — нет прохода через плечо; link down — линк упал; failback — возврат на приоритетное плечо; boot — старт.")
                    .font(.caption2).foregroundStyle(.secondary)
            }
        } header: {
            Text("History · primary-leg migrations")
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
        let f = DateFormatter(); f.dateFormat = "MMM d, HH:mm"
        f.timeZone = TimeZone(identifier: "Asia/Yekaterinburg")   // MSK+2 (UTC+5), not device-local
        return f.string(from: d)
    }

    private func load() async {
        // Один запрос вместо десяти PromQL-выборок: экран стал тонким клиентом над
        // vpncascade. Логика (активное плечо, RTT, throughput, месячный трафик, история,
        // здоровье узла) живёт в сервисе — том же, что рисует веб-панель, поэтому
        // приложение и веб больше не расходятся при каждой доработке.
        guard !appState.vpncascadeBaseURL.isEmpty else { return }
        loading = true; defer { loading = false }

        guard let p = await appState.fetchCascadePayload() else {
            errText = "vpncascade недоступен"
            return
        }
        errText = p.error

        // Порядок как на веб-странице: сначала «РКН Ingress», затем домашний каскад.
        // Группу берём из payload, а не из seed — сервис теперь её и определяет.
        let ordered = (p.segments ?? []).enumerated().sorted { a, b in
            let ra = (a.element.group == "rkn") ? 0 : 1
            let rb = (b.element.group == "rkn") ? 0 : 1
            return ra == rb ? a.offset < b.offset : ra < rb
        }.map(\.element)

        segs = ordered.map { sg in
            // Вложенный монитор каскада приезжает готовым блоком; собираем из него
            // MonitorStatus, чтобы не трогать вёрстку карточки.
            let mon: MonitorStatus? = sg.cascade.map { c in
                MonitorStatus(id: 0, name: "Cascade", groupName: "VPN Cascade",
                              isUp: c.isUp ?? false, latency: nil,
                              uptime24h: c.uptime24h ?? 0,
                              recentBeats: (c.recentBeats ?? []).map {
                                  KumaHeartbeat(status: $0.status ?? 0, ping: $0.ping, time: $0.time ?? "")
                              })
            }
            return Seg(host: sg.host, title: sg.title ?? sg.host,
                       activeLeg: sg.activeLeg ?? "—", activeSeconds: sg.activeSeconds ?? 0,
                       rtt: sg.rtt ?? [:], txBps: sg.txBps, rxBps: sg.rxBps,
                       healthy: sg.healthy ?? false, cascade: mon)
        }

        legs = (p.legs ?? []).map { Leg(leg: $0.leg, homeRTT: $0.homeRTT,
                                        txBytes: $0.txBytes, limitBytes: $0.limitBytes) }

        history = (p.history ?? []).compactMap { m -> Migration? in
            guard let h = m.host, let f = m.from, let t = m.to, let ts = m.time else { return nil }
            return Migration(host: h, from: f, to: t, time: Date(timeIntervalSince1970: ts),
                             reason: m.reason ?? "", group: m.group ?? "udm")
        }.sorted { $0.time > $1.time }

        // Ручной пин и график throughput — из тех же сегментов, отдельная выборка
        // fetchCascadeAux больше не нужна.
        var man: [String: String] = [:]
        var ser: [String: (tx: [Double], rx: [Double])] = [:]
        for sg in (p.segments ?? []) {
            if sg.manual == true, let o = sg.override, !o.isEmpty, o != "auto" { man[sg.host] = o }
            ser[sg.host] = (tx: sg.txSeries ?? [], rx: sg.rxSeries ?? [])
        }
        manual = man
        series = ser
        paths = p.paths ?? []
    }
}

// Compact WG throughput sparkline: tx (green, ↑) + rx (blue, ↓), shared scale.
struct Sparkline: View {
    let tx: [Double]; let rx: [Double]
    var body: some View {
        let mx = max((tx + rx).max() ?? 1, 1)
        GeometryReader { geo in
            ZStack {
                path(tx, geo.size, mx).stroke(Color.green, style: .init(lineWidth: 1.5, lineJoin: .round))
                path(rx, geo.size, mx).stroke(Color.blue, style: .init(lineWidth: 1.5, lineJoin: .round))
            }
        }
        .frame(height: 34)
    }
    private func path(_ s: [Double], _ size: CGSize, _ mx: Double) -> Path {
        var p = Path(); guard s.count > 1 else { return p }
        for (i, v) in s.enumerated() {
            let x = size.width * CGFloat(i) / CGFloat(s.count - 1)
            let y = size.height * (1 - CGFloat(max(0, v) / mx))
            if i == 0 { p.move(to: CGPoint(x: x, y: y)) } else { p.addLine(to: CGPoint(x: x, y: y)) }
        }
        return p
    }
}
