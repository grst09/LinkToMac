import SwiftUI

struct NotificationListView: View {
    var notifications: [NotificationPostedPayload]
    var onDismiss: (String) -> Void

    @State private var searchText = ""

    var body: some View {
        VStack(spacing: 0) {
            SectionHeaderView(
                icon: "bell.fill",
                iconColor: .orange,
                title: "Notifications",
                subtitle: "\(notifications.count) notification\(notifications.count == 1 ? "" : "s")"
            )
            SearchBarView(text: $searchText, prompt: "Search notifications")
            if notifications.isEmpty {
                VStack(spacing: 8) {
                    Image(systemName: "bell.slash")
                        .font(.system(size: 32))
                        .foregroundStyle(.secondary)
                    Text("No notifications yet")
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if filteredNotifications.isEmpty {
                Text("No matching notifications")
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 8) {
                        ForEach(filteredNotifications) { notification in
                            NotificationRowView(notification: notification, onDismiss: { onDismiss(notification.id) })
                        }
                    }
                    .padding()
                }
            }
        }
    }

    private var filteredNotifications: [NotificationPostedPayload] {
        guard !searchText.isEmpty else { return notifications }
        return notifications.filter { notification in
            notification.appName.localizedCaseInsensitiveContains(searchText)
                || notification.title.localizedCaseInsensitiveContains(searchText)
                || notification.text.localizedCaseInsensitiveContains(searchText)
        }
    }
}

struct NotificationRowView: View {
    var notification: NotificationPostedPayload
    var onDismiss: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack {
                Text(notification.appName)
                    .font(.caption.bold())
                    .foregroundStyle(.secondary)
                Spacer()
                Button(action: onDismiss) {
                    Image(systemName: "xmark.circle.fill")
                }
                .buttonStyle(.plain)
                .foregroundStyle(.secondary)
            }
            Text(notification.title).font(.subheadline.bold())
            Text(notification.text).font(.subheadline).lineLimit(2)
        }
        .padding(10)
        .background(.quaternary.opacity(0.3), in: RoundedRectangle(cornerRadius: 8))
    }
}
