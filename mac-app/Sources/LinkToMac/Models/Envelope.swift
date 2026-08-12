import Foundation

/// Outer wire frame. Before pairing completes, `EncryptedFrame` is skipped and
/// `Message` is sent as plain JSON; every message after that is wrapped in `EncryptedFrame`.
struct EncryptedFrame: Codable {
    let nonce: String
    let ciphertext: String
}

struct Message: Codable {
    let type: String
    let payload: JSONValue
}

// MARK: - Handshake payloads

struct HelloPayload: Codable {
    let androidPublicKey: String
    let pairingToken: String?
    let deviceToken: String?
    let deviceId: String
    let deviceName: String
    let protocolVersion: Int
}

struct HelloAckPayload: Codable {
    let status: String // "paired" | "rejected"
    let deviceToken: String?
    let macDeviceName: String
}

/// Sent unencrypted, immediately on every new connection (both first pairing and reconnect),
/// so the Mac's public key never has to be crammed into the pairing QR code — see
/// docs/PROTOCOL.md. Trust is established by the pairing token (first pairing, human-verified
/// via the QR scan) or by pinning against the previously learned key (reconnect).
struct ServerHelloPayload: Codable {
    let macPublicKey: String
    let macDeviceId: String
    let macDeviceName: String
}

// MARK: - Notification payloads

struct NotificationAction: Codable {
    let title: String
    let actionId: String
}

struct NotificationPostedPayload: Codable, Identifiable {
    let id: String
    let packageName: String
    let appName: String
    let title: String
    let text: String
    let subText: String?
    let category: String?
    let postedAt: Double
    let actions: [NotificationAction]
}

struct NotificationRemovedPayload: Codable {
    let id: String
}

// MARK: - Call log + SMS payloads (Phase 2)

struct CallLogEntry: Codable, Identifiable {
    let id: String
    let number: String
    let contactName: String?
    let type: String // incoming | outgoing | missed | rejected | blocked | voicemail | unknown
    let date: Double // epoch millis
    let durationSeconds: Int
}

struct CallLogSyncPayload: Codable {
    let calls: [CallLogEntry]
}

struct SmsMessage: Codable, Identifiable {
    let id: String
    let address: String
    let body: String
    let date: Double // epoch millis
    let isOutgoing: Bool
}

struct SmsThread: Codable, Identifiable {
    let threadId: String
    let address: String
    let contactName: String?
    let messages: [SmsMessage]

    var id: String { threadId }
}

struct SmsSyncPayload: Codable {
    let threads: [SmsThread]
}

struct SmsSendPayload: Codable {
    let address: String
    let body: String
}

// MARK: - Photo + device status payloads (Phase 3)

struct PhotoThumbnail: Codable, Identifiable {
    let id: String
    let takenAt: Double // epoch millis
    let thumbnailBase64: String
}

struct PhotoPageRequestPayload: Codable {
    let offset: Int
    let limit: Int
}

struct PhotoPagePayload: Codable {
    let photos: [PhotoThumbnail]
    let hasMore: Bool
}

struct PhotoFullRequestPayload: Codable {
    let id: String
}

struct PhotoFullPayload: Codable {
    let id: String
    let dataBase64: String
    let mimeType: String
}

struct DeviceStatusPayload: Codable {
    let batteryPercent: Int
    let isCharging: Bool
}

// MARK: - Screen mirroring payloads (Phase 4)

struct MirrorConfigPayload: Codable {
    let width: Int
    let height: Int
    let fps: Int
    let spsBase64: String
    let ppsBase64: String
}

struct MirrorStoppedPayload: Codable {
    let reason: String // requested | permission_denied | error
}

struct MirrorTapPayload: Codable {
    let x: Double // normalized 0.0-1.0
    let y: Double
}

struct MirrorSwipePayload: Codable {
    let startX: Double
    let startY: Double
    let endX: Double
    let endY: Double
    let durationMs: Int
}

struct MirrorKeyPayload: Codable {
    let action: String // back | home | recents
}

struct MirrorTextInputPayload: Codable {
    let text: String
}

// MARK: - Shared clipboard (Phase 5)

struct ClipboardUpdatePayload: Codable {
    let text: String
    let sourceDeviceId: String
    let timestamp: Double // epoch millis
}

/// Minimal untyped JSON box so `Message.payload` can hold any of the payload structs above
/// without a giant enum of coding keys. Encoded/decoded via JSONSerialization under the hood.
enum JSONValue: Codable {
    case object([String: JSONValue])
    case array([JSONValue])
    case string(String)
    case number(Double)
    case bool(Bool)
    case null

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if let value = try? container.decode([String: JSONValue].self) {
            self = .object(value)
        } else if let value = try? container.decode([JSONValue].self) {
            self = .array(value)
        } else if let value = try? container.decode(String.self) {
            self = .string(value)
        } else if let value = try? container.decode(Double.self) {
            self = .number(value)
        } else if let value = try? container.decode(Bool.self) {
            self = .bool(value)
        } else {
            self = .null
        }
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .object(let value): try container.encode(value)
        case .array(let value): try container.encode(value)
        case .string(let value): try container.encode(value)
        case .number(let value): try container.encode(value)
        case .bool(let value): try container.encode(value)
        case .null: try container.encodeNil()
        }
    }

    /// Round-trips through a `Codable` struct.
    func decoded<T: Decodable>(as type: T.Type) throws -> T {
        let data = try JSONEncoder().encode(self)
        return try JSONDecoder().decode(T.self, from: data)
    }

    static func from<T: Encodable>(_ value: T) throws -> JSONValue {
        let data = try JSONEncoder().encode(value)
        return try JSONDecoder().decode(JSONValue.self, from: data)
    }
}
