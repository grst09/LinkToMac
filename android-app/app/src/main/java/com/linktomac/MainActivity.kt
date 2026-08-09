package com.linktomac

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.journeyapps.barcodescanner.ScanContract
import com.linktomac.service.InputInjectionAccessibilityService
import com.linktomac.service.ScreenMirrorService
import com.linktomac.service.SyncForegroundService
import com.linktomac.storage.PairedDeviceStore
import com.linktomac.ui.PairingScreen
import com.linktomac.ui.qrScanOptions

class MainActivity : ComponentActivity() {

    // A second PairedDeviceStore instance, independent of SyncForegroundService's — reading
    // EncryptedSharedPreferences from multiple instances backed by the same file is safe, and
    // this lets the UI show paired-device state without depending on the service being alive.
    private val pairedDeviceStore by lazy { PairedDeviceStore(applicationContext) }

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
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PairingScreen(
                        isNotificationAccessGranted = { isNotificationAccessGranted() },
                        onRequestNotificationAccess = { openNotificationAccessSettings() },
                        isCallsAndMessagesAccessGranted = { isCallsAndMessagesAccessGranted() },
                        onRequestCallsAndMessagesAccess = { requestCallsAndMessagesAccess() },
                        isPhotoAccessGranted = { isPhotoAccessGranted() },
                        onRequestPhotoAccess = { requestPhotoAccess() },
                        isAccessibilityServiceEnabled = { isAccessibilityServiceEnabled() },
                        onRequestAccessibilityAccess = { openAccessibilitySettings() },
                        isPaired = { pairedDeviceStore.isPaired },
                        pairedMacName = { pairedDeviceStore.macDeviceName },
                        onReconnect = { SyncForegroundService.reconnectNow(applicationContext) },
                        onForgetDevice = { SyncForegroundService.forgetPairedDevice(applicationContext) },
                        onScanRequested = { requestCameraAndScan() },
                        onStartService = { SyncForegroundService.start(applicationContext) }
                    )
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
     *  and call log/SMS access is fully functional (falling back to raw numbers) without it. */
    private fun isCallsAndMessagesAccessGranted(): Boolean {
        return listOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS)
            .all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun requestCallsAndMessagesAccess() {
        callsAndMessagesPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.READ_CALL_LOG,
                Manifest.permission.READ_SMS,
                Manifest.permission.SEND_SMS,
                Manifest.permission.READ_CONTACTS
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

    companion object {
        const val ACTION_REQUEST_MIRROR_PERMISSION = "com.linktomac.action.REQUEST_MIRROR_PERMISSION"
    }
}
