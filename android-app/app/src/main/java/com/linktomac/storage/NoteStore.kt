package com.linktomac.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.linktomac.net.NoteEntry
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Local persistence for Notes — unlike every other feature in this app, there's no Android
 * system provider to read from (no public API exists for Samsung Notes or Google Keep; see
 * docs/PROTOCOL.md's Phase 8 notes), so this is genuinely this app's own data. Stored as a
 * single JSON-encoded list, same `EncryptedSharedPreferences` approach as [PairedDeviceStore]
 * rather than a full database — Notes doesn't need the scale Photos/Files do.
 */
class NoteStore(context: Context) {
    private val prefs: SharedPreferences
    private val json = Json { ignoreUnknownKeys = true }

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        prefs = EncryptedSharedPreferences.create(
            context,
            "linktomac_notes",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /** Newest-edited first — matches how a notes list is most useful to scan. */
    fun readAll(): List<NoteEntry> {
        val raw = prefs.getString(KEY_NOTES, null) ?: return emptyList()
        val notes = try {
            json.decodeFromString<List<NoteEntry>>(raw)
        } catch (e: Exception) {
            emptyList()
        }
        return notes.sortedByDescending { it.updatedAt }
    }

    fun create(title: String, body: String): NoteEntry {
        val now = System.currentTimeMillis().toDouble()
        val note = NoteEntry(id = UUID.randomUUID().toString(), title = title, body = body, createdAt = now, updatedAt = now)
        writeAll(readAll() + note)
        return note
    }

    fun update(id: String, title: String, body: String): Boolean {
        val existing = readAll()
        if (existing.none { it.id == id }) return false
        writeAll(
            existing.map {
                if (it.id == id) it.copy(title = title, body = body, updatedAt = System.currentTimeMillis().toDouble()) else it
            }
        )
        return true
    }

    fun delete(id: String): Boolean {
        val existing = readAll()
        val remaining = existing.filter { it.id != id }
        if (remaining.size == existing.size) return false
        writeAll(remaining)
        return true
    }

    private fun writeAll(notes: List<NoteEntry>) {
        prefs.edit().putString(KEY_NOTES, json.encodeToString(notes)).apply()
    }

    private companion object {
        const val KEY_NOTES = "notes_json"
    }
}
