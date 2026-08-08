import SwiftUI

@main
struct LinkToMacApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @State private var server: ConnectionServer

    init() {
        let identity = IdentityStore()
        let pairedDeviceStore = PairedDeviceStore()
        let server = ConnectionServer(identity: identity, pairedDeviceStore: pairedDeviceStore)
        _server = State(initialValue: server)
        AppDelegate.server = server
    }

    var body: some Scene {
        MenuBarExtra("LinkToMac", systemImage: "iphone.and.arrow.forward") {
            MenuBarView(server: server)
        }
        .menuBarExtraStyle(.window)
    }
}

/// `requestAuthorization` and `server.start()` need to run after the app has actually
/// finished launching — calling them from `App.init()` is too early for
/// `UNUserNotificationCenter` to hand off to the system permission prompt reliably
/// (observed: the completion handler never fired at all when called from init()).
final class AppDelegate: NSObject, NSApplicationDelegate {
    static var server: ConnectionServer?

    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApplication.shared.activate(ignoringOtherApps: true)
        LocalNotifier.requestAuthorization()
        Self.server?.start()
    }
}
