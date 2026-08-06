import SwiftUI

struct MenuBarView: View {
    var server: ConnectionServer

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            header
            Divider()
            switch server.state {
            case .connected(let deviceName):
                connectedHeader(deviceName)
                NotificationListView(
                    notifications: server.notificationStore.notifications,
                    onDismiss: { server.sendDismiss(id: $0) }
                )
            case .failed(let message):
                Text(message)
                    .font(.caption)
                    .foregroundStyle(.red)
            default:
                PairingQRView(server: server)
            }
            Divider()
            Button("Quit LinkToMac") { NSApplication.shared.terminate(nil) }
                .buttonStyle(.plain)
                .foregroundStyle(.secondary)
        }
        .padding()
        .frame(width: 340)
    }

    private var header: some View {
        HStack {
            Image(systemName: "iphone.and.arrow.forward")
            Text("LinkToMac").font(.headline)
            Spacer()
            Circle()
                .fill(statusColor)
                .frame(width: 8, height: 8)
        }
    }

    private func connectedHeader(_ deviceName: String) -> some View {
        Label(deviceName, systemImage: "checkmark.circle.fill")
            .foregroundStyle(.green)
            .font(.subheadline)
    }

    private var statusColor: Color {
        switch server.state {
        case .connected: return .green
        case .listening: return .yellow
        case .failed: return .red
        case .idle: return .gray
        }
    }
}
