package com.almog.radioclip.transport.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.almog.radioclip.core.Advertisement
import com.almog.radioclip.core.BleMessage
import com.almog.radioclip.core.BleMessages
import com.almog.radioclip.core.BleMode
import com.almog.radioclip.core.ChunkCodec
import com.almog.radioclip.core.ChunkReassembler
import com.almog.radioclip.core.Crypto
import com.almog.radioclip.core.DiagLog
import com.almog.radioclip.core.Format
import com.almog.radioclip.core.Settings
import com.almog.radioclip.model.Payload
import com.almog.radioclip.model.PeerDevice
import com.almog.radioclip.model.TransportState
import com.almog.radioclip.model.TransportType
import com.almog.radioclip.transport.DeviceTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bluetooth Low Energy transport.
 *
 * Every device is both a GATT server (advertises, receives writes, sends notifications) and a GATT
 * client (scans, connects, writes). Improvements over a naive design:
 *  - Advertisements carry a tag derived from the pairing code and a random node id: devices only
 *    connect to peers with the same code, and only ONE side of a pair opens the connection.
 *  - Every message is encrypted; devices exchange their names inside the encrypted channel.
 *  - Sending waits for the radio's acknowledgement of each packet instead of guessing delays.
 *  - Reconnects back off, scanning isn't restarted needlessly, and Bluetooth being switched off
 *    and on again is handled.
 */
@SuppressLint("MissingPermission")
class BleEngine(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + Job()),
    private val keyProvider: () -> ByteArray? = { null },
    private val settingsProvider: () -> Settings = { Settings() },
    private val nodeId: Int = 0,
    private val onNotice: (String) -> Unit = {}
) : DeviceTransport {

    companion object {
        private const val TAG = "BleEngine"
        val SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val REQUESTED_MTU = 512
        private const val DEFAULT_MTU = 23              // what BLE guarantees before MTU negotiation
        private const val MAX_CHUNK_DATA = 500
        private const val BUSY_RETRY_DELAY_MS = 10L
        private const val BUSY_RETRY_LIMIT = 300        // about 3 s per packet before giving up
        private const val ACK_TIMEOUT_MS = 1500L
        private const val BOOST_MIN_CHUNKS = 4          // ask for a faster link for bigger messages
        private const val RECONNECT_BASE_MS = 3000L
        private const val RECONNECT_MAX_MS = 60_000L
        private const val SCAN_MODE_DEBOUNCE_MS = 5000L
        private const val STALE_PEER_MS = 90_000L
        private const val MAINTENANCE_INTERVAL_MS = 15_000L
        private const val AUTH_NOTICE_INTERVAL_MS = 30_000L
    }

    override val transportType: TransportType = TransportType.BLE_GATT

    private val _state = MutableStateFlow(TransportState.IDLE)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    private val _discoveredPeers = MutableStateFlow<List<PeerDevice>>(emptyList())
    override val discoveredPeers: StateFlow<List<PeerDevice>> = _discoveredPeers.asStateFlow()

    private val _connectedPeers = MutableStateFlow<List<PeerDevice>>(emptyList())
    override val connectedPeers: StateFlow<List<PeerDevice>> = _connectedPeers.asStateFlow()

    private val _incomingPayloads = MutableSharedFlow<Payload>(extraBufferCapacity = 64)
    override val incomingPayloads: SharedFlow<Payload> = _incomingPayloads.asSharedFlow()

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private var gattServer: BluetoothGattServer? = null

    // A peer can be connected in two roles: we connected to it (client) and/or it connected to us (server).
    private val activeGattClients = ConcurrentHashMap<String, BluetoothGatt>()
    private val readyClients = ConcurrentHashMap.newKeySet<String>()        // services found, notifications on
    private val connectingAddresses = ConcurrentHashMap.newKeySet<String>() // connectGatt in flight
    private val serverConnectedDevices = ConcurrentHashMap<String, BluetoothDevice>()
    private val subscribedDevices = ConcurrentHashMap.newKeySet<String>()   // server-side peers listening for notifications
    private val scannedDevicesMap = ConcurrentHashMap<String, BluetoothDevice>()
    private val remoteNodeIds = ConcurrentHashMap<String, Int>()
    private val lastSeen = ConcurrentHashMap<String, Long>()
    private val peerNames = ConcurrentHashMap<String, String>()
    private val clientMtuMap = ConcurrentHashMap<String, Int>()
    private val serverMtuMap = ConcurrentHashMap<String, Int>()
    private val writeAcks = ConcurrentHashMap<String, Channel<Int>>()
    private val notifyAcks = ConcurrentHashMap<String, Channel<Int>>()
    private val targetReconnectAddresses = ConcurrentHashMap.newKeySet<String>()
    private val reconnectAttempts = ConcurrentHashMap<String, Int>()

    // One reassembler per sender and role, so chunks of different peers never mix.
    private val clientReassemblers = ConcurrentHashMap<String, ChunkReassembler>()
    private val serverReassemblers = ConcurrentHashMap<String, ChunkReassembler>()

    private val sendMutex = Mutex()
    private val peersLock = Any()
    private val messageCounter = AtomicInteger(SecureRandom().nextInt())

    @Volatile private var started = false
    @Volatile private var radiosUp = false
    @Volatile private var receiverRegistered = false
    @Volatile private var currentScanMode = -1
    @Volatile private var slimAdvertising = false
    @Volatile private var lastAuthNotice = 0L
    private var maintenanceJob: Job? = null
    private var scanModeJob: Job? = null

    private fun log(message: String) = DiagLog.d(TAG, message)

    // ---------------------------------------------------------------- callbacks

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            log("Advertising started${if (slimAdvertising) " (short form)" else ""}")
            if (_connectedPeers.value.isEmpty()) _state.value = TransportState.SCANNING
        }

        override fun onStartFailure(errorCode: Int) {
            log("Advertising failed: code $errorCode")
            if (errorCode == ADVERTISE_FAILED_DATA_TOO_LARGE && !slimAdvertising) {
                // Some phones can't fit our tag; fall back to a plain advertisement (encryption still protects).
                slimAdvertising = true
                scope.launch(Dispatchers.IO) { restartAdvertising() }
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.let { handleScanResult(it) }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { handleScanResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            log("Scan failed: code $errorCode")
            if (errorCode == SCAN_FAILED_ALREADY_STARTED) return
            currentScanMode = -1
            // Typically "scanning too frequently": wait a bit and try again.
            scope.launch(Dispatchers.IO) {
                delay(10_000)
                if (radiosUp) startScanning(force = true)
            }
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val address = device.address
            log("Server link ${DiagLog.mask(address)} state=$newState status=$status")

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                serverConnectedDevices[address] = device
                updateConnectedPeers(address, nameFor(address, device), true)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                serverConnectedDevices.remove(address)
                subscribedDevices.remove(address)
                serverReassemblers.remove(address)
                serverMtuMap.remove(address)
                notifyAcks.remove(address)
                updateConnectedPeers(address, nameFor(address, device), false)
                // The other side normally reconnects (it is the one with the higher node id).
                triggerAutoReconnect(address)
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            // Nothing to read here; answer so the other side doesn't wait for a timeout.
            try {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, ByteArray(0))
            } catch (e: Exception) {
                Log.w(TAG, "Failed sending read response", e)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (responseNeeded) {
                try {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed sending GATT response", e)
                }
            }
            if (value != null && characteristic.uuid == CHARACTERISTIC_UUID) {
                handleIncomingChunk(serverReassemblers, device.address, value)
            }
        }

        // Peers subscribe to notifications by writing the CCCD descriptor.
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (responseNeeded) {
                try {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed sending descriptor response", e)
                }
            }
            if (descriptor.uuid == CCCD_UUID) {
                val address = device.address
                val enabled = value != null && value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                if (enabled) {
                    subscribedDevices.add(address)
                    log("Peer ${DiagLog.mask(address)} subscribed")
                    scope.launch(Dispatchers.IO) { sendHello(address) }
                } else {
                    subscribedDevices.remove(address)
                }
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            notifyAcks[device.address]?.trySend(status)
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            log("Server MTU for ${DiagLog.mask(device.address)}: $mtu")
            serverMtuMap[device.address] = mtu
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_OFF -> scope.launch(Dispatchers.IO) {
                    log("Bluetooth turned off")
                    teardownRadios()
                }
                BluetoothAdapter.STATE_ON -> scope.launch(Dispatchers.IO) {
                    log("Bluetooth turned on")
                    if (started && isAvailable() && !radiosUp) bringUp()
                }
            }
        }
    }

    // ---------------------------------------------------------------- lifecycle

    private fun hasBlePermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            fun granted(permission: String) =
                ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            return granted(Manifest.permission.BLUETOOTH_CONNECT) &&
                granted(Manifest.permission.BLUETOOTH_SCAN) &&
                granted(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        return true
    }

    override fun isAvailable(): Boolean = bluetoothAdapter?.isEnabled == true && hasBlePermissions()

    /** Safe to call repeatedly: does the work once, and picks up late permission grants. */
    override suspend fun start() = withContext(Dispatchers.IO) {
        started = true
        registerBluetoothReceiver()
        if (!isAvailable()) {
            _state.value = TransportState.IDLE
            return@withContext
        }
        if (radiosUp) return@withContext
        bringUp()
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        started = false
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(bluetoothReceiver)
            } catch (e: Exception) {
                Log.w(TAG, "Failed unregistering receiver", e)
            }
            receiverRegistered = false
        }
        targetReconnectAddresses.clear()
        peerNames.clear()
        teardownRadios()
    }

    private fun registerBluetoothReceiver() {
        if (receiverRegistered) return
        ContextCompat.registerReceiver(
            context, bluetoothReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
    }

    private fun bringUp() {
        radiosUp = true
        _state.value = TransportState.SCANNING
        setupGattServer()
        startAdvertising()
        startScanning(force = true)
        startMaintenance()
        log("Bluetooth ready (paired=${keyProvider() != null})")
    }

    private fun teardownRadios() {
        radiosUp = false
        maintenanceJob?.cancel()
        scanModeJob?.cancel()
        stopScanning()
        stopAdvertising()
        closeGattServer()
        activeGattClients.values.forEach {
            try {
                it.disconnect()
                it.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing client", e)
            }
        }
        activeGattClients.clear()
        readyClients.clear()
        connectingAddresses.clear()
        serverConnectedDevices.clear()
        subscribedDevices.clear()
        scannedDevicesMap.clear()
        remoteNodeIds.clear()
        lastSeen.clear()
        clientMtuMap.clear()
        serverMtuMap.clear()
        writeAcks.clear()
        notifyAcks.clear()
        clientReassemblers.clear()
        serverReassemblers.clear()
        reconnectAttempts.clear()
        currentScanMode = -1
        synchronized(peersLock) {
            _discoveredPeers.value = emptyList()
            _connectedPeers.value = emptyList()
        }
        _state.value = TransportState.IDLE
    }

    // ---------------------------------------------------------------- public controls

    /** Re-applies settings that change how we advertise and scan (power mode, device name). */
    fun applySettings() {
        scope.launch(Dispatchers.IO) {
            if (!radiosUp) return@launch
            restartAdvertising()
            startScanning(force = true)
            announceName()
        }
    }

    /** Call after the pairing code changed: new tag, fresh scan, and tell peers our name again. */
    fun onPairingChanged() {
        scope.launch(Dispatchers.IO) {
            if (!radiosUp) return@launch
            synchronized(peersLock) {
                _discoveredPeers.value = _discoveredPeers.value.filter { it.isConnected }
            }
            restartAdvertising()
            startScanning(force = true)
            announceName()
        }
    }

    /** Clears the list of found devices and scans again (for the Rescan button). */
    fun rescan() {
        scope.launch(Dispatchers.IO) {
            if (!radiosUp) return@launch
            synchronized(peersLock) {
                _discoveredPeers.value = _discoveredPeers.value.filter { it.isConnected }
            }
            startScanning(force = true)
        }
    }

    /** Sends our (possibly changed) device name to every connected peer. */
    fun announceName() {
        scope.launch(Dispatchers.IO) {
            sendableAddresses().forEach { sendHello(it) }
        }
    }

    // ---------------------------------------------------------------- GATT server

    private fun setupGattServer() {
        if (!hasBlePermissions()) return
        val manager = bluetoothManager ?: return
        try {
            closeGattServer()
            gattServer = manager.openGattServer(context, gattServerCallback)?.apply {
                val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
                val characteristic = BluetoothGattCharacteristic(
                    CHARACTERISTIC_UUID,
                    BluetoothGattCharacteristic.PROPERTY_READ or
                        BluetoothGattCharacteristic.PROPERTY_WRITE or
                        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                        BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                    BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
                )
                // Client Characteristic Configuration Descriptor: required for notifications.
                characteristic.addDescriptor(
                    BluetoothGattDescriptor(
                        CCCD_UUID,
                        BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
                    )
                )
                service.addCharacteristic(characteristic)
                addService(service)
            }
        } catch (e: Exception) {
            log("GATT server setup error: ${e.message}")
        }
    }

    private fun closeGattServer() {
        try {
            gattServer?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing GATT server", e)
        }
        gattServer = null
    }

    // ---------------------------------------------------------------- advertising

    private fun restartAdvertising() {
        stopAdvertising()
        startAdvertising()
    }

    private fun startAdvertising() {
        if (!hasBlePermissions()) return
        val advertiser = bluetoothAdapter?.bluetoothLeAdvertiser ?: return
        val mode = settingsProvider().bleMode

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(
                when (mode) {
                    BleMode.LOW_POWER -> AdvertiseSettings.ADVERTISE_MODE_LOW_POWER
                    BleMode.BALANCED -> AdvertiseSettings.ADVERTISE_MODE_BALANCED
                    BleMode.LOW_LATENCY -> AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
                }
            )
            .setTxPowerLevel(
                if (mode == BleMode.LOW_LATENCY) AdvertiseSettings.ADVERTISE_TX_POWER_HIGH
                else AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM
            )
            .setConnectable(true)
            .setTimeout(0)
            .build()

        // Flags (3) + 128-bit service uuid (18) + manufacturer data (10) = 31 bytes, the legacy limit.
        // The device name is deliberately NOT advertised: it is sent encrypted after connecting.
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .apply {
                if (!slimAdvertising) {
                    addManufacturerData(Advertisement.COMPANY_ID, Advertisement.encode(keyProvider(), nodeId))
                }
            }
            .build()

        try {
            advertiser.startAdvertising(settings, data, advertiseCallback)
        } catch (e: Exception) {
            log("Start advertising error: ${e.message}")
        }
    }

    private fun stopAdvertising() {
        if (!hasBlePermissions()) return
        try {
            bluetoothAdapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Stop advertising error", e)
        }
    }

    // ---------------------------------------------------------------- scanning

    private fun scanModeFor(): Int {
        val connected = _connectedPeers.value.isNotEmpty()
        return when (settingsProvider().bleMode) {
            BleMode.LOW_POWER -> ScanSettings.SCAN_MODE_LOW_POWER
            BleMode.BALANCED -> if (connected) ScanSettings.SCAN_MODE_LOW_POWER else ScanSettings.SCAN_MODE_BALANCED
            BleMode.LOW_LATENCY -> if (connected) ScanSettings.SCAN_MODE_BALANCED else ScanSettings.SCAN_MODE_LOW_LATENCY
        }
    }

    /**
     * (Re)starts scanning. Android only allows a handful of scan starts per 30 seconds, so unless
     * [force] is set this does nothing when the scan mode would not change.
     */
    private fun startScanning(force: Boolean = false) {
        if (!hasBlePermissions()) return
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        val mode = scanModeFor()
        if (!force && mode == currentScanMode) return

        val settings = ScanSettings.Builder()
            .setScanMode(mode)
            .setReportDelay(0)
            .build()
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        try {
            scanner.stopScan(scanCallback)
            scanner.startScan(listOf(filter), settings, scanCallback)
            currentScanMode = mode
        } catch (e: Exception) {
            currentScanMode = -1
            log("Start scanning error: ${e.message}")
        }
    }

    private fun stopScanning() {
        currentScanMode = -1
        if (!hasBlePermissions()) return
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Stop scan error", e)
        }
    }

    /** Waits a few seconds before changing the scan mode so a flapping link doesn't restart scans repeatedly. */
    private fun scheduleScanModeUpdate() {
        scanModeJob?.cancel()
        scanModeJob = scope.launch(Dispatchers.IO) {
            delay(SCAN_MODE_DEBOUNCE_MS)
            if (radiosUp) startScanning(force = false)
        }
    }

    private fun startMaintenance() {
        maintenanceJob?.cancel()
        maintenanceJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(MAINTENANCE_INTERVAL_MS)
                pruneStalePeers()
            }
        }
    }

    /** Drops found-but-not-connected devices that stopped advertising. */
    private fun pruneStalePeers() {
        val now = System.currentTimeMillis()
        synchronized(peersLock) {
            val kept = _discoveredPeers.value.filter { peer ->
                peer.isConnected || now - (lastSeen[peer.id] ?: now) <= STALE_PEER_MS
            }
            if (kept.size != _discoveredPeers.value.size) _discoveredPeers.value = kept
        }
    }

    private fun handleScanResult(result: ScanResult) {
        val device = result.device ?: return
        val record = result.scanRecord ?: return
        if (record.serviceUuids?.any { it.uuid == SERVICE_UUID } != true) return

        val address = device.address
        val key = keyProvider()
        val info = Advertisement.decode(record.getManufacturerSpecificData(Advertisement.COMPANY_ID))
        val kind = Advertisement.classify(key, info)

        // When we have a pairing code, devices that use another code (or none) are not for us.
        if (key != null &&
            (kind == Advertisement.PeerClass.OTHER_GROUP || kind == Advertisement.PeerClass.UNPAIRED_REMOTE)
        ) {
            removeDiscovered(address)
            return
        }

        scannedDevicesMap[address] = device
        lastSeen[address] = System.currentTimeMillis()
        if (info != null) remoteNodeIds[address] = info.nodeId

        upsertDiscovered(
            PeerDevice(
                id = address,
                name = nameFor(address, device),
                transportType = TransportType.BLE_GATT,
                isConnected = _connectedPeers.value.any { it.id == address },
                rssi = Format.quantizeRssi(result.rssi)
            )
        )

        // Connecting needs a pairing code (nothing can be exchanged without one).
        if (key == null) return
        // Both phones see each other; only the one with the higher node id connects.
        if (info != null && !Advertisement.shouldInitiate(nodeId, info.nodeId)) return

        if (!activeGattClients.containsKey(address) && _connectedPeers.value.none { it.id == address }) {
            log("Connecting to ${DiagLog.mask(address)}")
            connectToPeerDevice(device)
        }
    }

    private fun upsertDiscovered(peer: PeerDevice) = synchronized(peersLock) {
        val list = _discoveredPeers.value.toMutableList()
        val index = list.indexOfFirst { it.id == peer.id }
        if (index == -1) {
            list.add(peer)
            _discoveredPeers.value = list
            log("Found ${DiagLog.mask(peer.id)} ${Format.signalLabel(peer.rssi)}")
        } else if (list[index] != peer) {
            list[index] = peer
            _discoveredPeers.value = list
        }
    }

    private fun removeDiscovered(address: String) = synchronized(peersLock) {
        val list = _discoveredPeers.value
        if (list.any { it.id == address }) _discoveredPeers.value = list.filter { it.id != address }
    }

    // ---------------------------------------------------------------- connecting

    override suspend fun connect(peerId: String): Result<Unit> = withContext(Dispatchers.IO) {
        resultOf {
            if (!hasBlePermissions()) error("Missing Bluetooth permissions")
            val device = scannedDevicesMap[peerId]
                ?: bluetoothAdapter?.getRemoteDevice(peerId)
                ?: error("Bluetooth device $peerId not found")
            targetReconnectAddresses.add(peerId)
            connectToPeerDevice(device)
        }
    }

    private fun connectToPeerDevice(device: BluetoothDevice) {
        if (!hasBlePermissions()) return
        val address = device.address
        // Scan results arrive many times per second: only one connection attempt per peer at a time.
        if (!connectingAddresses.add(address)) return
        if (_connectedPeers.value.isEmpty()) _state.value = TransportState.CONNECTING

        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    connectingAddresses.remove(address)
                    log("Linked to ${DiagLog.mask(address)}, negotiating")
                    try {
                        gatt.requestMtu(REQUESTED_MTU)
                    } catch (e: SecurityException) {
                        Log.w(TAG, "SecurityException requesting MTU", e)
                    }
                } else {
                    // Disconnected, or a failed connection attempt (for example status 133).
                    log("Link to ${DiagLog.mask(address)} closed (status=$status)")
                    connectingAddresses.remove(address)
                    val current = activeGattClients[address]
                    if (current == null || current === gatt) {
                        activeGattClients.remove(address)
                        readyClients.remove(address)
                        clientMtuMap.remove(address)
                        clientReassemblers.remove(address)
                        writeAcks.remove(address)
                        updateConnectedPeers(address, nameFor(address, gatt.device), false)
                    }
                    try {
                        gatt.close()
                    } catch (e: Exception) {
                        Log.w(TAG, "Gatt close error", e)
                    }
                    triggerAutoReconnect(address)
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    log("MTU with ${DiagLog.mask(address)}: $mtu")
                    clientMtuMap[address] = mtu
                }
                try {
                    gatt.discoverServices()
                } catch (e: SecurityException) {
                    Log.w(TAG, "SecurityException discovering services", e)
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val characteristic = if (status == BluetoothGatt.GATT_SUCCESS) {
                    gatt.getService(SERVICE_UUID)?.getCharacteristic(CHARACTERISTIC_UUID)
                } else null

                if (characteristic == null) {
                    log("RadioClip service not usable on ${DiagLog.mask(address)} (status=$status), disconnecting")
                    targetReconnectAddresses.remove(address)
                    try {
                        gatt.disconnect()
                    } catch (e: Exception) {
                        Log.w(TAG, "Disconnect error", e)
                    }
                    return
                }

                enableNotifications(gatt, characteristic)
                readyClients.add(address)
                targetReconnectAddresses.add(address)
                reconnectAttempts.remove(address)
                updateConnectedPeers(address, nameFor(address, gatt.device), true)
                log("Connected to ${DiagLog.mask(address)}")
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                if (descriptor.uuid == CCCD_UUID) {
                    // Notifications are on: introduce ourselves.
                    scope.launch(Dispatchers.IO) { sendHello(address) }
                }
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                writeAcks[address]?.trySend(status)
            }

            // Notifications from the peer's GATT server (API 33+ delivers the value directly).
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                if (characteristic.uuid == CHARACTERISTIC_UUID) {
                    handleIncomingChunk(clientReassemblers, address, value)
                }
            }

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                // Only used below API 33; newer systems call the overload above instead.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
                    characteristic.uuid == CHARACTERISTIC_UUID
                ) {
                    characteristic.value?.let { handleIncomingChunk(clientReassemblers, address, it) }
                }
            }
        }

        try {
            val gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            if (gatt != null) {
                activeGattClients[address] = gatt
            } else {
                connectingAddresses.remove(address)
            }
        } catch (e: Exception) {
            log("Connect error: ${e.message}")
            connectingAddresses.remove(address)
            if (_connectedPeers.value.isEmpty()) _state.value = TransportState.SCANNING
        }
    }

    /** Subscribes to notifications so the peer can send data to us through its GATT server. */
    private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        try {
            gatt.setCharacteristicNotification(characteristic, true)
            val cccd = characteristic.getDescriptor(CCCD_UUID) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(cccd)
            }
        } catch (e: Exception) {
            log("Enabling notifications failed: ${e.message}")
        }
    }

    /** Reconnects with growing delays (3 s, 6 s, 12 s ... up to a minute) so a missing peer doesn't drain the battery. */
    private fun triggerAutoReconnect(address: String) {
        if (!targetReconnectAddresses.contains(address) || !radiosUp) return
        // If the other side is the one that should connect, leave it to them.
        val remoteId = remoteNodeIds[address]
        if (remoteId != null && !Advertisement.shouldInitiate(nodeId, remoteId)) return

        val attempt = reconnectAttempts.merge(address, 1, Int::plus) ?: 1
        val delayMs = (RECONNECT_BASE_MS shl (attempt - 1).coerceAtMost(5)).coerceAtMost(RECONNECT_MAX_MS)
        scope.launch(Dispatchers.IO) {
            delay(delayMs)
            if (!radiosUp || activeGattClients.containsKey(address) || connectingAddresses.contains(address)) return@launch
            if (_connectedPeers.value.any { it.id == address }) return@launch
            scannedDevicesMap[address]?.let { device ->
                log("Reconnecting to ${DiagLog.mask(address)} (attempt $attempt)")
                connectToPeerDevice(device)
            }
        }
    }

    // ---------------------------------------------------------------- peer bookkeeping

    private fun nameFor(address: String, device: BluetoothDevice? = null): String =
        peerNames[address]
            ?: device?.let { runCatching { it.name }.getOrNull()?.takeIf { n -> n.isNotBlank() } }
            ?: "RadioClip (${address.takeLast(5)})"

    private fun updateConnectedPeers(address: String, name: String, isConnected: Boolean) {
        // A peer can be connected in both roles; it only counts as gone when neither is left.
        val stillConnected = isConnected ||
            serverConnectedDevices.containsKey(address) ||
            readyClients.contains(address)

        synchronized(peersLock) {
            val current = _connectedPeers.value.toMutableList()
            current.removeAll { it.id == address }
            if (stillConnected) {
                current.add(
                    PeerDevice(
                        id = address, name = name, transportType = TransportType.BLE_GATT,
                        isConnected = true, rssi = _discoveredPeers.value.firstOrNull { it.id == address }?.rssi
                    )
                )
            }
            _connectedPeers.value = current

            _discoveredPeers.value = _discoveredPeers.value.map { peer ->
                if (peer.id == address) peer.copy(isConnected = stillConnected, name = name) else peer
            }
        }

        if (radiosUp) {
            _state.value = if (_connectedPeers.value.isNotEmpty()) TransportState.CONNECTED else TransportState.SCANNING
            scheduleScanModeUpdate()
        }
    }

    private fun peerNameLearned(address: String, name: String) {
        peerNames[address] = name
        log("Peer ${DiagLog.mask(address)} is \"$name\"")
        synchronized(peersLock) {
            _connectedPeers.value = _connectedPeers.value.map { if (it.id == address) it.copy(name = name) else it }
            _discoveredPeers.value = _discoveredPeers.value.map { if (it.id == address) it.copy(name = name) else it }
        }
    }

    // ---------------------------------------------------------------- sending

    override suspend fun send(payload: Payload, targetPeerId: String?): Result<Unit> {
        if (payload !is Payload.Text) {
            return Result.failure(IllegalArgumentException("Bluetooth only carries text; files go over Wi-Fi Direct"))
        }
        return resultOf { sendMessage(BleMessages.encodeText(payload.id, payload.content), targetPeerId) }
    }

    /** Addresses we can currently send to: ready client links, and server-side peers that subscribed. */
    private fun sendableAddresses(): List<String> =
        (readyClients.toList() + subscribedDevices.filter { serverConnectedDevices.containsKey(it) }).distinct()

    private suspend fun sendHello(address: String) {
        if (keyProvider() == null) return
        val name = settingsProvider().announcedName(Build.MODEL ?: "Android")
        try {
            sendMessage(BleMessages.encodeHello(name), address)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Hello to ${DiagLog.mask(address)} failed: ${e.message}")
        }
    }

    private suspend fun sendMessage(plain: ByteArray, targetPeerId: String?) {
        sendMutex.withLock {
            withContext(Dispatchers.IO) {
                val key = keyProvider() ?: error("Not paired: enter the same pairing code on both devices first")
                // Encrypt first; the chunks below only ever carry ciphertext.
                val data = Crypto.encrypt(key, plain)
                val messageId = messageCounter.incrementAndGet()
                val addresses = if (targetPeerId != null) listOf(targetPeerId) else sendableAddresses()

                var delivered = 0
                var lastError: Throwable? = null
                for (address in addresses) {
                    try {
                        val gatt = if (readyClients.contains(address)) activeGattClients[address] else null
                        val serverDevice = if (subscribedDevices.contains(address)) serverConnectedDevices[address] else null
                        when {
                            gatt != null -> sendOverClient(gatt, messageId, data)
                            serverDevice != null -> sendOverServer(serverDevice, messageId, data)
                            else -> continue
                        }
                        delivered++
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log("Send to ${DiagLog.mask(address)} failed: ${e.message}")
                        lastError = e
                    }
                }
                if (delivered == 0) {
                    throw (lastError ?: IllegalStateException("No connected Bluetooth devices"))
                }
            }
        }
    }

    private suspend fun sendOverClient(gatt: BluetoothGatt, messageId: Int, data: ByteArray) {
        val address = gatt.device.address
        val chunks = ChunkCodec.encode(
            messageId, data, ChunkCodec.maxChunkData(clientMtuMap[address] ?: DEFAULT_MTU, MAX_CHUNK_DATA)
        )
        val characteristic = gatt.getService(SERVICE_UUID)?.getCharacteristic(CHARACTERISTIC_UUID)
            ?: error("GATT characteristic not found on ${DiagLog.mask(address)}")

        val boost = chunks.size >= BOOST_MIN_CHUNKS
        if (boost) runCatching { gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
        try {
            sendChunks(
                what = "write", chunks = chunks,
                acks = ackChannel(writeAcks, address),
                stillConnected = { activeGattClients[address] === gatt && readyClients.contains(address) },
                attempt = { writeNoResponse(gatt, characteristic, it) }
            )
        } finally {
            if (boost) runCatching { gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED) }
        }
    }

    private suspend fun sendOverServer(device: BluetoothDevice, messageId: Int, data: ByteArray) {
        val address = device.address
        val chunks = ChunkCodec.encode(
            messageId, data, ChunkCodec.maxChunkData(serverMtuMap[address] ?: DEFAULT_MTU, MAX_CHUNK_DATA)
        )
        val characteristic = gattServer?.getService(SERVICE_UUID)?.getCharacteristic(CHARACTERISTIC_UUID)
            ?: error("Server characteristic not found")

        sendChunks(
            what = "notification", chunks = chunks,
            acks = ackChannel(notifyAcks, address),
            stillConnected = { serverConnectedDevices.containsKey(address) },
            attempt = { notifyChunk(device, characteristic, it) }
        )
    }

    private fun ackChannel(map: ConcurrentHashMap<String, Channel<Int>>, address: String): Channel<Int> =
        map.getOrPut(address) { Channel(Channel.CONFLATED) }

    /**
     * Android's BLE stack handles one operation at a time. For each packet: retry until the stack
     * accepts it, then wait for its completion callback before sending the next one. If a phone
     * never reports completion we continue after a timeout rather than failing the whole message.
     */
    private suspend fun sendChunks(
        what: String,
        chunks: List<ByteArray>,
        acks: Channel<Int>,
        stillConnected: () -> Boolean,
        attempt: (ByteArray) -> Boolean
    ) {
        for (chunk in chunks) {
            var tries = 0
            while (true) {
                if (!stillConnected()) throw IOException("BLE peer disconnected during $what")
                while (acks.tryReceive().isSuccess) { /* drop stale acknowledgements */ }
                if (attempt(chunk)) break
                if (++tries >= BUSY_RETRY_LIMIT) throw IOException("BLE $what timed out (link busy or lost)")
                delay(BUSY_RETRY_DELAY_MS)
            }
            val status = withTimeoutOrNull(ACK_TIMEOUT_MS) { acks.receive() }
            if (status != null && status != BluetoothGatt.GATT_SUCCESS) {
                throw IOException("BLE $what failed (status $status)")
            }
        }
    }

    private fun writeNoResponse(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, packet: ByteArray): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                characteristic, packet, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            characteristic.value = packet
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }
    }

    private fun notifyChunk(device: BluetoothDevice, characteristic: BluetoothGattCharacteristic, packet: ByteArray): Boolean {
        val server = gattServer ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            server.notifyCharacteristicChanged(device, characteristic, false, packet) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = packet
            @Suppress("DEPRECATION")
            server.notifyCharacteristicChanged(device, characteristic, false)
        }
    }

    // ---------------------------------------------------------------- receiving

    private fun handleIncomingChunk(
        reassemblers: ConcurrentHashMap<String, ChunkReassembler>,
        senderAddress: String,
        packet: ByteArray
    ) {
        val complete = reassemblers.getOrPut(senderAddress) { ChunkReassembler() }.accept(packet) ?: return

        val key = keyProvider()
        if (key == null) {
            log("Dropped a message from ${DiagLog.mask(senderAddress)}: this device is not paired")
            return
        }
        val plain = Crypto.decrypt(key, complete)
        if (plain == null) {
            log("Dropped a message from ${DiagLog.mask(senderAddress)}: could not be verified")
            noticeAuthFailure()
            return
        }

        when (val message = BleMessages.decode(plain)) {
            is BleMessage.Text -> scope.launch {
                _incomingPayloads.emit(
                    Payload.Text(
                        id = message.id,
                        transportType = TransportType.BLE_GATT,
                        senderDeviceName = peerNames[senderAddress] ?: nameFor(senderAddress),
                        isOutgoing = false,
                        content = message.text
                    )
                )
            }
            is BleMessage.Hello -> peerNameLearned(senderAddress, message.deviceName)
            null -> log("Ignored an unreadable message from ${DiagLog.mask(senderAddress)}")
        }
    }

    /** A wrong pairing code is the most common problem, so tell the user (at most every 30 s). */
    private fun noticeAuthFailure() {
        val now = System.currentTimeMillis()
        if (now - lastAuthNotice < AUTH_NOTICE_INTERVAL_MS) return
        lastAuthNotice = now
        onNotice("A nearby device sent data that couldn't be verified. Check that both devices use the same pairing code.")
    }

    /** Like runCatching, but never swallows coroutine cancellation. */
    private inline fun <T> resultOf(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
}
