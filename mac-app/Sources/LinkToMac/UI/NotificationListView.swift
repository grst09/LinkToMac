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
        HStack(alignment: .top, spacing: 12) {
            NotificationIconView(notification: notification)
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    Text(notification.appName)
                        .font(.caption.bold())
                        .foregroundStyle(.secondary)
                    Spacer()
                    Text(Date(timeIntervalSince1970: notification.postedAt / 1000), style: .relative)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Button(action: onDismiss) {
                        Image(systemName: "xmark.circle.fill")
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(.secondary)
                }
                Text(notification.title).font(.subheadline.bold())
                Text(notification.text)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
                if !notification.actions.isEmpty {
                    HStack(spacing: 8) {
                        ForEach(Array(notification.actions.prefix(2).enumerated()), id: \.offset) { index, action in
                            Text(action.title)
                                .font(.caption.bold())
                                .padding(.horizontal, 10)
                                .padding(.vertical, 4)
                                .background(Capsule().fill((index == 0 ? Color.orange : Color.secondary).opacity(0.15)))
                                .foregroundStyle(index == 0 ? .orange : .secondary)
                        }
                    }
                    .padding(.top, 2)
                }
            }
        }
        .padding(10)
        .background(.quaternary.opacity(0.3), in: RoundedRectangle(cornerRadius: 8))
    }
}

/// Renders the sending app's real launcher icon when Android sent one, falling back to the
/// same colored-initial style `InitialsAvatarView` uses elsewhere for consistency.
private struct NotificationIconView: View {
    var notification: NotificationPostedPayload
    var diameter: CGFloat = 40

    var body: some View {
        Group {
            if let iconBase64 = notification.iconBase64,
               let data = Data(base64Encoded: iconBase64),
               let nsImage = NSImage(data: data) {
                Image(nsImage: nsImage)
                    .resizable()
                    .aspectRatio(contentMode: .fit)
                    .frame(width: diameter, height: diameter)
                    .clipShape(RoundedRectangle(cornerRadius: diameter * 0.22))
            } else {
                ZStack {
                    RoundedRectangle(cornerRadius: diameter * 0.22)
                        .fill(InitialsAvatarView.color(for: notification.appName).gradient)
                    Text(String(notification.appName.first ?? "?"))
                        .font(.system(size: diameter * 0.45, weight: .bold))
                        .foregroundStyle(.white)
                }
                .frame(width: diameter, height: diameter)
            }
        }
    }
}
