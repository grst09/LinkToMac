package com.linktomac.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Base64
import androidx.core.app.NotificationCompat
import com.linktomac.MainActivity
import com.linktomac.data.BatteryStatusRepository
import com.linktomac.data.CallLogRepository
import com.linktomac.data.PhotoRepository
import com.linktomac.data.SmsRepository
import com.linktomac.net.ConnectionState
import com.linktomac.net.DeviceStatusPayload
import com.linktomac.net.MacConnection
import com.linktomac.net.MacDiscovery
import com.linktomac.net.NotificationPostedPayload
import com.linktomac.net.PairingQrPayload
import com.linktomac.storage.PairedDeviceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Foreground service that owns the [MacConnection] for the app's lifetime: reconnects via
 * Bonjour/NSD when already paired, relays notification events and call log/SMS sync in both
 * directions, and stays alive so [PhoneNotificationListenerService] always has somewhere to
 * forward events.
 */
class SyncForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var pairedDeviceStore: PairedDeviceStore
    private lateinit var connection: MacConnection
    private lateinit var callLogRepository: CallLogRepository
    private lateinit var smsRepository: SmsRepository
    private lateinit var photoRepository: PhotoRepository
    private lateinit var batteryStatusRepository: BatteryStatusRepository
    private var discoveryJob: Job? = null
    private var syncJob: Job? = null
    private var photoLibraryChangedJob: Job? = null

    /** Tracks the last text synced in either direction so a remote update we just wrote to the
     *  system clipboard doesn't get read back and echoed to the Mac as if it were a new local
     *  copy — see [applyRemoteClipboard] and [reportLocalClipboardText]. */
    private var lastSyncedClipboardText: String? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        pairedDeviceStore = PairedDeviceStore(applicationContext)
        callLogRepository = CallLogRepository(applicationContext)
        smsRepository = SmsRepository(applicationContext)
        photoRepository = PhotoRepository(applicationContext)
        batteryStatusRepository = BatteryStatusRepository(applicationContext)
        connection = MacConnection(
            deviceId = localDeviceId(),
            deviceName = Build.MODEL,
            pairedDeviceStore = pairedDeviceStore
        )
        connection.onNotificationDismissRequested = { id ->
            PhoneNotificationListenerService.instance?.cancel(id)
        }
        connection.onSmsSendRequested = { address, body -> smsRepository.send(address, body) }
        connection.onPhotoPageRequested = { offset, limit ->
            scope.launch(Dispatchers.IO) {
                val (photos, hasMore) = photoRepository.readPage(offset, limit)
                connection.sendPhotoPage(photos, hasMore)
            }
        }
        connection.onPhotoFullRequested = { id ->
            scope.launch(Dispatchers.IO) {
                photoRepository.readFull(id)?.let { (bytes, mimeType) ->
                    connection.sendPhotoFull(id, Base64.encodeToString(bytes, Base64.NO_WRAP), mimeType)
                }
            }
        }
        connection.onMirrorStartRequested = {
            // MediaProjection permission can only be requested from a foreground Activity — no
            // way around bringing the phone's screen to the front for this, even though the
            // request originated from the Mac. See MainActivity's ACTION_REQUEST_MIRROR_PERMISSION.
            val intent = Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                action = MainActivity.ACTION_REQUEST_MIRROR_PERMISSION
            }
            startActivity(intent)
        }
        connection.onMirrorStopRequested = { ScreenMirrorService.stop(applicationContext) }
        connection.onMirrorTapRequested = { x, y ->
            android.util.Log.d("SyncForegroundService", "mirror.tap received x=$x y=$y instance=${InputInjectionAccessibilityService.instance}")
            InputInjectionAccessibilityService.instance?.performTap(x, y)
        }
        connection.onMirrorSwipeRequested = { startX, startY, endX, endY, durationMs ->
            android.util.Log.d("SyncForegroundService", "mirror.swipe received instance=${InputInjectionAccessibilityService.instance}")
            InputInjectionAccessibilityService.instance?.performSwipe(startX, startY, endX, endY, durationMs)
        }
        connection.onMirrorKeyRequested = { action ->
            InputInjectionAccessibilityService.instance?.performGlobalKeyAction(action)
        }
        connection.onMirrorTextInputRequested = { text ->
            InputInjectionAccessibilityService.instance?.pasteText(text)
        }
        connection.onClipboardUpdateReceived = { text -> applyRemoteClipboard(text) }
        connection.state.onEach { state ->
            updateNotification(state)
            if (state is ConnectionState.Connected) {
                syncCallsAndSms()
                batteryStatusRepository.readCurrent()?.let { connection.sendDeviceStatus(it) }
            }
        }.launchIn(scope)
        batteryStatusRepository.observe { connection.sendDeviceStatus(it) }
        startForeground(NOTIFICATION_ID, buildNotification(ConnectionState.Idle))

        // No-ops if permission isn't granted yet (see CallLogRepository.observe) — retried in
        // ACTION_REFRESH_DATA below, which fires right after the user grants access.
        callLogRepository.observe { scheduleSync() }
        smsRepository.observe { scheduleSync() }
        photoRepository.observe { schedulePhotoLibraryChangedNotification() }

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
            ACTION_REFRESH_DATA -> {
                callLogRepository.observe { scheduleSync() }
                smsRepository.observe { scheduleSync() }
                syncCallsAndSms()
            }
            ACTION_REFRESH_PHOTOS -> photoRepository.observe { schedulePhotoLibraryChangedNotification() }
            ACTION_RECONNECT -> if (pairedDeviceStore.isPaired) startDiscovery()
            ACTION_FORGET -> {
                discoveryJob?.cancel()
                connection.close()
                pairedDeviceStore.clear()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        discoveryJob?.cancel()
        syncJob?.cancel()
        photoLibraryChangedJob?.cancel()
        callLogRepository.stopObserving()
        smsRepository.stopObserving()
        photoRepository.stopObserving()
        batteryStatusRepository.stopObserving()
        connection.close()
        scope.cancel()
        super.onDestroy()
    }

    /** Writing to the clipboard, unlike reading it, isn't restricted while backgrounded — this
     *  runs the moment a `clipboard.update` arrives, no foreground Activity required. The item
     *  then shows up via the normal long-press "Paste" option in any text field. */
    private fun applyRemoteClipboard(text: String) {
        if (text == lastSyncedClipboardText) return
        lastSyncedClipboardText = text
        val clipboardManager = getSystemService(ClipboardManager::class.java)
        clipboardManager.setPrimaryClip(ClipData.newPlainText("LinkToMac", text))
        android.util.Log.d("SyncForegroundService", "clipboard.update applied from Mac: ${text.take(40)}")
    }

    private fun syncCallsAndSms() {
        connection.sendCallLogSync(callLogRepository.readRecent())
        connection.sendSmsSync(smsRepository.readThreads())
    }

    /** Debounces bursts of ContentObserver changes (e.g. a batch of messages arriving) into one sync. */
    private fun scheduleSync() {
        syncJob?.cancel()
        syncJob = scope.launch {
            delay(750)
            if (connection.state.value is ConnectionState.Connected) {
                syncCallsAndSms()
            }
        }
    }

    /** Debounces bursts of MediaStore changes (e.g. importing a batch of photos) into one notification. */
    private fun schedulePhotoLibraryChangedNotification() {
        photoLibraryChangedJob?.cancel()
        photoLibraryChangedJob = scope.launch {
            delay(750)
            if (connection.state.value is ConnectionState.Connected) {
                connection.sendPhotoLibraryChanged()
            }
        }
    }

    private fun pairWithQrPayload(qrJson: String) {
        val payload = json.decodeFromString(PairingQrPayload.serializer(), qrJson)
        connection.connectForPairing(payload)
    }

    /** Cancels any existing discovery listener and starts a fresh one — NsdManager's discovery
     *  doesn't reliably re-fire onServiceFound for a service that briefly disappeared and came
     *  back (e.g. the Mac's own disconnect/reconnect toggle), so restarting discovery outright
     *  is what actually gets a response, both for the background retry path and the explicit
     *  "Reconnect" action. Reconnecting is allowed from Idle or Failed — not just Idle — so a
     *  previous failed attempt doesn't permanently block further retries. */
    private fun startDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = scope.launch {
            MacDiscovery(applicationContext).discover().collect { discovered ->
                val state = connection.state.value
                if (state == ConnectionState.Idle || state is ConnectionState.Failed) {
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
        private const val ACTION_REFRESH_DATA = "com.linktomac.action.REFRESH_DATA"
        private const val ACTION_REFRESH_PHOTOS = "com.linktomac.action.REFRESH_PHOTOS"
        private const val ACTION_RECONNECT = "com.linktomac.action.RECONNECT"
        private const val ACTION_FORGET = "com.linktomac.action.FORGET"
        private const val EXTRA_QR_PAYLOAD = "qr_payload"
        private const val EXTRA_NOTIFICATION_JSON = "notification_json"
        private const val EXTRA_NOTIFICATION_ID = "notification_id"

        var instance: SyncForegroundService? = null
            private set

        fun connectionState(): kotlinx.coroutines.flow.StateFlow<ConnectionState>? = instance?.connection?.state

        /** Direct, same-process access to the live connection — used by [ScreenMirrorService]
         *  to send video frames without routing high-frequency binary data through Intents. */
        fun activeConnection(): MacConnection? = instance?.connection

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

        /** Call right after the user grants call log/SMS/contacts permissions to sync immediately
         *  rather than waiting for the next call/message to trigger a ContentObserver callback. */
        fun refreshCallsAndSms(context: Context) {
            val intent = Intent(context, SyncForegroundService::class.java).apply {
                action = ACTION_REFRESH_DATA
            }
            context.startForegroundService(intent)
        }

        /** Call right after the user grants photo access, so library-change notifications start
         *  working without waiting for the service to restart. */
        fun refreshPhotoObserver(context: Context) {
            val intent = Intent(context, SyncForegroundService::class.java).apply {
                action = ACTION_REFRESH_PHOTOS
            }
            context.startForegroundService(intent)
        }

        /** Reading the clipboard is only reliable while the app is focused (Android 10+ blocks
         *  background reads for anything but the default IME) — see MainActivity's
         *  OnPrimaryClipChangedListener, registered only across onResume/onPause. Same
         *  loop-prevention check as [applyRemoteClipboard], against the same service instance. */
        fun reportLocalClipboardText(text: String) {
            instance?.let { svc ->
                if (text.isNotBlank() && text != svc.lastSyncedClipboardText) {
                    svc.lastSyncedClipboardText = text
                    svc.connection.sendClipboardUpdate(text)
                    android.util.Log.d("SyncForegroundService", "clipboard.update sent to Mac: ${text.take(40)}")
                }
            }
        }

        /** Forces a fresh Bonjour/NSD discovery attempt using the stored pairing credentials —
         *  no QR rescan. Exposed for a manual "Reconnect" action in the UI. */
        fun reconnectNow(context: Context) {
            val intent = Intent(context, SyncForegroundService::class.java).apply {
                action = ACTION_RECONNECT
            }
            context.startForegroundService(intent)
        }

        /** Clears the stored pairing and drops the current connection. The phone won't attempt
         *  to reconnect to this Mac again until it's paired fresh via QR. */
        fun forgetPairedDevice(context: Context) {
            val intent = Intent(context, SyncForegroundService::class.java).apply {
                action = ACTION_FORGET
            }
            context.startForegroundService(intent)
        }
    }
}
