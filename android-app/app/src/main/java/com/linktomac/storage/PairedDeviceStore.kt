package com.linktomac.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores this phone's pairing state with the Mac: the Mac's stable identity (public key + id,
 * learned from the QR code or Bonjour TXT record), the device token issued on first pairing,
 * and the original pairing token reused as the HKDF salt on every reconnect — see
 * docs/PROTOCOL.md.
 */
class PairedDeviceStore(context: Context) {
    private val prefs: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        prefs = EncryptedSharedPreferences.create(
            context,
            "linktomac_paired_device",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    var macDeviceId: String?
        get() = prefs.getString(KEY_MAC_DEVICE_ID, null)
        private set(value) = prefs.edit().putString(KEY_MAC_DEVICE_ID, value).apply()

    var macPublicKey: String?
        get() = prefs.getString(KEY_MAC_PUBLIC_KEY, null)
        private set(value) = prefs.edit().putString(KEY_MAC_PUBLIC_KEY, value).apply()

    var deviceToken: String?
        get() = prefs.getString(KEY_DEVICE_TOKEN, null)
        private set(value) = prefs.edit().putString(KEY_DEVICE_TOKEN, value).apply()

    var pairingSalt: String?
        get() = prefs.getString(KEY_PAIRING_SALT, null)
        private set(value) = prefs.edit().putString(KEY_PAIRING_SALT, value).apply()

    /** Display name only — not part of the trust chain, just kept fresh so the paired-device
     *  UI can show something meaningful even while disconnected. */
    var macDeviceName: String?
        get() = prefs.getString(KEY_MAC_DEVICE_NAME, null)
        set(value) = prefs.edit().putString(KEY_MAC_DEVICE_NAME, value).apply()

    /** The host/port a connection last actually succeeded on. NSD/Bonjour discovery is how a
     *  reconnect normally finds the Mac, but mDNS multicast doesn't reliably cross WiFi bands on
     *  some routers/mesh systems (e.g. the Mac on 6GHz, the phone on 5GHz) even though both
     *  devices can reach each other fine over plain unicast IP. Remembering the last address
     *  that worked lets [com.linktomac.service.SyncForegroundService] try connecting there
     *  directly instead of only waiting on discovery that may never arrive — see
     *  `startDiscovery()`'s doc comment there. */
    var lastKnownHost: String?
        get() = prefs.getString(KEY_LAST_KNOWN_HOST, null)
        private set(value) = prefs.edit().putString(KEY_LAST_KNOWN_HOST, value).apply()

    var lastKnownPort: Int
        get() = prefs.getInt(KEY_LAST_KNOWN_PORT, 0)
        private set(value) = prefs.edit().putInt(KEY_LAST_KNOWN_PORT, value).apply()

    val isPaired: Boolean get() = deviceToken != null && pairingSalt != null && macPublicKey != null

    fun savePairing(macDeviceId: String, macPublicKey: String, deviceToken: String, pairingSalt: String) {
        this.macDeviceId = macDeviceId
        this.macPublicKey = macPublicKey
        this.deviceToken = deviceToken
        this.pairingSalt = pairingSalt
    }

    fun saveLastKnownAddress(host: String, port: Int) {
        lastKnownHost = host
        lastKnownPort = port
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_MAC_DEVICE_ID = "mac_device_id"
        const val KEY_MAC_PUBLIC_KEY = "mac_public_key"
        const val KEY_DEVICE_TOKEN = "device_token"
        const val KEY_PAIRING_SALT = "pairing_salt"
        const val KEY_MAC_DEVICE_NAME = "mac_device_name"
        const val KEY_LAST_KNOWN_HOST = "last_known_host"
        const val KEY_LAST_KNOWN_PORT = "last_known_port"
    }
}
