package com.almog.radioclip

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.almog.radioclip.model.Payload
import com.almog.radioclip.model.TransportState
import com.almog.radioclip.service.RadioSyncService
import com.almog.radioclip.ui.DashboardScreen
import com.almog.radioclip.ui.theme.RadioClipTheme

class MainActivity : ComponentActivity() {

    private var syncService: RadioSyncService? by mutableStateOf(RadioSyncService.activeInstance)
    private var isBound by mutableStateOf(false)
    private var pendingFilePayload: Payload.File? = null
    private var pendingSharedText: String? = null
    private var windowCurrentlyFocused = false
    private var activityResumed = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as RadioSyncService.LocalBinder
            syncService = binder.getService()
            isBound = true
            syncService?.setWindowFocused(activityResumed && windowCurrentlyFocused)
            pendingSharedText?.let { shared ->
                syncService?.sendPayload(Payload.Text(content = shared))
                pendingSharedText = null
            }
            pendingFilePayload?.let { file ->
                syncService?.sendPayload(file)
                pendingFilePayload = null
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            syncService = RadioSyncService.activeInstance
            isBound = false
        }
    }

    private var triggerFilePicker by mutableStateOf(false)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleExternalIntent(intent)
    }

    private fun handleExternalIntent(intent: Intent?) {
        if (intent?.action == "ACTION_SEND_FILE_FROM_NOTIFICATION") {
            triggerFilePicker = true
            return
        }

        // Sharesheet is the supported way to send from other apps without
        // bypassing Android's clipboard privacy protections.
        val shared = when (intent?.action) {
            Intent.ACTION_SEND -> if (intent.type?.startsWith("text/") == true) {
                intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            } else null
            Intent.ACTION_PROCESS_TEXT ->
                intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            else -> null
        } ?: return
        if (shared.isBlank()) return
        if (shared.toByteArray(Charsets.UTF_8).size > 1024 * 1024) {
            android.widget.Toast.makeText(
                this, "Shared text is too large to send", android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }
        if (syncService != null) {
            syncService?.sendPayload(Payload.Text(content = shared))
        } else {
            pendingSharedText = shared
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        windowCurrentlyFocused = hasFocus
        syncService?.setWindowFocused(activityResumed && hasFocus)
    }

    override fun onPause() {
        activityResumed = false
        syncService?.setWindowFocused(false)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        if (windowCurrentlyFocused) syncService?.setWindowFocused(true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        handleExternalIntent(intent)

        setContent {
            // The interface is English-only for now; the system's Hebrew RTL
            // setting must not reverse its buttons and text layout.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            RadioClipTheme {
                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { permissions ->
                    // Notification permission is optional for starting an Android 13+ service.
                    val allGranted = permissions
                        .filterKeys { it != Manifest.permission.POST_NOTIFICATIONS }
                        .values.all { it }
                    if (allGranted) {
                        startAndBindService()
                    }
                }

                // File picker launcher that can be triggered from UI or from notification action
                val filePickerLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument()
                ) { uri: android.net.Uri? ->
                    triggerFilePicker = false
                    uri?.let {
                        try {
                            contentResolver.takePersistableUriPermission(
                                it, Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        } catch (_: SecurityException) {
                            // Some document providers do not grant persistence.
                        }
                        val (name, size) = queryFileMeta(this, it)
                        val mime = contentResolver.getType(it) ?: "application/octet-stream"
                        val chosenFile = Payload.File(
                            fileName = name,
                            mimeType = mime,
                            sizeBytes = size,
                            uriString = it.toString()
                        )
                        if (syncService != null) {
                            syncService?.sendPayload(chosenFile)
                        } else {
                            pendingFilePayload = chosenFile
                        }
                    }
                }

                androidx.compose.runtime.LaunchedEffect(triggerFilePicker) {
                    if (triggerFilePicker) {
                        filePickerLauncher.launch(arrayOf("*/*"))
                    }
                }

                DisposableEffect(Unit) {
                    checkAndRequestPermissions { permissionsToRequest ->
                        if (permissionsToRequest.isEmpty()) {
                            startAndBindService()
                        } else {
                            permissionLauncher.launch(permissionsToRequest.toTypedArray())
                        }
                    }

                    onDispose {
                        if (isBound) {
                            unbindService(serviceConnection)
                            isBound = false
                        }
                    }
                }

                val currentService = syncService
                val bleState = currentService?.bleEngine?.state?.collectAsState()?.value
                    ?: TransportState.IDLE
                val wifiState = currentService?.wifiEngine?.state?.collectAsState()?.value
                    ?: TransportState.IDLE
                val discoveredBlePeers = currentService?.bleEngine?.discoveredPeers?.collectAsState()?.value ?: emptyList()
                val discoveredWifiPeers = currentService?.wifiEngine?.discoveredPeers?.collectAsState()?.value ?: emptyList()
                val connectedBlePeers = currentService?.bleEngine?.connectedPeers?.collectAsState()?.value ?: emptyList()
                val connectedWifiPeers = currentService?.wifiEngine?.connectedPeers?.collectAsState()?.value ?: emptyList()
                val history = currentService?.history?.collectAsState()?.value ?: emptyList()
                val clipboardSyncEnabled =
                    currentService?.clipboardSyncEnabled?.collectAsState()?.value ?: false
                val clipboardSyncStatus =
                    currentService?.clipboardSyncStatus?.collectAsState()?.value ?: "Off"
                val pairingConfigured =
                    currentService?.secureLink?.configured?.collectAsState()?.value ?: false
                val pairingError =
                    currentService?.pairingError?.collectAsState()?.value
                val transferError =
                    currentService?.transferError?.collectAsState()?.value

                DashboardScreen(
                    bleState = bleState,
                    wifiState = wifiState,
                    discoveredBlePeers = discoveredBlePeers,
                    discoveredWifiPeers = discoveredWifiPeers,
                    connectedBlePeers = connectedBlePeers,
                    connectedWifiPeers = connectedWifiPeers,
                    history = history,
                    clipboardSyncEnabled = clipboardSyncEnabled,
                    clipboardSyncStatus = clipboardSyncStatus,
                    onClipboardSyncChange = { currentService?.setClipboardSyncEnabled(it) },
                    onSyncClipboardNow = { currentService?.syncClipboardNow() },
                    pairingConfigured = pairingConfigured,
                    pairingError = pairingError,
                    transferError = transferError,
                    onClearTransferError = { currentService?.clearTransferError() },
                    onPairingPhrase = { currentService?.setPairingPhrase(it) },
                    onForgetPairing = { currentService?.forgetPairingPhrase() },
                    onConnectPeer = { transportType, peerId ->
                        currentService?.connectToPeer(transportType, peerId)
                    },
                    onSendText = { text ->
                        currentService?.sendPayload(Payload.Text(content = text))
                    },
                    onSendFile = { name, mime, size, uri ->
                        currentService?.sendPayload(
                            Payload.File(
                                fileName = name,
                                mimeType = mime,
                                sizeBytes = size,
                                uriString = uri
                            )
                        )
                    },
                    onRequestPermissions = {
                        checkAndRequestPermissions { permissionsToRequest ->
                            if (permissionsToRequest.isNotEmpty()) {
                                permissionLauncher.launch(permissionsToRequest.toTypedArray())
                            }
                        }
                    }
                )
            }
            }
        }
    }

    private fun queryFileMeta(context: Context, uri: android.net.Uri): Pair<String, Long> {
        var name = "unknown_file"
        var size = 0L
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex != -1) name = cursor.getString(nameIndex) ?: name
                if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
            }
        }
        return Pair(name, size)
    }

    private fun startAndBindService() {
        RadioSyncService.start(this)
        val intent = Intent(this, RadioSyncService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun checkAndRequestPermissions(onReady: (List<String>) -> Unit) {
        val requiredPermissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)

        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        onReady(missing)
    }
}