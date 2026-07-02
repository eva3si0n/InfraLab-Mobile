import SwiftUI

enum AppSection: String, CaseIterable, Identifiable {
    case monitors = "Monitors"
    case cascade = "Cascade"
    case metrics = "Metrics"
    case homePage = "HomePage"
    case settings = "Settings"

    var id: String { rawValue }

    var icon: String {
        switch self {
        case .monitors: return "dot.radiowaves.up.forward"
        case .cascade:  return "arrow.triangle.branch"
        case .metrics:  return "chart.xyaxis.line"
        case .homePage: return "house"
        case .settings: return "gear"
        }
    }
}

struct RootView: View {
    @EnvironmentObject var appState: AppState
    @State private var selection: AppSection? = .monitors

    var body: some View {
        NavigationSplitView {
            List(AppSection.allCases, selection: $selection) { section in
                Label(section.rawValue, systemImage: section.icon)
                    .tag(section)
            }
            .navigationTitle("InfraLab")
            .listStyle(.sidebar)
        } detail: {
            // NavigationStack per section enables push navigation in the detail column.
            // Wrapping each branch separately ensures the stack is recreated (and path
            // cleared) whenever the user switches sections.
            switch selection {
            case .monitors:
                NavigationStack { MonitorsView() }
            case .cascade:
                NavigationStack { CascadeView() }
            case .metrics:
                NavigationStack { MetricsView() }
            case .homePage:
                HomePageView()
            case .settings:
                SettingsView()
            case nil:
                ContentUnavailableView(
                    "Select a Section",
                    systemImage: "sidebar.left",
                    description: Text("Choose a section from the sidebar")
                )
            }
        }
    }
}
