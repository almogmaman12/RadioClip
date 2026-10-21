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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

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

    private val _discoveredPeers = MutableStateFlow<List<PeerDevice>>(emptyList())
    override val discoveredPeers: StateFlow<List<PeerDevice>> = _discoveredPeers.asStateFlow()

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

    @Volatile
    var groupOwnerAddress: String? = null
        private set

    @Volatile
    var isGroupOwner: Boolean = false
        private set

    private val connectedClientIps = ConcurrentHashMap.newKeySet<String>()
    private val discoveredDevices = ConcurrentHashMap<String, WifiP2pDevice>()

    // MAC address of the peer we initiated connect() to, used for UI ID matching
    @Volatile
    private var pendingConnectMac: String? = null

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
                        intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                    }

                    // Android 10+ (Q) includes WifiP2pInfo directly in the intent extras
                    // This is more reliable than NetworkInfo which can be null even when connected
                    val directP2pInfo: WifiP2pInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO, WifiP2pInfo::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra<WifiP2pInfo>(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
                        }
                    } else null

                    if (networkInfo?.isConnected == true || directP2pInfo?.groupFormed == true) {
                        requestConnectionInfo()
                    } else {
                        if (_connectedPeers.value.isNotEmpty()) {
                            _state.value = TransportState.DISCONNECTED
                        } else if (_state.value == TransportState.CONNECTING) {
                            _state.value = TransportState.SCANNING
                        }
                        _connectedPeers.value = emptyList()
                        groupOwnerAddress = null
                        connectedClientIps.clear()
                        pendingConnectMac = null
                        // Resume scanning so peers reappear in UI after disconnect
                        startPeerDiscovery()
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
        startConnectionMonitor()
    }

    private var connectionMonitorJob: Job? = null

    /**
     * Active polling monitor for Wi-Fi Direct connection state.
     * Android often drops or denies broadcast intents for WIFI_P2P_CONNECTION_CHANGED
     * due to background appop/location restrictions. This ensures group formation
     * is promptly and reliably detected regardless of broadcast delivery.
     */
    private fun startConnectionMonitor() {
        connectionMonitorJob?.cancel()
        connectionMonitorJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                // Poll connection info periodically: faster during CONNECTING, moderate otherwise
                val pollDelay = if (_state.value == TransportState.CONNECTING) 1500L else 5000L
                delay(pollDelay)
                checkConnectionState()
            }
        }
    }

    private fun checkConnectionState() {
        val p2p = wifiP2pManager ?: return
        val ch = channel ?: return
        p2p.requestConnectionInfo(ch) { info: WifiP2pInfo? ->
            if (info != null && info.groupFormed) {
                if (_state.value != TransportState.CONNECTED || groupOwnerAddress == null) {
                    Log.d(TAG, "Connection monitor detected formed group: GO=${info.groupOwnerAddress?.hostAddress}, isGO=${info.isGroupOwner}")
                    handleGroupFormed(info)
                }
            } else if (_state.value == TransportState.CONNECTED) {
                Log.d(TAG, "Connection monitor detected group teardown")
                handleGroupDisconnected()
            }
        }
    }

    private fun handleGroupDisconnected() {
        _state.value = TransportState.DISCONNECTED
        _connectedPeers.value = emptyList()
        groupOwnerAddress = null
        connectedClientIps.clear()
        pendingConnectMac = null
        startPeerDiscovery()
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        connectionMonitorJob?.cancel()
        connectionMonitorJob = null

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

        _discoveredPeers.value = emptyList()
        _connectedPeers.value = emptyList()
        connectedClientIps.clear()
        groupOwnerAddress = null
        _state.value = TransportState.IDLE
    }

    fun startPeerDiscovery() {
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
            val list = mutableListOf<PeerDevice>()
            peers?.deviceList?.forEach { device ->
                if (!isExcludedDevice(device)) {
                    discoveredDevices[device.deviceAddress] = device
                    list.add(
                        PeerDevice(
                            id = device.deviceAddress,
                            name = device.deviceName?.takeIf { it.isNotBlank() } ?: "Android (${device.deviceAddress.takeLast(5)})",
                            transportType = TransportType.WIFI_DIRECT_SOCKET,
                            isConnected = device.status == WifiP2pDevice.CONNECTED
                        )
                    )
                }
            }
            _discoveredPeers.value = list
            Log.d(TAG, "Discovered ${list.size} eligible Wi-Fi Direct peers")
        }
    }

    private fun isExcludedDevice(device: WifiP2pDevice): Boolean {
        val name = device.deviceName?.lowercase() ?: ""
        val primaryType = device.primaryDeviceType?.lowercase() ?: ""

        val excludedKeywords = listOf("tv", "bravia", "samsung", "lg webos", "roku", "printer", "chromecast", "display", "fridge")
        if (excludedKeywords.any { name.contains(it) }) return true

        if (primaryType.startsWith("7-") || primaryType.startsWith("3-") || primaryType.startsWith("8-")) {
            return true
        }

        return false
    }

    override suspend fun connect(peerId: String): Result<Unit> = withContext(Dispatchers.IO) {
        suspendCoroutine { cont ->
            val p2p = wifiP2pManager
            val ch = channel
            if (p2p == null || ch == null) {
                cont.resume(Result.failure(IllegalStateException("WifiP2pManager not initialized")))
                return@suspendCoroutine
            }

            _state.value = TransportState.CONNECTING
            pendingConnectMac = peerId // Track MAC address for UI peer ID matching

            // Cancel discovery first to let Android Wi-Fi Direct negotiate the P2P group
            p2p.stopPeerDiscovery(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    initiateGroupConnect(p2p, ch, peerId, cont)
                }

                override fun onFailure(reason: Int) {
                    initiateGroupConnect(p2p, ch, peerId, cont)
                }
            })
        }
    }

    private fun initiateGroupConnect(
        p2p: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        peerId: String,
        cont: kotlin.coroutines.Continuation<Result<Unit>>
    ) {
        val config = WifiP2pConfig().apply {
            this.deviceAddress = peerId
            // Use standard PBC (Push Button Configuration) for peer-to-peer pairing without PIN
            wps.setup = android.net.wifi.WpsInfo.PBC
        }

        p2p.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Wi-Fi Direct connect initiation to $peerId succeeded")
                cont.resume(Result.success(Unit))
            }

            override fun onFailure(reason: Int) {
                Log.e(TAG, "Wi-Fi Direct connect initiation to $peerId failed: $reason")
                _state.value = TransportState.SCANNING
                cont.resume(Result.failure(Exception("Wi-Fi Direct connect failed code: $reason")))
            }
        })
    }

    private fun requestConnectionInfo() {
        val p2p = wifiP2pManager ?: return
        val ch = channel ?: return
        p2p.requestConnectionInfo(ch) { info: WifiP2pInfo? ->
            if (info != null && info.groupFormed) {
                handleGroupFormed(info)
            }
        }
    }

    private fun handleGroupFormed(info: WifiP2pInfo) {
        isGroupOwner = info.isGroupOwner
        val goAddress = info.groupOwnerAddress?.hostAddress
        groupOwnerAddress = goAddress
        _state.value = TransportState.CONNECTED
        Log.d(TAG, "P2P Group Formed: isGroupOwner=$isGroupOwner, Owner IP=$goAddress")

        val peers = mutableListOf<PeerDevice>()
        val connectedMac = pendingConnectMac

        if (!isGroupOwner && connectedMac != null) {
            val device = discoveredDevices[connectedMac]
            val peerName = device?.deviceName?.takeIf { it.isNotBlank() }
                ?: "Wi-Fi Direct GO ($goAddress)"
            peers.add(PeerDevice(
                id = connectedMac,
                name = peerName,
                transportType = TransportType.WIFI_DIRECT_SOCKET,
                isConnected = true
            ))
            val existing = _discoveredPeers.value.toMutableList()
            val idx = existing.indexOfFirst { it.id == connectedMac }
            if (idx < 0) {
                existing.add(PeerDevice(id = connectedMac, name = peerName,
                    transportType = TransportType.WIFI_DIRECT_SOCKET, isConnected = true))
            } else {
                existing[idx] = existing[idx].copy(isConnected = true)
            }
            _discoveredPeers.value = existing
            sendHelloToGroupOwner(goAddress)
        } else if (isGroupOwner) {
            peers.add(PeerDevice(
                id = goAddress ?: "p2p_group",
                name = "Group Owner (GO: $goAddress)",
                transportType = TransportType.WIFI_DIRECT_SOCKET,
                isConnected = true
            ))
            connectedClientIps.forEach { clientIp ->
                peers.add(PeerDevice(id = clientIp, name = "Client ($clientIp)",
                    transportType = TransportType.WIFI_DIRECT_SOCKET, isConnected = true))
            }
        }

        _connectedPeers.value = peers
        startServerSocket()
    }

    /**
     * Sends a minimal hello packet to the Group Owner immediately after joining the P2P group.
     * This causes the GO's ServerSocket to accept the connection and register our IP address,
     * which the GO needs to send data back to this client device.
     */
    private fun sendHelloToGroupOwner(goAddress: String?) {
        if (goAddress.isNullOrBlank()) return
        scope.launch(Dispatchers.IO) {
            try {
                delay(1500) // Brief wait for DHCP/routing to be fully ready
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(goAddress, PORT), SOCKET_TIMEOUT_MS)
                    val output = DataOutputStream(socket.getOutputStream())
                    // Send a TYPE_TEXT hello so GO's protocol parser can handle it cleanly
                    output.writeByte(TYPE_TEXT.toInt())
                    output.writeUTF("__radioclip_hello__")
                    output.writeLong(System.currentTimeMillis())
                    output.writeUTF("__peer_hello__")
                    output.flush()
                }
                Log.d(TAG, "Peer hello sent to Group Owner: $goAddress")
            } catch (e: Exception) {
                Log.w(TAG, "Peer hello to GO failed (non-critical): ${e.message}")
            }
        }
    }

    private fun startServerSocket() {
        if (serverSocket != null && serverSocket?.isClosed == false) return

        serverJob?.cancel()
        serverJob = scope.launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket(PORT).apply {
                    reuseAddress = true
                }
                Log.d(TAG, "ServerSocket listening on port $PORT (isGO=$isGroupOwner)")

                while (isActive) {
                    val socket = serverSocket?.accept() ?: break
                    val clientIp = socket.inetAddress?.hostAddress
                    if (clientIp != null && isGroupOwner) {
                        connectedClientIps.add(clientIp)
                        updateConnectedPeersList()
                    }
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

    private fun updateConnectedPeersList() {
        val goAddress = groupOwnerAddress ?: "p2p_group"
        val goId = if (isGroupOwner) goAddress else (pendingConnectMac ?: goAddress)
        val ownerLabel = if (isGroupOwner) "Group Owner (GO: $goAddress)" else "Connected (GO: $goAddress)"
        val list = mutableListOf(
            PeerDevice(
                id = goId,
                name = ownerLabel,
                transportType = TransportType.WIFI_DIRECT_SOCKET,
                isConnected = true
            )
        )
        connectedClientIps.forEach { clientIp ->
            list.add(PeerDevice(
                id = clientIp,
                name = "Client ($clientIp)",
                transportType = TransportType.WIFI_DIRECT_SOCKET,
                isConnected = true
            ))
        }
        _connectedPeers.value = list

        // For GO: also add clients into discoveredPeers so they show in the UI list
        if (isGroupOwner && connectedClientIps.isNotEmpty()) {
            val existing = _discoveredPeers.value.toMutableList()
            connectedClientIps.forEach { clientIp ->
                if (existing.none { it.id == clientIp }) {
                    existing.add(PeerDevice(id = clientIp, name = "Client ($clientIp)",
                        transportType = TransportType.WIFI_DIRECT_SOCKET, isConnected = true))
                }
            }
            _discoveredPeers.value = existing
        }
    }

    private suspend fun handleIncomingConnection(socket: Socket) = withContext(Dispatchers.IO) {
        socket.use { s ->
            try {
                val input = DataInputStream(s.getInputStream())
                val wireType = input.readByte()
                val payloadId = input.readUTF()
                val timestamp = input.readLong()

                val clientIp = s.inetAddress?.hostAddress
                val resolvedSenderName = when {
                    !isGroupOwner -> {
                        val mac = pendingConnectMac
                        val dev = if (mac != null) discoveredDevices[mac] else null
                        dev?.deviceName?.takeIf { it.isNotBlank() } ?: "Group Owner ($clientIp)"
                    }
                    else -> {
                        // We are Group Owner, find matching client device by looking at discovered devices
                        val matchingDevice = discoveredDevices.values.firstOrNull { it.status == WifiP2pDevice.CONNECTED }
                        matchingDevice?.deviceName?.takeIf { it.isNotBlank() } ?: "Client ($clientIp)"
                    }
                }

                when (wireType) {
                    TYPE_TEXT -> {
                        val text = input.readUTF()
                        if (text == "__peer_hello__") {
                            // Client sent a hello to register its IP on the GO's ServerSocket
                            Log.d(TAG, "Received peer hello from $clientIp — IP registered")
                            return@use // IP already captured via ServerSocket.accept()
                        }
                        _incomingPayloads.emit(
                            Payload.Text(
                                id = payloadId,
                                timestamp = timestamp,
                                transportType = TransportType.WIFI_DIRECT_SOCKET,
                                senderDeviceName = resolvedSenderName,
                                isOutgoing = false,
                                content = text
                            )
                        )
                        Log.d(TAG, "Received Text via Wi-Fi socket from $resolvedSenderName: ${text.take(30)}")
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
                                transportType = TransportType.WIFI_DIRECT_SOCKET,
                                senderDeviceName = resolvedSenderName,
                                isOutgoing = false,
                                fileName = fileName,
                                mimeType = mimeType,
                                sizeBytes = fileSize,
                                localPath = targetFile.absolutePath
                            )
                        )
                        Log.d(TAG, "Received File via Wi-Fi socket from $resolvedSenderName: $fileName ($fileSize bytes)")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling incoming socket connection", e)
            }
        }
    }

    override suspend fun send(payload: Payload, targetPeerId: String?): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val destinationIp = when {
                targetPeerId != null && targetPeerId.contains(".") -> targetPeerId
                !isGroupOwner && !groupOwnerAddress.isNullOrBlank() -> groupOwnerAddress
                isGroupOwner && connectedClientIps.isNotEmpty() -> connectedClientIps.first()
                else -> groupOwnerAddress ?: error("Destination IP unknown. No P2P group formed or client IP recorded.")
            }

            Log.d(TAG, "Connecting TCP socket to $destinationIp:$PORT to send ${payload.javaClass.simpleName}")

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
                Log.d(TAG, "Successfully sent payload to $destinationIp")
                Unit
            }
        }
    }
}
