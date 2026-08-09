import SwiftUI

/// Device status card pinned at the top of the sidebar, above the navigation list.
struct SidebarDeviceCard: View {
    var server: ConnectionServer

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Image(systemName: "iphone")
                    .font(.title2)
                    .foregroundStyle(.secondary)
                VStack(alignment: .leading, spacing: 2) {
                    Text(deviceName)
                        .font(.headline)
                        .lineLimit(1)
                    HStack(spacing: 4) {
                        Circle()
                            .fill(statusColor)
                            .frame(width: 6, height: 6)
                        Text(statusText)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        if case .connected = server.state {
                            Image(systemName: "wifi")
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
                Spacer()
                Toggle("", isOn: isActive)
                    .labelsHidden()
                    .toggleStyle(.switch)
                    .controlSize(.mini)
                    .help(isActive.wrappedValue ? "Disconnect" : "Reconnect")
            }

            if let percent = server.deviceStatusStore.batteryPercent {
                HStack(spacing: 4) {
                    Image(systemName: BatteryIcon.symbolName(percent: percent, isCharging: server.deviceStatusStore.isCharging))
                        .foregroundStyle(server.deviceStatusStore.isCharging ? .green : .secondary)
                    Text("\(percent)%")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .padding(10)
        .background(.quaternary.opacity(0.3), in: RoundedRectangle(cornerRadius: 10))
    }

    private var deviceName: String {
        if case .connected(let name) = server.state { return name }
        return "No device"
    }

    private var statusText: String {
        switch server.state {
        case .connected: return "Connected"
        case .listening: return "Waiting to pair"
        case .idle: return "Disconnected"
        case .failed: return "Connection error"
        }
    }

    private var statusColor: Color {
        switch server.state {
        case .connected: return .green
        case .listening: return .yellow
        case .failed: return .red
        case .idle: return .gray
        }
    }

    /// Bound directly to the live connection state rather than a locally-tracked bool, so the
    /// switch always reflects reality — including the brief moment after tapping "on" where the
    /// listener hasn't finished (re)binding yet.
    private var isActive: Binding<Bool> {
        Binding(
            get: {
                switch server.state {
                case .listening, .connected: return true
                case .idle, .failed: return false
                }
            },
            set: { newValue in
                if newValue {
                    server.start()
                } else {
                    server.disconnect()
                }
            }
        )
    }
}
