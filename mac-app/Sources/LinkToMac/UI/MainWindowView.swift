import SwiftUI

struct MainWindowView: View {
    var server: ConnectionServer
    @State private var selection: SidebarSection? = .notifications

    enum SidebarSection: String, CaseIterable, Identifiable {
        case notifications = "Notifications"
        case messages = "Messages"
        case calls = "Calls"
        case photos = "Photos"
        case mirroring = "Screen Mirroring"
        case device = "This Device"

        var id: String { rawValue }

        var systemImage: String {
            switch self {
            case .notifications: return "bell.fill"
            case .messages: return "message.fill"
            case .calls: return "phone.fill"
            case .photos: return "photo.on.rectangle.angled"
            case .mirroring: return "rectangle.on.rectangle"
            case .device: return "iphone"
            }
        }
    }

    var body: some View {
        NavigationSplitView {
            List(SidebarSection.allCases, selection: $selection) { section in
                Label(section.rawValue, systemImage: section.systemImage)
                    .tag(section)
            }
            .navigationSplitViewColumnWidth(min: 180, ideal: 200)
        } detail: {
            detailView
                .navigationTitle(selection?.rawValue ?? "LinkToMac")
        }
    }

    @ViewBuilder
    private var detailView: some View {
        switch selection {
        case .notifications:
            NotificationListView(
                notifications: server.notificationStore.notifications,
                onDismiss: { server.sendDismiss(id: $0) }
            )
        case .device:
            DeviceStatusView(server: server)
        case .messages:
            MessagesView(
                store: server.messageStore,
                onSend: { address, body in server.sendSms(address: address, body: body) }
            )
        case .calls:
            CallsView(store: server.callLogStore)
        case .photos:
            ComingSoonView(
                title: "Photos",
                systemImage: "photo.on.rectangle.angled",
                detail: "Browsing and importing photos is planned for Phase 3 — see docs/PLAN.md."
            )
        case .mirroring:
            ComingSoonView(
                title: "Screen Mirroring",
                systemImage: "rectangle.on.rectangle",
                detail: "Screen mirroring is planned for Phase 4 — see docs/PLAN.md."
            )
        case .none:
            EmptyView()
        }
    }
}
