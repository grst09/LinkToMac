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
            case .failed(let message):
                Label("Not connected", systemImage: "exclamationmark.triangle.fill")
                    .foregroundStyle(.red)
                    .font(.title2)
                Text(message)
                    .foregroundStyle(.secondary)
            case .listening, .idle:
                Label("Waiting to pair", systemImage: "qrcode")
                    .font(.title2)
                Text("Click the menu bar icon to scan the pairing QR code from your phone.")
                    .foregroundStyle(.secondary)
            }
            Spacer()
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }
}
