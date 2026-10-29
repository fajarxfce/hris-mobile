import SwiftUI
import Foundation

@main
struct StarterApp: App {
    @Environment(\.scenePhase) private var scenePhase
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var delegate

    var body: some Scene {
        WindowGroup {
            ZStack {
                ComposeView(host: delegate.host)
                if scenePhase != .active {
                    Color(.systemBackground)
                        .accessibilityLabel("HRIS Mobile")
                }
            }
                .ignoresSafeArea()
                .onOpenURL { delegate.host.openLink(uri: $0.absoluteString) }
                .onContinueUserActivity(NSUserActivityTypeBrowsingWeb) { activity in
                    if let url = activity.webpageURL {
                        delegate.host.openLink(uri: url.absoluteString)
                    }
                }
                .onChange(of: scenePhase) { phase in
                    if phase == .active { delegate.host.startForegroundWork() }
                    if phase == .background { delegate.enterBackground() }
                }
        }
    }
}
