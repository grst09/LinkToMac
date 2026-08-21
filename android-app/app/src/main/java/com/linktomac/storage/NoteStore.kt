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
 *
 * All mutations go through [synchronized] on this instance — `readAll() + mutate` is a
 * read-modify-write over one shared file, and reconciliation (see `SyncForegroundService`'s
 * `onNoteCreateRequested`) can fire several creates back-to-back on independent coroutines;
 * without a lock two concurrent creates can each read the same starting list and each write
 * back their own version, silently dropping one.
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

    /** Pinned first, then newest-edited first within each group — matches how the note list UI
     *  is sectioned on both platforms. */
    fun readAll(): List<NoteEntry> {
        val raw = prefs.getString(KEY_NOTES, null) ?: return emptyList()
        val notes = try {
            json.decodeFromString<List<NoteEntry>>(raw)
        } catch (e: Exception) {
            emptyList()
        }
        return notes.sortedWith(compareByDescending<NoteEntry> { it.isPinned }.thenByDescending { it.updatedAt })
    }

    fun create(title: String, body: String, imageBase64: String? = null): NoteEntry = synchronized(this) {
        val now = System.currentTimeMillis().toDouble()
        val note = NoteEntry(
            id = UUID.randomUUID().toString(), title = title, body = body, createdAt = now, updatedAt = now,
            imageBase64 = imageBase64
        )
        writeAllLocked(readAllUnsorted() + note)
        note
    }

    fun update(id: String, title: String, body: String, imageBase64: String? = null): Boolean = synchronized(this) {
        val existing = readAllUnsorted()
        if (existing.none { it.id == id }) return@synchronized false
        writeAllLocked(
            existing.map {
                if (it.id == id) {
                    it.copy(title = title, body = body, imageBase64 = imageBase64, updatedAt = System.currentTimeMillis().toDouble())
                } else it
            }
        )
        true
    }

    fun delete(id: String): Boolean = synchronized(this) {
        val existing = readAllUnsorted()
        val remaining = existing.filter { it.id != id }
        if (remaining.size == existing.size) return@synchronized false
        writeAllLocked(remaining)
        true
    }

    fun setPinned(id: String, pinned: Boolean): Boolean = synchronized(this) {
        val existing = readAllUnsorted()
        if (existing.none { it.id == id }) return@synchronized false
        writeAllLocked(existing.map { if (it.id == id) it.copy(isPinned = pinned) else it })
        true
    }

    private fun readAllUnsorted(): List<NoteEntry> {
        val raw = prefs.getString(KEY_NOTES, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<NoteEntry>>(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writeAllLocked(notes: List<NoteEntry>) {
        prefs.edit().putString(KEY_NOTES, json.encodeToString(notes)).apply()
    }

    private companion object {
        const val KEY_NOTES = "notes_json"
    }
}
