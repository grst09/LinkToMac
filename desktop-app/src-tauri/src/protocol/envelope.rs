//! Wire-protocol structs, ported 1:1 from `mac-app/Sources/LinkToMac/Models/Envelope.swift`.
//! See `docs/PROTOCOL.md` for the authoritative spec. `serde_json::Value` stands in for the
//! Swift app's hand-rolled `JSONValue` enum — it does the same job natively in Rust.

use serde::{Deserialize, Serialize};

/// Outer wire frame once a session key exists. Before pairing completes, `Message` is sent
/// as plain JSON with no `EncryptedFrame` wrapper.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EncryptedFrame {
    pub nonce: String,
    pub ciphertext: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Message {
    #[serde(rename = "type")]
    pub message_type: String,
    pub payload: serde_json::Value,
}

// MARK: - Handshake payloads

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HelloPayload {
    pub android_public_key: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub pairing_token: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub device_token: Option<String>,
    pub device_id: String,
    pub device_name: String,
    pub protocol_version: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HelloAckPayload {
    /// "paired" | "rejected"
    pub status: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub device_token: Option<String>,
    pub mac_device_name: String,
}

/// Sent unencrypted, immediately on every new connection (both first pairing and reconnect) —
/// see docs/PROTOCOL.md. Trust is established by the pairing token (first pairing) or by
/// pinning against the previously learned key (reconnect).
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ServerHelloPayload {
    pub mac_public_key: String,
    pub mac_device_id: String,
    pub mac_device_name: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EmptyPayload {}

/// The pairing QR's contents — deliberately doesn't include the Mac's public key (see
/// `serverHello`'s doc comment for why); ported from `PairingQRPayload` in
/// `ConnectionServer.swift`. Not part of the encrypted-envelope wire protocol itself, but sent
/// to the frontend so it can render the QR code and to Android by scanning it.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PairingQrPayload {
    pub host: String,
    pub port: u16,
    #[serde(rename = "pairingToken")]
    pub pairing_token: String,
    #[serde(rename = "macDeviceId")]
    pub mac_device_id: String,
}

// MARK: - Notification payloads

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct NotificationAction {
    pub title: String,
    pub action_id: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NotificationPostedPayload {
    pub id: String,
    pub package_name: String,
    pub app_name: String,
    pub title: String,
    pub text: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub sub_text: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub category: Option<String>,
    pub posted_at: f64,
    pub actions: Vec<NotificationAction>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub icon_base64: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct NotificationRemovedPayload {
    pub id: String,
}

// MARK: - Call log + SMS payloads (Phase 2)

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CallLogEntry {
    pub id: String,
    pub number: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub contact_name: Option<String>,
    /// incoming | outgoing | missed | rejected | blocked | voicemail | unknown
    #[serde(rename = "type")]
    pub call_type: String,
    pub date: f64,
    pub duration_seconds: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CallLogSyncPayload {
    pub calls: Vec<CallLogEntry>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SmsMessage {
    pub id: String,
    pub address: String,
    pub body: String,
    pub date: f64,
    pub is_outgoing: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SmsThread {
    pub thread_id: String,
    pub address: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub contact_name: Option<String>,
    pub messages: Vec<SmsMessage>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SmsSyncPayload {
    pub threads: Vec<SmsThread>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SmsSendPayload {
    pub address: String,
    pub body: String,
}

// MARK: - Contacts (Phase 7)

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ContactEntry {
    pub id: String,
    pub name: String,
    pub phone_number: String,
    pub is_starred: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub email: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub organization: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ContactsSyncPayload {
    pub contacts: Vec<ContactEntry>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ContactsDialPayload {
    pub phone_number: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ContactUpdatePayload {
    pub id: String,
    pub name: String,
    pub phone_number: String,
    pub is_starred: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub email: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub organization: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ContactUpdateResultPayload {
    pub id: String,
    pub success: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ContactCreatePayload {
    pub name: String,
    pub phone_number: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub email: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub organization: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ContactCreateResultPayload {
    pub success: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ContactDeletePayload {
    pub id: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ContactDeleteResultPayload {
    pub id: String,
    pub success: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
}

// MARK: - Photo + device status payloads (Phase 3)

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PhotoThumbnail {
    pub id: String,
    pub taken_at: f64,
    pub thumbnail_base64: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PhotoPageRequestPayload {
    pub offset: i64,
    pub limit: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PhotoPagePayload {
    pub photos: Vec<PhotoThumbnail>,
    pub has_more: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PhotoFullRequestPayload {
    pub id: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PhotoFullPayload {
    pub id: String,
    pub data_base64: String,
    pub mime_type: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DeviceStatusPayload {
    pub battery_percent: i64,
    pub is_charging: bool,
}

// MARK: - Screen mirroring payloads (Phase 4)

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MirrorConfigPayload {
    pub width: i64,
    pub height: i64,
    pub fps: i64,
    pub sps_base64: String,
    pub pps_base64: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MirrorStoppedPayload {
    /// requested | permission_denied | error
    pub reason: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MirrorTapPayload {
    /// normalized 0.0-1.0
    pub x: f64,
    pub y: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MirrorSwipePayload {
    pub start_x: f64,
    pub start_y: f64,
    pub end_x: f64,
    pub end_y: f64,
    pub duration_ms: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MirrorKeyPayload {
    /// back | home | recents
    pub action: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MirrorTextInputPayload {
    pub text: String,
}

// MARK: - Shared clipboard (Phase 5)

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ClipboardUpdatePayload {
    pub text: String,
    pub source_device_id: String,
    pub timestamp: f64,
}

// MARK: - File browsing (Phase 6)

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FileEntry {
    pub name: String,
    pub is_directory: bool,
    pub size_bytes: f64,
    pub modified_at: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FilesListRequestPayload {
    pub path: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FilesListResultPayload {
    pub path: String,
    pub entries: Vec<FileEntry>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FilesDownloadRequestPayload {
    pub path: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FilesDownloadResultPayload {
    pub path: String,
    pub name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub data_base64: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub mime_type: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FilesUploadPayload {
    /// destination directory
    pub path: String,
    pub name: String,
    pub data_base64: String,
    pub mime_type: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FilesUploadResultPayload {
    pub path: String,
    pub name: String,
    pub success: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FilesCreateFolderPayload {
    /// parent directory
    pub path: String,
    pub name: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FilesCreateFolderResultPayload {
    pub path: String,
    pub name: String,
    pub success: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FilesRenamePayload {
    pub path: String,
    pub new_name: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FilesRenameResultPayload {
    pub path: String,
    pub new_name: String,
    pub success: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FilesDeletePayload {
    pub path: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FilesDeleteResultPayload {
    pub path: String,
    pub success: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
}

/// Shared by files.copy/files.move — both take a source item and a destination directory.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FilesTransferPayload {
    pub source_path: String,
    pub destination_path: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FilesTransferResultPayload {
    pub source_path: String,
    pub destination_path: String,
    pub success: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
}
