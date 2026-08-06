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

    val isPaired: Boolean get() = deviceToken != null && pairingSalt != null && macPublicKey != null

    fun savePairing(macDeviceId: String, macPublicKey: String, deviceToken: String, pairingSalt: String) {
        this.macDeviceId = macDeviceId
        this.macPublicKey = macPublicKey
        this.deviceToken = deviceToken
        this.pairingSalt = pairingSalt
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_MAC_DEVICE_ID = "mac_device_id"
        const val KEY_MAC_PUBLIC_KEY = "mac_public_key"
        const val KEY_DEVICE_TOKEN = "device_token"
        const val KEY_PAIRING_SALT = "pairing_salt"
    }
}
