package com.linktomac.data

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import com.linktomac.net.CallLogEntry

/**
 * Reads the device call log and observes it for changes. Capped at [MAX_ENTRIES] most recent
 * calls — see docs/PROTOCOL.md's full-snapshot-on-change note.
 */
class CallLogRepository(private val context: Context) {
    private var observer: ContentObserver? = null

    fun readRecent(): List<CallLogEntry> {
        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION
        )
        // Deliberately no "LIMIT n" appended to the sort order: while that works against the
        // stock AOSP provider, some OEM call log providers (observed: Samsung) validate the
        // sort-order string strictly and throw IllegalArgumentException: Invalid token LIMIT.
        // Capping in Kotlin below is the portable approach.
        val cursor = try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                "${CallLog.Calls.DATE} DESC"
            )
        } catch (e: SecurityException) {
            null
        } ?: return emptyList()

        val entries = mutableListOf<CallLogEntry>()
        cursor.use {
            val idCol = it.getColumnIndexOrThrow(CallLog.Calls._ID)
            val numberCol = it.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
            val nameCol = it.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
            val typeCol = it.getColumnIndexOrThrow(CallLog.Calls.TYPE)
            val dateCol = it.getColumnIndexOrThrow(CallLog.Calls.DATE)
            val durationCol = it.getColumnIndexOrThrow(CallLog.Calls.DURATION)

            while (entries.size < MAX_ENTRIES && it.moveToNext()) {
                val number = it.getString(numberCol) ?: "Unknown"
                entries.add(
                    CallLogEntry(
                        id = it.getString(idCol),
                        number = number,
                        contactName = it.getString(nameCol) ?: ContactLookup.resolve(context, number),
                        type = callType(it.getInt(typeCol)),
                        date = it.getLong(dateCol).toDouble(),
                        durationSeconds = it.getInt(durationCol)
                    )
                )
            }
        }
        return entries
    }

    /** No-ops without READ_CALL_LOG rather than crashing — same reasoning as [readRecent]'s
     *  SecurityException handling, but registerContentObserver throws instead of returning null.
     *  Idempotent: safe to call again (e.g. from SyncForegroundService.refreshCallsAndSms())
     *  after the user grants access, to pick up live updates without waiting for a restart. */
    fun observe(onChange: () -> Unit) {
        if (observer != null) return
        val contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = onChange()
        }
        try {
            context.contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, contentObserver)
            observer = contentObserver
        } catch (e: SecurityException) {
            // Not granted yet — caller can retry once permission is granted.
        }
    }

    fun stopObserving() {
        observer?.let { context.contentResolver.unregisterContentObserver(it) }
        observer = null
    }

    private fun callType(type: Int): String = when (type) {
        CallLog.Calls.INCOMING_TYPE -> "incoming"
        CallLog.Calls.OUTGOING_TYPE -> "outgoing"
        CallLog.Calls.MISSED_TYPE -> "missed"
        CallLog.Calls.REJECTED_TYPE -> "rejected"
        CallLog.Calls.BLOCKED_TYPE -> "blocked"
        CallLog.Calls.VOICEMAIL_TYPE -> "voicemail"
        else -> "unknown"
    }

    companion object {
        private const val MAX_ENTRIES = 200
    }
}
