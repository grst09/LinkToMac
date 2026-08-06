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
    val macDeviceId: String,
    val macPublicKey: String,
    val saltBase64: String,
    val isNewPairing: Boolean
)

/**
 * Owns the WebSocket connection to the Mac app: performs the handshake described in
 * docs/PROTOCOL.md (deriving the session key before the socket even opens, since it only
 * depends on our own fresh ephemeral key and the Mac's already-known public key), then
 * encrypts/decrypts every message after that.
 */
class MacConnection(
    private val deviceId: String,
    private val deviceName: String,
    private val pairedDeviceStore: PairedDeviceStore
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null
    private var sessionKey: SecretKeySpec? = null
    private var ephemeralKeyPair: SecureChannel.KeyPair? = null
    private var pending: PendingHandshake? = null

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state

    var onNotificationDismissRequested: ((String) -> Unit)? = null

    /** Connect using a freshly scanned QR pairing payload. */
    fun connectForPairing(payload: PairingQrPayload) {
        connect(
            host = payload.host,
            port = payload.port,
            macDeviceId = payload.macDeviceId,
            macPublicKey = payload.macPublicKey,
            saltBase64 = payload.pairingToken,
            pairingTokenToSend = payload.pairingToken,
            isNewPairing = true
        )
    }

    /** Connect to a Mac discovered via Bonjour/NSD, using previously stored pairing state. */
    fun connectForReconnect(discovered: DiscoveredMac) {
        val macDeviceId = discovered.macDeviceId ?: pairedDeviceStore.macDeviceId ?: return
        val macPublicKey = discovered.macPublicKey ?: pairedDeviceStore.macPublicKey ?: return
        val salt = pairedDeviceStore.pairingSalt ?: return
        connect(
            host = discovered.host,
            port = discovered.port,
            macDeviceId = macDeviceId,
            macPublicKey = macPublicKey,
            saltBase64 = salt,
            pairingTokenToSend = null,
            isNewPairing = false
        )
    }

    private fun connect(
        host: String,
        port: Int,
        macDeviceId: String,
        macPublicKey: String,
        saltBase64: String,
        pairingTokenToSend: String?,
        isNewPairing: Boolean
    ) {
        _state.value = ConnectionState.Connecting
        val keyPair = SecureChannel.generateKeyPair()
        ephemeralKeyPair = keyPair
        pending = PendingHandshake(macDeviceId, macPublicKey, saltBase64, isNewPairing)
        sessionKey = SecureChannel.deriveSessionKey(
            keyPair.privateKey,
            macPublicKey,
            Base64.decode(saltBase64, Base64.NO_WRAP)
        )

        val request = Request.Builder().url("ws://$host:$port/").build()
        webSocket = client.newWebSocket(request, Listener(pairingTokenToSend))
    }

    private inner class Listener(private val pairingTokenToSend: String?) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val keyPair = ephemeralKeyPair ?: return
            val hello = HelloPayload(
                androidPublicKey = keyPair.publicKeyBase64,
                pairingToken = pairingTokenToSend,
                deviceToken = if (pairingTokenToSend == null) pairedDeviceStore.deviceToken else null,
                deviceId = deviceId,
                deviceName = deviceName
            )
            val envelope = Envelope(type = "hello", payload = json.encodeToJsonElement(hello))
            webSocket.send(json.encodeToString(envelope))
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
                "pong" -> {}
            }
        } catch (e: Exception) {
            Log.e("MacConnection", "Failed to handle incoming frame", e)
        }
    }

    private fun handleHelloAck(ack: HelloAckPayload) {
        if (ack.status != "paired") {
            _state.value = ConnectionState.Failed("Pairing rejected")
            return
        }
        val ctx = pending
        if (ctx != null && ctx.isNewPairing && ack.deviceToken != null) {
            pairedDeviceStore.savePairing(
                macDeviceId = ctx.macDeviceId,
                macPublicKey = ctx.macPublicKey,
                deviceToken = ack.deviceToken,
                pairingSalt = ctx.saltBase64
            )
        }
        _state.value = ConnectionState.Connected(ack.macDeviceName)
    }

    fun sendNotificationPosted(payload: NotificationPostedPayload) {
        send("notification.posted", json.encodeToJsonElement(payload))
    }

    fun sendNotificationRemoved(id: String) {
        send("notification.removed", json.encodeToJsonElement(NotificationRemovedPayload(id)))
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
