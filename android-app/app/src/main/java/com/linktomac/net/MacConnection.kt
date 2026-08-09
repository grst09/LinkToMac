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
