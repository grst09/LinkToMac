package com.linktomac.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.linktomac.MainActivity
import com.linktomac.net.ConnectionState
import com.linktomac.net.MacConnection
import com.linktomac.net.MacDiscovery
import com.linktomac.net.NotificationPostedPayload
import com.linktomac.net.PairingQrPayload
import com.linktomac.storage.PairedDeviceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Foreground service that owns the [MacConnection] for the app's lifetime: reconnects via
 * Bonjour/NSD when already paired, relays notification events in both directions, and stays
 * alive so [PhoneNotificationListenerService] always has somewhere to forward events.
 */
class SyncForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var pairedDeviceStore: PairedDeviceStore
    private lateinit var connection: MacConnection
    private var discoveryJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        pairedDeviceStore = PairedDeviceStore(applicationContext)
        connection = MacConnection(
            deviceId = localDeviceId(),
            deviceName = Build.MODEL,
            pairedDeviceStore = pairedDeviceStore
        )
        connection.onNotificationDismissRequested = { id ->
            PhoneNotificationListenerService.instance?.cancel(id)
        }
        connection.state.onEach { updateNotification(it) }.launchIn(scope)
        startForeground(NOTIFICATION_ID, buildNotification(ConnectionState.Idle))

        if (pairedDeviceStore.isPaired) {
            startDiscovery()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAIR -> intent.getStringExtra(EXTRA_QR_PAYLOAD)?.let { pairWithQrPayload(it) }
            ACTION_NOTIFICATION_POSTED -> intent.getStringExtra(EXTRA_NOTIFICATION_JSON)?.let {
                connection.sendNotificationPosted(json.decodeFromString(NotificationPostedPayload.serializer(), it))
            }
            ACTION_NOTIFICATION_REMOVED -> intent.getStringExtra(EXTRA_NOTIFICATION_ID)?.let {
                connection.sendNotificationRemoved(it)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        discoveryJob?.cancel()
        connection.close()
        scope.cancel()
        super.onDestroy()
    }

    private fun pairWithQrPayload(qrJson: String) {
        val payload = json.decodeFromString(PairingQrPayload.serializer(), qrJson)
        connection.connectForPairing(payload)
    }

    private fun startDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = scope.launch {
            MacDiscovery(applicationContext).discover().collect { discovered ->
                if (connection.state.value == ConnectionState.Idle) {
                    connection.connectForReconnect(discovered)
                }
            }
        }
    }

    private fun localDeviceId(): String {
        val prefs = getSharedPreferences("linktomac_device", Context.MODE_PRIVATE)
        return prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }
    }

    private fun updateNotification(state: ConnectionState) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun buildNotification(state: ConnectionState): Notification {
        ensureChannel()
        val statusText = when (state) {
            is ConnectionState.Connected -> "Connected to ${state.macDeviceName}"
            is ConnectionState.Connecting -> "Connecting…"
            is ConnectionState.Failed -> "Disconnected — ${state.message}"
            ConnectionState.Idle -> "Waiting to pair"
        }
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LinkToMac")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "LinkToMac sync", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "linktomac_sync"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_PAIR = "com.linktomac.action.PAIR"
        private const val ACTION_NOTIFICATION_POSTED = "com.linktomac.action.NOTIFICATION_POSTED"
        private const val ACTION_NOTIFICATION_REMOVED = "com.linktomac.action.NOTIFICATION_REMOVED"
        private const val EXTRA_QR_PAYLOAD = "qr_payload"
        private const val EXTRA_NOTIFICATION_JSON = "notification_json"
        private const val EXTRA_NOTIFICATION_ID = "notification_id"

        var instance: SyncForegroundService? = null
            private set

        fun connectionState(): kotlinx.coroutines.flow.StateFlow<ConnectionState>? = instance?.connection?.state

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SyncForegroundService::class.java))
        }

        fun pair(context: Context, qrPayloadJson: String) {
            val intent = Intent(context, SyncForegroundService::class.java).apply {
                action = ACTION_PAIR
                putExtra(EXTRA_QR_PAYLOAD, qrPayloadJson)
            }
            context.startForegroundService(intent)
        }

        fun notifyPosted(context: Context, payload: NotificationPostedPayload) {
            val json = Json.encodeToString(NotificationPostedPayload.serializer(), payload)
            val intent = Intent(context, SyncForegroundService::class.java).apply {
                action = ACTION_NOTIFICATION_POSTED
                putExtra(EXTRA_NOTIFICATION_JSON, json)
            }
            context.startForegroundService(intent)
        }

        fun notifyRemoved(context: Context, key: String) {
            val intent = Intent(context, SyncForegroundService::class.java).apply {
                action = ACTION_NOTIFICATION_REMOVED
                putExtra(EXTRA_NOTIFICATION_ID, key)
            }
            context.startForegroundService(intent)
        }
    }
}
