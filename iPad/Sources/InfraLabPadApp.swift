import SwiftUI

@main
struct InfraLabPadApp: App {
    @StateObject private var appState = AppState()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(appState)
                .preferredColorScheme(.dark)
                .task {
                    await appState.refreshAll()
                    appState.startAutoRefresh()
                }
        }
    }
}
