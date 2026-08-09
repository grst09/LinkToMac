package com.linktomac.data

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.telephony.SmsManager
import com.linktomac.net.SmsMessage
import com.linktomac.net.SmsThread

/**
 * Reads SMS conversations (Telephony.Sms — MMS is out of scope, see docs/PROTOCOL.md),
 * observes for changes, and sends outgoing texts via SmsManager.
 */
class SmsRepository(private val context: Context) {
    private var observer: ContentObserver? = null

    fun readThreads(): List<SmsThread> {
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE,
            Telephony.Sms.THREAD_ID
        )
        // No "LIMIT n" in the sort order — see the matching comment in CallLogRepository;
        // some OEM providers reject it outright. Capped in Kotlin below instead.
        val cursor = try {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                projection,
                null,
                null,
                "${Telephony.Sms.DATE} DESC"
            )
        } catch (e: SecurityException) {
            null
        } ?: return emptyList()

        val messagesByThread = LinkedHashMap<String, MutableList<SmsMessage>>()
        val addressByThread = mutableMapOf<String, String>()
        var read = 0

        cursor.use {
            val idCol = it.getColumnIndexOrThrow(Telephony.Sms._ID)
            val addressCol = it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyCol = it.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateCol = it.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val typeCol = it.getColumnIndexOrThrow(Telephony.Sms.TYPE)
            val threadCol = it.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)

            while (read < MAX_MESSAGES && it.moveToNext()) {
                read++
                val threadId = it.getString(threadCol) ?: continue
                val address = it.getString(addressCol) ?: "Unknown"
                val message = SmsMessage(
                    id = it.getString(idCol),
                    address = address,
                    body = it.getString(bodyCol) ?: "",
                    date = it.getLong(dateCol).toDouble(),
                    isOutgoing = it.getInt(typeCol) == Telephony.Sms.MESSAGE_TYPE_SENT
                )
                messagesByThread.getOrPut(threadId) { mutableListOf() }.add(message)
                addressByThread.putIfAbsent(threadId, address)
            }
        }

        return messagesByThread.entries
            .take(MAX_THREADS)
            .map { (threadId, messages) ->
                val address = addressByThread[threadId] ?: "Unknown"
                SmsThread(
                    threadId = threadId,
                    address = address,
                    contactName = ContactLookup.resolve(context, address),
                    messages = messages.sortedBy { it.date } // chronological within a thread
                )
            }
            .sortedByDescending { it.messages.lastOrNull()?.date ?: 0.0 }
    }

    fun send(address: String, body: String) {
        @Suppress("DEPRECATION")
        val smsManager = SmsManager.getDefault()
        val parts = smsManager.divideMessage(body)
        if (parts.size > 1) {
            smsManager.sendMultipartTextMessage(address, null, parts, null, null)
        } else {
            smsManager.sendTextMessage(address, null, body, null, null)
        }
    }

    /** No-ops without READ_SMS rather than crashing — see CallLogRepository.observe for the
     *  same reasoning. Idempotent: safe to call again after the user grants access. */
    fun observe(onChange: () -> Unit) {
        if (observer != null) return
        val contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = onChange()
        }
        try {
            context.contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, contentObserver)
            observer = contentObserver
        } catch (e: SecurityException) {
            // Not granted yet — caller can retry once permission is granted.
        }
    }

    fun stopObserving() {
        observer?.let { context.contentResolver.unregisterContentObserver(it) }
        observer = null
    }

    companion object {
        private const val MAX_MESSAGES = 500
        private const val MAX_THREADS = 50
    }
}
