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
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.util.Log
import com.almog.radioclip.security.SecureLink
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
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@SuppressLint("MissingPermission")
class WifiEngine(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + Job()),
    private val secureLink: SecureLink
) : DeviceTransport {

    companion object {
        private const val TAG = "WifiEngine"
        const val PORT = 8888
        private const val SOCKET_TIMEOUT_MS = 10000

        // Stream wire protocol tags
        private const val TYPE_HELLO: Byte = 0
        private const val TYPE_TEXT: Byte = 1
        private const val TYPE_FILE: Byte = 2
        private const val ACK_SUCCESS = 1
        private const val FILE_CHUNK = 64 * 1024
        private const val MAX_TEXT_BYTES = 1024 * 1024
        private const val CRYPTO_OVERHEAD = 28
        private const val PEER_EXPIRY_MS = 90_000L
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
    // Only devices advertising the RadioClip DNS-SD service are displayable.
    private val radioClipSeenAt = ConcurrentHashMap<String, Long>()
    private var localRadioClipService: WifiP2pDnsSdServiceInfo? = null
    private var radioClipServiceRequest: WifiP2pDnsSdServiceRequest? = null
    private var discoveryRegistrationInProgress = false
    private var discoveryRetryJob: Job? = null
    private val deliveredIds = ConcurrentHashMap<String, Long>()

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
                    // General P2P peer results alone DO NOT identify RadioClip devices.
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
                        closeServerSocket()
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
        registerRadioClipDiscovery()
        // Never expose a TCP receiver before a Wi-Fi Direct group exists.
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
        closeServerSocket()
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
        discoveryRetryJob?.cancel()
        discoveryRetryJob = null

        if (isReceiverRegistered) {
            try {
                context.unregisterReceiver(p2pReceiver)
            } catch (e: Exception) {
                Log.w(TAG, "Failed unregistering receiver", e)
            }
            isReceiverRegistered = false
        }

        closeServerSocket()

        val p2p = wifiP2pManager
        val ch = channel
        if (p2p != null && ch != null) {
            p2p.stopPeerDiscovery(ch, null)
            radioClipServiceRequest?.let { p2p.removeServiceRequest(ch, it, null) }
            localRadioClipService?.let { p2p.removeLocalService(ch, it, null) }
            p2p.removeGroup(ch, null)
        }
        radioClipServiceRequest = null
        discoveryRegistrationInProgress = false
        localRadioClipService = null
        radioClipSeenAt.clear()
        discoveredDevices.clear()

        _discoveredPeers.value = emptyList()
        _connectedPeers.value = emptyList()
        connectedClientIps.clear()
        groupOwnerAddress = null
        _state.value = TransportState.IDLE
    }

    /**
     * General discoverPeers() finds TVs, printers, and unrelated phones.
     * Android's DNS-SD service discovery identifies devices advertising
     * RadioClip's own protocol before they are offered for connection.
     */
    private fun registerRadioClipDiscovery() {
        val p2p = wifiP2pManager ?: return
        val ch = channel ?: return

        p2p.setDnsSdResponseListeners(
            ch,
            WifiP2pManager.DnsSdServiceResponseListener { instance, serviceType, device ->
                if (RadioClipServiceIdentity.matchesService(instance, serviceType)) {
                    markRadioClipPeer(device)
                }
            },
            WifiP2pManager.DnsSdTxtRecordListener { domain, record, device ->
                if (RadioClipServiceIdentity.matchesRecord(domain, record)) {
                    markRadioClipPeer(device)
                }
            }
        )

        val service = WifiP2pDnsSdServiceInfo.newInstance(
            RadioClipServiceIdentity.INSTANCE,
            RadioClipServiceIdentity.TYPE,
            mapOf("protocol" to RadioClipServiceIdentity.PROTOCOL, "port" to PORT.toString())
        )
        localRadioClipService = service
        p2p.addLocalService(ch, service, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "RadioClip DNS-SD service advertised")
                startPeerDiscovery()
            }

            override fun onFailure(reason: Int) {
                Log.e(TAG, "Could not advertise RadioClip DNS-SD service: $reason")
                // The P2P engine isn't ready to find other RadioClip devices.
                _state.value = TransportState.ERROR
            }
        })

        // Android can stop discovering after radio power or group changes.
        discoveryRetryJob?.cancel()
        discoveryRetryJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(30_000L)
                if (_state.value == TransportState.SCANNING ||
                    _state.value == TransportState.DISCONNECTED
                ) {
                    startPeerDiscovery()
                }
            }
        }
    }

    private fun markRadioClipPeer(device: WifiP2pDevice) {
        if (_state.value == TransportState.IDLE) return
        val address = device.deviceAddress ?: return
        if (address.isBlank()) return
        radioClipSeenAt[address] = System.currentTimeMillis()
        discoveredDevices[address] = device
        refreshVerifiedPeerList()
    }

    private fun refreshVerifiedPeerList() {
        val now = System.currentTimeMillis()
        radioClipSeenAt.entries.removeIf { now - it.value > PEER_EXPIRY_MS }
        _discoveredPeers.value = radioClipSeenAt.keys.mapNotNull { address ->
            val device = discoveredDevices[address] ?: return@mapNotNull null
            PeerDevice(
                id = address,
                name = device.deviceName?.takeIf { it.isNotBlank() }
                    ?: "RadioClip (${address.takeLast(5)})",
                transportType = TransportType.WIFI_DIRECT_SOCKET,
                isConnected = _connectedPeers.value.any { it.id == address }
            )
        }.sortedBy { it.name }
    }

    fun startPeerDiscovery() {
        val p2p = wifiP2pManager ?: return
        val ch = channel ?: return
        if (_state.value == TransportState.IDLE) return

        if (radioClipServiceRequest == null) {
            if (discoveryRegistrationInProgress) return
            discoveryRegistrationInProgress = true
            val request = WifiP2pDnsSdServiceRequest.newInstance(RadioClipServiceIdentity.TYPE)
            p2p.addServiceRequest(ch, request, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    discoveryRegistrationInProgress = false
                    radioClipServiceRequest = request
                    searchRadioClipServices()
                }

                override fun onFailure(reason: Int) {
                    discoveryRegistrationInProgress = false
                    Log.w(TAG, "Could not register RadioClip service search: $reason")
                }
            })
        } else {
            searchRadioClipServices()
        }
    }

    private fun searchRadioClipServices() {
        val p2p = wifiP2pManager ?: return
        val ch = channel ?: return
        refreshVerifiedPeerList()
        p2p.discoverServices(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Searching only for RadioClip DNS-SD services")
            }

            override fun onFailure(reason: Int) {
                Log.w(TAG, "RadioClip service discovery failed: $reason")
            }
        })
    }

    private fun requestPeers() {
        val p2p = wifiP2pManager ?: return
        val ch = channel ?: return
        p2p.requestPeers(ch) { peers: WifiP2pDeviceList? ->
            // Refresh metadata solely for peers already identified by DNS-SD.
            peers?.deviceList?.forEach { device ->
                if (radioClipSeenAt.containsKey(device.deviceAddress)) {
                    discoveredDevices[device.deviceAddress] = device
                }
            }
            refreshVerifiedPeerList()
        }
    }

    override suspend fun connect(peerId: String): Result<Unit> = withContext(Dispatchers.IO) {
        suspendCoroutine { cont ->
            val p2p = wifiP2pManager
            val ch = channel
            if (p2p == null || ch == null) {
                cont.resume(Result.failure(IllegalStateException("WifiP2pManager not initialized")))
                return@suspendCoroutine
            }

            if (!radioClipSeenAt.containsKey(peerId)) {
                cont.resume(Result.failure(IllegalArgumentException("This device is not advertising RadioClip")))
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

        if (isGroupOwner) {
            // A group can form before another RadioClip peer has authenticated.
            // Only peers that complete the encrypted hello enter the list.
            connectedClientIps.forEach { clientIp ->
                peers.add(
                    PeerDevice(
                        id = clientIp,
                        name = "RadioClip peer ($clientIp)",
                        transportType = TransportType.WIFI_DIRECT_SOCKET,
                        isConnected = true
                    )
                )
            }
        } else {
            // The group owner's Wi-Fi identity alone does not prove it has RadioClip.
            // sendHelloToGroupOwner() adds it only after an authenticated response.
            sendHelloToGroupOwner(goAddress)
        }

        _connectedPeers.value = peers
        startServerSocket()
    }

    /**
     * Sends a minimal hello packet to the Group Owner immediately after joining the P2P group.
     * This causes the GO's ServerSocket to accept the connection and register our IP address,
     * which the GO needs to send data back to this client device.
     */
    fun onPairingChanged() {
        // Invalidate all previous authentication when the group phrase changes.
        connectedClientIps.clear()
        _connectedPeers.value = emptyList()
        refreshVerifiedPeerList()
        if (!isGroupOwner && _state.value == TransportState.CONNECTED) {
            sendHelloToGroupOwner(groupOwnerAddress)
        }
    }

    private fun sendHelloToGroupOwner(goAddress: String?) {
        if (goAddress.isNullOrBlank() || !secureLink.configured.value) return
        scope.launch(Dispatchers.IO) {
            delay(1500)
            try {
                sendOnce(goAddress, null)
                val peerId = pendingConnectMac
                if (peerId != null && radioClipSeenAt.containsKey(peerId)) {
                    val device = discoveredDevices[peerId]
                    _connectedPeers.value = listOf(
                        PeerDevice(
                            id = peerId,
                            name = device?.deviceName?.takeIf { it.isNotBlank() }
                                ?: "RadioClip peer",
                            transportType = TransportType.WIFI_DIRECT_SOCKET,
                            isConnected = true
                        )
                    )
                    refreshVerifiedPeerList()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Authenticated Wi-Fi Direct peer registration failed", e)
            }
        }
    }

    private fun closeServerSocket() {
        serverJob?.cancel()
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Could not close Wi-Fi receiver socket", e)
        }
        serverSocket = null
        serverJob = null
    }

    private fun startServerSocket() {
        if (groupOwnerAddress == null || _state.value != TransportState.CONNECTED) return
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
                    socket.soTimeout = SOCKET_TIMEOUT_MS
                    if (_state.value != TransportState.CONNECTED || groupOwnerAddress == null) {
                        socket.close()
                        continue
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
        // Group Owner is this phone, not another discovered RadioClip peer.
        val list = mutableListOf<PeerDevice>()
        if (!isGroupOwner) {
            val address = pendingConnectMac
            if (address != null && radioClipSeenAt.containsKey(address)) {
                list.add(
                    PeerDevice(
                        id = address,
                        name = discoveredDevices[address]?.deviceName ?: "RadioClip peer",
                        transportType = TransportType.WIFI_DIRECT_SOCKET,
                        isConnected = true
                    )
                )
            }
        }
        connectedClientIps.forEach { clientIp ->
            list.add(PeerDevice(
                id = clientIp,
                name = "Client ($clientIp)",
                transportType = TransportType.WIFI_DIRECT_SOCKET,
                isConnected = true
            ))
        }
        _connectedPeers.value = list

        // For GO: these IPs have sent a valid encrypted hello.
        // Do not place group-owner placeholders in the nearby-device list.
        // Connected RadioClip peers are merged into the dashboard separately.
    }

    private fun authenticationData(
        type: Byte,
        id: String,
        timestamp: Long,
        fileName: String = "",
        mimeType: String = "",
        fileSize: Long = 0,
        chunkIndex: Int = 0
    ): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeByte(2) // RadioClip protocol version
            out.writeByte(type.toInt())
            out.writeUTF(id)
            out.writeLong(timestamp)
            out.writeUTF(fileName)
            out.writeUTF(mimeType)
            out.writeLong(fileSize)
            out.writeInt(chunkIndex)
        }
        bytes.toByteArray()
    }

    private fun registerAuthenticatedClient(address: String?) {
        if (isGroupOwner && !address.isNullOrBlank() && connectedClientIps.add(address)) {
            updateConnectedPeersList()
        }
    }

    private fun rememberDelivery(id: String): Boolean {
        val now = System.currentTimeMillis()
        deliveredIds.entries.removeIf { now - it.value > 10 * 60_000L }
        return deliveredIds.putIfAbsent(id, now) == null
    }

    private suspend fun handleIncomingConnection(socket: Socket) = withContext(Dispatchers.IO) {
        socket.use { s ->
            try {
                val input = DataInputStream(s.getInputStream())
                val output = DataOutputStream(s.getOutputStream())
                val wireType = input.readByte()
                val payloadId = input.readUTF()
                val timestamp = input.readLong()
                val clientIp = s.inetAddress?.hostAddress
                val resolvedSenderName = if (!isGroupOwner) {
                    val device = pendingConnectMac?.let { discoveredDevices[it] }
                    device?.deviceName?.takeIf { it.isNotBlank() } ?: "Group Owner"
                } else {
                    "Paired Wi-Fi Direct peer"
                }

                when (wireType) {
                    TYPE_HELLO -> {
                        val length = input.readInt()
                        require(length in CRYPTO_OVERHEAD..256) { "Invalid pairing hello" }
                        val packet = ByteArray(length)
                        input.readFully(packet)
                        val message = secureLink.open(
                            packet, authenticationData(wireType, payloadId, timestamp)
                        )
                        require(message.contentEquals("RadioClip hello v2".toByteArray(Charsets.UTF_8))) {
                            "Unrecognized pairing hello"
                        }
                        registerAuthenticatedClient(clientIp)
                    }

                    TYPE_TEXT -> {
                        val length = input.readInt()
                        require(length in CRYPTO_OVERHEAD..(MAX_TEXT_BYTES + CRYPTO_OVERHEAD)) {
                            "Invalid encrypted message size"
                        }
                        val packet = ByteArray(length)
                        input.readFully(packet)
                        val plain = secureLink.open(
                            packet, authenticationData(wireType, payloadId, timestamp)
                        )
                        require(plain.size <= MAX_TEXT_BYTES) { "Text is too large" }
                        registerAuthenticatedClient(clientIp)
                        if (rememberDelivery(payloadId)) {
                            _incomingPayloads.emit(
                                Payload.Text(
                                    id = payloadId,
                                    timestamp = timestamp,
                                    transportType = TransportType.WIFI_DIRECT_SOCKET,
                                    senderDeviceName = resolvedSenderName,
                                    isOutgoing = false,
                                    content = String(plain, Charsets.UTF_8)
                                )
                            )
                        }
                    }

                    TYPE_FILE -> {
                        val fileName = input.readUTF()
                        val mimeType = input.readUTF()
                        val fileSize = input.readLong()
                        FileTransferSafety.checkSize(fileSize)
                        val safeName = FileTransferSafety.safeName(fileName)
                        val directory = File(context.filesDir, "received_files").apply { mkdirs() }
                        val target = File(directory, "${UUID.randomUUID()}-$safeName")
                        try {
                            target.outputStream().use { stream ->
                                var remaining = fileSize
                                var chunkIndex = 0
                                do {
                                    val expectedPlain = minOf(remaining, FILE_CHUNK.toLong()).toInt()
                                    val expectedSealed = expectedPlain + CRYPTO_OVERHEAD
                                    val sealedSize = input.readInt()
                                    require(sealedSize == expectedSealed) { "Invalid encrypted file chunk" }
                                    val sealed = ByteArray(sealedSize)
                                    input.readFully(sealed)
                                    val plain = secureLink.open(
                                        sealed,
                                        authenticationData(
                                            wireType, payloadId, timestamp,
                                            fileName, mimeType, fileSize, chunkIndex
                                        )
                                    )
                                    require(plain.size == expectedPlain) { "File chunk size mismatch" }
                                    stream.write(plain)
                                    remaining -= plain.size
                                    chunkIndex++
                                } while (remaining > 0)
                            }
                        } catch (e: Exception) {
                            target.delete()
                            throw e
                        }
                        registerAuthenticatedClient(clientIp)
                        if (rememberDelivery(payloadId)) {
                            _incomingPayloads.emit(
                                Payload.File(
                                    id = payloadId,
                                    timestamp = timestamp,
                                    transportType = TransportType.WIFI_DIRECT_SOCKET,
                                    senderDeviceName = resolvedSenderName,
                                    isOutgoing = false,
                                    fileName = safeName,
                                    mimeType = mimeType,
                                    sizeBytes = fileSize,
                                    localPath = target.absolutePath
                                )
                            )
                        } else {
                            target.delete()
                        }
                    }

                    else -> error("Unknown RadioClip wire type")
                }
                output.writeByte(ACK_SUCCESS)
                output.flush()
            } catch (e: Exception) {
                Log.w(TAG, "Rejected or interrupted Wi-Fi Direct transfer", e)
            }
        }
    }

    private fun sendOnce(destinationIp: String, payload: Payload?) {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(destinationIp, PORT), SOCKET_TIMEOUT_MS)
            socket.soTimeout = SOCKET_TIMEOUT_MS
            val output = DataOutputStream(socket.getOutputStream())
            val payloadId = payload?.id ?: "__peer_hello__"
            val timestamp = payload?.timestamp ?: 0L
            val wireType: Byte = when (payload) {
                is Payload.Text -> TYPE_TEXT
                is Payload.File -> TYPE_FILE
                null -> TYPE_HELLO
            }
            output.writeByte(wireType.toInt())
            output.writeUTF(payloadId)
            output.writeLong(timestamp)
            when (payload) {
                null -> {
                    val message = "RadioClip hello v2".toByteArray(Charsets.UTF_8)
                    val sealed = secureLink.seal(
                        message, authenticationData(wireType, payloadId, timestamp)
                    )
                    output.writeInt(sealed.size)
                    output.write(sealed)
                }
                is Payload.Text -> {
                    val plaintext = payload.content.toByteArray(Charsets.UTF_8)
                    require(plaintext.size <= MAX_TEXT_BYTES) { "Text exceeds 1 MB" }
                    val sealed = secureLink.seal(
                        plaintext, authenticationData(wireType, payloadId, timestamp)
                    )
                    output.writeInt(sealed.size)
                    output.write(sealed)
                }
                is Payload.File -> {
                    FileTransferSafety.checkSize(payload.sizeBytes)
                    output.writeUTF(payload.fileName)
                    output.writeUTF(payload.mimeType)
                    output.writeLong(payload.sizeBytes)
                    val inputStream: InputStream? = if (payload.uriString != null) {
                        context.contentResolver.openInputStream(Uri.parse(payload.uriString))
                    } else if (payload.localPath != null) {
                        File(payload.localPath).inputStream()
                    } else null
                    (inputStream ?: error("Unable to open selected file")).use { stream ->
                        var remaining = payload.sizeBytes
                        var chunkIndex = 0
                        val buffer = ByteArray(FILE_CHUNK)
                        do {
                            val expected = minOf(remaining, FILE_CHUNK.toLong()).toInt()
                            var readTotal = 0
                            while (readTotal < expected) {
                                val count = stream.read(buffer, readTotal, expected - readTotal)
                                if (count < 0) throw java.io.EOFException("File changed during transfer")
                                if (count == 0) continue
                                readTotal += count
                            }
                            val sealed = secureLink.seal(
                                buffer.copyOf(expected),
                                authenticationData(
                                    wireType, payloadId, timestamp,
                                    payload.fileName, payload.mimeType, payload.sizeBytes, chunkIndex
                                )
                            )
                            output.writeInt(sealed.size)
                            output.write(sealed)
                            remaining -= expected
                            chunkIndex++
                        } while (remaining > 0)
                        require(stream.read() == -1) { "File size changed during transfer" }
                    }
                }
            }
            output.flush()
            check(DataInputStream(socket.getInputStream()).readUnsignedByte() == ACK_SUCCESS) {
                "Receiver did not acknowledge this transfer"
            }
        }
    }

    override suspend fun send(payload: Payload, targetPeerId: String?): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(secureLink.configured.value) { "Enter a matching pairing phrase first" }
                require(_state.value == TransportState.CONNECTED) { "Not connected over Wi-Fi Direct" }

                val destinations = when {
                    targetPeerId != null && targetPeerId.contains(".") -> {
                        require(!isGroupOwner || connectedClientIps.contains(targetPeerId)) {
                            "Target peer is not paired"
                        }
                        listOf(targetPeerId)
                    }
                    !isGroupOwner -> listOfNotNull(groupOwnerAddress)
                    else -> connectedClientIps.toList()
                }
                check(destinations.isNotEmpty()) { "No authenticated Wi-Fi Direct peers" }

                val failed = mutableListOf<String>()
                for (address in destinations) {
                    var delivered = false
                    for (attempt in 0 until 3) {
                        try {
                            sendOnce(address, payload)
                            delivered = true
                            break
                        } catch (e: Exception) {
                            Log.w(TAG, "Wi-Fi peer transfer attempt ${attempt + 1} failed", e)
                            if (attempt < 2) delay((attempt + 1) * 450L)
                        }
                    }
                    if (!delivered) failed.add(address)
                }
                check(failed.isEmpty()) {
                    "No delivery confirmation from ${failed.joinToString()}"
                }
                Unit
            }
        }
}
