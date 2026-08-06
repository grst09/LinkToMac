package com.linktomac.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.linktomac.net.NotificationAction
import com.linktomac.net.NotificationPostedPayload

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
            } ?: emptyList()
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

    companion object {
        var instance: PhoneNotificationListenerService? = null
            private set
    }
}
