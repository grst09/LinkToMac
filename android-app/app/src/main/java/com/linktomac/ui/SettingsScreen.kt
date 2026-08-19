package com.linktomac.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * Settings tab. Battery optimization is the one setting here that actually matters for
 * reliability — a foreground service can still get killed by OEM battery management (Samsung's
 * One UI is particularly aggressive about it), so this surfaces the exemption request the same
 * way PairingScreen surfaces other required-access cards.
 */
@Composable
fun SettingsScreen(
    isBatteryOptimizationIgnored: () -> Boolean,
    onRequestIgnoreBatteryOptimization: () -> Unit,
    clipboardSyncEnabled: () -> Boolean,
    onClipboardSyncChanged: (Boolean) -> Unit,
    appVersion: String,
    deviceId: String
) {
    var batteryOptimizationIgnored by remember { mutableStateOf(isBatteryOptimizationIgnored()) }
    var clipboardEnabled by remember { mutableStateOf(clipboardSyncEnabled()) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryOptimizationIgnored = isBatteryOptimizationIgnored()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        if (!batteryOptimizationIgnored) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Battery optimization", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Some phones (especially Samsung) kill background apps aggressively, which can silently disconnect LinkToMac. Exempting it from battery optimization keeps the connection alive.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onRequestIgnoreBatteryOptimization) {
                        Text("Allow Background Activity")
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Clipboard sync", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Share copied text with your Mac, and apply text copied there.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = clipboardEnabled,
                    onCheckedChange = {
                        clipboardEnabled = it
                        onClipboardSyncChanged(it)
                    }
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("About", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text("Version $appVersion", style = MaterialTheme.typography.bodySmall)
                Text("Device ID: $deviceId", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
