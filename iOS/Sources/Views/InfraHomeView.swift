import SwiftUI

/// InfraHome — нативная витрина лаборатории вместо прежнего web-view на gethomepage.
///
/// Почему нативно, а не адресом в web-view: снаружи дома InfraHome закрыт Cloudflare
/// Access, и встроенный браузер требовал бы логина на каждом запуске. Экран ходит по
/// `/api/home` с тем же service token, что и каскад, и работает из любой сети.
///
/// Порядок на телефоне НЕ повторяет веб. Там наверху плитки, а здесь сначала то, ради
/// чего в дашборд заглядывают с телефона: активные алерты и сводка мониторов. Плитки
/// ниже, группами, свёрнутыми так же, как на вебе.
struct InfraHomeView: View {
    @EnvironmentObject var appState: AppState

    @State private var data: InfraHomeAPI.Payload?
    @State private var loading = false
    @State private var errText: String?
    @State private var expanded: Set<String> = []

    var body: some View {
        NavigationStack {
            Group {
                if appState.homePageBaseURL.isEmpty {
                    ContentUnavailableView("InfraHome Not Configured", systemImage: "square.grid.2x2",
                        description: Text("Add InfraHome URL in Settings"))
                } else if data == nil && loading {
                    ProgressView("Loading…").frame(maxWidth: .infinity, maxHeight: .infinity)
                } else if let e = errText, data == nil {
                    ContentUnavailableView("Failed to Load", systemImage: "exclamationmark.triangle",
                        description: Text(e))
                } else {
                    list
                }
            }
            .navigationTitle("InfraHome")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { if loading { ToolbarItem(placement: .topBarTrailing) { ProgressView() } } }
        }
        .task { await load() }
    }

    private var list: some View {
        List {
            if let a = data?.alerts, (a.firing ?? 0) > 0 || (a.pending ?? 0) > 0 {
                Section("Алерты") {
                    ForEach(Array((a.list ?? []).enumerated()), id: \.offset) { _, al in
                        HStack(alignment: .top, spacing: 8) {
                            Circle().fill(sevColor(al.severity)).frame(width: 8, height: 8).padding(.top, 5)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(al.name ?? "—").font(.callout.weight(.semibold))
                                if let s = al.summary, !s.isEmpty {
                                    Text(s).font(.caption).foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                }
            }

            if let k = data?.kuma {
                Section("Мониторы") {
                    HStack {
                        counter("up", k.up ?? 0, .green)
                        counter("down", k.down ?? 0, .red)
                        counter("pending", k.pending ?? 0, .orange)
                        Spacer()
                        Text("всего \(k.total ?? 0)").font(.caption).foregroundStyle(.secondary)
                    }
                }
            }

            ForEach(Array((data?.groups ?? []).enumerated()), id: \.offset) { _, g in
                let key = g.name ?? UUID().uuidString
                Section {
                    // Свёрнутость берём с веба, но пользовательское раскрытие её
                    // перекрывает и переживает автообновление.
                    if isOpen(key, g.collapsed ?? false) {
                        ForEach(Array((g.tiles ?? []).enumerated()), id: \.offset) { _, t in
                            tileRow(t)
                        }
                    }
                } header: {
                    Button { toggle(key, g.collapsed ?? false) } label: {
                        HStack {
                            Image(systemName: isOpen(key, g.collapsed ?? false) ? "chevron.down" : "chevron.right")
                                .font(.caption2)
                            Text(g.name ?? "—")
                            Spacer()
                            Text("\((g.tiles ?? []).count)").font(.caption2).foregroundStyle(.secondary)
                        }
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .listStyle(.insetGrouped)
        .refreshable { await load() }
    }

    @ViewBuilder
    private func tileRow(_ t: InfraHomeAPI.Tile) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 8) {
                Circle().fill(statusColor(t.status)).frame(width: 8, height: 8)
                Text(t.name ?? "—").font(.callout)
                Spacer()
                if let b = t.badge, !b.isEmpty {
                    Text(b).font(.caption2.weight(.bold)).monospaced()
                        .padding(.horizontal, 5).padding(.vertical, 1)
                        .background(Color.secondary.opacity(0.15))
                        .clipShape(RoundedRectangle(cornerRadius: 4))
                }
            }
            if let d = t.description, !d.isEmpty {
                Text(d).font(.caption2).foregroundStyle(.secondary)
            }
            // Показываем ТОЛЬКО метрики не в порядке: на телефоне полный список из
            // семи строк на плитку превращает экран в простыню, а смысл витрины —
            // заметить отклонение.
            let bad = (t.metrics ?? []).filter { ($0.state ?? "ok") != "ok" }
            ForEach(Array(bad.enumerated()), id: \.offset) { _, m in
                HStack(spacing: 6) {
                    Circle().fill(statusColor(m.state)).frame(width: 5, height: 5)
                    Text(m.label ?? "—").font(.caption2)
                    Spacer()
                    Text(m.text ?? "").font(.caption2.monospacedDigit()).foregroundStyle(.secondary)
                }
                .padding(.leading, 14)
            }
        }
        .padding(.vertical, 2)
    }

    private func counter(_ label: String, _ n: Int, _ c: Color) -> some View {
        HStack(spacing: 4) {
            Circle().fill(n > 0 ? c : Color.secondary.opacity(0.4)).frame(width: 7, height: 7)
            Text("\(n)").font(.callout.weight(.semibold).monospacedDigit())
            Text(label).font(.caption2).foregroundStyle(.secondary)
        }
        .padding(.trailing, 10)
    }

    private func isOpen(_ key: String, _ collapsedByDefault: Bool) -> Bool {
        expanded.contains(key) ? true : !collapsedByDefault
    }

    private func toggle(_ key: String, _ collapsedByDefault: Bool) {
        if isOpen(key, collapsedByDefault) {
            // Свёрнуть раскрытую: если она открыта «по умолчанию», запоминать нечего —
            // храним только явные раскрытия, поэтому закрываем через удаление ключа.
            expanded.remove(key)
        } else {
            expanded.insert(key)
        }
    }

    private func statusColor(_ s: String?) -> Color {
        switch s {
        case "crit", "critical", "down": return .red
        case "warn", "warning": return .orange
        case "ok", "up": return .green
        default: return .secondary
        }
    }

    private func sevColor(_ s: String?) -> Color {
        s == "critical" ? .red : (s == "warning" ? .orange : .secondary)
    }

    private func load() async {
        guard !appState.homePageBaseURL.isEmpty else { return }
        loading = true; defer { loading = false }
        if let p = await appState.fetchInfraHome() {
            data = p; errText = nil
        } else {
            errText = "InfraHome недоступен"
        }
    }
}
