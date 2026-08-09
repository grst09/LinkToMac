import SwiftUI

struct MainWindowView: View {
    var server: ConnectionServer
    @State private var selection: SidebarSection? = .notifications

    enum SidebarSection: String, CaseIterable, Identifiable {
        case notifications = "Notifications"
        case messages = "Messages"
        case calls = "Calls"
        case photos = "Photos"
        case files = "Files"
        case contacts = "Contacts"
        case mirroring = "Screen Mirroring"
        case device = "This Device"
        case settings = "Settings"

        /// Everything except Settings, which is pinned separately at the bottom of the sidebar.
        static var navigationItems: [SidebarSection] {
            [.notifications, .messages, .calls, .photos, .files, .contacts, .mirroring, .device]
        }

        var id: String { rawValue }

        var systemImage: String {
            switch self {
            case .notifications: return "bell.fill"
            case .messages: return "message.fill"
            case .calls: return "phone.fill"
            case .photos: return "photo.on.rectangle.angled"
            case .files: return "folder.fill"
            case .contacts: return "person.2.fill"
            case .mirroring: return "rectangle.on.rectangle"
            case .device: return "iphone"
            case .settings: return "gearshape.fill"
            }
        }
    }

    var body: some View {
        NavigationSplitView {
            VStack(spacing: 0) {
                SidebarDeviceCard(server: server)
                    .padding(.horizontal, 12)
                    .padding(.top, 12)
                    .padding(.bottom, 8)

                List(SidebarSection.navigationItems, selection: $selection) { section in
                    Label(section.rawValue, systemImage: section.systemImage)
                        .tag(section)
                }

                Divider()

                Button {
                    selection = .settings
                } label: {
                    Label("Settings", systemImage: "gearshape")
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .padding(12)
            }
            .navigationSplitViewColumnWidth(min: 200, ideal: 220)
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
            PhotosView(server: server)
        case .files:
            ComingSoonView(
                title: "Files",
                systemImage: "folder.fill",
                detail: "Browsing files on your phone isn't currently planned, but may be added in a future phase."
            )
        case .contacts:
            ComingSoonView(
                title: "Contacts",
                systemImage: "person.2.fill",
                detail: "A contacts browser isn't currently planned, but may be added in a future phase."
            )
        case .mirroring:
            ScreenMirrorView(server: server)
        case .settings:
            ComingSoonView(
                title: "Settings",
                systemImage: "gearshape",
                detail: "App settings aren't built yet — for now there's nothing to configure."
            )
        case .none:
            EmptyView()
        }
    }
}
