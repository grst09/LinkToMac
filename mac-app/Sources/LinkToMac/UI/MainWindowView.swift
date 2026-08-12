import SwiftUI

struct MainWindowView: View {
    var server: ConnectionServer
    @State private var selection: SidebarSection? = .notifications

    enum SidebarSection: String, CaseIterable, Identifiable {
        case notifications = "Notifications"
        case messages = "Messages"
        case photos = "Photos"
        case files = "Files"
        case contacts = "Contacts"
        case mirroring = "Screen Mirroring"
        case device = "This Device"
        case settings = "Settings"

        /// Everything except Settings, which is pinned separately at the bottom of the sidebar.
        /// Call history isn't a separate item — it's a sub-tab inside Contacts (ContactsView),
        /// matching Phone Link's combined Calls app layout.
        static var navigationItems: [SidebarSection] {
            [.notifications, .messages, .photos, .files, .contacts, .mirroring, .device]
        }

        var id: String { rawValue }

        var systemImage: String {
            switch self {
            case .notifications: return "bell.fill"
            case .messages: return "message.fill"
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
                        .padding(.vertical, 4)
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
                .navigationTitle("LinkToMac")
        }
        .onChange(of: server.pendingMessageAddress) {
            if server.pendingMessageAddress != nil {
                selection = .messages
            }
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
            MessagesView(server: server)
        case .photos:
            PhotosView(server: server)
        case .files:
            FilesView(server: server)
        case .contacts:
            ContactsView(server: server)
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
