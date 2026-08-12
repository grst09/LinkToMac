package com.linktomac.data

import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import com.linktomac.net.ContactEntry

/**
 * Reads and writes the phone's contacts for the Mac's Contacts list. Only contacts with at
 * least one phone number are surfaced (the point is to message or call them from the Mac) —
 * everything else (email, organization, starred) is best-effort extra detail shown "if
 * available", never required.
 */
class ContactRepository(private val context: Context) {
    private var observer: ContentObserver? = null

    fun readAll(): List<ContactEntry> {
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.STARRED
        )
        val cursor = try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )
        } catch (e: SecurityException) {
            null
        } ?: return emptyList()

        val emailsByContactId = readFirstValueByContactId(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            ContactsContract.CommonDataKinds.Email.CONTACT_ID,
            ContactsContract.CommonDataKinds.Email.ADDRESS
        )
        // Organization has no dedicated CONTENT_URI (unlike Phone/Email) — it's a row in the
        // generic Data table, filtered by mimetype.
        val organizationsByContactId = readFirstValueByContactId(
            ContactsContract.Data.CONTENT_URI,
            ContactsContract.Data.CONTACT_ID,
            ContactsContract.CommonDataKinds.Organization.COMPANY,
            mimeTypeFilter = ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE
        )

        // The Phone table has one row per number, so a contact with 3 numbers appears 3 times —
        // keep just the first (arbitrary but stable within one query) row per contact id.
        val seenContactIds = mutableSetOf<Long>()
        val contacts = mutableListOf<ContactEntry>()
        cursor.use {
            val idCol = it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameCol = it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberCol = it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val starredCol = it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.STARRED)
            while (it.moveToNext()) {
                val contactId = it.getLong(idCol)
                if (!seenContactIds.add(contactId)) continue
                val name = it.getString(nameCol) ?: continue
                val number = it.getString(numberCol) ?: continue
                contacts.add(
                    ContactEntry(
                        id = contactId.toString(),
                        name = name,
                        phoneNumber = number,
                        isStarred = it.getInt(starredCol) != 0,
                        email = emailsByContactId[contactId],
                        organization = organizationsByContactId[contactId]
                    )
                )
            }
        }
        return contacts
    }

    /** One query per field across all contacts, rather than one query per contact per field —
     *  keeps a sync of a few hundred contacts to a handful of queries instead of hundreds. */
    private fun readFirstValueByContactId(
        uri: android.net.Uri,
        contactIdColumn: String,
        valueColumn: String,
        mimeTypeFilter: String? = null
    ): Map<Long, String> {
        val map = mutableMapOf<Long, String>()
        val selection = mimeTypeFilter?.let { "${ContactsContract.Data.MIMETYPE} = ?" }
        val selectionArgs = mimeTypeFilter?.let { arrayOf(it) }
        val cursor = try {
            context.contentResolver.query(uri, arrayOf(contactIdColumn, valueColumn), selection, selectionArgs, null)
        } catch (e: SecurityException) {
            null
        } ?: return map
        cursor.use {
            val idCol = it.getColumnIndexOrThrow(contactIdColumn)
            val valueCol = it.getColumnIndexOrThrow(valueColumn)
            while (it.moveToNext()) {
                val contactId = it.getLong(idCol)
                if (map.containsKey(contactId)) continue
                val value = it.getString(valueCol) ?: continue
                if (value.isNotBlank()) map[contactId] = value
            }
        }
        return map
    }

    /** Updates the first raw contact backing this aggregate contact id. A contact merged from
     *  multiple accounts has several raw contacts; picking the first is the same simplification
     *  most lightweight contact editors make rather than reconciling every source. */
    fun update(id: String, name: String, phoneNumber: String, isStarred: Boolean, email: String?, organization: String?): Boolean {
        val contactId = id.toLongOrNull() ?: return false
        val rawContactId = rawContactIdFor(contactId) ?: return false

        val ops = ArrayList<ContentProviderOperation>()
        ops.add(replaceDataRow(rawContactId, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE) {
            it.withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
        })
        ops.add(replaceDataRow(rawContactId, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE) {
            it.withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phoneNumber)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
        })
        ops.add(
            ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                .withSelection(dataSelection, arrayOf(rawContactId.toString(), ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE))
                .build()
        )
        if (!email.isNullOrBlank()) {
            ops.add(insertDataRow(rawContactId, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE) {
                it.withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, email)
                    .withValue(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_HOME)
            })
        }
        ops.add(
            ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                .withSelection(dataSelection, arrayOf(rawContactId.toString(), ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE))
                .build()
        )
        if (!organization.isNullOrBlank()) {
            ops.add(insertDataRow(rawContactId, ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE) {
                it.withValue(ContactsContract.CommonDataKinds.Organization.COMPANY, organization)
            })
        }

        return try {
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            val starredValues = ContentValues().apply { put(ContactsContract.Contacts.STARRED, if (isStarred) 1 else 0) }
            context.contentResolver.update(
                ContactsContract.Contacts.CONTENT_URI,
                starredValues,
                "${ContactsContract.Contacts._ID} = ?",
                arrayOf(contactId.toString())
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    fun create(name: String, phoneNumber: String, email: String?, organization: String?): Boolean {
        val ops = ArrayList<ContentProviderOperation>()
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                .build()
        )
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                .build()
        )
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phoneNumber)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                .build()
        )
        if (!email.isNullOrBlank()) {
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, email)
                    .withValue(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_HOME)
                    .build()
            )
        }
        if (!organization.isNullOrBlank()) {
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Organization.COMPANY, organization)
                    .build()
            )
        }

        return try {
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Deletes the aggregate contact — Android cascades this to every raw contact merged into
     *  it, so this removes the whole visible contact, not just one source. */
    fun delete(id: String): Boolean {
        val contactId = id.toLongOrNull() ?: return false
        val uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId)
        return try {
            context.contentResolver.delete(uri, null, null) > 0
        } catch (e: Exception) {
            false
        }
    }

    private val dataSelection = "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?"

    private fun replaceDataRow(
        rawContactId: Long,
        mimeType: String,
        configure: (ContentProviderOperation.Builder) -> ContentProviderOperation.Builder
    ): ContentProviderOperation {
        val builder = ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
            .withSelection(dataSelection, arrayOf(rawContactId.toString(), mimeType))
        return configure(builder).build()
    }

    private fun insertDataRow(
        rawContactId: Long,
        mimeType: String,
        configure: (ContentProviderOperation.Builder) -> ContentProviderOperation.Builder
    ): ContentProviderOperation {
        val builder = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
            .withValue(ContactsContract.Data.MIMETYPE, mimeType)
        return configure(builder).build()
    }

    private fun rawContactIdFor(contactId: Long): Long? {
        val cursor = context.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts._ID),
            "${ContactsContract.RawContacts.CONTACT_ID} = ?",
            arrayOf(contactId.toString()),
            null
        )
        return cursor?.use { if (it.moveToFirst()) it.getLong(0) else null }
    }

    /** No-op without contacts access rather than crashing — same reasoning as
     *  CallLogRepository.observe. Idempotent: safe to call again after permission is granted. */
    fun observe(onChange: () -> Unit) {
        if (observer != null) return
        val contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = onChange()
        }
        try {
            context.contentResolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, contentObserver)
            observer = contentObserver
        } catch (e: SecurityException) {
            // Not granted yet — caller can retry once permission is granted.
        }
    }

    fun stopObserving() {
        observer?.let { context.contentResolver.unregisterContentObserver(it) }
        observer = null
    }
}
