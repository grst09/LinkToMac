import SwiftUI

struct PairingQRView: View {
    var server: ConnectionServer
    @State private var qrImage: NSImage?

    var body: some View {
        VStack(spacing: 8) {
            if let qrImage {
                Image(nsImage: qrImage)
                    .interpolation(.none)
                    .resizable()
                    .frame(width: 200, height: 200)
                Text("Open LinkToMac on your phone and scan this code.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            } else {
                ProgressView()
                    .frame(width: 200, height: 200)
            }
        }
        .frame(maxWidth: .infinity)
        .task { generate() }
    }

    private func generate() {
        let payload = server.beginNewPairingSession()
        guard let data = try? JSONEncoder().encode(payload),
              let string = String(data: data, encoding: .utf8) else { return }
        qrImage = QRCodeGenerator.image(from: string)
    }
}
