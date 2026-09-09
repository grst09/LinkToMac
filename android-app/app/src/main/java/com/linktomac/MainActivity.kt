package com.linktomac

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.StickyNote2
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.view.WindowCompat
import com.journeyapps.barcodescanner.ScanContract
import com.linktomac.service.InputInjectionAccessibilityService
import com.linktomac.service.ScreenMirrorService
import com.linktomac.service.SyncForegroundService
import com.linktomac.storage.AppSettingsStore
import com.linktomac.storage.NoteStore
import com.linktomac.storage.PairedDeviceStore
import com.linktomac.storage.SyncCategory
import com.linktomac.ui.NotesScreen
import com.linktomac.ui.PairingScreen
import com.linktomac.ui.SettingsScreen
import com.linktomac.ui.qrScanOptions
import com.linktomac.ui.theme.AccentViolet
import com.linktomac.ui.theme.AccentYellow
import com.linktomac.ui.theme.AccentYellowOn
import com.linktomac.ui.theme.LinkToMacTheme
import com.linktomac.ui.theme.ThemeMode
import com.linktomac.ui.theme.resolveDarkTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    // A second PairedDeviceStore instance, independent of SyncForegroundService's — reading
    // EncryptedSharedPreferences from multiple instances backed by the same file is safe, and
    // this lets the UI show paired-device state without depending on the service being alive.
    private val pairedDeviceStore by lazy { PairedDeviceStore(applicationContext) }

    // Same reasoning as pairedDeviceStore above — NotesScreen reads/writes this directly and
    // pushes sync via SyncForegroundService.notifyNotesChangedLocally rather than needing a
    // live reference to the service.
    private val noteStore by lazy { NoteStore(applicationContext) }

    // Same reasoning again — SettingsScreen toggles this directly; SyncForegroundService reads
    // the same underlying file for the clipboard-sync gate (see its own appSettingsStore field).
    private val appSettingsStore by lazy { AppSettingsStore(applicationContext) }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) launchScanner()
        }

    private val callsAndMessagesPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results.values.any { it }) {
                SyncForegroundService.refreshCallsAndSms(applicationContext)
            }
        }

    private val photoPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                SyncForegroundService.refreshPhotoObserver(applicationContext)
            }
        }

    /** Only reached below API 30 — API 30+ file access is the MANAGE_EXTERNAL_STORAGE special
     *  permission, granted via Settings rather than a runtime dialog (see requestFileAccess). */
    private val legacyFileAccessPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    /** Result is ignored — whatever actually got deleted (the user can cancel or partially
     *  approve a batch) shows up via PhotoRepository's content observer regardless, which is
     *  what actually drives the Mac-side refresh. See ACTION_DELETE_PHOTOS. */
    private val photoDeleteIntentSenderLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { }

    private val screenCapturePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                ScreenMirrorService.start(applicationContext, result.resultCode, data)
            } else {
                SyncForegroundService.activeConnection()?.sendMirrorStopped("permission_denied")
            }
        }

    private lateinit var scanLauncher: ActivityResultLauncher<com.journeyapps.barcodescanner.ScanOptions>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The manifest theme is a raw framework style (Theme.Material.Light.NoActionBar, kept
        // for API 26 safety — see the SideEffect below), which paints the status/nav bars with
        // its own default color instead of the app's actual canvas. enableEdgeToEdge() makes the
        // system draw them transparently and edge-to-edge, so the app's own background (already
        // Scaffold's default containerColor) shows straight through underneath instead — this is
        // what makes the status bar read as part of the app rather than a mismatched system bar.
        enableEdgeToEdge()

        scanLauncher = registerForActivityResult(ScanContract()) { result ->
            result.contents?.let { qrJson ->
                SyncForegroundService.pair(applicationContext, qrJson)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        handleIntent(intent)

        setContent {
            var themeMode by remember { mutableStateOf(ThemeMode.valueOf(appSettingsStore.themeMode)) }
            val darkTheme = resolveDarkTheme(themeMode)
            // Manifest theme is fixed (Theme.Material.Light.NoActionBar, API 26-safe), so the
            // system status/nav bar icon contrast is set here instead, following the same
            // darkTheme flag LinkToMacTheme uses for the Compose content.
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
            LinkToMacTheme(darkTheme = darkTheme) {
                var selectedTab by remember { mutableIntStateOf(0) }
                // Land back on the Device tab before actually exiting — matches standard
                // bottom-nav back behavior (system back is otherwise unhandled anywhere above
                // this and just finishes the Activity). Each tab's own sub-screens (the note
                // editor, sync options, …) register their own BackHandler further down and take
                // priority automatically — Compose dispatches to the innermost enabled one first.
                BackHandler(enabled = selectedTab != 0) { selectedTab = 0 }
                // NavigationSuiteScaffold renders a bottom NavigationBar on a compact window
                // (the Fold's outer cover screen), and switches to a side NavigationRail once the
                // window is wide enough (the Fold unfolded) — same three destinations either way,
                // just picking whichever layout actually fits the current screen instead of a
                // bottom bar hard-coded for phone-sized windows. See
                // https://developer.android.com/develop/adaptive-apps/guides/get-started-with-adaptive-apps.
                // NavigationSuiteScaffold's content slot is a bare `() -> Unit` (no PaddingValues,
                // unlike the old Scaffold's innerPadding) — with enableEdgeToEdge() drawing behind
                // the system bars, both the rail/bar and the main content need this explicit inset
                // themselves or they render straight under the status bar.
                // Each tab gets its own accent (green/yellow/violet) on both the icon and its
                // indicator pill, rather than every destination sharing one color — makes the
                // current section identifiable by color alone, and ties Notes' accent to its own
                // FAB, which uses the same yellow. Built outside `navigationSuiteItems` below:
                // that scope's `item()` calls aren't fully @Composable-scoped, so reading
                // MaterialTheme.colorScheme directly inside one of them fails to compile.
                val deviceBarColors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val deviceRailColors = NavigationRailItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val deviceItemColors = NavigationSuiteDefaults.itemColors(
                    navigationBarItemColors = deviceBarColors,
                    navigationRailItemColors = deviceRailColors
                )
                val notesBarColors = NavigationBarItemDefaults.colors(
                    selectedIconColor = AccentYellowOn,
                    indicatorColor = AccentYellow.copy(alpha = 0.3f),
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val notesRailColors = NavigationRailItemDefaults.colors(
                    selectedIconColor = AccentYellowOn,
                    indicatorColor = AccentYellow.copy(alpha = 0.3f),
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val notesItemColors = NavigationSuiteDefaults.itemColors(
                    navigationBarItemColors = notesBarColors,
                    navigationRailItemColors = notesRailColors
                )
                val settingsBarColors = NavigationBarItemDefaults.colors(
                    selectedIconColor = AccentViolet,
                    indicatorColor = AccentViolet.copy(alpha = 0.25f),
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val settingsRailColors = NavigationRailItemDefaults.colors(
                    selectedIconColor = AccentViolet,
                    indicatorColor = AccentViolet.copy(alpha = 0.25f),
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val settingsItemColors = NavigationSuiteDefaults.itemColors(
                    navigationBarItemColors = settingsBarColors,
                    navigationRailItemColors = settingsRailColors
                )
                val tabContent: @Composable () -> Unit = {
                    AnimatedContent(
                        targetState = selectedTab,
                        modifier = Modifier.fillMaxSize(),
                        // A plain crossfade — no horizontal slide. The slide read as a leftover
                        // "page turn" affordance that didn't fit tabs living in a side rail on a
                        // wide window (nothing is spatially "left" or "right" of a vertical rail).
                        transitionSpec = { fadeIn(tween(220)).togetherWith(fadeOut(tween(140))) },
                        label = "tabContent"
                    ) { tab ->
                        when (tab) {
                            0 -> PairingScreen(
                                isNotificationAccessGranted = { isNotificationAccessGranted() },
                                onRequestNotificationAccess = { openNotificationAccessSettings() },
                                isCallsAndMessagesAccessGranted = { isCallsAndMessagesAccessGranted() },
                                onRequestCallsAndMessagesAccess = { requestCallsAndMessagesAccess() },
                                isPhotoAccessGranted = { isPhotoAccessGranted() },
                                onRequestPhotoAccess = { requestPhotoAccess() },
                                isAccessibilityServiceEnabled = { isAccessibilityServiceEnabled() },
                                onRequestAccessibilityAccess = { openAccessibilitySettings() },
                                isFileAccessGranted = { isFileAccessGranted() },
                                onRequestFileAccess = { requestFileAccess() },
                                isPaired = { pairedDeviceStore.isPaired },
                                pairedMacName = { pairedDeviceStore.macDeviceName },
                                onReconnect = { SyncForegroundService.reconnectNow(applicationContext) },
                                onDisconnect = { SyncForegroundService.disconnect(applicationContext) },
                                onForgetDevice = { SyncForegroundService.forgetPairedDevice(applicationContext) },
                                onScanRequested = { requestCameraAndScan() },
                                onStartService = { SyncForegroundService.start(applicationContext) },
                                syncOptions = {
                                    SyncCategory.entries.associateWith { appSettingsStore.isSyncEnabled(it) }
                                },
                                onSyncOptionChanged = { category, enabled ->
                                    SyncForegroundService.updateSyncSetting(applicationContext, category, enabled)
                                }
                            )
                            1 -> NotesScreen(
                                noteStore = noteStore,
                                onChanged = { SyncForegroundService.notifyNotesChangedLocally(applicationContext) },
                                onSyncRequested = { SyncForegroundService.notifyNotesChangedLocally(applicationContext) },
                                initialListPaneWidthDp = appSettingsStore.notesListPaneWidthDp,
                                onListPaneWidthChanged = { appSettingsStore.notesListPaneWidthDp = it }
                            )
                            else -> SettingsScreen(
                                isBatteryOptimizationIgnored = { isBatteryOptimizationIgnored() },
                                onRequestIgnoreBatteryOptimization = { requestIgnoreBatteryOptimization() },
                                themeMode = themeMode,
                                onThemeModeChanged = {
                                    themeMode = it
                                    appSettingsStore.themeMode = it.name
                                },
                                appVersion = appVersionName(),
                                deviceId = localDeviceId()
                            )
                        }
                    }
                }
                // NavigationSuiteScaffold's own NavigationRail always packs items against the top
                // (Material's spec default, and not something the 1.3.1 API we're on exposes a
                // param to change) — on a rail this is a much longer reach than a phone's bottom
                // bar ever was, so on a wide window we render the rail ourselves with a weighted
                // Spacer above and below the items to center them instead, and fall back to
                // NavigationSuiteScaffold's own bottom NavigationBar unchanged on a compact window
                // (a horizontal bar has no "center" to reach for in the first place).
                val useRail = LocalConfiguration.current.screenWidthDp >= 600
                // Both branches used to take `Modifier.safeDrawingPadding()` on their outermost
                // element — which, since that element is also the one painting the background
                // (NavigationSuiteScaffold wraps everything in a Surface; the rail branch's Row
                // was the outermost element here), pushed the *painted background* itself in from
                // under the status/nav bars along with the content, leaving the system bar area
                // showing the plain window background instead of the app's own. A Surface here
                // with no padding of its own paints edge-to-edge first; padding then moves to just
                // the content each branch renders, so only content — not the color behind it —
                // clears the system bars. The nav rail/bar chrome needs no padding of its own
                // either way: both apply their own window-insets padding internally.
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (useRail) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            NavigationRail {
                                Spacer(Modifier.weight(1f))
                                NavigationRailItem(
                                    selected = selectedTab == 0,
                                    onClick = { selectedTab = 0 },
                                    icon = { Icon(Icons.Filled.PhoneAndroid, contentDescription = "Device") },
                                    colors = deviceRailColors
                                )
                                NavigationRailItem(
                                    selected = selectedTab == 1,
                                    onClick = { selectedTab = 1 },
                                    icon = { Icon(Icons.Filled.StickyNote2, contentDescription = "Notes") },
                                    colors = notesRailColors
                                )
                                NavigationRailItem(
                                    selected = selectedTab == 2,
                                    onClick = { selectedTab = 2 },
                                    icon = { Icon(Icons.Filled.Settings, contentDescription = "Settings") },
                                    colors = settingsRailColors
                                )
                                Spacer(Modifier.weight(1f))
                            }
                            Box(modifier = Modifier.weight(1f).safeDrawingPadding()) { tabContent() }
                        }
                    } else {
                        NavigationSuiteScaffold(
                            navigationSuiteItems = {
                                item(
                                    selected = selectedTab == 0,
                                    onClick = { selectedTab = 0 },
                                    icon = { Icon(Icons.Filled.PhoneAndroid, contentDescription = "Device") },
                                    colors = deviceItemColors
                                )
                                item(
                                    selected = selectedTab == 1,
                                    onClick = { selectedTab = 1 },
                                    icon = { Icon(Icons.Filled.StickyNote2, contentDescription = "Notes") },
                                    colors = notesItemColors
                                )
                                item(
                                    selected = selectedTab == 2,
                                    onClick = { selectedTab = 2 },
                                    icon = { Icon(Icons.Filled.Settings, contentDescription = "Settings") },
                                    colors = settingsItemColors
                                )
                            },
                            content = { Box(modifier = Modifier.safeDrawingPadding()) { tabContent() } }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** The Mac-initiated "start mirroring"/"delete photos" requests arrive in
     *  SyncForegroundService (a background service), but both MediaProjection consent and
     *  MediaStore's delete consent can only be requested from a foreground Activity — this is
     *  what brings the phone's screen to the front for either. */
    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_REQUEST_MIRROR_PERMISSION -> requestScreenCapturePermission()
            ACTION_DELETE_PHOTOS -> {
                val ids = intent.getStringArrayListExtra(EXTRA_PHOTO_IDS)
                if (!ids.isNullOrEmpty()) requestPhotoDeletion(ids)
            }
        }
    }

    private fun requestScreenCapturePermission() {
        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        screenCapturePermissionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    /** Deleting a `MediaStore` item this app didn't create itself needs the user's consent.
     *  API 30+ batches that into a single system dialog covering the whole selection
     *  ([MediaStore.createDeleteRequest]); below that, [android.provider.MediaStore] has no
     *  batch-consent API, so this falls back to a best-effort direct delete per item (works on
     *  API 26–28's pre-scoped-storage model; on API 29 a photo this app doesn't own will just
     *  throw and get silently skipped rather than opening a consent dialog per photo — a
     *  narrow, aging OS slice not worth the extra one-at-a-time IntentSender plumbing here).
     */
    private fun requestPhotoDeletion(ids: List<String>) {
        val uris = ids.mapNotNull { id ->
            id.toLongOrNull()?.let { ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, it) }
        }
        if (uris.isEmpty()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val pendingIntent = MediaStore.createDeleteRequest(contentResolver, uris)
            photoDeleteIntentSenderLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
        } else {
            lifecycleScope.launch(Dispatchers.IO) {
                for (uri in uris) {
                    try {
                        contentResolver.delete(uri, null, null)
                    } catch (e: SecurityException) {
                        // No consent path below API 30 — skip; see doc comment above.
                    }
                }
            }
        }
    }

    private fun requestCameraAndScan() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchScanner()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchScanner() {
        scanLauncher.launch(qrScanOptions())
    }

    private fun isNotificationAccessGranted(): Boolean {
        val enabledListeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return enabledListeners.contains(packageName)
    }

    private fun openNotificationAccessSettings() {
        startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
    }

    /** READ_CONTACTS is excluded from this check — it only improves contact-name resolution
     *  and call log/SMS access is fully functional (falling back to raw numbers) without it.
     *  WRITE_CONTACTS *is* included: without it the Contacts tab's edit/create/delete actions
     *  fail outright, and since this whole check gates whether the "Grant Access" card even
     *  appears, leaving it out would mean a user who already granted everything else has no way
     *  to ever be re-prompted for it — the card just never comes back. */
    private fun isCallsAndMessagesAccessGranted(): Boolean {
        return listOf(
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.WRITE_CONTACTS
        ).all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun requestCallsAndMessagesAccess() {
        callsAndMessagesPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.READ_CALL_LOG,
                Manifest.permission.READ_SMS,
                Manifest.permission.SEND_SMS,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.WRITE_CONTACTS
            )
        )
    }

    /** READ_MEDIA_IMAGES replaced READ_EXTERNAL_STORAGE for media access on API 33+. */
    private fun photoPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    private fun isPhotoAccessGranted(): Boolean =
        checkSelfPermission(photoPermission()) == PackageManager.PERMISSION_GRANTED

    private fun requestPhotoAccess() {
        photoPermissionLauncher.launch(photoPermission())
    }

    /** Same "check the system list" pattern as isNotificationAccessGranted — accessibility
     *  services can't be queried via checkSelfPermission, only via this settings string. */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponent = "$packageName/${InputInjectionAccessibilityService::class.java.name}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.split(':').any { it.equals(expectedComponent, ignoreCase = true) }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    /** MANAGE_EXTERNAL_STORAGE (API 30+) is a "special app access" permission — it can only be
     *  granted from its own Settings screen, not a runtime dialog. Below API 30, ordinary
     *  READ/WRITE_EXTERNAL_STORAGE was all scoped storage required. */
    private fun isFileAccessGranted(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

    private fun requestFileAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
            )
        } else {
            legacyFileAccessPermissionLauncher.launch(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            )
        }
    }

    /** Whether the OEM's battery management already leaves this app alone — not a runtime
     *  permission, just a query against PowerManager's allowlist. */
    private fun isBatteryOptimizationIgnored(): Boolean {
        val powerManager = getSystemService(PowerManager::class.java)
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestIgnoreBatteryOptimization() {
        startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        )
    }

    private fun appVersionName(): String =
        try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
        } catch (e: PackageManager.NameNotFoundException) {
            "unknown"
        }

    /** Same id/storage SyncForegroundService.localDeviceId() reads/generates — a second read of
     *  the same SharedPreferences file is safe, same reasoning as pairedDeviceStore above. */
    private fun localDeviceId(): String =
        getSharedPreferences("linktomac_device", MODE_PRIVATE).getString("device_id", null) ?: "unassigned"

    // Android only allows reading clipboard content while the app is focused (or is the
    // default IME), so this is the only reliable place to pick up local copies — a background
    // service can write the clipboard freely but can't read it. See SyncForegroundService's
    // reportLocalClipboardText/applyRemoteClipboard for the sync-loop-prevention half of this.
    private val clipboardManager by lazy { getSystemService(ClipboardManager::class.java) }
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener { checkClipboard() }

    override fun onResume() {
        super.onResume()
        clipboardManager.addPrimaryClipChangedListener(clipboardListener)
        checkClipboard()
    }

    override fun onPause() {
        super.onPause()
        clipboardManager.removePrimaryClipChangedListener(clipboardListener)
    }

    private fun checkClipboard() {
        val clip = clipboardManager.primaryClip ?: return
        if (clip.itemCount == 0) return

        // An image clip's item has a content:// URI, not text — `coerceToText` below would just
        // stringify that URI (not the pixels), which isn't remotely the same thing as "this
        // clipboard content is text". Check for that first and read the actual bytes instead.
        if (clip.description.hasMimeType("image/*")) {
            val uri = clip.getItemAt(0).uri ?: return
            try {
                // Normalized to PNG regardless of source format (a Gallery copy is commonly
                // JPEG/WEBP) — decoding to a Bitmap and re-encoding keeps the wire format the
                // same PNG-both-directions contract the Mac side already assumes, rather than
                // needing it to handle every format Android might hand back.
                val bitmap = contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) }
                    ?: return
                val out = java.io.ByteArrayOutputStream()
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                val base64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                SyncForegroundService.reportLocalClipboardImage(SyncForegroundService.bitmapPixelHash(bitmap), base64)
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Failed to read clipboard image", e)
            }
            return
        }

        val text = clip.getItemAt(0).coerceToText(this)?.toString() ?: return
        if (text.isBlank()) return
        SyncForegroundService.reportLocalClipboardText(text)
    }

    companion object {
        const val ACTION_REQUEST_MIRROR_PERMISSION = "com.linktomac.action.REQUEST_MIRROR_PERMISSION"
        const val ACTION_DELETE_PHOTOS = "com.linktomac.action.DELETE_PHOTOS"
        const val EXTRA_PHOTO_IDS = "com.linktomac.extra.PHOTO_IDS"
    }
}
