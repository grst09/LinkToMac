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
    val actions: List<NotificationAction> = emptyList()
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
