import SwiftUI

@main
struct LinkToMacApp: App {
    @State private var server: ConnectionServer

    init() {
        let identity = IdentityStore()
        let pairedDeviceStore = PairedDeviceStore()
        let notificationStore = NotificationStore()
        let server = ConnectionServer(identity: identity, pairedDeviceStore: pairedDeviceStore, notificationStore: notificationStore)
        _server = State(initialValue: server)
        LocalNotifier.requestAuthorization()
        server.start()
    }

    var body: some Scene {
        MenuBarExtra("LinkToMac", systemImage: "iphone.and.arrow.forward") {
            MenuBarView(server: server)
        }
        .menuBarExtraStyle(.window)
    }
}
