package com.linktomac.service

import android.app.Notification
import android.graphics.Bitmap
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import androidx.core.graphics.drawable.toBitmap
import com.linktomac.net.NotificationAction
import com.linktomac.net.NotificationPostedPayload
import java.io.ByteArrayOutputStream

/**
 * Captures posted/removed notifications system-wide and forwards them to
 * [SyncForegroundService], which owns the actual connection to the Mac.
 */
class PhoneNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (instance === this) instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return // don't mirror our own notifications
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return

        val payload = NotificationPostedPayload(
            id = sbn.key,
            packageName = sbn.packageName,
            appName = appName(sbn.packageName),
            title = title,
            text = text,
            subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
            category = sbn.notification.category,
            postedAt = sbn.postTime.toDouble(),
            actions = sbn.notification.actions?.mapNotNull { action ->
                action.title?.toString()?.let { NotificationAction(title = it, actionId = it) }
            } ?: emptyList(),
            iconBase64 = appIconBase64(sbn.packageName)
        )
        SyncForegroundService.notifyPosted(applicationContext, payload)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        SyncForegroundService.notifyRemoved(applicationContext, sbn.key)
    }

    fun cancel(key: String) {
        cancelNotification(key)
    }

    private fun appName(packageName: String): String {
        return try {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (e: Exception) {
            packageName
        }
    }

    /** App icons don't change between notifications, so cache the encoded PNG per package
     *  rather than re-drawing/re-encoding it on every single notification. */
    private fun appIconBase64(packageName: String): String? {
        iconCache[packageName]?.let { return it }
        val encoded = try {
            val icon = packageManager.getApplicationIcon(packageName)
            val bitmap = icon.toBitmap(width = ICON_SIZE_PX, height = ICON_SIZE_PX)
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
        iconCache[packageName] = encoded
        return encoded
    }

    companion object {
        var instance: PhoneNotificationListenerService? = null
            private set

        private const val ICON_SIZE_PX = 96
        private val iconCache = mutableMapOf<String, String?>()
    }
}
