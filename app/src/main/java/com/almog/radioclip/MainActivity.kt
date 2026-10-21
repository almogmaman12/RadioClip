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

    private var syncService: RadioSyncService? by mutableStateOf(null)
    private var isBound by mutableStateOf(false)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as RadioSyncService.LocalBinder
            syncService = binder.getService()
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            syncService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            RadioClipTheme {
                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { permissions ->
                    val allGranted = permissions.values.all { it }
                    if (allGranted) {
                        startAndBindService()
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
                val history = currentService?.history?.collectAsState()?.value ?: emptyList()

                DashboardScreen(
                    bleState = bleState,
                    wifiState = wifiState,
                    history = history,
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