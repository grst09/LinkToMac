package com.linktomac.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class EncryptedFrame(
    val nonce: String,
    val ciphertext: String
)

@Serializable
data class Envelope(
    val type: String,
    val payload: JsonElement
)

@Serializable
class EmptyPayload

@Serializable
data class HelloPayload(
    val androidPublicKey: String,
    val pairingToken: String? = null,
    val deviceToken: String? = null,
    val deviceId: String,
    val deviceName: String,
    val protocolVersion: Int = 1
)

@Serializable
data class HelloAckPayload(
    val status: String,
    val deviceToken: String? = null,
    val macDeviceName: String
)

/**
 * Sent unencrypted, immediately on every new connection (both first pairing and reconnect),
 * so the Mac's public key never has to be crammed into the pairing QR code — see
 * docs/PROTOCOL.md. Trust is established by the pairing token (first pairing, human-verified
 * via the QR scan) or by pinning against the previously learned key (reconnect).
 */
@Serializable
data class ServerHelloPayload(
    val macPublicKey: String,
    val macDeviceId: String,
    val macDeviceName: String
)

@Serializable
data class NotificationAction(
    val title: String,
    val actionId: String
)

@Serializable
data class NotificationPostedPayload(
    val id: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val subText: String? = null,
    val category: String? = null,
    val postedAt: Double,
    val actions: List<NotificationAction> = emptyList(),
    val iconBase64: String? = null
)

@Serializable
data class NotificationRemovedPayload(
    val id: String
)

@Serializable
data class PairingQrPayload(
    val host: String,
    val port: Int,
    val pairingToken: String,
    val macDeviceId: String
)

// Phase 2: call log + SMS

@Serializable
data class CallLogEntry(
    val id: String,
    val number: String,
    val contactName: String? = null,
    val type: String, // incoming | outgoing | missed | rejected | blocked | voicemail | unknown
    val date: Double, // epoch millis
    val durationSeconds: Int
)

@Serializable
data class CallLogSyncPayload(
    val calls: List<CallLogEntry>
)

@Serializable
data class SmsMessage(
    val id: String,
    val address: String,
    val body: String,
    val date: Double, // epoch millis
    val isOutgoing: Boolean
)

@Serializable
data class SmsThread(
    val threadId: String,
    val address: String,
    val contactName: String? = null,
    val messages: List<SmsMessage>
)

@Serializable
data class SmsSyncPayload(
    val threads: List<SmsThread>
)

@Serializable
data class SmsSendPayload(
    val address: String,
    val body: String
)


// Phase 7: contacts

@Serializable
data class ContactEntry(
    val id: String,
    val name: String,
    val phoneNumber: String,
    val isStarred: Boolean,
    val email: String? = null,
    val organization: String? = null
)

@Serializable
data class ContactsSyncPayload(
    val contacts: List<ContactEntry>
)

@Serializable
data class ContactsDialPayload(
    val phoneNumber: String
)

@Serializable
data class ContactUpdatePayload(
    val id: String,
    val name: String,
    val phoneNumber: String,
    val isStarred: Boolean,
    val email: String? = null,
    val organization: String? = null
)

@Serializable
data class ContactUpdateResultPayload(
    val id: String,
    val success: Boolean,
    val error: String? = null
)

@Serializable
data class ContactCreatePayload(
    val name: String,
    val phoneNumber: String,
    val email: String? = null,
    val organization: String? = null
)

@Serializable
data class ContactCreateResultPayload(
    val success: Boolean,
    val error: String? = null
)

@Serializable
data class ContactDeletePayload(
    val id: String
)

@Serializable
data class ContactDeleteResultPayload(
    val id: String,
    val success: Boolean,
    val error: String? = null
)

// Phase 3: photos + device status

@Serializable
data class PhotoThumbnail(
    val id: String,
    val takenAt: Double, // epoch millis
    val thumbnailBase64: String
)

@Serializable
data class PhotoPageRequestPayload(
    val offset: Int,
    val limit: Int
)

@Serializable
data class PhotoPagePayload(
    val photos: List<PhotoThumbnail>,
    val hasMore: Boolean
)

@Serializable
data class PhotoFullRequestPayload(
    val id: String
)

@Serializable
data class PhotoFullPayload(
    val id: String,
    val dataBase64: String,
    val mimeType: String
)

/** No `photo.deleteResult` response — whatever actually gets deleted (the user may cancel the
 *  system consent dialog for some or all of a batch) is picked up by the existing
 *  `PhotoRepository.observe` content-observer, which already triggers `sendPhotoLibraryChanged`
 *  on any library change and drives the Mac's reset-and-re-page-from-0 flow. */
@Serializable
data class PhotoDeleteRequestPayload(
    val ids: List<String>
)

@Serializable
data class DeviceStatusPayload(
    val batteryPercent: Int,
    val isCharging: Boolean
)

// Phase 4: screen mirroring

@Serializable
data class MirrorConfigPayload(
    val width: Int,
    val height: Int,
    val fps: Int,
    val spsBase64: String,
    val ppsBase64: String
)

@Serializable
data class MirrorStoppedPayload(
    val reason: String // requested | permission_denied | error
)

@Serializable
data class MirrorTapPayload(
    val x: Double, // normalized 0.0-1.0
    val y: Double
)

@Serializable
data class MirrorSwipePayload(
    val startX: Double,
    val startY: Double,
    val endX: Double,
    val endY: Double,
    val durationMs: Int
)

@Serializable
data class MirrorKeyPayload(
    val action: String // back | home | recents
)

@Serializable
data class MirrorTextInputPayload(
    val text: String
)

@Serializable
data class AppInfo(
    val packageName: String,
    val appName: String,
    val iconBase64: String
)

@Serializable
data class AppsListPayload(
    val apps: List<AppInfo>
)

@Serializable
data class LaunchAppPayload(
    val packageName: String
)

// Phase 5: shared clipboard

@Serializable
data class ClipboardUpdatePayload(
    val text: String,
    val sourceDeviceId: String,
    val timestamp: Double // epoch millis
)

@Serializable
data class ClipboardImageUpdatePayload(
    val imageBase64: String, // PNG
    val sourceDeviceId: String,
    val timestamp: Double // epoch millis
)

// Phase 6: file browsing

@Serializable
data class FileEntry(
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Double,
    val modifiedAt: Double // epoch millis
)

@Serializable
data class FilesListRequestPayload(
    val path: String
)

@Serializable
data class FilesListResultPayload(
    val path: String,
    val entries: List<FileEntry>,
    val error: String? = null
)

@Serializable
data class FilesDownloadRequestPayload(
    val path: String
)

@Serializable
data class FilesDownloadResultPayload(
    val path: String,
    val name: String,
    val dataBase64: String? = null,
    val mimeType: String? = null,
    val error: String? = null
)

@Serializable
data class FilesUploadPayload(
    val path: String,
    val name: String,
    val dataBase64: String,
    val mimeType: String
)

@Serializable
data class FilesUploadResultPayload(
    val path: String,
    val name: String,
    val success: Boolean,
    val error: String? = null
)

@Serializable
data class FilesCreateFolderPayload(
    val path: String,
    val name: String
)

@Serializable
data class FilesCreateFolderResultPayload(
    val path: String,
    val name: String,
    val success: Boolean,
    val error: String? = null
)

@Serializable
data class FilesRenamePayload(
    val path: String,
    val newName: String
)

@Serializable
data class FilesRenameResultPayload(
    val path: String,
    val newName: String,
    val success: Boolean,
    val error: String? = null
)

@Serializable
data class FilesDeletePayload(
    val path: String
)

@Serializable
data class FilesDeleteResultPayload(
    val path: String,
    val success: Boolean,
    val error: String? = null
)

@Serializable
data class FilesTransferPayload(
    val sourcePath: String,
    val destinationPath: String
)

@Serializable
data class FilesTransferResultPayload(
    val sourcePath: String,
    val destinationPath: String,
    val success: Boolean,
    val error: String? = null
)

// Phase 8: notes

@Serializable
data class NoteEntry(
    val id: String,
    val title: String,
    val body: String,
    val createdAt: Double, // epoch millis
    val updatedAt: Double, // epoch millis
    val isPinned: Boolean = false,
    /** A single cover image, already resized/compressed on whichever side attached it — raw
     *  base64, no `data:image/...;base64,` prefix. Default `null` so a note synced from a build
     *  that predates this field decodes fine. */
    val imageBase64: String? = null
)

@Serializable
data class NotesSyncPayload(
    val notes: List<NoteEntry>
)

@Serializable
data class NoteCreatePayload(
    val title: String,
    val body: String,
    val imageBase64: String? = null
)

@Serializable
data class NoteCreateResultPayload(
    val success: Boolean,
    val error: String? = null
)

@Serializable
data class NoteUpdatePayload(
    val id: String,
    val title: String,
    val body: String,
    val imageBase64: String? = null
)

/** The phone's current per-category sync toggles (`SyncCategory` in AppSettingsStore.kt) —
 *  pushed to the Mac on every connect and whenever a toggle changes, so it knows the current
 *  state before attempting a write rather than discovering it via a rejected result. Field names
 *  match `SyncSettingsPayload` in the Mac's protocol/envelope.rs. */
@Serializable
data class SyncSettingsPayload(
    val notificationsEnabled: Boolean,
    val callsAndMessagesEnabled: Boolean,
    val contactsEnabled: Boolean,
    val photosEnabled: Boolean,
    val notesEnabled: Boolean,
    val clipboardEnabled: Boolean
)

@Serializable
data class NoteUpdateResultPayload(
    val id: String,
    val success: Boolean,
    val error: String? = null
)

@Serializable
data class NoteDeletePayload(
    val id: String
)

@Serializable
data class NoteDeleteResultPayload(
    val id: String,
    val success: Boolean,
    val error: String? = null
)

@Serializable
data class NoteSetPinnedPayload(
    val id: String,
    val isPinned: Boolean
)

@Serializable
data class NoteSetPinnedResultPayload(
    val id: String,
    val success: Boolean,
    val error: String? = null
)
