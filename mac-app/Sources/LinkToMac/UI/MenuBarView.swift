import SwiftUI

struct MenuBarView: View {
    var server: ConnectionServer
    @Environment(\.openWindow) private var openWindow
    @State private var isPairingNewDevice = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            header
            Divider()
            if isPairingNewDevice {
                PairingQRView(server: server)
                Button("Cancel") { isPairingNewDevice = false }
                    .buttonStyle(.plain)
                    .foregroundStyle(.secondary)
            } else {
                deviceList
                Button("Pair New Device…") { isPairingNewDevice = true }
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
            Button {
                openWindow(id: "main")
                NSApplication.shared.activate(ignoringOtherApps: true)
            } label: {
                Text("LinkToMac").font(.headline)
            }
            .buttonStyle(.plain)
            Spacer()
            Circle()
                .fill(statusColor)
                .frame(width: 8, height: 8)
        }
    }

    @ViewBuilder
    private var deviceList: some View {
        if server.pairedDevices.isEmpty {
            Text("No paired devices yet.")
                .font(.caption)
                .foregroundStyle(.secondary)
        } else {
            VStack(alignment: .leading, spacing: 8) {
                ForEach(server.pairedDevices) { device in
                    deviceRow(device)
                }
            }
        }
    }

    private func deviceRow(_ device: PairedDevice) -> some View {
        let isActive = server.activeDeviceIdIfConnected == device.id
        return HStack {
            Circle()
                .fill(isActive ? Color.green : Color.gray)
                .frame(width: 8, height: 8)
            Text(device.deviceName)
                .font(.subheadline)
                .foregroundStyle(isActive ? .primary : .secondary)
            Spacer()
            Button {
                server.forgetDevice(id: device.id)
            } label: {
                Image(systemName: "xmark.circle.fill")
            }
            .buttonStyle(.plain)
            .foregroundStyle(.secondary)
            .help("Forget this device")
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
}
