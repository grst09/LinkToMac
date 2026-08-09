import SwiftUI

struct DeviceStatusView: View {
    var server: ConnectionServer

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            switch server.state {
            case .connected(let deviceName):
                Label(deviceName, systemImage: "checkmark.circle.fill")
                    .foregroundStyle(.green)
                    .font(.title2)
                Text("Connected and syncing.")
                    .foregroundStyle(.secondary)
                if let percent = server.deviceStatusStore.batteryPercent {
                    Label(
                        "\(percent)%\(server.deviceStatusStore.isCharging ? " (charging)" : "")",
                        systemImage: BatteryIcon.symbolName(percent: percent, isCharging: server.deviceStatusStore.isCharging)
                    )
                    .foregroundStyle(server.deviceStatusStore.isCharging ? .green : .secondary)
                }
                Button("Disconnect") { server.disconnect() }
                    .padding(.top, 4)
            case .listening:
                Label("Waiting to pair", systemImage: "qrcode")
                    .font(.title2)
                Text("Click the menu bar icon to scan the pairing QR code from your phone.")
                    .foregroundStyle(.secondary)
            case .idle:
                Label("Disconnected", systemImage: "pause.circle")
                    .font(.title2)
                Text("You disconnected this device. Reconnect to resume syncing.")
                    .foregroundStyle(.secondary)
                Button("Reconnect") { server.start() }
                    .padding(.top, 4)
            case .failed(let message):
                Label("Connection error", systemImage: "exclamationmark.triangle.fill")
                    .foregroundStyle(.red)
                    .font(.title2)
                Text(message)
                    .foregroundStyle(.secondary)
                Button("Retry") { server.start() }
                    .padding(.top, 4)
            }
            Spacer()
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }
}
