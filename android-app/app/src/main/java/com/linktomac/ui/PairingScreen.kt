package com.linktomac.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Spacer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.linktomac.net.ConnectionState
import com.linktomac.service.SyncForegroundService
import kotlinx.coroutines.delay

@Composable
fun PairingScreen(
    isNotificationAccessGranted: () -> Boolean,
    onRequestNotificationAccess: () -> Unit,
    isCallsAndMessagesAccessGranted: () -> Boolean,
    onRequestCallsAndMessagesAccess: () -> Unit,
    isPhotoAccessGranted: () -> Boolean,
    onRequestPhotoAccess: () -> Unit,
    onScanRequested: () -> Unit,
    onStartService: () -> Unit
) {
    var notificationAccessGranted by remember { mutableStateOf(isNotificationAccessGranted()) }
    var callsAndMessagesAccessGranted by remember { mutableStateOf(isCallsAndMessagesAccessGranted()) }
    var photoAccessGranted by remember { mutableStateOf(isPhotoAccessGranted()) }
    var connectionState by remember { mutableStateOf<ConnectionState>(ConnectionState.Idle) }

    LaunchedEffect(Unit) {
        onStartService()
        // The foreground service's onCreate() runs asynchronously after startForegroundService()
        // returns, so its connection StateFlow isn't available on the same frame.
        while (SyncForegroundService.connectionState() == null) {
            delay(50)
        }
        SyncForegroundService.connectionState()?.collect { connectionState = it }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationAccessGranted = isNotificationAccessGranted()
                callsAndMessagesAccessGranted = isCallsAndMessagesAccessGranted()
                photoAccessGranted = isPhotoAccessGranted()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("LinkToMac", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Mirror notifications from this phone to your Mac.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(24.dp))

        if (!notificationAccessGranted) {
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("Notification access needed", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "LinkToMac needs permission to read notifications so it can mirror them to your Mac.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onRequestNotificationAccess) {
                        Text("Open Settings")
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        if (!callsAndMessagesAccessGranted) {
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("Call & message access needed", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "LinkToMac needs permission to read your call log and messages so it can show them on your Mac, and to send texts on your behalf when you reply from there.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onRequestCallsAndMessagesAccess) {
                        Text("Grant Access")
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        if (!photoAccessGranted) {
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("Photo access needed", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "LinkToMac needs permission to read your photos so you can browse them from your Mac.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onRequestPhotoAccess) {
                        Text("Grant Access")
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        when (val state = connectionState) {
            is ConnectionState.Connected -> {
                Text(
                    "Connected to ${state.macDeviceName}",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFF2E7D32)
                )
            }
            ConnectionState.Connecting -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("Connecting…", style = MaterialTheme.typography.bodyMedium)
            }
            is ConnectionState.Failed -> {
                Text(
                    "Couldn't pair: ${state.message}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFC62828)
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = onScanRequested) {
                    Text("Try Again")
                }
            }
            ConnectionState.Idle -> {
                Button(onClick = onScanRequested) {
                    Text("Scan Pairing Code")
                }
            }
        }
    }
}
