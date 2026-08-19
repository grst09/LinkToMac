package com.linktomac.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** The categories of data LinkToMac pushes to the paired Mac automatically — surfaced as
 *  per-item toggles on the paired-device screen rather than one blanket setting. */
enum class SyncCategory { NOTIFICATIONS, CALLS_AND_MESSAGES, CONTACTS, PHOTOS, NOTES, CLIPBOARD }

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

    var notificationsSyncEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATIONS_SYNC_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFICATIONS_SYNC_ENABLED, value).apply()

    var callsAndMessagesSyncEnabled: Boolean
        get() = prefs.getBoolean(KEY_CALLS_AND_MESSAGES_SYNC_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_CALLS_AND_MESSAGES_SYNC_ENABLED, value).apply()

    var contactsSyncEnabled: Boolean
        get() = prefs.getBoolean(KEY_CONTACTS_SYNC_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_CONTACTS_SYNC_ENABLED, value).apply()

    var photosSyncEnabled: Boolean
        get() = prefs.getBoolean(KEY_PHOTOS_SYNC_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_PHOTOS_SYNC_ENABLED, value).apply()

    var notesSyncEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTES_SYNC_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTES_SYNC_ENABLED, value).apply()

    /** Raw values are `ThemeMode` enum names ("SYSTEM"/"LIGHT"/"DARK") — kept as a plain String
     *  here rather than importing the UI-layer enum into storage. */
    var themeMode: String
        get() = prefs.getString(KEY_THEME_MODE, "SYSTEM") ?: "SYSTEM"
        set(value) = prefs.edit().putString(KEY_THEME_MODE, value).apply()

    fun isSyncEnabled(category: SyncCategory): Boolean = when (category) {
        SyncCategory.NOTIFICATIONS -> notificationsSyncEnabled
        SyncCategory.CALLS_AND_MESSAGES -> callsAndMessagesSyncEnabled
        SyncCategory.CONTACTS -> contactsSyncEnabled
        SyncCategory.PHOTOS -> photosSyncEnabled
        SyncCategory.NOTES -> notesSyncEnabled
        SyncCategory.CLIPBOARD -> clipboardSyncEnabled
    }

    fun setSyncEnabled(category: SyncCategory, enabled: Boolean) {
        when (category) {
            SyncCategory.NOTIFICATIONS -> notificationsSyncEnabled = enabled
            SyncCategory.CALLS_AND_MESSAGES -> callsAndMessagesSyncEnabled = enabled
            SyncCategory.CONTACTS -> contactsSyncEnabled = enabled
            SyncCategory.PHOTOS -> photosSyncEnabled = enabled
            SyncCategory.NOTES -> notesSyncEnabled = enabled
            SyncCategory.CLIPBOARD -> clipboardSyncEnabled = enabled
        }
    }

    private companion object {
        const val KEY_CLIPBOARD_SYNC_ENABLED = "clipboard_sync_enabled"
        const val KEY_NOTIFICATIONS_SYNC_ENABLED = "notifications_sync_enabled"
        const val KEY_CALLS_AND_MESSAGES_SYNC_ENABLED = "calls_and_messages_sync_enabled"
        const val KEY_CONTACTS_SYNC_ENABLED = "contacts_sync_enabled"
        const val KEY_PHOTOS_SYNC_ENABLED = "photos_sync_enabled"
        const val KEY_NOTES_SYNC_ENABLED = "notes_sync_enabled"
        const val KEY_THEME_MODE = "theme_mode"
    }
}
