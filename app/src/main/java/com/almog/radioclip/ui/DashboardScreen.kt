package com.almog.radioclip.ui

import android.content.Context
import android.content.ClipData
import android.content.Intent
import android.content.ClipboardManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.painterResource
import com.almog.radioclip.R
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.input.VisualTransformation
import java.security.SecureRandom
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.almog.radioclip.model.Payload
import com.almog.radioclip.model.PeerDevice
import com.almog.radioclip.model.TransportState
import com.almog.radioclip.model.TransportType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    bleState: TransportState,
    wifiState: TransportState,
    discoveredBlePeers: List<PeerDevice>,
    discoveredWifiPeers: List<PeerDevice>,
    connectedBlePeers: List<PeerDevice>,
    connectedWifiPeers: List<PeerDevice>,
    history: List<Payload>,
    clipboardSyncEnabled: Boolean,
    clipboardSyncStatus: String,
    onClipboardSyncChange: (Boolean) -> Unit,
    onSyncClipboardNow: () -> Unit,
    pairingConfigured: Boolean,
    pairingError: String?,
    onPairingPhrase: (String) -> Unit,
    onForgetPairing: () -> Unit,
    onConnectPeer: (transportType: TransportType, peerId: String) -> Unit,
    onSendText: (String) -> Unit,
    onSendFile: (name: String, mimeType: String, sizeBytes: Long, uriString: String) -> Unit,
    onRequestPermissions: () -> Unit
) {
    val context = LocalContext.current
    var textInput by remember { mutableStateOf("") }
    var pairingPhrase by remember { mutableStateOf("") }
    var revealPairing by remember { mutableStateOf(false) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            try {
                context.contentResolver.takePersistableUriPermission(
                    it, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Some document providers cannot persist URI access.
            }
            val (name, size) = queryFileMeta(context, it)
            val mime = context.contentResolver.getType(it) ?: "application/octet-stream"
            onSendFile(name, mime, size, it.toString())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(id = R.drawable.app_logo),
                            contentDescription = "RadioClip Logo",
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("RadioClip", fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    color = MaterialTheme.colorScheme.tertiaryContainer,
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = "BETA",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Text(
                                "Offline P2P Clipboard & File Sync",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                actions = {
                    FilledTonalButton(
                        onClick = onRequestPermissions,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text("Perms")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Each phone must have the same locally entered passphrase.
            // Without it, no payloads are accepted or transmitted.
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Secure connection", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold)
                        Text(
                            if (pairingConfigured) "Pairing phrase is configured"
                            else "Enter the same private phrase on both devices before transferring.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        OutlinedTextField(
                            value = pairingPhrase,
                            onValueChange = { pairingPhrase = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = if (revealPairing) VisualTransformation.None
                                else PasswordVisualTransformation(),
                            label = { Text(if (pairingConfigured) "Change pairing phrase" else "Pairing phrase") },
                            placeholder = { Text("5+ characters, 12+ recommended") }
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                enabled = pairingPhrase.length >= 5,
                                onClick = {
                                    onPairingPhrase(pairingPhrase)
                                    pairingPhrase = ""
                                    revealPairing = false
                                }
                            ) {
                                Text(if (pairingConfigured) "Update code" else "Save code")
                            }
                            OutlinedButton(
                                onClick = {
                                    val letters = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
                                    val random = SecureRandom()
                                    pairingPhrase = (1..10)
                                        .map { letters[random.nextInt(letters.length)] }
                                        .joinToString("")
                                    revealPairing = true
                                }
                            ) { Text("Generate") }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { revealPairing = !revealPairing }) {
                                Text(if (revealPairing) "Hide code" else "Show code")
                            }
                            if (pairingConfigured) {
                                TextButton(onClick = onForgetPairing) { Text("Forget saved code") }
                            }
                        }
                        pairingError?.let { error ->
                            Text(error, color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            "Both phones must use exactly the same code. " +
                                "A 5-character code is convenient but much easier to guess. " +
                                "Use 12 or more random characters for sensitive transfers.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            // Transport Status Cards
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StatusCard(
                        modifier = Modifier.weight(1f),
                        title = "BLE GATT",
                        subtitle = "Text <= 20 KB",
                        state = bleState,
                        connectedCount = connectedBlePeers.size
                    )
                    StatusCard(
                        modifier = Modifier.weight(1f),
                        title = "Wi-Fi Direct",
                        subtitle = "Files & Large Payloads",
                        state = wifiState,
                        connectedCount = connectedWifiPeers.size
                    )
                }
            }

            // Keep connected peers visible when their advertisements become stale.
            // Nearby results are deduplicated by RadioClip session ID, not MAC.
            item {
                val allBlePeers = buildList {
                    addAll(discoveredBlePeers)
                    connectedBlePeers.forEach { connected ->
                        if (discoveredBlePeers.none { it.id == connected.id }) {
                            add(connected)
                        }
                    }
                }
                PeerSectionCard(
                    title = "Bluetooth Peers (BLE)",
                    peers = allBlePeers,
                    connectedPeers = connectedBlePeers,
                    emptyText = "Scanning for nearby RadioClip BLE devices...",
                    transportType = TransportType.BLE_GATT,
                    onConnect = { onConnectPeer(TransportType.BLE_GATT, it) }
                )
            }

            // Wi-Fi Direct Peers Section
            item {
                // Once a P2P group forms, Android stops returning connected devices in scan
                // results. Merge connectedWifiPeers into the displayed list so CONNECTED peers
                // stay visible even after discovery clears them from discoveredWifiPeers.
                val allWifiPeers = buildList {
                    addAll(discoveredWifiPeers)
                    connectedWifiPeers.forEach { connected ->
                        if (discoveredWifiPeers.none { it.id == connected.id }) {
                            add(connected)
                        }
                    }
                }
                PeerSectionCard(
                    title = "Wi-Fi Direct Peers",
                    peers = allWifiPeers,
                    connectedPeers = connectedWifiPeers,
                    emptyText = "Searching for nearby devices advertising RadioClip.\nInstall and open RadioClip on the other device, and enable Wi-Fi and Location.",
                    transportType = TransportType.WIFI_DIRECT_SOCKET,
                    onConnect = { onConnectPeer(TransportType.WIFI_DIRECT_SOCKET, it) }
                )
            }

            // Android restricts background clipboard reads. Require opt-in.
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Auto-sync when RadioClip is open", fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (clipboardSyncEnabled) "On" else "Off",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Switch(
                                checked = clipboardSyncEnabled,
                                onCheckedChange = onClipboardSyncChange,
                                enabled = pairingConfigured
                            )
                        }
                        Text(
                            clipboardSyncStatus,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (clipboardSyncStatus.startsWith("Not delivered") ||
                                clipboardSyncStatus.startsWith("Unable") ||
                                clipboardSyncStatus.startsWith("Clipboard access denied")
                            ) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(
                            enabled = pairingConfigured,
                            onClick = onSyncClipboardNow
                        ) {
                            Text("Send current clipboard")
                        }
                        Text(
                            "Copy text in another app, return to RadioClip, then tap " +
                                "Send current clipboard. Auto-sync only works while this app " +
                                "has focus. For instant sending use Share → RadioClip.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Quick Send Clipboard Text Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Broadcast Clipboard / Text",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = textInput,
                            onValueChange = { textInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Paste clipboard text or type message...") },
                            maxLines = 4
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${textInput.toByteArray(Charsets.UTF_8).size} bytes",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(
                                    onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                                    enabled = pairingConfigured
                                ) {
                                    Text("Pick File")
                                }
                                Button(
                                    onClick = {
                                        if (textInput.isNotBlank()) {
                                            onSendText(textInput)
                                            textInput = ""
                                        }
                                    },
                                    enabled = pairingConfigured && textInput.isNotBlank()
                                ) {
                                    Text("Send")
                                }
                            }
                        }
                    }
                }
            }

            // Activity History Title
            item {
                Text(
                    "Activity History (${history.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (history.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No transfers yet.\nConnect a peer, then send text or a file.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            } else {
                items(history, key = { it.id }) { payload ->
                    PayloadItem(payload = payload)
                }
            }
        }
    }
}

@Composable
fun PeerSectionCard(
    title: String,
    peers: List<PeerDevice>,
    connectedPeers: List<PeerDevice>,
    emptyText: String,
    transportType: TransportType,
    onConnect: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "${peers.size} found",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (peers.isEmpty()) {
                Text(
                    emptyText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    peers.forEach { peer ->
                        val isConnected = connectedPeers.any { it.id == peer.id }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.surfaceContainerLow,
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = peer.name,
                                    fontWeight = FontWeight.Medium,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = if (transportType == TransportType.BLE_GATT &&
                                        peer.id.length == 36
                                    ) {
                                        "RadioClip ID: ${peer.id.takeLast(8).uppercase()}"
                                    } else peer.id,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            if (isConnected) {
                                Surface(
                                    color = Color(0xFF2E7D32).copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        "CONNECTED",
                                        color = Color(0xFF2E7D32),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { onConnect(peer.id) },
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Connect", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StatusCard(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    state: TransportState,
    connectedCount: Int = 0
) {
    val statusColor = when (state) {
        TransportState.CONNECTED -> Color(0xFF2E7D32)
        TransportState.SCANNING, TransportState.ADVERTISING, TransportState.CONNECTING -> Color(0xFFED6C02)
        TransportState.ERROR -> Color(0xFFD32F2F)
        TransportState.IDLE, TransportState.DISCONNECTED -> Color(0xFF757575)
    }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
            }
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    color = statusColor.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = state.name,
                        color = statusColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                if (connectedCount > 0) {
                    Text(
                        text = "$connectedCount peer(s)",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF2E7D32)
                    )
                }
            }
        }
    }
}

@Composable
fun PayloadItem(payload: Payload) {
    val timeFormatted = remember(payload.timestamp) {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(payload.timestamp))
    }

    val badgeColor = if (payload.transportType == TransportType.BLE_GATT) Color(0xFF1565C0) else Color(0xFF6A1B9A)
    val badgeText = if (payload.transportType == TransportType.BLE_GATT) "BLE GATT" else "WI-FI DIRECT"
    val context = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(
                        color = badgeColor.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = badgeText,
                            color = badgeColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    // Direction badge (Sent vs Received)
                    val dirColor = when {
                        payload.isFailed -> Color(0xFFD32F2F)
                        payload.isOutgoing -> Color(0xFF2E7D32)
                        else -> Color(0xFFE65100)
                    }
                    Surface(
                        color = dirColor.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = when {
                                payload.isFailed -> "FAILED"
                                payload.isOutgoing -> "SENT"
                                else -> "RECEIVED"
                            },
                            color = dirColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    Text(
                        text = if (payload is Payload.Text) "CLIPBOARD" else "FILE",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = timeFormatted,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Sender / Device details
            val deviceLabel = when {
                payload.isOutgoing -> "From: This Device"
                payload.senderDeviceName != null -> "From: ${payload.senderDeviceName}"
                else -> "From: Nearby Peer"
            }
            Text(
                text = deviceLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.height(6.dp))
            when (payload) {
                is Payload.Text -> {
                    Text(
                        text = payload.content,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace
                    )
                    if (!payload.isOutgoing) {
                        OutlinedButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                    as? ClipboardManager
                                clipboard?.setPrimaryClip(
                                    ClipData.newPlainText("RadioClip", payload.content)
                                )
                            }
                        ) {
                            Text("Copy text")
                        }
                    }
                }
                is Payload.File -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = payload.fileName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "${payload.mimeType} • ${(payload.sizeBytes / 1024.0).toInt()} KB",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Only received files already saved locally are available to open.
                        FilledTonalButton(
                            onClick = {
                                openFileIntent(context, payload)
                            },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Open")
                        }
                    }
                }
            }
        }
    }
}

private fun openFileIntent(context: Context, filePayload: Payload.File) {
    try {
        val targetUri = when {
            filePayload.localPath != null -> {
                val file = java.io.File(filePayload.localPath)
                if (file.exists()) {
                    androidx.core.content.FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )
                } else null
            }
            filePayload.uriString != null -> Uri.parse(filePayload.uriString)
            else -> null
        }

        if (targetUri != null) {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(targetUri, filePayload.mimeType.ifBlank { "*/*" })
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(android.content.Intent.createChooser(intent, "Open with...").apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, "Could not open file: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
    }
}

private fun queryFileMeta(context: Context, uri: Uri): Pair<String, Long> {
    var name = "unknown_file"
    var size = 0L
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (cursor.moveToFirst()) {
            if (nameIndex != -1) name = cursor.getString(nameIndex) ?: name
            if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
        }
    }
    return Pair(name, size)
}
