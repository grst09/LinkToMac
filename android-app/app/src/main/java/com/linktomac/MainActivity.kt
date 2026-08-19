package com.linktomac

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import com.linktomac.ui.theme.LinkToMacTheme
import com.linktomac.ui.theme.ThemeMode
import com.linktomac.ui.theme.resolveDarkTheme

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
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                icon = { Icon(Icons.Filled.Smartphone, contentDescription = "Device") },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                            NavigationBarItem(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                icon = { Icon(Icons.AutoMirrored.Filled.Notes, contentDescription = "Notes") },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                            NavigationBarItem(
                                selected = selectedTab == 2,
                                onClick = { selectedTab = 2 },
                                icon = { Icon(Icons.Filled.Settings, contentDescription = "Settings") },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    }
                ) { innerPadding ->
                    AnimatedContent(
                        targetState = selectedTab,
                        modifier = Modifier.fillMaxSize().padding(innerPadding),
                        transitionSpec = {
                            val direction = if (targetState > initialState) 1 else -1
                            (fadeIn(tween(220)) + slideInHorizontally(tween(220)) { direction * it / 6 })
                                .togetherWith(fadeOut(tween(140)) + slideOutHorizontally(tween(140)) { -direction * it / 6 })
                        },
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
                                onSyncRequested = { SyncForegroundService.notifyNotesChangedLocally(applicationContext) }
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
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** The Mac-initiated "start mirroring" request arrives in SyncForegroundService (a
     *  background service), but MediaProjection consent can only be requested from a
     *  foreground Activity — this is what brings the phone's screen to the front for that. */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ACTION_REQUEST_MIRROR_PERMISSION) {
            requestScreenCapturePermission()
        }
    }

    private fun requestScreenCapturePermission() {
        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        screenCapturePermissionLauncher.launch(projectionManager.createScreenCaptureIntent())
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
        val text = clip.getItemAt(0).coerceToText(this)?.toString() ?: return
        if (text.isBlank()) return
        SyncForegroundService.reportLocalClipboardText(text)
    }

    companion object {
        const val ACTION_REQUEST_MIRROR_PERMISSION = "com.linktomac.action.REQUEST_MIRROR_PERMISSION"
    }
}
