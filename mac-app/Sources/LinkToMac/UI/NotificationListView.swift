import SwiftUI

struct NotificationListView: View {
    var notifications: [NotificationPostedPayload]
    var onDismiss: (String) -> Void

    var body: some View {
        if notifications.isEmpty {
            Text("No notifications yet.")
                .font(.caption)
                .foregroundStyle(.secondary)
                .padding(.vertical, 8)
        } else {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 6) {
                    ForEach(notifications) { notification in
                        NotificationRowView(notification: notification, onDismiss: { onDismiss(notification.id) })
                    }
                }
            }
            .frame(maxHeight: 320)
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
        .padding(8)
        .background(.quaternary.opacity(0.3), in: RoundedRectangle(cornerRadius: 8))
    }
}
