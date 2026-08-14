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
    let iconBase64: String?
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

struct SmsMessage: Codable, Identifiable, Equatable {
    let id: String
    let address: String
    let body: String
    let date: Double // epoch millis
    let isOutgoing: Bool
}

struct SmsThread: Codable, Identifiable, Equatable {
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

// MARK: - Contacts (Phase 7)

/// One phone number per contact — the primary/first number Android's ContactsContract reports,
/// not the full set. Enough to message/call from the Mac; a contact-detail multi-number picker
/// isn't in scope.
struct ContactEntry: Codable, Identifiable, Equatable {
    let id: String
    let name: String
    let phoneNumber: String
    let isStarred: Bool
    let email: String?
    let organization: String?
}

struct ContactsSyncPayload: Codable {
    let contacts: [ContactEntry]
}

/// Opens the phone's own dialer pre-filled with the number (`ACTION_DIAL`) — the user still has
/// to tap call on the phone. Placing a call directly (`ACTION_CALL`) needs the CALL_PHONE
/// runtime permission and would let the Mac silently dial without the phone's own confirmation;
/// that's a deliberately different (and more invasive) capability this app doesn't take on.
struct ContactsDialPayload: Codable {
    let phoneNumber: String
}

struct ContactUpdatePayload: Codable {
    let id: String
    let name: String
    let phoneNumber: String
    let isStarred: Bool
    let email: String?
    let organization: String?
}

struct ContactUpdateResultPayload: Codable {
    let id: String
    let success: Bool
    let error: String?
}

struct ContactCreatePayload: Codable {
    let name: String
    let phoneNumber: String
    let email: String?
    let organization: String?
}

struct ContactCreateResultPayload: Codable {
    let success: Bool
    let error: String?
}

struct ContactDeletePayload: Codable {
    let id: String
}

struct ContactDeleteResultPayload: Codable {
    let id: String
    let success: Bool
    let error: String?
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

// MARK: - File browsing (Phase 6)

struct FileEntry: Codable, Identifiable, Equatable {
    let name: String
    let isDirectory: Bool
    let sizeBytes: Double
    let modifiedAt: Double // epoch millis

    var id: String { name }
}

/// `path` is always relative to the phone's shared storage root ("" is that root itself) —
/// never an absolute filesystem path, so the wire protocol never leaks device-specific mount
/// points. Segments are joined with "/"; see docs/PROTOCOL.md.
struct FilesListRequestPayload: Codable {
    let path: String
}

struct FilesListResultPayload: Codable {
    let path: String
    let entries: [FileEntry]
    let error: String?
}

struct FilesDownloadRequestPayload: Codable {
    let path: String
}

struct FilesDownloadResultPayload: Codable {
    let path: String
    let name: String
    let dataBase64: String?
    let mimeType: String?
    let error: String?
}

struct FilesUploadPayload: Codable {
    let path: String // destination directory
    let name: String
    let dataBase64: String
    let mimeType: String
}

struct FilesUploadResultPayload: Codable {
    let path: String
    let name: String
    let success: Bool
    let error: String?
}

struct FilesCreateFolderPayload: Codable {
    let path: String // parent directory
    let name: String
}

struct FilesCreateFolderResultPayload: Codable {
    let path: String
    let name: String
    let success: Bool
    let error: String?
}

struct FilesRenamePayload: Codable {
    let path: String
    let newName: String
}

struct FilesRenameResultPayload: Codable {
    let path: String
    let newName: String
    let success: Bool
    let error: String?
}

struct FilesDeletePayload: Codable {
    let path: String
}

struct FilesDeleteResultPayload: Codable {
    let path: String
    let success: Bool
    let error: String?
}

/// Shared by files.copy/files.move — both take a source item and a destination directory to
/// place it in (named after the source's own basename); they differ only in whether the source
/// survives the operation.
struct FilesTransferPayload: Codable {
    let sourcePath: String
    let destinationPath: String
}

struct FilesTransferResultPayload: Codable {
    let sourcePath: String
    let destinationPath: String
    let success: Bool
    let error: String?
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
