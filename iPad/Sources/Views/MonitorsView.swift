import SwiftUI

// Opaque wrapper to avoid navigationDestination(for: String.self) conflicts
struct MonitorGroupNav: Hashable { let groupName: String }

struct MonitorsView: View {
    @EnvironmentObject var appState: AppState

    var body: some View {
        Group {
            if appState.kumaBaseURL.isEmpty || appState.kumaSlug.isEmpty {
                ContentUnavailableView(
                    "Kuma Not Configured",
                    systemImage: "dot.radiowaves.up.forward",
                    description: Text("Add Uptime Kuma URL and slug in Settings")
                )
            } else if appState.monitors.isEmpty && appState.monitorsLoading {
                ProgressView("Loading monitors…")
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if let error = appState.monitorsError, appState.monitors.isEmpty {
                ContentUnavailableView(
                    "Failed to Load",
                    systemImage: "exclamationmark.triangle",
                    description: Text(error)
                )
            } else {
                List {
                    ForEach(orderedGroups, id: \.name) { group in
                        NavigationLink(value: MonitorGroupNav(groupName: group.name)) {
                            GroupSummaryRow(name: group.name, monitors: group.monitors)
                        }
                    }
                }
                .listStyle(.insetGrouped)
                .refreshable { await appState.refreshMonitors() }
            }
        }
        .navigationTitle("Monitors")
        .toolbar {
            if appState.monitorsLoading {
                ToolbarItem(placement: .topBarTrailing) { ProgressView() }
            } else {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { Task { await appState.refreshMonitors() } } label: {
                        Image(systemName: "arrow.clockwise")
                    }
                }
            }
        }
        .navigationDestination(for: MonitorGroupNav.self) { nav in
            MonitorGroupDetailView(groupName: nav.groupName)
        }
    }

    private var orderedGroups: [(name: String, monitors: [MonitorStatus])] {
        var order: [String] = []
        var map: [String: [MonitorStatus]] = [:]
        for m in appState.monitors {
            if map[m.groupName] == nil { order.append(m.groupName) }
            map[m.groupName, default: []].append(m)
        }
        return order.map { ($0, map[$0] ?? []) }
    }
}

// MARK: - Group detail: monitor cards in a responsive grid

struct MonitorGroupDetailView: View {
    @EnvironmentObject var appState: AppState
    let groupName: String

    private var monitors: [MonitorStatus] {
        appState.monitors.filter { $0.groupName == groupName }
    }

    // Two cards per row on iPad (adaptive min 340 pt)
    private let columns = [
        GridItem(.adaptive(minimum: 340), spacing: 14)
    ]

    var body: some View {
        ScrollView {
            LazyVGrid(columns: columns, spacing: 14) {
                ForEach(monitors) { MonitorCard(monitor: $0) }
            }
            .padding()
        }
        .navigationTitle(groupName)
        .navigationBarTitleDisplayMode(.inline)
    }
}
