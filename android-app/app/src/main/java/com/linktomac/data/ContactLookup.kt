package com.linktomac.data

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract

/** Resolves a phone number to a contact display name; returns null without READ_CONTACTS or no match. */
object ContactLookup {
    fun resolve(context: Context, phoneNumber: String): String? {
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber))
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        } catch (e: SecurityException) {
            null
        }
    }
}
