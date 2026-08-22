package com.linktomac.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.linktomac.ui.components.LinkCard
import com.linktomac.ui.components.PillButton
import com.linktomac.ui.components.SlidingSegmentedControl
import com.linktomac.ui.theme.ThemeMode

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
    themeMode: ThemeMode,
    onThemeModeChanged: (ThemeMode) -> Unit,
    appVersion: String,
    deviceId: String
) {
    var batteryOptimizationIgnored by remember { mutableStateOf(isBatteryOptimizationIgnored()) }

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        LinkCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Appearance", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                // One connected capsule, not three separate pills (those either scroll or need
                // to shrink to fit this card's width), with the active segment sliding smoothly
                // between positions instead of Material3's SegmentedButton abruptly flipping
                // each segment's own color with no shared animated element.
                SlidingSegmentedControl(
                    options = ThemeMode.entries,
                    selected = themeMode,
                    onSelect = onThemeModeChanged,
                    label = { mode ->
                        when (mode) {
                            ThemeMode.SYSTEM -> "System"
                            ThemeMode.LIGHT -> "Light"
                            ThemeMode.DARK -> "Dark"
                        }
                    },
                    icon = { mode ->
                        when (mode) {
                            ThemeMode.SYSTEM -> Icons.Filled.BrightnessAuto
                            ThemeMode.LIGHT -> Icons.Filled.LightMode
                            ThemeMode.DARK -> Icons.Filled.DarkMode
                        }
                    },
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        if (!batteryOptimizationIgnored) {
            LinkCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Battery optimization", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Some phones (especially Samsung) kill background apps aggressively, which can silently disconnect LinkToMac. Exempting it from battery optimization keeps the connection alive.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(12.dp))
                    PillButton(onClick = onRequestIgnoreBatteryOptimization) {
                        Text("Allow Background Activity")
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        LinkCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("About", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text("Version $appVersion", style = MaterialTheme.typography.bodySmall)
                Text("Device ID: $deviceId", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
