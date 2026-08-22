package com.linktomac.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import com.linktomac.storage.SyncCategory
import com.linktomac.ui.components.AppHeader
import com.linktomac.ui.components.InfoCard
import com.linktomac.ui.components.LinkCard
import com.linktomac.ui.components.ListItemCard
import com.linktomac.ui.components.PillButton
import com.linktomac.ui.components.PillOutlinedButton
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
    syncOptions: () -> Map<SyncCategory, Boolean>,
    onSyncOptionChanged: (SyncCategory, Boolean) -> Unit
) {
    var notificationAccessGranted by remember { mutableStateOf(isNotificationAccessGranted()) }
    var callsAndMessagesAccessGranted by remember { mutableStateOf(isCallsAndMessagesAccessGranted()) }
    var photoAccessGranted by remember { mutableStateOf(isPhotoAccessGranted()) }
    var accessibilityServiceEnabled by remember { mutableStateOf(isAccessibilityServiceEnabled()) }
    var fileAccessGranted by remember { mutableStateOf(isFileAccessGranted()) }
    var paired by remember { mutableStateOf(isPaired()) }
    var macName by remember { mutableStateOf(pairedMacName()) }
    var connectionState by remember { mutableStateOf<ConnectionState>(ConnectionState.Idle) }
    var showSyncOptions by remember { mutableStateOf(false) }

    // Otherwise the system Back button falls through to the Activity and closes the app instead
    // of returning from Sync Options to the main Device screen — same reasoning as NotesScreen's
    // BackHandler. Only active while the sub-screen is actually showing, so back behaves normally
    // (exits, same as always) everywhere else on this screen.
    BackHandler(enabled = showSyncOptions) { showSyncOptions = false }

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

    AnimatedContent(
        targetState = showSyncOptions,
        label = "deviceScreen",
        transitionSpec = {
            if (targetState) {
                (slideInHorizontally(tween(220)) { it } + fadeIn(tween(220)))
                    .togetherWith(fadeOut(tween(140)))
            } else {
                fadeIn(tween(220))
                    .togetherWith(slideOutHorizontally(tween(220)) { it } + fadeOut(tween(140)))
            }
        }
    ) { optionsVisible ->
        if (optionsVisible) {
            DeviceSyncOptionsView(
                macName = macName ?: "Paired Mac",
                initialOptions = syncOptions(),
                onToggle = onSyncOptionChanged,
                onBack = { showSyncOptions = false }
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                HeroCard(paired = paired, connectionState = connectionState)

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
                        onReconnect = onReconnect,
                        onDisconnect = onDisconnect,
                        onForget = {
                            paired = false
                            onForgetDevice()
                        },
                        onOpenSyncOptions = { showSyncOptions = true }
                    )
                } else {
                    LinkCard(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                "No Mac paired yet. Scan a QR code from your Mac to get started.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (connectionState is ConnectionState.Failed) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Couldn't pair: ${(connectionState as ConnectionState.Failed).message}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            } else if (connectionState is ConnectionState.Connecting) {
                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Connecting…", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            PillButton(onClick = onScanRequested) {
                                Icon(Icons.Filled.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Scan Pairing Code")
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
                        icon = Icons.Filled.Notifications,
                        title = "Notification access needed",
                        bullets = listOf(
                            "Reads incoming notifications.",
                            "Mirrors them to your Mac in real time.",
                        ),
                        actionLabel = "Open Settings",
                        onAction = onRequestNotificationAccess
                    )
                    PermissionCard(
                        visible = !callsAndMessagesAccessGranted,
                        icon = Icons.Filled.Call,
                        title = "Call & message access needed",
                        bullets = listOf(
                            "Reads your call log and messages.",
                            "Shows them on your Mac.",
                            "Sends texts on your behalf when you reply there.",
                        ),
                        actionLabel = "Grant Access",
                        onAction = onRequestCallsAndMessagesAccess
                    )
                    PermissionCard(
                        visible = !photoAccessGranted,
                        icon = Icons.Filled.Photo,
                        title = "Photo access needed",
                        bullets = listOf(
                            "Reads your photos.",
                            "Lets you browse them from your Mac.",
                        ),
                        actionLabel = "Grant Access",
                        onAction = onRequestPhotoAccess
                    )
                    PermissionCard(
                        visible = !accessibilityServiceEnabled,
                        icon = Icons.Filled.TouchApp,
                        title = "Screen mirroring access needed",
                        bullets = listOf(
                            "Needs Accessibility access to work.",
                            "Lets your Mac tap, swipe, and type on your phone during mirroring.",
                            "Find LinkToMac in the list and turn it on.",
                        ),
                        actionLabel = "Open Settings",
                        onAction = onRequestAccessibilityAccess
                    )
                    PermissionCard(
                        visible = !fileAccessGranted,
                        icon = Icons.Filled.Folder,
                        title = "File access needed",
                        bullets = listOf(
                            "Accesses your phone's storage.",
                            "Lets you browse and transfer files from your Mac.",
                        ),
                        actionLabel = "Grant Access",
                        onAction = onRequestFileAccess
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun HeroCard(paired: Boolean, connectionState: ConnectionState) {
    // Left-aligned branding header (was centered) — frees vertical space for the real content
    // below (paired-device card, permission cards) instead of the icon/title eating the top
    // third of the screen, matching the reference design's compact home-screen header.
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        AppHeader(
            leadingIcon = Icons.Filled.Smartphone,
            title = "LinkToMac",
            subtitle = "Mirror notifications, calls, and files with your Mac.",
        )
        Spacer(Modifier.height(16.dp))
        StatusPill(paired = paired, connectionState = connectionState)
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
private fun PairedDeviceRow(
    macName: String,
    connectionState: ConnectionState,
    onReconnect: () -> Unit,
    onDisconnect: () -> Unit,
    onForget: () -> Unit,
    onOpenSyncOptions: () -> Unit
) {
    val connected = connectionState is ConnectionState.Connected
    val busy = connectionState is ConnectionState.Connected || connectionState is ConnectionState.Connecting
    ListItemCard(
        onClick = onOpenSyncOptions,
        leading = {
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
        },
        title = macName,
        subtitle = pairedStatusText(connectionState),
        subtitleColor = pairedStatusColor(connectionState, MaterialTheme.colorScheme),
        trailingTop = {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Sync options",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (busy) {
                PillButton(onClick = onDisconnect) { Text("Disconnect") }
            } else {
                PillButton(onClick = onReconnect) { Text("Reconnect") }
            }
            PillOutlinedButton(
                onClick = onForget,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text("Forget This Mac")
            }
        }
    }
}

@Composable
private fun DeviceSyncOptionsView(
    macName: String,
    initialOptions: Map<SyncCategory, Boolean>,
    onToggle: (SyncCategory, Boolean) -> Unit,
    onBack: () -> Unit
) {
    var options by remember { mutableStateOf(initialOptions) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Spacer(Modifier.width(4.dp))
            Column {
                Text("Sync Options", style = MaterialTheme.typography.titleLarge)
                Text(macName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(20.dp))

        Column(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SyncCategory.entries.forEach { category ->
                SyncOptionRow(
                    category = category,
                    enabled = options[category] ?: true,
                    onToggle = { newValue ->
                        options = options + (category to newValue)
                        onToggle(category, newValue)
                    }
                )
            }
        }
    }
}

@Composable
private fun SyncOptionRow(category: SyncCategory, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val (icon, label, description) = syncCategoryDisplay(category)
    ListItemCard(
        leading = {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        title = label,
        subtitle = description,
        trailingTop = { Switch(checked = enabled, onCheckedChange = onToggle) },
    )
}

private data class SyncCategoryDisplay(val icon: ImageVector, val label: String, val description: String)

private fun syncCategoryDisplay(category: SyncCategory): SyncCategoryDisplay = when (category) {
    SyncCategory.NOTIFICATIONS -> SyncCategoryDisplay(
        Icons.Filled.Notifications, "Notifications", "Mirror phone notifications to your Mac."
    )
    SyncCategory.CALLS_AND_MESSAGES -> SyncCategoryDisplay(
        Icons.Filled.Call, "Calls & Messages", "Show your call log and texts on your Mac."
    )
    SyncCategory.CONTACTS -> SyncCategoryDisplay(
        Icons.Filled.Contacts, "Contacts", "Keep contacts in sync with your Mac."
    )
    SyncCategory.PHOTOS -> SyncCategoryDisplay(
        Icons.Filled.Photo, "Photos", "Let your Mac browse new photos as they're taken."
    )
    SyncCategory.NOTES -> SyncCategoryDisplay(
        Icons.AutoMirrored.Filled.Notes, "Notes", "Sync notes between this phone and your Mac."
    )
    SyncCategory.CLIPBOARD -> SyncCategoryDisplay(
        Icons.Filled.ContentPaste, "Clipboard", "Share copied text with your Mac, and apply text copied there."
    )
}

@Composable
private fun PermissionCard(
    visible: Boolean,
    icon: ImageVector,
    title: String,
    bullets: List<String>,
    actionLabel: String,
    onAction: () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically(initialOffsetY = { -it / 4 }),
        exit = fadeOut() + shrinkVertically()
    ) {
        Column {
            InfoCard(
                icon = icon,
                title = title,
                bullets = bullets,
                footer = { PillButton(onClick = onAction) { Text(actionLabel) } },
            )
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
