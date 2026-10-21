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
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
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
    private var bindRequested = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as RadioSyncService.LocalBinder
            syncService = binder.getService()
            isBound = true
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
        if (intent.action == "ACTION_SEND_FILE_FROM_NOTIFICATION") {
            triggerFilePicker = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (intent?.action == "ACTION_SEND_FILE_FROM_NOTIFICATION") {
            triggerFilePicker = true
        }

        setContent {
            RadioClipTheme {
                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { _ ->
                    // Start even if some permissions were denied (for example notifications): each
                    // transport checks what it needs and the service retries when asked again.
                    startAndBindService()
                }

                // File picker launcher that can be triggered from UI or from notification action
                val filePickerLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.GetContent()
                ) { uri: android.net.Uri? ->
                    triggerFilePicker = false
                    uri?.let {
                        val (name, size) = queryFileMeta(this, it)
                        val mime = contentResolver.getType(it) ?: "application/octet-stream"
                        syncService?.sendPayload(
                            Payload.File(
                                fileName = name,
                                mimeType = mime,
                                sizeBytes = size,
                                uriString = it.toString()
                            )
                        )
                    }
                }

                androidx.compose.runtime.LaunchedEffect(triggerFilePicker) {
                    if (triggerFilePicker) {
                        filePickerLauncher.launch("*/*")
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
                        if (bindRequested) {
                            unbindService(serviceConnection)
                            bindRequested = false
                            isBound = false
                        }
                    }
                }

                val currentService = syncService

                androidx.compose.runtime.LaunchedEffect(currentService) {
                    currentService?.messages?.collect { message ->
                        Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                    }
                }
                val bleState = currentService?.bleEngine?.state?.collectAsState()?.value
                    ?: TransportState.IDLE
                val wifiState = currentService?.wifiEngine?.state?.collectAsState()?.value
                    ?: TransportState.IDLE
                val discoveredBlePeers = currentService?.bleEngine?.discoveredPeers?.collectAsState()?.value ?: emptyList()
                val discoveredWifiPeers = currentService?.wifiEngine?.discoveredPeers?.collectAsState()?.value ?: emptyList()
                val connectedBlePeers = currentService?.bleEngine?.connectedPeers?.collectAsState()?.value ?: emptyList()
                val connectedWifiPeers = currentService?.wifiEngine?.connectedPeers?.collectAsState()?.value ?: emptyList()
                val history = currentService?.history?.collectAsState()?.value ?: emptyList()
                val isPaired = currentService?.pairing?.isPaired?.collectAsState()?.value ?: false

                DashboardScreen(
                    isPaired = isPaired,
                    onSetPairingCode = { code -> currentService?.setPairingCode(code) },
                    onClearPairing = { currentService?.clearPairing() },
                    bleState = bleState,
                    wifiState = wifiState,
                    discoveredBlePeers = discoveredBlePeers,
                    discoveredWifiPeers = discoveredWifiPeers,
                    connectedBlePeers = connectedBlePeers,
                    connectedWifiPeers = connectedWifiPeers,
                    history = history,
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
        if (!bindRequested) {
            val intent = Intent(this, RadioSyncService::class.java)
            bindRequested = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
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