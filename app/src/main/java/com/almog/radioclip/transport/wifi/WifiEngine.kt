package com.almog.radioclip.transport.wifi

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.Uri
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.util.Log
import com.almog.radioclip.model.Payload
import com.almog.radioclip.model.PeerDevice
import com.almog.radioclip.model.TransportState
import com.almog.radioclip.model.TransportType
import com.almog.radioclip.transport.DeviceTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

@SuppressLint("MissingPermission")
class WifiEngine(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + Job())
) : DeviceTransport {

    companion object {
        private const val TAG = "WifiEngine"
        const val PORT = 8888
        private const val SOCKET_TIMEOUT_MS = 10000

        // Stream wire protocol tags
        private const val TYPE_TEXT: Byte = 1
        private const val TYPE_FILE: Byte = 2
    }

    override val transportType: TransportType = TransportType.WIFI_DIRECT_SOCKET

    private val _state = MutableStateFlow(TransportState.IDLE)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    private val _connectedPeers = MutableStateFlow<List<PeerDevice>>(emptyList())
    override val connectedPeers: StateFlow<List<PeerDevice>> = _connectedPeers.asStateFlow()

    private val _incomingPayloads = MutableSharedFlow<Payload>(extraBufferCapacity = 64)
    override val incomingPayloads: SharedFlow<Payload> = _incomingPayloads.asSharedFlow()

    private val wifiP2pManager: WifiP2pManager? =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private var isReceiverRegistered = false

    private var groupOwnerAddress: String? = null
    private var isGroupOwner = false
    private val discoveredDevices = ConcurrentHashMap<String, WifiP2pDevice>()

    private val p2pReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val p2pState = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    if (p2pState != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                        _state.value = TransportState.ERROR
                    }
                }

                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    requestPeers()
                }

                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val networkInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO, NetworkInfo::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
                    }

                    if (networkInfo?.isConnected == true) {
                        requestConnectionInfo()
                    } else {
                        _state.value = TransportState.DISCONNECTED
                        _connectedPeers.value = emptyList()
                    }
                }
            }
        }
    }

    override fun isAvailable(): Boolean {
        return wifiP2pManager != null
    }

    override suspend fun start() = withContext(Dispatchers.IO) {
        if (!isAvailable()) {
            _state.value = TransportState.ERROR
            return@withContext
        }

        channel = wifiP2pManager?.initialize(context, context.mainLooper, null)

        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }

        if (!isReceiverRegistered) {
            context.registerReceiver(p2pReceiver, intentFilter)
            isReceiverRegistered = true
        }

        _state.value = TransportState.SCANNING
        startPeerDiscovery()
        startServerSocket()
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        if (isReceiverRegistered) {
            try {
                context.unregisterReceiver(p2pReceiver)
            } catch (e: Exception) {
                Log.w(TAG, "Failed unregistering receiver", e)
            }
            isReceiverRegistered = false
        }

        serverJob?.cancel()
        serverSocket?.close()
        serverSocket = null

        val p2p = wifiP2pManager
        val ch = channel
        if (p2p != null && ch != null) {
            p2p.stopPeerDiscovery(ch, null)
            p2p.removeGroup(ch, null)
        }

        _connectedPeers.value = emptyList()
        _state.value = TransportState.IDLE
    }

    private fun startPeerDiscovery() {
        val p2p = wifiP2pManager ?: return
        val ch = channel ?: return

        p2p.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Wi-Fi Direct peer discovery started successfully")
            }

            override fun onFailure(reason: Int) {
                Log.e(TAG, "Wi-Fi Direct peer discovery failed: $reason")
            }
        })
    }

    private fun requestPeers() {
        val p2p = wifiP2pManager ?: return
        val ch = channel ?: return

        p2p.requestPeers(ch) { peers: WifiP2pDeviceList? ->
            peers?.deviceList?.forEach { device ->
                discoveredDevices[device.deviceAddress] = device
                Log.d(TAG, "Discovered Wi-Fi Direct peer: ${device.deviceName} (${device.deviceAddress})")
            }
        }
    }

    fun connectToDevice(deviceAddress: String) {
        val p2p = wifiP2pManager ?: return
        val ch = channel ?: return
        val config = WifiP2pConfig().apply {
            this.deviceAddress = deviceAddress
        }

        _state.value = TransportState.CONNECTING
        p2p.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Connection initiation to $deviceAddress succeeded")
            }

            override fun onFailure(reason: Int) {
                Log.e(TAG, "Connection initiation failed: $reason")
                _state.value = TransportState.ERROR
            }
        })
    }

    private fun requestConnectionInfo() {
        val p2p = wifiP2pManager ?: return
        val ch = channel ?: return

        p2p.requestConnectionInfo(ch) { info: WifiP2pInfo? ->
            if (info != null && info.groupFormed) {
                isGroupOwner = info.isGroupOwner
                groupOwnerAddress = info.groupOwnerAddress?.hostAddress

                _state.value = TransportState.CONNECTED
                val ownerLabel = if (isGroupOwner) "Group Owner (Local)" else "Group Owner ($groupOwnerAddress)"
                _connectedPeers.value = listOf(
                    PeerDevice(
                        id = groupOwnerAddress ?: "p2p_group",
                        name = ownerLabel,
                        transportType = TransportType.WIFI_DIRECT_SOCKET,
                        isConnected = true
                    )
                )
                Log.d(TAG, "P2P Group Formed. isOwner=$isGroupOwner, Owner IP=$groupOwnerAddress")
            }
        }
    }

    private fun startServerSocket() {
        serverJob?.cancel()
        serverJob = scope.launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket(PORT).apply {
                    reuseAddress = true
                }
                Log.d(TAG, "ServerSocket listening on port $PORT")

                while (isActive) {
                    val socket = serverSocket?.accept() ?: break
                    launch(Dispatchers.IO) {
                        handleIncomingConnection(socket)
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.e(TAG, "ServerSocket error", e)
                }
            }
        }
    }

    private suspend fun handleIncomingConnection(socket: Socket) = withContext(Dispatchers.IO) {
        socket.use { s ->
            try {
                val input = DataInputStream(s.getInputStream())
                val wireType = input.readByte()
                val payloadId = input.readUTF()
                val timestamp = input.readLong()

                when (wireType) {
                    TYPE_TEXT -> {
                        val text = input.readUTF()
                        _incomingPayloads.emit(
                            Payload.Text(
                                id = payloadId,
                                timestamp = timestamp,
                                content = text
                            )
                        )
                        Log.d(TAG, "Received Text via Wi-Fi socket: ${text.take(30)}")
                    }

                    TYPE_FILE -> {
                        val fileName = input.readUTF()
                        val mimeType = input.readUTF()
                        val fileSize = input.readLong()

                        val receivedDir = File(context.cacheDir, "received_files").apply { mkdirs() }
                        val targetFile = File(receivedDir, fileName)

                        targetFile.outputStream().use { out ->
                            val buffer = ByteArray(64 * 1024)
                            var remaining = fileSize
                            while (remaining > 0) {
                                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                if (read == -1) break
                                out.write(buffer, 0, read)
                                remaining -= read
                            }
                        }

                        _incomingPayloads.emit(
                            Payload.File(
                                id = payloadId,
                                timestamp = timestamp,
                                fileName = fileName,
                                mimeType = mimeType,
                                sizeBytes = fileSize,
                                localPath = targetFile.absolutePath
                            )
                        )
                        Log.d(TAG, "Received File via Wi-Fi socket: $fileName ($fileSize bytes)")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling incoming socket connection", e)
            }
        }
    }

    override suspend fun send(payload: Payload, targetPeerId: String?): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val destinationIp = targetPeerId ?: groupOwnerAddress
                ?: error("Destination IP unknown. No P2P group owner established.")

            Socket().use { socket ->
                socket.connect(InetSocketAddress(destinationIp, PORT), SOCKET_TIMEOUT_MS)
                val output = DataOutputStream(socket.getOutputStream())

                when (payload) {
                    is Payload.Text -> {
                        output.writeByte(TYPE_TEXT.toInt())
                        output.writeUTF(payload.id)
                        output.writeLong(payload.timestamp)
                        output.writeUTF(payload.content)
                        output.flush()
                    }

                    is Payload.File -> {
                        output.writeByte(TYPE_FILE.toInt())
                        output.writeUTF(payload.id)
                        output.writeLong(payload.timestamp)
                        output.writeUTF(payload.fileName)
                        output.writeUTF(payload.mimeType)
                        output.writeLong(payload.sizeBytes)

                        val inputStream: InputStream? = if (payload.uriString != null) {
                            context.contentResolver.openInputStream(Uri.parse(payload.uriString))
                        } else if (payload.localPath != null) {
                            File(payload.localPath).inputStream()
                        } else null

                        inputStream?.use { stream ->
                            val buffer = ByteArray(64 * 1024)
                            var read: Int
                            while (stream.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                            }
                        } ?: error("Unable to open stream for file payload: ${payload.fileName}")

                        output.flush()
                    }
                }
            }
        }
    }
}
