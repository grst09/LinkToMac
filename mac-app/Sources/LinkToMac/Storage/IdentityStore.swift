import Foundation
import CryptoKit

/// The Mac's stable P-256 identity key pair, generated once and reused across pairing
/// sessions and reconnects. A Keychain-backed store is planned; JSON-on-disk is the
/// interim choice for local development (see PairedDeviceStore for the same tradeoff).
final class IdentityStore {
    let privateKey: P256.KeyAgreement.PrivateKey
    let deviceId: String

    private struct Record: Codable {
        let deviceId: String
        let rawPrivateKey: Data
    }

    init() {
        let appSupport = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let dir = appSupport.appendingPathComponent("LinkToMac", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let fileURL = dir.appendingPathComponent("identity.json")

        if let data = try? Data(contentsOf: fileURL),
           let record = try? JSONDecoder().decode(Record.self, from: data),
           let key = try? P256.KeyAgreement.PrivateKey(rawRepresentation: record.rawPrivateKey) {
            privateKey = key
            deviceId = record.deviceId
        } else {
            let key = P256.KeyAgreement.PrivateKey()
            let id = UUID().uuidString
            privateKey = key
            deviceId = id
            let record = Record(deviceId: id, rawPrivateKey: key.rawRepresentation)
            if let data = try? JSONEncoder().encode(record) {
                try? data.write(to: fileURL, options: .atomic)
            }
        }
    }

    var publicKeyBase64: String {
        privateKey.publicKey.x963Representation.base64EncodedString()
    }
}
