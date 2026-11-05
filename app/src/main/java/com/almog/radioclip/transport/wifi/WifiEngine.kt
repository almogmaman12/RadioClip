package com.almog.radioclip.transport.wifi

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.almog.radioclip.core.BleMessages
import com.almog.radioclip.core.ClipPolicy
import com.almog.radioclip.core.DiagLog
import com.almog.radioclip.core.FileNames
import com.almog.radioclip.core.FrameAuthenticationException
import com.almog.radioclip.core.SecureInputStream
import com.almog.radioclip.core.SecureOutputStream
import com.almog.radioclip.core.Settings
import com.almog.radioclip.core.WifiDeviceTypes
import com.almog.radioclip.model.Payload
import com.almog.radioclip.model.PeerDevice
import com.almog.radioclip.model.TransportState
import com.almog.radioclip.model.TransportType
import com.almog.radioclip.storage.AppFiles
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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Wi-Fi Direct transport for files and large text.
 *
 * Wi-Fi Direct forms a small group with one "group owner" (GO) and clients. Whichever phone
 * becomes GO is decided by Android, so BOTH roles must work:
 *  - a client says hello to the GO (and repeats it as a heartbeat) so the GO learns the client's
 *    IP address, name and that it is still there;
 *  - the GO tracks its clients and drops the ones it stops hearing from.
 */
@SuppressLint("MissingPermission")
class WifiEngine(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + Job()),
    private val keyProvider: () -> ByteArray? = { null },
    private val settingsProvider: () -> Settings = { Settings() },
    private val onNotice: (String) -> Unit = {}
) : DeviceTransport {

    companion object {
        private const val TAG = "WifiEngine"
        const val PORT = 8888
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val IO_TIMEOUT_MS = 30_000
        private const val MAX_CONCURRENT_CONNECTIONS = 6

        const val MAX_FILE_BYTES = 1L shl 30                 // 1 GiB
        const val MAX_TEXT_BYTES = ClipPolicy.MAX_TEXT_BYTES
        private const val MIN_FREE_SPACE_BYTES = 50L * 1024 * 1024
        private const val MAX_MIME_LENGTH = 255

        private const val REQUEST_ACCEPT_TIMEOUT_MS = 45_000L   // how long the other phone has to accept
        private const val HELLO_INTERVAL_MS = 20_000L           // client heartbeat
        private const val CLIENT_TIMEOUT_MS = 75_000L           // GO forgets a client after this much silence
        private const val DISCOVERY_REFRESH_MS = 60_000L        // Android stops discovery by itself after ~2 min

        // Stream wire protocol tags
        private const val TYPE_TEXT: Byte = 1
        private const val TYPE_FILE: Byte = 2
        private const val TYPE_HELLO: Byte = 3
    }

    override val transportType: TransportType = TransportType.WIFI_DIRECT_SOCKET

    private val _state = MutableStateFlow(TransportState.IDLE)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    /** Short explanation shown on the dashboard when [state] is ERROR. */
    private val _errorDetail = MutableStateFlow<String?>(null)
    val errorDetail: StateFlow<String?> = _errorDetail.asStateFlow()

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
    private var connectionMonitorJob: Job? = null
    private var connectTimeoutJob: Job? = null
    private var helloJob: Job? = null
    private var isReceiverRegistered = false

    @Volatile private var started = false
    @Volatile private var lastDiscoveryAt = 0L

    private val connectionLimiter = Semaphore(MAX_CONCURRENT_CONNECTIONS)
    private val noticeTimes = ConcurrentHashMap<String, Long>()

    @Volatile
    var groupOwnerAddress: String? = null
        private set

    @Volatile
    var isGroupOwner: Boolean = false
        private set

    /** Identifies the group we already handled, so repeated "group formed" reports are ignored. */
    @Volatile private var handledGroupKey: String? = null

    // Group owner side: clients that said hello (ip -> last time heard) and their names.
    private val connectedClientIps = ConcurrentHashMap<String, Long>()
    private val clientNames = ConcurrentHashMap<String, String>()

    // Client side: the group owner we joined.
    @Volatile private var ownerPeer: PeerDevice? = null

    private val discoveredDevices = ConcurrentHashMap<String, WifiP2pDevice>()

    // MAC address of the peer we initiated connect() to.
    @Volatile private var pendingConnectMac: String? = null

    private fun log(message: String) = DiagLog.d(TAG, message)

    private fun notice(key: String, text: String, intervalMs: Long = 30_000) {
        val now = System.currentTimeMillis()
        val last = noticeTimes[key] ?: 0L
        if (now - last < intervalMs) return
        noticeTimes[key] = now
        onNotice(text)
    }

    private val p2pReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val p2pState = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    if (p2pState == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                        log("Wi-Fi Direct enabled")
                        _errorDetail.value = null
                        if (handledGroupKey == null) {
                            _state.value = TransportState.SCANNING
                            startPeerDiscovery()
                        }
                    } else {
                        log("Wi-Fi Direct disabled (state $p2pState)")
                        _state.value = TransportState.ERROR
                        _errorDetail.value = "Turn Wi-Fi on (no internet needed)"
                        notice("p2p-off", "Wi-Fi is off. Wi-Fi Direct needs Wi-Fi turned on.", 60_000)
                    }
                }

                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()

                // The monitor below polls as well, because Android often withholds this broadcast
                // from background apps. A "disconnected" report is only meaningful if we had a group:
                // during negotiation it is normal noise, and reacting to it (for example by restarting
                // discovery) can break the connection that is being set up.
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> checkConnectionState()
            }
        }
    }

    override fun isAvailable(): Boolean = wifiP2pManager != null

    override suspend fun start() = withContext(Dispatchers.IO) {
        if (started) return@withContext
        if (!isAvailable()) {
            _state.value = TransportState.ERROR
            _errorDetail.value = "This device has no Wi-Fi Direct"
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
            ContextCompat.registerReceiver(context, p2pReceiver, intentFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
            isReceiverRegistered = true
        }

        _state.value = TransportState.SCANNING
        startPeerDiscovery()
        startServerSocket()
        startConnectionMonitor()
        log("Wi-Fi Direct started")
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        started = false
        connectionMonitorJob?.cancel()
        connectionMonitorJob = null
        connectTimeoutJob?.cancel()
        helloJob?.cancel()

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
            p2p.cancelConnect(ch, null)
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

        resetGroupState()
        _discoveredPeers.value = emptyList()
        _state.value = TransportState.IDLE
        log("Wi-Fi Direct stopped")
    }

    /** Call after the pairing code changed: introduce ourselves again right away. */
    fun onPairingChanged() {
        val go = groupOwnerAddress
        if (!isGroupOwner && go != null) {
            scope.launch(Dispatchers.IO) { sendHelloOnce(go) }
        }
    }

    // ---------------------------------------------------------------- discovery

    fun startPeerDiscovery(retryIfBusy: Boolean = true) {
        val p2p = wifiP2pManager ?: return
        val ch = channel ?: return
        lastDiscoveryAt = System.currentTimeMillis()

        p2p.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                log("Looking for nearby devices")
            }

            override fun onFailure(reason: Int) {
                log("Discovery failed: ${reasonText(reason)}")
                if (reason == WifiP2pManager.BUSY && retryIfBusy) {
                    scope.launch(Dispatchers.IO) {
                        delay(3000)
                        if (started && handledGroupKey == null) startPeerDiscovery(retryIfBusy = false)
                    }
                } else if (reason == WifiP2pManager.ERROR || reason == WifiP2pManager.P2P_UNSUPPORTED) {
                    notice(
                        "discovery",
                        "Wi-Fi Direct couldn't search for devices (${reasonText(reason)}). " +
                            "Check that Wi-Fi is on" +
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) " and Location is enabled." else "."
                    )
                }
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
                            name = device.deviceName?.takeIf { it.isNotBlank() }
                                ?: "Android (${device.deviceAddress.takeLast(5)})",
                            transportType = TransportType.WIFI_DIRECT_SOCKET,
                            isConnected = device.status == WifiP2pDevice.CONNECTED
                        )
                    )
                }
            }
            _discoveredPeers.value = list
        }
    }

    /** By default every nearby Wi-Fi Direct device is listed; the setting can hide TVs, printers, speakers... */
    private fun isEligiblePeer(device: WifiP2pDevice): Boolean =
        settingsProvider().wifiShowAll || WifiDeviceTypes.isPhoneLike(device.primaryDeviceType)

    private fun reasonText(reason: Int): String = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct is not supported"
        WifiP2pManager.BUSY -> "the Wi-Fi system is busy"
        WifiP2pManager.ERROR -> "system error"
        WifiP2pManager.NO_SERVICE_REQUESTS -> "no service requests"
        else -> "code $reason"
    }

    // ---------------------------------------------------------------- connecting

    override suspend fun connect(peerId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val p2p = wifiP2pManager
        val ch = channel
        if (p2p == null || ch == null) {
            return@withContext Result.failure(IllegalStateException("Wi-Fi Direct is not running"))
        }

        _state.value = TransportState.CONNECTING
        pendingConnectMac = peerId
        log("Connecting to ${DiagLog.mask(peerId)}")

        suspendCoroutine<Result<Unit>> { cont ->
            // Stop discovery first: Android negotiates the group more reliably without it.
            p2p.stopPeerDiscovery(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = initiateGroupConnect(p2p, ch, peerId, cont, retryIfBusy = true)
                override fun onFailure(reason: Int) = initiateGroupConnect(p2p, ch, peerId, cont, retryIfBusy = true)
            })
        }
    }

    private fun initiateGroupConnect(
        p2p: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        peerId: String,
        cont: Continuation<Result<Unit>>,
        retryIfBusy: Boolean
    ) {
        val config = WifiP2pConfig().apply {
            deviceAddress = peerId
            // Push Button Configuration: the other phone only has to tap "Accept".
            wps.setup = WpsInfo.PBC
        }

        p2p.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                log("Connection request sent")
                notice("request-sent", "Request sent. Tap Accept on the other phone.", 5_000)
                scheduleRequestTimeout()
                cont.resume(Result.success(Unit))
            }

            override fun onFailure(reason: Int) {
                if (reason == WifiP2pManager.BUSY && retryIfBusy) {
                    // A leftover group or half-finished attempt is in the way: clear it and try once more.
                    log("Wi-Fi Direct busy, clearing old state and retrying")
                    p2p.cancelConnect(ch, null)
                    p2p.removeGroup(ch, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() = retryLater()
                        override fun onFailure(reason: Int) = retryLater()

                        private fun retryLater() {
                            scope.launch(Dispatchers.IO) {
                                delay(1000)
                                initiateGroupConnect(p2p, ch, peerId, cont, retryIfBusy = false)
                            }
                        }
                    })
                } else {
                    log("Connect failed: ${reasonText(reason)}")
                    pendingConnectMac = null
                    _state.value = TransportState.SCANNING
                    startPeerDiscovery()
                    cont.resume(Result.failure(IOException("Wi-Fi Direct connect failed: ${reasonText(reason)}")))
                }
            }
        })
    }

    private fun scheduleRequestTimeout() {
        connectTimeoutJob?.cancel()
        connectTimeoutJob = scope.launch(Dispatchers.IO) {
            delay(REQUEST_ACCEPT_TIMEOUT_MS)
            if (started && handledGroupKey == null && _state.value == TransportState.CONNECTING) {
                log("Nobody accepted the request in time")
                val p2p = wifiP2pManager
                val ch = channel
                if (p2p != null && ch != null) p2p.cancelConnect(ch, null)
                pendingConnectMac = null
                _state.value = TransportState.SCANNING
                onNotice("The other phone didn't accept in time. Open RadioClip there, try again, and tap Accept.")
                startPeerDiscovery()
            }
        }
    }

    // ---------------------------------------------------------------- group state

    /** Polls the connection: Android doesn't always deliver the broadcasts to background apps. */
    private fun startConnectionMonitor() {
        connectionMonitorJob?.cancel()
        connectionMonitorJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(if (_state.value == TransportState.CONNECTING) 1500L else 5000L)
                checkConnectionState()
                pruneSilentClients()
                refreshDiscoveryIfStale()
            }
        }
    }

    private fun checkConnectionState() {
        val p2p = wifiP2pManager ?: return
        val ch = channel ?: return
        p2p.requestConnectionInfo(ch) { info: WifiP2pInfo? ->
            if (info != null && info.groupFormed) {
                handleGroupFormed(info)
            } else if (handledGroupKey != null) {
                log("Group ended")
                handleGroupDisconnected()
            }
        }
    }

    /** Android ends discovery by itself after about two minutes; start it again while nobody is connected. */
    private fun refreshDiscoveryIfStale() {
        if (handledGroupKey != null || _state.value == TransportState.CONNECTING || !started) return
        if (System.currentTimeMillis() - lastDiscoveryAt > DISCOVERY_REFRESH_MS) startPeerDiscovery()
    }

    private fun handleGroupFormed(info: WifiP2pInfo) {
        val owner = info.isGroupOwner
        val goAddress = info.groupOwnerAddress?.hostAddress
        val key = "$owner/$goAddress"
        if (key == handledGroupKey) return
        handledGroupKey = key

        isGroupOwner = owner
        groupOwnerAddress = goAddress
        connectTimeoutJob?.cancel()
        log("Group formed: we are the ${if (owner) "group owner" else "client"}, owner ip ${goAddress ?: "?"}")

        startServerSocket()

        if (owner) {
            // Clients register themselves with a hello; until one does there is nobody to talk to.
            _state.value = if (connectedClientIps.isEmpty()) TransportState.CONNECTING else TransportState.CONNECTED
            updateConnectedPeersList()
        } else {
            // This is the case that used to be missed when the OTHER phone started the connection.
            _state.value = TransportState.CONNECTED
            ownerPeer = PeerDevice(
                id = pendingConnectMac ?: goAddress ?: "p2p_group",
                name = "Wi-Fi Direct device",
                transportType = TransportType.WIFI_DIRECT_SOCKET,
                isConnected = true
            )
            updateConnectedPeersList()
            resolveGroupOwner(goAddress)
            startHelloLoop(goAddress)
        }
    }

    /** Looks up the name and address of the phone that owns the group we joined. */
    private fun resolveGroupOwner(goAddress: String?) {
        val p2p = wifiP2pManager ?: return
        val ch = channel ?: return
        p2p.requestGroupInfo(ch) { group: WifiP2pGroup? ->
            val device = group?.owner ?: return@requestGroupInfo
            ownerPeer = PeerDevice(
                id = device.deviceAddress?.takeIf { it.isNotBlank() } ?: pendingConnectMac ?: goAddress ?: "p2p_group",
                name = device.deviceName?.takeIf { it.isNotBlank() } ?: "Wi-Fi Direct device",
                transportType = TransportType.WIFI_DIRECT_SOCKET,
                isConnected = true
            )
            updateConnectedPeersList()
        }
    }

    private fun handleGroupDisconnected() {
        resetGroupState()
        if (started) {
            _state.value = TransportState.SCANNING
            startPeerDiscovery()
        }
    }

    private fun resetGroupState() {
        handledGroupKey = null
        helloJob?.cancel()
        helloJob = null
        groupOwnerAddress = null
        isGroupOwner = false
        ownerPeer = null
        pendingConnectMac = null
        connectedClientIps.clear()
        clientNames.clear()
        _connectedPeers.value = emptyList()
    }

    private fun updateConnectedPeersList() {
        val list = mutableListOf<PeerDevice>()
        if (isGroupOwner) {
            connectedClientIps.keys.forEach { ip ->
                list.add(
                    PeerDevice(
                        id = ip,
                        name = clientNames[ip] ?: "Client ($ip)",
                        transportType = TransportType.WIFI_DIRECT_SOCKET,
                        isConnected = true
                    )
                )
            }
            _state.value = if (list.isEmpty()) TransportState.CONNECTING else TransportState.CONNECTED
        } else {
            ownerPeer?.let { list.add(it) }
        }
        _connectedPeers.value = list
    }

    /** GO side: forget clients that stopped sending heartbeats (phone walked away, Wi-Fi turned off...). */
    private fun pruneSilentClients() {
        if (!isGroupOwner || handledGroupKey == null) return
        val now = System.currentTimeMillis()
        val gone = connectedClientIps.filterValues { now - it > CLIENT_TIMEOUT_MS }.keys
        if (gone.isEmpty()) return
        gone.forEach {
            connectedClientIps.remove(it)
            clientNames.remove(it)
            log("Client ${DiagLog.mask(it)} went silent")
        }
        updateConnectedPeersList()
    }

    // ---------------------------------------------------------------- hello (client -> group owner)

    private fun startHelloLoop(goAddress: String?) {
        helloJob?.cancel()
        if (goAddress.isNullOrBlank()) return
        helloJob = scope.launch(Dispatchers.IO) {
            delay(1000) // let DHCP/routing settle
            var failures = 0
            while (isActive) {
                if (sendHelloOnce(goAddress)) {
                    failures = 0
                    delay(HELLO_INTERVAL_MS)
                } else {
                    failures++
                    delay((2000L * failures).coerceAtMost(10_000L))
                }
            }
        }
    }

    /** Sends one encrypted hello (our name and Wi-Fi Direct address) to the group owner. */
    private fun sendHelloOnce(goAddress: String): Boolean {
        val key = keyProvider() ?: return false   // not paired yet; the loop retries
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(goAddress, PORT), CONNECT_TIMEOUT_MS)
                socket.soTimeout = IO_TIMEOUT_MS
                val output = DataOutputStream(SecureOutputStream(socket.getOutputStream(), key))
                output.writeByte(TYPE_HELLO.toInt())
                output.writeUTF(UUID.randomUUID().toString())
                output.writeLong(System.currentTimeMillis())
                output.writeUTF(settingsProvider().announcedName(Build.MODEL ?: "Android"))
                output.flush()
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Hello to group owner failed: ${e.message}")
            false
        }
    }

    // ---------------------------------------------------------------- receiving

    private fun startServerSocket() {
        if (serverJob?.isActive == true) return

        serverJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val server = ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress(PORT))
                    }
                    serverSocket = server
                    log("Listening on port $PORT")

                    while (isActive) {
                        val socket = server.accept()
                        if (!connectionLimiter.tryAcquire()) {
                            log("Too many simultaneous connections, dropping one")
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
                    if (!isActive) return@launch
                    // For example the port is still held by a previous run: wait and try again.
                    log("Server socket error: ${e.message}")
                    runCatching { serverSocket?.close() }
                    serverSocket = null
                    delay(2000)
                }
            }
        }
    }

    private fun senderNameFor(clientIp: String?): String {
        if (isGroupOwner) return clientNames[clientIp] ?: "Client ($clientIp)"
        return ownerPeer?.name ?: "Group owner ($clientIp)"
    }

    private suspend fun handleIncomingConnection(socket: Socket) = withContext(Dispatchers.IO) {
        socket.use { s ->
            try {
                val key = keyProvider()
                if (key == null) {
                    log("Dropped a connection: this device is not paired")
                    return@use
                }
                s.soTimeout = IO_TIMEOUT_MS

                // Everything below is authenticated: a wrong pairing code fails on the first read.
                val input = DataInputStream(SecureInputStream(s.getInputStream(), key))
                val wireType = input.readByte()
                val payloadId = input.readUTF()
                val timestamp = input.readLong()

                // Only authenticated peers count as clients.
                val clientIp = s.inetAddress?.hostAddress
                if (isGroupOwner && clientIp != null) {
                    val isNew = connectedClientIps.put(clientIp, System.currentTimeMillis()) == null
                    if (isNew) {
                        log("Client ${DiagLog.mask(clientIp)} joined")
                        updateConnectedPeersList()
                    }
                }

                when (wireType) {
                    TYPE_HELLO -> {
                        val name = BleMessages.cleanName(input.readUTF())
                        if (isGroupOwner && clientIp != null && name.isNotEmpty() && clientNames.put(clientIp, name) != name) {
                            updateConnectedPeersList()
                        }
                    }

                    TYPE_TEXT -> {
                        val length = input.readInt()
                        require(length in 0..MAX_TEXT_BYTES) { "Text payload size out of range: $length" }
                        val bytes = ByteArray(length)
                        input.readFully(bytes)

                        _incomingPayloads.emit(
                            Payload.Text(
                                id = payloadId,
                                timestamp = timestamp,
                                transportType = TransportType.WIFI_DIRECT_SOCKET,
                                senderDeviceName = senderNameFor(clientIp),
                                isOutgoing = false,
                                content = String(bytes, Charsets.UTF_8)
                            )
                        )
                        log("Received text (${bytes.size} bytes)")
                    }

                    TYPE_FILE -> {
                        val fileName = input.readUTF()
                        val mimeType = input.readUTF().take(MAX_MIME_LENGTH)
                        val fileSize = input.readLong()

                        require(fileSize in 0..MAX_FILE_BYTES) { "File size out of range: $fileSize" }
                        val dir = AppFiles.receivedDir(context)
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
                                senderDeviceName = senderNameFor(clientIp),
                                isOutgoing = false,
                                fileName = targetFile.name,
                                mimeType = mimeType,
                                sizeBytes = fileSize,
                                localPath = targetFile.absolutePath
                            )
                        )
                        log("Received file ${targetFile.name} ($fileSize bytes)")
                    }

                    else -> log("Unknown message type $wireType")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: FrameAuthenticationException) {
                log("A device connected but its data couldn't be verified (different pairing code?)")
                notice(
                    "auth",
                    "A nearby device sent data that couldn't be verified. Check that both devices use the same pairing code."
                )
            } catch (e: Exception) {
                log("Error handling incoming connection: ${e.message}")
            }
        }
    }

    // ---------------------------------------------------------------- sending

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
        try {
            val key = keyProvider() ?: error("Not paired: enter the same pairing code on both devices first")

            val destinations: List<String> = when {
                targetPeerId != null && targetPeerId.contains(".") -> listOf(targetPeerId)
                !isGroupOwner && !groupOwnerAddress.isNullOrBlank() -> listOf(groupOwnerAddress!!)
                // Group owner: deliver to every client, not just the first one.
                isGroupOwner && connectedClientIps.isNotEmpty() -> connectedClientIps.keys.toList()
                else -> error("No device is connected over Wi-Fi Direct yet")
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
                    log("Send to ${DiagLog.mask(ip)} failed: ${e.message}")
                    lastError = e
                }
            }
            if (delivered == 0) throw (lastError ?: IllegalStateException("Nothing was sent"))
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun sendTo(ip: String, payload: Payload, prepared: PreparedFile?, key: ByteArray) {
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
        }
    }
}
