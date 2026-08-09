import Foundation
import Observation

struct PairedDevice: Codable, Identifiable, Equatable {
    let id: String // Android deviceId
    var deviceName: String
    var deviceToken: String
    /// The original pairing token from QR pairing, reused as the HKDF salt on every reconnect.
    var pairingSalt: String
    var pairedAt: Date
}

/// Persists paired-device records as JSON in Application Support.
///
/// A proper Keychain-backed store is planned before this ships; for local development
/// a flat JSON file is simpler to inspect and reset (`rm ~/Library/Application Support/LinkToMac/paired-devices.json`).
@Observable
final class PairedDeviceStore {
    private let fileURL: URL
    private(set) var devices: [PairedDevice] = []

    init() {
        let appSupport = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let dir = appSupport.appendingPathComponent("LinkToMac", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        fileURL = dir.appendingPathComponent("paired-devices.json")
        load()
    }

    func device(withId id: String) -> PairedDevice? {
        devices.first { $0.id == id }
    }

    func device(withToken token: String) -> PairedDevice? {
        devices.first { $0.deviceToken == token }
    }

    @discardableResult
    func upsert(id: String, deviceName: String, deviceToken: String, pairingSalt: String) -> PairedDevice {
        if let index = devices.firstIndex(where: { $0.id == id }) {
            devices[index].deviceName = deviceName
            save()
            return devices[index]
        }
        let device = PairedDevice(id: id, deviceName: deviceName, deviceToken: deviceToken, pairingSalt: pairingSalt, pairedAt: Date())
        devices.append(device)
        save()
        return device
    }

    func remove(id: String) {
        devices.removeAll { $0.id == id }
        save()
    }

    private func load() {
        guard let data = try? Data(contentsOf: fileURL) else { return }
        devices = (try? JSONDecoder().decode([PairedDevice].self, from: data)) ?? []
    }

    private func save() {
        guard let data = try? JSONEncoder().encode(devices) else { return }
        try? data.write(to: fileURL, options: .atomic)
    }
}
