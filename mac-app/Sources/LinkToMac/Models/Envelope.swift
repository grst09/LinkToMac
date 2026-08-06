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
