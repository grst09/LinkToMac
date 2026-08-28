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
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.linktomac.MainActivity
import com.linktomac.data.BatteryStatusRepository
import com.linktomac.data.CallLogRepository
import com.linktomac.data.ContactRepository
import com.linktomac.data.FileRepository
import com.linktomac.data.PhotoRepository
import com.linktomac.data.SmsRepository
import com.linktomac.net.ConnectionState
import com.linktomac.net.DeviceStatusPayload
import com.linktomac.net.DiscoveredMac
import com.linktomac.net.MacConnection
import com.linktomac.net.MacDiscovery
import com.linktomac.net.NotificationPostedPayload
import com.linktomac.net.PairingQrPayload
import com.linktomac.net.SyncSettingsPayload
import com.linktomac.storage.AppSettingsStore
import com.linktomac.storage.NoteStore
import com.linktomac.storage.PairedDeviceStore
import com.linktomac.storage.SyncCategory
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
    private lateinit var contactRepository: ContactRepository
    private lateinit var photoRepository: PhotoRepository
    private lateinit var batteryStatusRepository: BatteryStatusRepository
    private lateinit var fileRepository: FileRepository
    private lateinit var noteStore: NoteStore
    private lateinit var appSettingsStore: AppSettingsStore
    private var discoveryJob: Job? = null
    private var syncJob: Job? = null
    private var contactSyncJob: Job? = null
    private var photoLibraryChangedJob: Job? = null

    /** Tracks the last text synced in either direction so a remote update we just wrote to the
     *  system clipboard doesn't get read back and echoed to the Mac as if it were a new local
     *  copy — see [applyRemoteClipboard] and [reportLocalClipboardText]. */
    private var lastSyncedClipboardText: String? = null

    /** Set by the explicit "Disconnect" action (distinct from [ACTION_FORGET] — the pairing
     *  itself is kept) so the persistent sync notification stays hidden until the user
     *  reconnects, instead of [connection]'s state settling back to Idle and immediately
     *  reposting a "Waiting to pair" notification via the [ConnectionState] listener below. */
    private var manuallyDisconnected = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        pairedDeviceStore = PairedDeviceStore(applicationContext)
        callLogRepository = CallLogRepository(applicationContext)
        smsRepository = SmsRepository(applicationContext)
        contactRepository = ContactRepository(applicationContext)
        photoRepository = PhotoRepository(applicationContext)
        batteryStatusRepository = BatteryStatusRepository(applicationContext)
        fileRepository = FileRepository()
        noteStore = NoteStore(applicationContext)
        appSettingsStore = AppSettingsStore(applicationContext)
        connection = MacConnection(
            deviceId = localDeviceId(),
            deviceName = Build.MODEL,
            pairedDeviceStore = pairedDeviceStore
        )
        connection.onNotificationDismissRequested = { id ->
            PhoneNotificationListenerService.instance?.cancel(id)
        }
        connection.onSmsSendRequested = { address, body ->
            if (appSettingsStore.callsAndMessagesSyncEnabled) smsRepository.send(address, body)
        }
        connection.onPhotoPageRequested = { offset, limit ->
            scope.launch(Dispatchers.IO) {
                if (!appSettingsStore.photosSyncEnabled) {
                    connection.sendPhotoPage(emptyList(), hasMore = false)
                    return@launch
                }
                val (photos, hasMore) = photoRepository.readPage(offset, limit)
                connection.sendPhotoPage(photos, hasMore)
            }
        }
        connection.onPhotoFullRequested = { id ->
            scope.launch(Dispatchers.IO) {
                if (!appSettingsStore.photosSyncEnabled) return@launch
                photoRepository.readFull(id)?.let { (bytes, mimeType) ->
                    connection.sendPhotoFull(id, Base64.encodeToString(bytes, Base64.NO_WRAP), mimeType)
                }
            }
        }
        connection.onPhotoDeleteRequested = { ids ->
            if (appSettingsStore.photosSyncEnabled) {
                // Same reasoning as onMirrorStartRequested below: deleting a MediaStore item the
                // app didn't create itself needs user consent (MediaStore.createDeleteRequest on
                // API 30+), which only a foreground Activity can present — see MainActivity's
                // ACTION_DELETE_PHOTOS. Whatever actually ends up deleted is picked up by
                // PhotoRepository's own content observer, which already drives the Mac's
                // reset-and-re-page refresh — no separate result message needed here.
                val intent = Intent(applicationContext, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    action = MainActivity.ACTION_DELETE_PHOTOS
                    putStringArrayListExtra(MainActivity.EXTRA_PHOTO_IDS, ArrayList(ids))
                }
                startActivity(intent)
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
        connection.onFilesListRequested = { path ->
            scope.launch(Dispatchers.IO) {
                if (!fileRepository.hasAccess()) {
                    connection.sendFilesListResult(path, emptyList(), error = "File access not granted")
                } else {
                    val entries = fileRepository.list(path)
                    if (entries != null) {
                        connection.sendFilesListResult(path, entries)
                    } else {
                        connection.sendFilesListResult(path, emptyList(), error = "Can't open that folder")
                    }
                }
            }
        }
        connection.onFilesDownloadRequested = { path ->
            scope.launch(Dispatchers.IO) {
                val name = path.substringAfterLast('/')
                val result = fileRepository.readFile(path)
                if (result != null) {
                    val (bytes, mimeType) = result
                    connection.sendFilesDownloadResult(path, name, Base64.encodeToString(bytes, Base64.NO_WRAP), mimeType)
                } else {
                    connection.sendFilesDownloadResult(path, name, error = "Couldn't read that file")
                }
            }
        }
        connection.onFilesUploadRequested = { path, name, dataBase64, _ ->
            scope.launch(Dispatchers.IO) {
                val bytes = Base64.decode(dataBase64, Base64.NO_WRAP)
                val success = fileRepository.writeFile(path, name, bytes)
                connection.sendFilesUploadResult(path, name, success, if (success) null else "Couldn't write that file")
            }
        }
        connection.onFilesCreateFolderRequested = { path, name ->
            scope.launch(Dispatchers.IO) {
                val success = fileRepository.createFolder(path, name)
                connection.sendFilesCreateFolderResult(path, name, success, if (success) null else "Couldn't create that folder")
            }
        }
        connection.onFilesRenameRequested = { path, newName ->
            scope.launch(Dispatchers.IO) {
                val success = fileRepository.rename(path, newName)
                connection.sendFilesRenameResult(path, newName, success, if (success) null else "Couldn't rename that item")
            }
        }
        connection.onFilesDeleteRequested = { path ->
            scope.launch(Dispatchers.IO) {
                val success = fileRepository.delete(path)
                connection.sendFilesDeleteResult(path, success, if (success) null else "Couldn't delete that item")
            }
        }
        connection.onFilesCopyRequested = { sourcePath, destinationPath ->
            scope.launch(Dispatchers.IO) {
                val success = fileRepository.copy(sourcePath, destinationPath)
                connection.sendFilesCopyResult(sourcePath, destinationPath, success, if (success) null else "Couldn't copy that item")
            }
        }
        connection.onFilesMoveRequested = { sourcePath, destinationPath ->
            scope.launch(Dispatchers.IO) {
                val success = fileRepository.move(sourcePath, destinationPath)
                connection.sendFilesMoveResult(sourcePath, destinationPath, success, if (success) null else "Couldn't move that item")
            }
        }
        connection.onContactsRefreshRequested = {
            if (appSettingsStore.contactsSyncEnabled) {
                scope.launch(Dispatchers.IO) { connection.sendContactsSync(contactRepository.readAll()) }
            }
        }
        connection.onContactsDialRequested = { phoneNumber ->
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phoneNumber")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        }
        connection.onContactUpdateRequested = { payload ->
            scope.launch(Dispatchers.IO) {
                if (!appSettingsStore.contactsSyncEnabled) {
                    connection.sendContactUpdateResult(payload.id, false, "Contacts sync is off")
                    return@launch
                }
                val success = contactRepository.update(
                    payload.id, payload.name, payload.phoneNumber, payload.isStarred, payload.email, payload.organization
                )
                connection.sendContactUpdateResult(payload.id, success, if (success) null else "Couldn't update that contact")
                if (success) connection.sendContactsSync(contactRepository.readAll())
            }
        }
        connection.onContactCreateRequested = { payload ->
            scope.launch(Dispatchers.IO) {
                if (!appSettingsStore.contactsSyncEnabled) {
                    connection.sendContactCreateResult(false, "Contacts sync is off")
                    return@launch
                }
                val success = contactRepository.create(payload.name, payload.phoneNumber, payload.email, payload.organization)
                connection.sendContactCreateResult(success, if (success) null else "Couldn't create that contact")
                if (success) connection.sendContactsSync(contactRepository.readAll())
            }
        }
        connection.onContactDeleteRequested = { id ->
            scope.launch(Dispatchers.IO) {
                if (!appSettingsStore.contactsSyncEnabled) {
                    connection.sendContactDeleteResult(id, false, "Contacts sync is off")
                    return@launch
                }
                val success = contactRepository.delete(id)
                connection.sendContactDeleteResult(id, success, if (success) null else "Couldn't delete that contact")
                if (success) connection.sendContactsSync(contactRepository.readAll())
            }
        }
        connection.onMessagesRefreshRequested = {
            if (appSettingsStore.callsAndMessagesSyncEnabled) {
                scope.launch(Dispatchers.IO) { connection.sendSmsSync(smsRepository.readThreads()) }
            }
        }
        connection.onNotesRefreshRequested = {
            if (appSettingsStore.notesSyncEnabled) {
                scope.launch(Dispatchers.IO) { connection.sendNotesSync(noteStore.readAll()) }
            }
        }
        connection.onNoteCreateRequested = { payload ->
            scope.launch(Dispatchers.IO) {
                if (!appSettingsStore.notesSyncEnabled) {
                    connection.sendNoteCreateResult(false, "Notes sync is off")
                    return@launch
                }
                val note = noteStore.create(payload.title, payload.body, payload.imageBase64)
                connection.sendNoteCreateResult(true)
                connection.sendNotesSync(noteStore.readAll())
                android.util.Log.d("SyncForegroundService", "notes.create: ${note.id}")
            }
        }
        connection.onNoteUpdateRequested = { payload ->
            scope.launch(Dispatchers.IO) {
                if (!appSettingsStore.notesSyncEnabled) {
                    connection.sendNoteUpdateResult(payload.id, false, "Notes sync is off")
                    return@launch
                }
                val success = noteStore.update(payload.id, payload.title, payload.body, payload.imageBase64)
                connection.sendNoteUpdateResult(payload.id, success, if (success) null else "Couldn't find that note")
                if (success) connection.sendNotesSync(noteStore.readAll())
            }
        }
        connection.onNoteDeleteRequested = { id ->
            scope.launch(Dispatchers.IO) {
                if (!appSettingsStore.notesSyncEnabled) {
                    connection.sendNoteDeleteResult(id, false, "Notes sync is off")
                    return@launch
                }
                val success = noteStore.delete(id)
                connection.sendNoteDeleteResult(id, success, if (success) null else "Couldn't find that note")
                if (success) connection.sendNotesSync(noteStore.readAll())
            }
        }
        connection.onNoteSetPinnedRequested = { payload ->
            scope.launch(Dispatchers.IO) {
                if (!appSettingsStore.notesSyncEnabled) {
                    connection.sendNoteSetPinnedResult(payload.id, false, "Notes sync is off")
                    return@launch
                }
                val success = noteStore.setPinned(payload.id, payload.isPinned)
                connection.sendNoteSetPinnedResult(payload.id, success, if (success) null else "Couldn't find that note")
                if (success) connection.sendNotesSync(noteStore.readAll())
            }
        }
        connection.state.onEach { state ->
            if (!manuallyDisconnected) updateNotification(state)
            if (state is ConnectionState.Failed) {
                // An unexpected drop (Mac unreachable, network blip, etc.) — same "stop looking
                // for connections until asked" posture as a manual disconnect: cancel the
                // background discovery/reconnect loop instead of leaving it running indefinitely.
                // ACTION_RECONNECT (or a fresh pairing) is what re-arms it.
                discoveryJob?.cancel()
                discoveryJob = null
            }
            if (state is ConnectionState.Connected) {
                // Sent before anything else so the Mac knows what's actually enabled before it
                // sees any sync data arrive (or not) — see SyncCategory's doc comment.
                connection.sendSyncSettings(currentSyncSettingsPayload())
                if (appSettingsStore.callsAndMessagesSyncEnabled) syncCallsAndSms()
                if (appSettingsStore.contactsSyncEnabled) connection.sendContactsSync(contactRepository.readAll())
                if (appSettingsStore.notesSyncEnabled) connection.sendNotesSync(noteStore.readAll())
                batteryStatusRepository.readCurrent()?.let { connection.sendDeviceStatus(it) }
            }
        }.launchIn(scope)
        batteryStatusRepository.observe { connection.sendDeviceStatus(it) }
        startForeground(NOTIFICATION_ID, buildNotification(ConnectionState.Idle))

        // No-ops if permission isn't granted yet (see CallLogRepository.observe) — retried in
        // ACTION_REFRESH_DATA below, which fires right after the user grants access.
        callLogRepository.observe { scheduleSync() }
        smsRepository.observe { scheduleSync() }
        contactRepository.observe { scheduleContactSync() }
        photoRepository.observe { schedulePhotoLibraryChangedNotification() }

        if (pairedDeviceStore.isPaired) {
            startDiscovery()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAIR -> intent.getStringExtra(EXTRA_QR_PAYLOAD)?.let { pairWithQrPayload(it) }
            ACTION_NOTIFICATION_POSTED -> if (appSettingsStore.notificationsSyncEnabled) {
                intent.getStringExtra(EXTRA_NOTIFICATION_JSON)?.let {
                    connection.sendNotificationPosted(json.decodeFromString(NotificationPostedPayload.serializer(), it))
                }
            }
            ACTION_NOTIFICATION_REMOVED -> if (appSettingsStore.notificationsSyncEnabled) {
                intent.getStringExtra(EXTRA_NOTIFICATION_ID)?.let {
                    connection.sendNotificationRemoved(it)
                }
            }
            ACTION_REFRESH_DATA -> {
                callLogRepository.observe { scheduleSync() }
                smsRepository.observe { scheduleSync() }
                contactRepository.observe { scheduleContactSync() }
                if (appSettingsStore.callsAndMessagesSyncEnabled) syncCallsAndSms()
                if (appSettingsStore.contactsSyncEnabled) connection.sendContactsSync(contactRepository.readAll())
            }
            ACTION_REFRESH_PHOTOS -> photoRepository.observe { schedulePhotoLibraryChangedNotification() }
            ACTION_NOTES_CHANGED -> if (appSettingsStore.notesSyncEnabled) connection.sendNotesSync(noteStore.readAll())
            ACTION_SYNC_SETTING_CHANGED -> {
                val categoryName = intent.getStringExtra(EXTRA_SYNC_CATEGORY)
                val enabled = intent.getBooleanExtra(EXTRA_SYNC_ENABLED, true)
                val category = categoryName?.let { runCatching { SyncCategory.valueOf(it) }.getOrNull() }
                if (category != null) {
                    val wasEnabled = appSettingsStore.isSyncEnabled(category)
                    appSettingsStore.setSyncEnabled(category, enabled)
                    if (connection.state.value is ConnectionState.Connected) {
                        connection.sendSyncSettings(currentSyncSettingsPayload())
                        // The Mac's own reconciliation (dispatch::sync_settings::reconcile_*)
                        // only pushes whatever *it* queued locally while this category was off —
                        // it has no way to know about changes made independently on the phone
                        // during that window (e.g. a note created directly in the phone's Notes
                        // tab). Pushing a fresh full sync here is what actually catches the Mac
                        // up on those.
                        if (!wasEnabled && enabled) pushFreshSync(category)
                    }
                }
            }
            ACTION_RECONNECT -> if (pairedDeviceStore.isPaired) {
                manuallyDisconnected = false
                startForeground(NOTIFICATION_ID, buildNotification(connection.state.value))
                startDiscovery()
            }
            ACTION_DISCONNECT -> {
                // Set before close() — close() flips connection.state to Idle, which the
                // StateFlow collector above picks up asynchronously on a background dispatcher.
                // Setting the guard first (rather than after) avoids a race where that collector
                // reads manuallyDisconnected as still false and reposts the notification before
                // stopForeground below ever runs.
                manuallyDisconnected = true
                discoveryJob?.cancel()
                discoveryJob = null
                connection.close()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            }
            ACTION_FORGET -> {
                discoveryJob?.cancel()
                discoveryJob = null
                connection.close()
                pairedDeviceStore.clear()
                manuallyDisconnected = false
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        discoveryJob?.cancel()
        syncJob?.cancel()
        contactSyncJob?.cancel()
        photoLibraryChangedJob?.cancel()
        callLogRepository.stopObserving()
        smsRepository.stopObserving()
        contactRepository.stopObserving()
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
        if (!appSettingsStore.clipboardSyncEnabled) return
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

    /** Debounces bursts of ContentObserver changes (e.g. importing a batch of contacts) into one sync. */
    private fun scheduleContactSync() {
        contactSyncJob?.cancel()
        contactSyncJob = scope.launch {
            delay(750)
            if (connection.state.value is ConnectionState.Connected && appSettingsStore.contactsSyncEnabled) {
                connection.sendContactsSync(contactRepository.readAll())
            }
        }
    }

    /** Debounces bursts of ContentObserver changes (e.g. a batch of messages arriving) into one sync. */
    private fun scheduleSync() {
        syncJob?.cancel()
        syncJob = scope.launch {
            delay(750)
            if (connection.state.value is ConnectionState.Connected && appSettingsStore.callsAndMessagesSyncEnabled) {
                syncCallsAndSms()
            }
        }
    }

    /** Debounces bursts of MediaStore changes (e.g. importing a batch of photos) into one notification. */
    private fun schedulePhotoLibraryChangedNotification() {
        photoLibraryChangedJob?.cancel()
        photoLibraryChangedJob = scope.launch {
            delay(750)
            if (connection.state.value is ConnectionState.Connected && appSettingsStore.photosSyncEnabled) {
                connection.sendPhotoLibraryChanged()
            }
        }
    }

    private fun currentSyncSettingsPayload() = SyncSettingsPayload(
        notificationsEnabled = appSettingsStore.notificationsSyncEnabled,
        callsAndMessagesEnabled = appSettingsStore.callsAndMessagesSyncEnabled,
        contactsEnabled = appSettingsStore.contactsSyncEnabled,
        photosEnabled = appSettingsStore.photosSyncEnabled,
        notesEnabled = appSettingsStore.notesSyncEnabled,
        clipboardEnabled = appSettingsStore.clipboardSyncEnabled
    )

    /** Pushes the phone's current state for one category right after its toggle flips on — see
     *  the call site's comment. Notifications/Photos/Clipboard have no "current snapshot" to
     *  proactively push (notifications are discrete events, Photos is pulled on-demand by the
     *  Mac, Clipboard has no history), so those are no-ops here. */
    private fun pushFreshSync(category: SyncCategory) {
        when (category) {
            SyncCategory.NOTIFICATIONS -> {}
            SyncCategory.CALLS_AND_MESSAGES -> syncCallsAndSms()
            SyncCategory.CONTACTS -> connection.sendContactsSync(contactRepository.readAll())
            SyncCategory.PHOTOS -> {}
            SyncCategory.NOTES -> connection.sendNotesSync(noteStore.readAll())
            SyncCategory.CLIPBOARD -> {}
        }
    }

    private fun pairWithQrPayload(qrJson: String) {
        manuallyDisconnected = false
        startForeground(NOTIFICATION_ID, buildNotification(connection.state.value))
        val payload = json.decodeFromString(PairingQrPayload.serializer(), qrJson)
        connection.connectForPairing(payload)
    }

    /** Cancels any existing discovery listener and starts a fresh one — NsdManager's discovery
     *  doesn't reliably re-fire onServiceFound for a service that briefly disappeared and came
     *  back (e.g. the Mac's own disconnect/reconnect toggle), so restarting discovery outright
     *  is what actually gets a response, both for the background retry path and the explicit
     *  "Reconnect" action. Reconnecting is allowed from Idle or Failed — not just Idle — so a
     *  previous failed attempt doesn't permanently block further retries.
     *
     *  Also fires off a direct connection attempt to whatever address last worked (see
     *  [PairedDeviceStore.lastKnownHost]) before mDNS has resolved anything. mDNS discovery relies
     *  on multicast, which plenty of routers/mesh systems don't bridge reliably across WiFi bands
     *  — the Mac roaming onto 6GHz while the phone's stuck on 5GHz is enough to make
     *  [MacDiscovery] never see a response even though both devices can reach each other fine
     *  over plain unicast IP. The two races harmlessly: `connection.state` flips to `Connecting`
     *  the moment the direct attempt starts, so the guard below skips anything mDNS resolves
     *  until that attempt actually finishes (succeeds, or fails and frees the guard back up). */
    private fun startDiscovery() {
        discoveryJob?.cancel()

        val lastHost = pairedDeviceStore.lastKnownHost
        val lastPort = pairedDeviceStore.lastKnownPort
        if (lastHost != null && lastPort != 0) {
            val state = connection.state.value
            if (state == ConnectionState.Idle || state is ConnectionState.Failed) {
                connection.connectForReconnect(DiscoveredMac(lastHost, lastPort, pairedDeviceStore.macDeviceId))
            }
        }

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
            // A background reconnect attempt failing isn't something worth alarming the user
            // over in an ongoing notification — same neutral copy as never having connected.
            is ConnectionState.Failed, ConnectionState.Idle -> "Waiting to pair"
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
        private const val ACTION_NOTES_CHANGED = "com.linktomac.action.NOTES_CHANGED"
        private const val ACTION_RECONNECT = "com.linktomac.action.RECONNECT"
        private const val ACTION_DISCONNECT = "com.linktomac.action.DISCONNECT"
        private const val ACTION_FORGET = "com.linktomac.action.FORGET"
        private const val ACTION_SYNC_SETTING_CHANGED = "com.linktomac.action.SYNC_SETTING_CHANGED"
        private const val EXTRA_QR_PAYLOAD = "qr_payload"
        private const val EXTRA_NOTIFICATION_JSON = "notification_json"
        private const val EXTRA_NOTIFICATION_ID = "notification_id"
        private const val EXTRA_SYNC_CATEGORY = "sync_category"
        private const val EXTRA_SYNC_ENABLED = "sync_enabled"

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

        /** Notes has no ContentObserver to react to (it's not a system provider — see
         *  docs/PROTOCOL.md's Phase 8 notes), so [NotesScreen] calls this itself right after
         *  every local create/update/delete to push a fresh `notes.sync`, the same way a
         *  Mac-initiated mutation does via [SyncForegroundService]'s own `onNoteCreateRequested`
         *  etc. */
        fun notifyNotesChangedLocally(context: Context) {
            val intent = Intent(context, SyncForegroundService::class.java).apply {
                action = ACTION_NOTES_CHANGED
            }
            context.startForegroundService(intent)
        }

        /** Reading the clipboard is only reliable while the app is focused (Android 10+ blocks
         *  background reads for anything but the default IME) — see MainActivity's
         *  OnPrimaryClipChangedListener, registered only across onResume/onPause. Same
         *  loop-prevention check as [applyRemoteClipboard], against the same service instance. */
        fun reportLocalClipboardText(text: String) {
            instance?.let { svc ->
                if (!svc.appSettingsStore.clipboardSyncEnabled) return
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

        /** Closes the current connection and stops the persistent sync notification, without
         *  clearing the stored pairing — [reconnectNow] (or a fresh pair) brings both back. */
        fun disconnect(context: Context) {
            val intent = Intent(context, SyncForegroundService::class.java).apply {
                action = ACTION_DISCONNECT
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

        /** Persists the toggle and, if currently connected, immediately pushes the full updated
         *  set to the Mac (see [SyncSettingsPayload]) so it can react right away — e.g. flush
         *  anything it queued locally while this category was off. */
        fun updateSyncSetting(context: Context, category: SyncCategory, enabled: Boolean) {
            val intent = Intent(context, SyncForegroundService::class.java).apply {
                action = ACTION_SYNC_SETTING_CHANGED
                putExtra(EXTRA_SYNC_CATEGORY, category.name)
                putExtra(EXTRA_SYNC_ENABLED, enabled)
            }
            context.startForegroundService(intent)
        }
    }
}
