package com.linktomac.net

import android.util.Base64
import android.util.Log
import com.linktomac.crypto.SecureChannel
import com.linktomac.storage.PairedDeviceStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import javax.crypto.spec.SecretKeySpec

sealed interface ConnectionState {
    data object Idle : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(val macDeviceName: String) : ConnectionState
    data class Failed(val message: String) : ConnectionState
}

private data class PendingHandshake(
    val expectedMacDeviceId: String,
    /** Null for a first pairing (trust-on-first-use, verified by the human scanning the QR);
     *  set for a reconnect, pinning against the identity learned during original pairing. */
    val expectedMacPublicKey: String?,
    val saltBase64: String,
    val pairingTokenToSend: String?,
    val isNewPairing: Boolean
)

/**
 * Owns the WebSocket connection to the Mac app: performs the handshake described in
 * docs/PROTOCOL.md. The session key can't be derived until the Mac's `serverHello` arrives
 * (its public key travels over the socket, not the QR — see PairingQrPayload), so `hello` is
 * sent only after that; everything after is encrypted.
 */
class MacConnection(
    private val deviceId: String,
    private val deviceName: String,
    private val pairedDeviceStore: PairedDeviceStore
) {
    // encodeDefaults = true: Swift's Codable requires non-optional fields to be present in the
    // JSON, but kotlinx.serialization silently omits fields that equal their Kotlin default
    // (e.g. protocolVersion, actions = emptyList()) unless told otherwise — that mismatch would
    // otherwise break decoding on the Mac side.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null
    private var sessionKey: SecretKeySpec? = null
    private var ephemeralKeyPair: SecureChannel.KeyPair? = null
    private var pending: PendingHandshake? = null

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state

    var onNotificationDismissRequested: ((String) -> Unit)? = null
    var onSmsSendRequested: ((address: String, body: String) -> Unit)? = null
    var onPhotoPageRequested: ((offset: Int, limit: Int) -> Unit)? = null
    var onPhotoFullRequested: ((id: String) -> Unit)? = null
    var onMirrorStartRequested: (() -> Unit)? = null
    var onMirrorStopRequested: (() -> Unit)? = null
    var onMirrorTapRequested: ((x: Double, y: Double) -> Unit)? = null
    var onMirrorSwipeRequested: ((startX: Double, startY: Double, endX: Double, endY: Double, durationMs: Int) -> Unit)? = null
    var onMirrorKeyRequested: ((action: String) -> Unit)? = null
    var onMirrorTextInputRequested: ((text: String) -> Unit)? = null
    var onClipboardUpdateReceived: ((text: String) -> Unit)? = null
    var onFilesListRequested: ((path: String) -> Unit)? = null
    var onFilesDownloadRequested: ((path: String) -> Unit)? = null
    var onFilesUploadRequested: ((path: String, name: String, dataBase64: String, mimeType: String) -> Unit)? = null
    var onFilesCreateFolderRequested: ((path: String, name: String) -> Unit)? = null
    var onFilesRenameRequested: ((path: String, newName: String) -> Unit)? = null
    var onFilesDeleteRequested: ((path: String) -> Unit)? = null
    var onFilesCopyRequested: ((sourcePath: String, destinationPath: String) -> Unit)? = null
    var onFilesMoveRequested: ((sourcePath: String, destinationPath: String) -> Unit)? = null
    var onContactsRefreshRequested: (() -> Unit)? = null
    var onContactsDialRequested: ((phoneNumber: String) -> Unit)? = null
    var onContactUpdateRequested: ((ContactUpdatePayload) -> Unit)? = null
    var onContactCreateRequested: ((ContactCreatePayload) -> Unit)? = null
    var onContactDeleteRequested: ((id: String) -> Unit)? = null
    var onMessagesRefreshRequested: (() -> Unit)? = null

    /** Connect using a freshly scanned QR pairing payload. */
    fun connectForPairing(payload: PairingQrPayload) {
        connect(
            host = payload.host,
            port = payload.port,
            expectedMacDeviceId = payload.macDeviceId,
            expectedMacPublicKey = null,
            saltBase64 = payload.pairingToken,
            pairingTokenToSend = payload.pairingToken,
            isNewPairing = true
        )
    }

    /** Connect to a Mac discovered via Bonjour/NSD, using previously stored pairing state. */
    fun connectForReconnect(discovered: DiscoveredMac) {
        val macDeviceId = discovered.macDeviceId ?: pairedDeviceStore.macDeviceId ?: return
        val macPublicKey = pairedDeviceStore.macPublicKey ?: return
        val salt = pairedDeviceStore.pairingSalt ?: return
        connect(
            host = discovered.host,
            port = discovered.port,
            expectedMacDeviceId = macDeviceId,
            expectedMacPublicKey = macPublicKey,
            saltBase64 = salt,
            pairingTokenToSend = null,
            isNewPairing = false
        )
    }

    private fun connect(
        host: String,
        port: Int,
        expectedMacDeviceId: String,
        expectedMacPublicKey: String?,
        saltBase64: String,
        pairingTokenToSend: String?,
        isNewPairing: Boolean
    ) {
        _state.value = ConnectionState.Connecting
        ephemeralKeyPair = SecureChannel.generateKeyPair()
        sessionKey = null
        pending = PendingHandshake(expectedMacDeviceId, expectedMacPublicKey, saltBase64, pairingTokenToSend, isNewPairing)

        val request = Request.Builder().url("ws://$host:$port/").build()
        webSocket = client.newWebSocket(request, Listener())
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            // Nothing to send yet — we wait for the Mac's serverHello, which carries the
            // public key we need before we can derive a session key or send our own hello.
        }

        override fun onMessage(webSocket: WebSocket, text: String) = handleIncoming(text)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e("MacConnection", "WebSocket failure", t)
            _state.value = ConnectionState.Failed(t.message ?: "connection failed")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            _state.value = ConnectionState.Idle
        }
    }

    private fun handleIncoming(text: String) {
        if (sessionKey == null) {
            handleServerHello(text)
            return
        }
        val key = sessionKey ?: return
        try {
            val frame = json.decodeFromString<EncryptedFrame>(text)
            val plaintext = SecureChannel.open(frame, key)
            val envelope = json.decodeFromString<Envelope>(String(plaintext, Charsets.UTF_8))
            when (envelope.type) {
                "helloAck" -> handleHelloAck(json.decodeFromJsonElement(envelope.payload))
                "notification.dismiss" -> {
                    val payload = json.decodeFromJsonElement<NotificationRemovedPayload>(envelope.payload)
                    onNotificationDismissRequested?.invoke(payload.id)
                }
                "sms.send" -> {
                    val payload = json.decodeFromJsonElement<SmsSendPayload>(envelope.payload)
                    onSmsSendRequested?.invoke(payload.address, payload.body)
                }
                "photo.pageRequest" -> {
                    val payload = json.decodeFromJsonElement<PhotoPageRequestPayload>(envelope.payload)
                    onPhotoPageRequested?.invoke(payload.offset, payload.limit)
                }
                "photo.fullRequest" -> {
                    val payload = json.decodeFromJsonElement<PhotoFullRequestPayload>(envelope.payload)
                    onPhotoFullRequested?.invoke(payload.id)
                }
                "mirror.start" -> onMirrorStartRequested?.invoke()
                "mirror.stop" -> onMirrorStopRequested?.invoke()
                "mirror.tap" -> {
                    val payload = json.decodeFromJsonElement<MirrorTapPayload>(envelope.payload)
                    onMirrorTapRequested?.invoke(payload.x, payload.y)
                }
                "mirror.swipe" -> {
                    val payload = json.decodeFromJsonElement<MirrorSwipePayload>(envelope.payload)
                    onMirrorSwipeRequested?.invoke(payload.startX, payload.startY, payload.endX, payload.endY, payload.durationMs)
                }
                "mirror.key" -> {
                    val payload = json.decodeFromJsonElement<MirrorKeyPayload>(envelope.payload)
                    onMirrorKeyRequested?.invoke(payload.action)
                }
                "mirror.textInput" -> {
                    val payload = json.decodeFromJsonElement<MirrorTextInputPayload>(envelope.payload)
                    onMirrorTextInputRequested?.invoke(payload.text)
                }
                "clipboard.update" -> {
                    val payload = json.decodeFromJsonElement<ClipboardUpdatePayload>(envelope.payload)
                    onClipboardUpdateReceived?.invoke(payload.text)
                }
                "files.list" -> {
                    val payload = json.decodeFromJsonElement<FilesListRequestPayload>(envelope.payload)
                    onFilesListRequested?.invoke(payload.path)
                }
                "files.download" -> {
                    val payload = json.decodeFromJsonElement<FilesDownloadRequestPayload>(envelope.payload)
                    onFilesDownloadRequested?.invoke(payload.path)
                }
                "files.upload" -> {
                    val payload = json.decodeFromJsonElement<FilesUploadPayload>(envelope.payload)
                    onFilesUploadRequested?.invoke(payload.path, payload.name, payload.dataBase64, payload.mimeType)
                }
                "files.createFolder" -> {
                    val payload = json.decodeFromJsonElement<FilesCreateFolderPayload>(envelope.payload)
                    onFilesCreateFolderRequested?.invoke(payload.path, payload.name)
                }
                "files.rename" -> {
                    val payload = json.decodeFromJsonElement<FilesRenamePayload>(envelope.payload)
                    onFilesRenameRequested?.invoke(payload.path, payload.newName)
                }
                "files.delete" -> {
                    val payload = json.decodeFromJsonElement<FilesDeletePayload>(envelope.payload)
                    onFilesDeleteRequested?.invoke(payload.path)
                }
                "files.copy" -> {
                    val payload = json.decodeFromJsonElement<FilesTransferPayload>(envelope.payload)
                    onFilesCopyRequested?.invoke(payload.sourcePath, payload.destinationPath)
                }
                "files.move" -> {
                    val payload = json.decodeFromJsonElement<FilesTransferPayload>(envelope.payload)
                    onFilesMoveRequested?.invoke(payload.sourcePath, payload.destinationPath)
                }
                "contacts.refresh" -> onContactsRefreshRequested?.invoke()
                "sms.refresh" -> onMessagesRefreshRequested?.invoke()
                "contacts.dial" -> {
                    val payload = json.decodeFromJsonElement<ContactsDialPayload>(envelope.payload)
                    onContactsDialRequested?.invoke(payload.phoneNumber)
                }
                "contacts.update" -> {
                    val payload = json.decodeFromJsonElement<ContactUpdatePayload>(envelope.payload)
                    onContactUpdateRequested?.invoke(payload)
                }
                "contacts.create" -> {
                    val payload = json.decodeFromJsonElement<ContactCreatePayload>(envelope.payload)
                    onContactCreateRequested?.invoke(payload)
                }
                "contacts.delete" -> {
                    val payload = json.decodeFromJsonElement<ContactDeletePayload>(envelope.payload)
                    onContactDeleteRequested?.invoke(payload.id)
                }
                "pong" -> {}
            }
        } catch (e: Exception) {
            Log.e("MacConnection", "Failed to handle incoming frame", e)
        }
    }

    private fun handleServerHello(text: String) {
        val ctx = pending ?: return
        try {
            val envelope = json.decodeFromString<Envelope>(text)
            if (envelope.type != "serverHello") return
            val serverHello = json.decodeFromJsonElement<ServerHelloPayload>(envelope.payload)

            if (serverHello.macDeviceId != ctx.expectedMacDeviceId) {
                Log.e("MacConnection", "Unexpected Mac device id")
                _state.value = ConnectionState.Failed("Unexpected Mac identity")
                webSocket?.close(1000, "identity mismatch")
                return
            }
            if (ctx.expectedMacPublicKey != null && ctx.expectedMacPublicKey != serverHello.macPublicKey) {
                Log.e("MacConnection", "Mac public key changed since last pairing")
                _state.value = ConnectionState.Failed("Mac identity changed — re-pair required")
                webSocket?.close(1000, "identity mismatch")
                return
            }

            val keyPair = ephemeralKeyPair ?: return
            sessionKey = SecureChannel.deriveSessionKey(
                keyPair.privateKey,
                serverHello.macPublicKey,
                Base64.decode(ctx.saltBase64, Base64.NO_WRAP)
            )
            pending = ctx.copy(expectedMacPublicKey = serverHello.macPublicKey)

            val hello = HelloPayload(
                androidPublicKey = keyPair.publicKeyBase64,
                pairingToken = ctx.pairingTokenToSend,
                deviceToken = if (ctx.pairingTokenToSend == null) pairedDeviceStore.deviceToken else null,
                deviceId = deviceId,
                deviceName = deviceName
            )
            val helloEnvelope = Envelope(type = "hello", payload = json.encodeToJsonElement(hello))
            webSocket?.send(json.encodeToString(helloEnvelope))
        } catch (e: Exception) {
            Log.e("MacConnection", "Failed to handle serverHello", e)
            _state.value = ConnectionState.Failed(e.message ?: "handshake failed")
        }
    }

    private fun handleHelloAck(ack: HelloAckPayload) {
        if (ack.status != "paired") {
            _state.value = ConnectionState.Failed("Pairing rejected")
            return
        }
        val ctx = pending
        if (ctx != null && ctx.isNewPairing && ack.deviceToken != null && ctx.expectedMacPublicKey != null) {
            pairedDeviceStore.savePairing(
                macDeviceId = ctx.expectedMacDeviceId,
                macPublicKey = ctx.expectedMacPublicKey,
                deviceToken = ack.deviceToken,
                pairingSalt = ctx.saltBase64
            )
        }
        pairedDeviceStore.macDeviceName = ack.macDeviceName
        _state.value = ConnectionState.Connected(ack.macDeviceName)
    }

    fun sendNotificationPosted(payload: NotificationPostedPayload) {
        send("notification.posted", json.encodeToJsonElement(payload))
    }

    fun sendNotificationRemoved(id: String) {
        send("notification.removed", json.encodeToJsonElement(NotificationRemovedPayload(id)))
    }

    fun sendCallLogSync(calls: List<CallLogEntry>) {
        send("call.sync", json.encodeToJsonElement(CallLogSyncPayload(calls)))
    }

    fun sendSmsSync(threads: List<SmsThread>) {
        send("sms.sync", json.encodeToJsonElement(SmsSyncPayload(threads)))
    }

    fun sendPhotoPage(photos: List<PhotoThumbnail>, hasMore: Boolean) {
        send("photo.page", json.encodeToJsonElement(PhotoPagePayload(photos, hasMore)))
    }

    fun sendPhotoFull(id: String, dataBase64: String, mimeType: String) {
        send("photo.full", json.encodeToJsonElement(PhotoFullPayload(id, dataBase64, mimeType)))
    }

    /** Tells the Mac a photo was added/deleted so it can reset and re-page from 0. */
    fun sendPhotoLibraryChanged() {
        send("photo.libraryChanged", json.encodeToJsonElement(EmptyPayload()))
    }

    fun sendDeviceStatus(payload: DeviceStatusPayload) {
        send("device.status", json.encodeToJsonElement(payload))
    }

    fun sendMirrorConfig(payload: MirrorConfigPayload) {
        send("mirror.config", json.encodeToJsonElement(payload))
    }

    fun sendMirrorStopped(reason: String) {
        send("mirror.stopped", json.encodeToJsonElement(MirrorStoppedPayload(reason)))
    }

    fun sendClipboardUpdate(text: String) {
        val payload = ClipboardUpdatePayload(text = text, sourceDeviceId = deviceId, timestamp = System.currentTimeMillis().toDouble())
        send("clipboard.update", json.encodeToJsonElement(payload))
    }

    fun sendFilesListResult(path: String, entries: List<FileEntry>, error: String? = null) {
        send("files.listResult", json.encodeToJsonElement(FilesListResultPayload(path, entries, error)))
    }

    fun sendFilesDownloadResult(path: String, name: String, dataBase64: String? = null, mimeType: String? = null, error: String? = null) {
        send("files.downloadResult", json.encodeToJsonElement(FilesDownloadResultPayload(path, name, dataBase64, mimeType, error)))
    }

    fun sendFilesUploadResult(path: String, name: String, success: Boolean, error: String? = null) {
        send("files.uploadResult", json.encodeToJsonElement(FilesUploadResultPayload(path, name, success, error)))
    }

    fun sendFilesCreateFolderResult(path: String, name: String, success: Boolean, error: String? = null) {
        send("files.createFolderResult", json.encodeToJsonElement(FilesCreateFolderResultPayload(path, name, success, error)))
    }

    fun sendFilesRenameResult(path: String, newName: String, success: Boolean, error: String? = null) {
        send("files.renameResult", json.encodeToJsonElement(FilesRenameResultPayload(path, newName, success, error)))
    }

    fun sendFilesDeleteResult(path: String, success: Boolean, error: String? = null) {
        send("files.deleteResult", json.encodeToJsonElement(FilesDeleteResultPayload(path, success, error)))
    }

    fun sendFilesCopyResult(sourcePath: String, destinationPath: String, success: Boolean, error: String? = null) {
        send("files.copyResult", json.encodeToJsonElement(FilesTransferResultPayload(sourcePath, destinationPath, success, error)))
    }

    fun sendFilesMoveResult(sourcePath: String, destinationPath: String, success: Boolean, error: String? = null) {
        send("files.moveResult", json.encodeToJsonElement(FilesTransferResultPayload(sourcePath, destinationPath, success, error)))
    }

    fun sendContactsSync(contacts: List<ContactEntry>) {
        send("contacts.sync", json.encodeToJsonElement(ContactsSyncPayload(contacts)))
    }

    fun sendContactUpdateResult(id: String, success: Boolean, error: String? = null) {
        send("contacts.updateResult", json.encodeToJsonElement(ContactUpdateResultPayload(id, success, error)))
    }

    fun sendContactCreateResult(success: Boolean, error: String? = null) {
        send("contacts.createResult", json.encodeToJsonElement(ContactCreateResultPayload(success, error)))
    }

    fun sendContactDeleteResult(id: String, success: Boolean, error: String? = null) {
        send("contacts.deleteResult", json.encodeToJsonElement(ContactDeleteResultPayload(id, success, error)))
    }

    /** Binary WebSocket frame, not the JSON envelope — see docs/PROTOCOL.md's Phase 4 binary
     *  frame format. `nalBytes` is exactly what MediaCodec's encoder output buffer contains. */
    fun sendMirrorFrame(nalBytes: ByteArray) {
        val ws = webSocket ?: return
        val key = sessionKey ?: return
        val frame = SecureChannel.sealRaw(nalBytes, key)
        ws.send(frame.toByteString())
    }

    private fun send(type: String, payload: kotlinx.serialization.json.JsonElement) {
        val ws = webSocket ?: return
        val key = sessionKey ?: return
        val envelope = Envelope(type = type, payload = payload)
        val plaintext = json.encodeToString(envelope).toByteArray(Charsets.UTF_8)
        val frame = SecureChannel.seal(plaintext, key)
        ws.send(json.encodeToString(frame))
    }

    fun close() {
        webSocket?.close(1000, "client closing")
        webSocket = null
        sessionKey = null
        _state.value = ConnectionState.Idle
    }
}
