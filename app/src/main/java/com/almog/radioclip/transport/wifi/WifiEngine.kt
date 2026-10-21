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
import androidx.core.content.ContextCompat
import com.almog.radioclip.core.FileNames
import com.almog.radioclip.core.SecureInputStream
import com.almog.radioclip.core.SecureOutputStream
import com.almog.radioclip.model.Payload
import com.almog.radioclip.model.PeerDevice
import com.almog.radioclip.model.TransportState
import com.almog.radioclip.model.TransportType
import com.almog.radioclip.transport.DeviceTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
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
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + Job()),
    private val keyProvider: () -> ByteArray? = { null }
) : DeviceTransport {

    companion object {
        private const val TAG = "WifiEngine"
        const val PORT = 8888
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val IO_TIMEOUT_MS = 30_000
        private const val MAX_CONCURRENT_CONNECTIONS = 6

        const val MAX_FILE_BYTES = 1L shl 30        // 1 GiB
        const val MAX_TEXT_BYTES = 1 shl 20         // 1 MiB
        private const val MIN_FREE_SPACE_BYTES = 50L * 1024 * 1024
        private const val MAX_MIME_LENGTH = 255

        // Stream wire protocol tags
        private const val TYPE_TEXT: Byte = 1
        private const val TYPE_FILE: Byte = 2
        private const val TYPE_HELLO: Byte = 3
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
    private var started = false

    private val connectionLimiter = Semaphore(MAX_CONCURRENT_CONNECTIONS)

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
        if (started) return@withContext
        if (!isAvailable()) {
            _state.value = TransportState.ERROR
            return@withContext
        }
        started = true

        channel = wifiP2pManager?.initialize(context, context.mainLooper, null)

        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }

        if (!isReceiverRegistered) {
            ContextCompat.registerReceiver(
                context, p2pReceiver, intentFilter, ContextCompat.RECEIVER_NOT_EXPORTED
            )
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
        started = false
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
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Failed closing server socket", e)
        }
        serverSocket = null

        val p2p = wifiP2pManager
        val ch = channel
        if (p2p != null && ch != null) {
            p2p.stopPeerDiscovery(ch, null)
            p2p.removeGroup(ch, null)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                try {
                    ch.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed closing p2p channel", e)
                }
            }
        }
        channel = null

        _discoveredPeers.value = emptyList()
        _connectedPeers.value = emptyList()
        connectedClientIps.clear()
        groupOwnerAddress = null
        pendingConnectMac = null
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
                if (isEligiblePeer(device)) {
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

    /**
     * Only phones/tablets/computers can run RadioClip. Wi-Fi Direct reports a "primary device type"
     * such as "10-0050F204-5" (category 10 = telephone, 1 = computer); TVs, printers, speakers etc.
     * use other categories. Devices that report no type are kept so nothing is hidden by mistake.
     * (Matching on device *names* is unreliable: a phone called "Samsung Galaxy" is not a TV.)
     */
    private fun isEligiblePeer(device: WifiP2pDevice): Boolean {
        val type = device.primaryDeviceType?.trim().orEmpty()
        if (type.isEmpty()) return true
        return type.startsWith("10-") || type.startsWith("1-")
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
     * Sends a minimal, encrypted hello to the Group Owner right after joining the P2P group.
     * The GO registers our IP address once the hello authenticates, which it needs to send data
     * back to this client.
     */
    private fun sendHelloToGroupOwner(goAddress: String?) {
        if (goAddress.isNullOrBlank()) return
        scope.launch(Dispatchers.IO) {
            val key = keyProvider()
            if (key == null) {
                Log.w(TAG, "Not paired yet: skipping hello to Group Owner")
                return@launch
            }
            try {
                delay(1500) // Brief wait for DHCP/routing to be fully ready
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(goAddress, PORT), CONNECT_TIMEOUT_MS)
                    socket.soTimeout = IO_TIMEOUT_MS
                    val output = DataOutputStream(SecureOutputStream(socket.getOutputStream(), key))
                    output.writeByte(TYPE_HELLO.toInt())
                    output.writeUTF(java.util.UUID.randomUUID().toString())
                    output.writeLong(System.currentTimeMillis())
                    output.flush()
                }
                Log.d(TAG, "Peer hello sent to Group Owner: $goAddress")
            } catch (e: CancellationException) {
                throw e
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
                val server = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(PORT))
                }
                serverSocket = server
                Log.d(TAG, "ServerSocket listening on port $PORT (isGO=$isGroupOwner)")

                while (isActive) {
                    val socket = server.accept()
                    if (!connectionLimiter.tryAcquire()) {
                        Log.w(TAG, "Too many simultaneous connections, dropping one")
                        runCatching { socket.close() }
                        continue
                    }
                    launch(Dispatchers.IO) {
                        try {
                            handleIncomingConnection(socket)
                        } finally {
                            connectionLimiter.release()
                        }
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

    private fun senderNameFor(clientIp: String?): String {
        if (isGroupOwner) return "Client ($clientIp)"
        val mac = pendingConnectMac
        val name = if (mac != null) discoveredDevices[mac]?.deviceName else null
        return name?.takeIf { it.isNotBlank() } ?: "Group Owner ($clientIp)"
    }

    private fun receivedFilesDir(): File =
        (context.getExternalFilesDir("received") ?: File(context.filesDir, "received")).apply { mkdirs() }

    private suspend fun handleIncomingConnection(socket: Socket) = withContext(Dispatchers.IO) {
        socket.use { s ->
            try {
                val key = keyProvider()
                if (key == null) {
                    Log.w(TAG, "Dropping incoming connection: not paired")
                    return@use
                }
                s.soTimeout = IO_TIMEOUT_MS

                // Everything below is authenticated: a wrong pairing code fails on the first read.
                val input = DataInputStream(SecureInputStream(s.getInputStream(), key))
                val wireType = input.readByte()
                val payloadId = input.readUTF()
                val timestamp = input.readLong()

                val clientIp = s.inetAddress?.hostAddress
                if (clientIp != null && isGroupOwner && connectedClientIps.add(clientIp)) {
                    updateConnectedPeersList()
                }
                val senderName = senderNameFor(clientIp)

                when (wireType) {
                    TYPE_HELLO -> {
                        Log.d(TAG, "Received peer hello from $clientIp - IP registered")
                    }

                    TYPE_TEXT -> {
                        val length = input.readInt()
                        require(length in 0..MAX_TEXT_BYTES) { "Text payload size out of range: $length" }
                        val bytes = ByteArray(length)
                        input.readFully(bytes)
                        val text = String(bytes, Charsets.UTF_8)

                        _incomingPayloads.emit(
                            Payload.Text(
                                id = payloadId,
                                timestamp = timestamp,
                                transportType = TransportType.WIFI_DIRECT_SOCKET,
                                senderDeviceName = senderName,
                                isOutgoing = false,
                                content = text
                            )
                        )
                        Log.d(TAG, "Received text via Wi-Fi socket from $senderName (${bytes.size} bytes)")
                    }

                    TYPE_FILE -> {
                        val fileName = input.readUTF()
                        val mimeType = input.readUTF().take(MAX_MIME_LENGTH)
                        val fileSize = input.readLong()

                        require(fileSize in 0..MAX_FILE_BYTES) { "File size out of range: $fileSize" }
                        val dir = receivedFilesDir()
                        require(dir.usableSpace > fileSize + MIN_FREE_SPACE_BYTES) { "Not enough free space for $fileSize bytes" }

                        // The name comes from another device: sanitize it and never overwrite a file.
                        val targetFile = FileNames.reserveUniqueFile(dir, fileName)
                        var complete = false
                        try {
                            targetFile.outputStream().use { out ->
                                val buffer = ByteArray(64 * 1024)
                                var remaining = fileSize
                                while (remaining > 0) {
                                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                    if (read == -1) throw EOFException("Connection ended with $remaining bytes missing")
                                    out.write(buffer, 0, read)
                                    remaining -= read
                                }
                            }
                            complete = true
                        } finally {
                            if (!complete) targetFile.delete() // never keep half a file
                        }

                        _incomingPayloads.emit(
                            Payload.File(
                                id = payloadId,
                                timestamp = timestamp,
                                transportType = TransportType.WIFI_DIRECT_SOCKET,
                                senderDeviceName = senderName,
                                isOutgoing = false,
                                fileName = targetFile.name,
                                mimeType = mimeType,
                                sizeBytes = fileSize,
                                localPath = targetFile.absolutePath
                            )
                        )
                        Log.d(TAG, "Received file via Wi-Fi socket from $senderName: ${targetFile.name} ($fileSize bytes)")
                    }

                    else -> Log.w(TAG, "Unknown wire type $wireType from $clientIp")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Error handling incoming socket connection: ${e.message}")
            }
        }
    }

    /** A file ready to stream: its exact size and a way to open it. */
    private class PreparedFile(val size: Long, val open: () -> InputStream)

    private fun prepareFile(payload: Payload.File): PreparedFile {
        val uri = payload.uriString?.let { Uri.parse(it) }
        val localPath = payload.localPath
        val open: () -> InputStream = when {
            uri != null -> { { context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open ${payload.fileName}") } }
            localPath != null -> { { File(localPath).inputStream() } }
            else -> throw IOException("No source for file payload: ${payload.fileName}")
        }

        var size = payload.sizeBytes
        if (size <= 0 && uri != null) {
            // Some providers don't report a size to the file picker; ask the file descriptor.
            size = runCatching {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            }.getOrDefault(-1L)
        }
        if (size <= 0) {
            // Still unknown: measure by reading once. The receiver needs the exact size up front.
            size = open().use { stream ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    total += read
                }
                total
            }
        }
        if (size > MAX_FILE_BYTES) throw IOException("File is too large (limit is ${MAX_FILE_BYTES / (1024 * 1024)} MB)")
        return PreparedFile(size, open)
    }

    override suspend fun send(payload: Payload, targetPeerId: String?): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val key = keyProvider() ?: error("Not paired: enter the same pairing code on both devices first")

            val destinations: List<String> = when {
                targetPeerId != null && targetPeerId.contains(".") -> listOf(targetPeerId)
                !isGroupOwner && !groupOwnerAddress.isNullOrBlank() -> listOf(groupOwnerAddress!!)
                // Group owner: deliver to every client, not just the first one.
                isGroupOwner && connectedClientIps.isNotEmpty() -> connectedClientIps.toList()
                else -> error("Destination IP unknown. No P2P group formed or client IP recorded.")
            }

            val prepared = if (payload is Payload.File) prepareFile(payload) else null

            var delivered = 0
            var lastError: Throwable? = null
            for (ip in destinations) {
                try {
                    sendTo(ip, payload, prepared, key)
                    delivered++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Send to $ip failed: ${e.message}")
                    lastError = e
                }
            }
            if (delivered == 0) throw (lastError ?: IllegalStateException("Nothing was sent"))
        }
    }

    private fun sendTo(ip: String, payload: Payload, prepared: PreparedFile?, key: ByteArray) {
        Log.d(TAG, "Connecting TCP socket to $ip:$PORT to send ${payload.javaClass.simpleName}")

        Socket().use { socket ->
            socket.connect(InetSocketAddress(ip, PORT), CONNECT_TIMEOUT_MS)
            socket.soTimeout = IO_TIMEOUT_MS
            val output = DataOutputStream(SecureOutputStream(socket.getOutputStream(), key))

            when (payload) {
                is Payload.Text -> {
                    val bytes = payload.content.toByteArray(Charsets.UTF_8)
                    if (bytes.size > MAX_TEXT_BYTES) throw IOException("Text is too large (limit is ${MAX_TEXT_BYTES / 1024} KB)")
                    output.writeByte(TYPE_TEXT.toInt())
                    output.writeUTF(payload.id)
                    output.writeLong(payload.timestamp)
                    output.writeInt(bytes.size)
                    output.write(bytes)
                    output.flush()
                }

                is Payload.File -> {
                    val file = prepared ?: throw IllegalStateException("File was not prepared")
                    output.writeByte(TYPE_FILE.toInt())
                    output.writeUTF(payload.id)
                    output.writeLong(payload.timestamp)
                    output.writeUTF(payload.fileName)
                    output.writeUTF(payload.mimeType)
                    output.writeLong(file.size)

                    file.open().use { stream ->
                        val buffer = ByteArray(64 * 1024)
                        var remaining = file.size
                        while (remaining > 0) {
                            val read = stream.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                            if (read < 0) throw EOFException("File ended $remaining bytes early")
                            output.write(buffer, 0, read)
                            remaining -= read
                        }
                    }
                    output.flush()
                }
            }
            Log.d(TAG, "Successfully sent payload to $ip")
        }
    }
}
