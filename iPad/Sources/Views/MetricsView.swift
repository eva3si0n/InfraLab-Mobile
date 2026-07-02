import SwiftUI
import Charts

// MARK: - Dashboard list

struct MetricsView: View {
    @EnvironmentObject var appState: AppState

    var body: some View {
        Group {
            if appState.grafanaBaseURL.isEmpty {
                ContentUnavailableView(
                    "Grafana Not Configured",
                    systemImage: "chart.xyaxis.line",
                    description: Text("Add Grafana URL and token in Settings")
                )
            } else if appState.dashboards.isEmpty && appState.dashboardsLoading {
                ProgressView("Loading dashboards…")
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if let e = appState.dashboardsError, appState.dashboards.isEmpty {
                ContentUnavailableView(
                    "Failed to Load",
                    systemImage: "exclamationmark.triangle",
                    description: Text(e)
                )
            } else {
                List(appState.dashboards) { dash in
                    NavigationLink(value: dash) {
                        Label(dash.title, systemImage: "rectangle.3.group")
                    }
                }
                .refreshable { await appState.loadDashboards() }
            }
        }
        .navigationTitle("Metrics")
        .toolbar {
            if appState.dashboardsLoading {
                ToolbarItem(placement: .topBarTrailing) { ProgressView() }
            }
        }
        .navigationDestination(for: DashboardInfo.self) { DashboardPanelsView(dashboard: $0) }
        .task { if appState.dashboards.isEmpty { await appState.loadDashboards() } }
    }
}

// MARK: - Dashboard detail: 2-column panel grid

struct DashboardPanelsView: View {
    @EnvironmentObject var appState: AppState
    let dashboard: DashboardInfo

    @State private var sections: [(title: String, panels: [PanelDef])] = []
    @State private var loading = true
    @State private var error: String?
    @State private var reloadToken = 0

    // Two flexible columns with 16 pt gap
    private let columns = [GridItem(.flexible(), spacing: 16), GridItem(.flexible(), spacing: 16)]

    var body: some View {
        ScrollView {
            if loading && sections.isEmpty {
                ProgressView().padding(.top, 60)
            } else if let error, sections.isEmpty {
                ContentUnavailableView(
                    "Failed to Load",
                    systemImage: "exclamationmark.triangle",
                    description: Text(error)
                )
                .padding(.top, 40)
            } else {
                LazyVStack(alignment: .leading, spacing: 28) {
                    ForEach(sections, id: \.title) { section in
                        VStack(alignment: .leading, spacing: 12) {
                            if !section.title.isEmpty {
                                Text(section.title.uppercased())
                                    .font(.caption.weight(.bold))
                                    .foregroundStyle(.secondary)
                                    .padding(.horizontal, 4)
                            }
                            LazyVGrid(columns: columns, spacing: 16) {
                                ForEach(section.panels) { panel in
                                    NativePanelView(panel: panel, reloadToken: reloadToken)
                                }
                            }
                        }
                    }
                }
                .padding()
            }
        }
        .navigationTitle(dashboard.title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { reloadToken += 1 } label: { Image(systemName: "arrow.clockwise") }
            }
        }
        .task(id: dashboard.uid) { await load() }
    }

    private func load() async {
        loading = true; error = nil
        do {
            let flat = try await appState.fetchPanels(uid: dashboard.uid)
            sections = groupBySections(flat)
        } catch {
            self.error = error.localizedDescription
        }
        loading = false
    }

    // Split flat panel list into (rowTitle, [dataPanels]) sections.
    private func groupBySections(_ panels: [PanelDef]) -> [(title: String, panels: [PanelDef])] {
        var result: [(title: String, panels: [PanelDef])] = []
        var currentTitle = ""
        var currentPanels: [PanelDef] = []

        for panel in panels {
            if panel.kind == .row {
                if !currentPanels.isEmpty {
                    result.append((title: currentTitle, panels: currentPanels))
                    currentPanels = []
                }
                currentTitle = panel.title
            } else {
                currentPanels.append(panel)
            }
        }
        if !currentPanels.isEmpty {
            result.append((title: currentTitle, panels: currentPanels))
        }
        return result
    }
}
