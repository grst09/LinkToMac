package com.linktomac.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.linktomac.net.ConnectionState
import com.linktomac.service.SyncForegroundService
import com.linktomac.ui.theme.AccentBlue
import com.linktomac.ui.theme.AccentBlueContainerDark
import com.linktomac.ui.theme.AccentBlueContainerLight
import com.linktomac.ui.theme.AccentViolet
import com.linktomac.ui.theme.AccentVioletContainerDark
import com.linktomac.ui.theme.AccentVioletContainerLight
import com.linktomac.ui.theme.LinkGreen
import com.linktomac.ui.theme.LinkGreenContainerDark
import com.linktomac.ui.theme.LinkGreenContainerLight
import kotlinx.coroutines.delay

@Composable
fun PairingScreen(
    isNotificationAccessGranted: () -> Boolean,
    onRequestNotificationAccess: () -> Unit,
    isCallsAndMessagesAccessGranted: () -> Boolean,
    onRequestCallsAndMessagesAccess: () -> Unit,
    isPhotoAccessGranted: () -> Boolean,
    onRequestPhotoAccess: () -> Unit,
    isAccessibilityServiceEnabled: () -> Boolean,
    onRequestAccessibilityAccess: () -> Unit,
    isFileAccessGranted: () -> Boolean,
    onRequestFileAccess: () -> Unit,
    isPaired: () -> Boolean,
    pairedMacName: () -> String?,
    onReconnect: () -> Unit,
    onDisconnect: () -> Unit,
    onForgetDevice: () -> Unit,
    onScanRequested: () -> Unit,
    onStartService: () -> Unit,
    onNavigateToNotes: () -> Unit,
    onNavigateToSettings: () -> Unit,
    darkTheme: Boolean
) {
    var notificationAccessGranted by remember { mutableStateOf(isNotificationAccessGranted()) }
    var callsAndMessagesAccessGranted by remember { mutableStateOf(isCallsAndMessagesAccessGranted()) }
    var photoAccessGranted by remember { mutableStateOf(isPhotoAccessGranted()) }
    var accessibilityServiceEnabled by remember { mutableStateOf(isAccessibilityServiceEnabled()) }
    var fileAccessGranted by remember { mutableStateOf(isFileAccessGranted()) }
    var paired by remember { mutableStateOf(isPaired()) }
    var macName by remember { mutableStateOf(pairedMacName()) }
    var connectionState by remember { mutableStateOf<ConnectionState>(ConnectionState.Idle) }
    var menuExpanded by remember { mutableStateOf(false) }

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
                accessibilityServiceEnabled = isAccessibilityServiceEnabled()
                fileAccessGranted = isFileAccessGranted()
                paired = isPaired()
                macName = pairedMacName()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        HeroCard(
            paired = paired,
            connectionState = connectionState,
            darkTheme = darkTheme,
            onPrimaryAction = if (paired) onReconnect else onScanRequested,
            onNavigateToNotes = onNavigateToNotes,
            onNavigateToSettings = onNavigateToSettings
        )

        Spacer(Modifier.height(24.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Laptop, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text("Paired Device", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    if (paired) "1" else "0",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        if (paired) {
            PairedDeviceRow(
                macName = macName ?: "Paired Mac",
                connectionState = connectionState,
                menuExpanded = menuExpanded,
                onMenuExpandedChange = { menuExpanded = it },
                onReconnect = { menuExpanded = false; onReconnect() },
                onDisconnect = { menuExpanded = false; onDisconnect() },
                onForget = {
                    menuExpanded = false
                    paired = false
                    onForgetDevice()
                }
            )
        } else {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "No Mac paired yet. Tap Pair above to scan a QR code from your Mac.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (connectionState is ConnectionState.Failed) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Couldn't pair: ${(connectionState as ConnectionState.Failed).message}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = onScanRequested) { Text("Try Again") }
                    } else if (connectionState is ConnectionState.Connecting) {
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Connecting…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        val anyPermissionMissing = !notificationAccessGranted || !callsAndMessagesAccessGranted ||
            !photoAccessGranted || !accessibilityServiceEnabled || !fileAccessGranted

        AnimatedVisibility(visible = anyPermissionMissing) {
            Column {
                Spacer(Modifier.height(24.dp))
                Text("Setup", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
            }
        }

        Column(modifier = Modifier.animateContentSize()) {
            PermissionCard(
                visible = !notificationAccessGranted,
                title = "Notification access needed",
                description = "LinkToMac needs permission to read notifications so it can mirror them to your Mac.",
                actionLabel = "Open Settings",
                onAction = onRequestNotificationAccess
            )
            PermissionCard(
                visible = !callsAndMessagesAccessGranted,
                title = "Call & message access needed",
                description = "LinkToMac needs permission to read your call log and messages so it can show them on your Mac, and to send texts on your behalf when you reply from there.",
                actionLabel = "Grant Access",
                onAction = onRequestCallsAndMessagesAccess
            )
            PermissionCard(
                visible = !photoAccessGranted,
                title = "Photo access needed",
                description = "LinkToMac needs permission to read your photos so you can browse them from your Mac.",
                actionLabel = "Grant Access",
                onAction = onRequestPhotoAccess
            )
            PermissionCard(
                visible = !accessibilityServiceEnabled,
                title = "Screen mirroring access needed",
                description = "To tap, swipe, and type on your phone from your Mac during screen mirroring, LinkToMac needs Accessibility access. Find LinkToMac in the list and turn it on.",
                actionLabel = "Open Settings",
                onAction = onRequestAccessibilityAccess
            )
            PermissionCard(
                visible = !fileAccessGranted,
                title = "File access needed",
                description = "LinkToMac needs access to your phone's storage so you can browse and transfer files from your Mac.",
                actionLabel = "Grant Access",
                onAction = onRequestFileAccess
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun HeroCard(
    paired: Boolean,
    connectionState: ConnectionState,
    darkTheme: Boolean,
    onPrimaryAction: () -> Unit,
    onNavigateToNotes: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    // Follows the app theme like every other surface — only the tone (surfaceContainerHigh)
    // is a step more elevated than the page background, so it still reads as a distinct "hero"
    // in both light and dark mode instead of just in one of them.
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Smartphone,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "LinkToMac",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Mirror notifications, calls, and files with your Mac.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            StatusPill(paired = paired, connectionState = connectionState)
            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                QuickActionTile(
                    label = if (paired) "Reconnect" else "Pair",
                    icon = if (paired) Icons.Filled.Refresh else Icons.Filled.QrCodeScanner,
                    accent = AccentBlue,
                    containerColor = if (darkTheme) AccentBlueContainerDark else AccentBlueContainerLight,
                    onClick = onPrimaryAction
                )
                QuickActionTile(
                    label = "Notes",
                    icon = Icons.AutoMirrored.Filled.Notes,
                    accent = LinkGreen,
                    containerColor = if (darkTheme) LinkGreenContainerDark else LinkGreenContainerLight,
                    onClick = onNavigateToNotes
                )
                QuickActionTile(
                    label = "Settings",
                    icon = Icons.Filled.Settings,
                    accent = AccentViolet,
                    containerColor = if (darkTheme) AccentVioletContainerDark else AccentVioletContainerLight,
                    onClick = onNavigateToSettings
                )
            }
        }
    }
}

@Composable
private fun StatusPill(paired: Boolean, connectionState: ConnectionState) {
    val colors = MaterialTheme.colorScheme
    val (text, dotColor) = when {
        !paired && connectionState is ConnectionState.Connecting -> "Connecting…" to colors.onSurfaceVariant
        !paired && connectionState is ConnectionState.Failed -> "Not Connected" to colors.error
        !paired -> "Not Paired" to colors.onSurfaceVariant
        connectionState is ConnectionState.Connected -> "Connected to ${connectionState.macDeviceName}" to colors.primary
        connectionState is ConnectionState.Connecting -> "Connecting…" to colors.onSurfaceVariant
        connectionState is ConnectionState.Failed -> "Disconnected" to colors.error
        else -> "Not Connected" to colors.onSurfaceVariant
    }
    val pulsing = connectionState is ConnectionState.Connecting
    val infiniteTransition = rememberInfiniteTransition(label = "statusPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "statusPulseAlpha"
    )

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(colors.surfaceContainerHighest)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor.copy(alpha = if (pulsing) pulseAlpha else 1f))
        )
        Spacer(Modifier.width(8.dp))
        AnimatedContent(targetState = text, label = "statusText") { label ->
            Text(label, style = MaterialTheme.typography.labelLarge, color = colors.onSurface)
        }
    }
}

@Composable
private fun QuickActionTile(
    label: String,
    icon: ImageVector,
    accent: Color,
    containerColor: Color,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(containerColor)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = label, tint = accent)
        }
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = accent)
    }
}

@Composable
private fun PairedDeviceRow(
    macName: String,
    connectionState: ConnectionState,
    menuExpanded: Boolean,
    onMenuExpandedChange: (Boolean) -> Unit,
    onReconnect: () -> Unit,
    onDisconnect: () -> Unit,
    onForget: () -> Unit
) {
    val connected = connectionState is ConnectionState.Connected
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        if (connected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (connected) Icons.Filled.Check else Icons.Filled.Laptop,
                    contentDescription = null,
                    tint = if (connected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(macName, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    pairedStatusText(connectionState),
                    style = MaterialTheme.typography.bodySmall,
                    color = pairedStatusColor(connectionState, MaterialTheme.colorScheme)
                )
            }
            Box {
                IconButton(onClick = { onMenuExpandedChange(true) }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Device actions")
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { onMenuExpandedChange(false) }) {
                    if (connectionState is ConnectionState.Connected || connectionState is ConnectionState.Connecting) {
                        DropdownMenuItem(text = { Text("Disconnect") }, onClick = onDisconnect)
                    } else {
                        DropdownMenuItem(text = { Text("Reconnect") }, onClick = onReconnect)
                    }
                    DropdownMenuItem(text = { Text("Forget This Mac") }, onClick = onForget)
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(
    visible: Boolean,
    title: String,
    description: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically(initialOffsetY = { -it / 4 }),
        exit = fadeOut() + shrinkVertically()
    ) {
        Column {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(description, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = onAction) { Text(actionLabel) }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun pairedStatusText(state: ConnectionState): String = when (state) {
    is ConnectionState.Connected -> "Connected"
    ConnectionState.Connecting -> "Connecting…"
    is ConnectionState.Failed -> "Disconnected — ${state.message}"
    ConnectionState.Idle -> "Not connected"
}

private fun pairedStatusColor(state: ConnectionState, colors: ColorScheme): Color = when (state) {
    is ConnectionState.Connected -> colors.primary
    ConnectionState.Connecting -> colors.onSurfaceVariant
    is ConnectionState.Failed -> colors.error
    ConnectionState.Idle -> colors.onSurfaceVariant
}
