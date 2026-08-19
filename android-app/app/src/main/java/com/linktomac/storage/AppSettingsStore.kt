package com.linktomac.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * User-configurable preferences, same persistence approach as [PairedDeviceStore]/[NoteStore] —
 * a small `EncryptedSharedPreferences` file, safe to instantiate from multiple places (the
 * Settings UI and [com.linktomac.service.SyncForegroundService] both read/write the same file).
 */
class AppSettingsStore(context: Context) {
    private val prefs: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        prefs = EncryptedSharedPreferences.create(
            context,
            "linktomac_settings",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    var clipboardSyncEnabled: Boolean
        get() = prefs.getBoolean(KEY_CLIPBOARD_SYNC_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_CLIPBOARD_SYNC_ENABLED, value).apply()

    private companion object {
        const val KEY_CLIPBOARD_SYNC_ENABLED = "clipboard_sync_enabled"
    }
}
