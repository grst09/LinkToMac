package com.linktomac

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import com.linktomac.service.SyncForegroundService
import com.linktomac.ui.PairingScreen
import com.linktomac.ui.qrScanOptions

class MainActivity : ComponentActivity() {

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
                        onScanRequested = { requestCameraAndScan() },
                        onStartService = { SyncForegroundService.start(applicationContext) }
                    )
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
}
