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
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.almog.radioclip.core.ChunkCodec
import com.almog.radioclip.core.ChunkReassembler
import com.almog.radioclip.core.Crypto
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@SuppressLint("MissingPermission")
class BleEngine(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + Job()),
    private val keyProvider: () -> ByteArray? = { null }
) : DeviceTransport {

    companion object {
        private const val TAG = "BleEngine"
        val SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val REQUESTED_MTU = 512
        private const val DEFAULT_MTU = 23          // what BLE guarantees before MTU negotiation
        private const val MAX_CHUNK_DATA = 500
        private const val BUSY_RETRY_DELAY_MS = 10L
        private const val BUSY_RETRY_LIMIT = 300    // ~3 s per packet before giving up
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

    // Two roles can exist for the same peer: we connected to them (client) and/or they connected to us (server).
    private val activeGattClients = ConcurrentHashMap<String, BluetoothGatt>()
    private val readyClients = ConcurrentHashMap.newKeySet<String>()       // services discovered + notifications on
    private val connectingAddresses = ConcurrentHashMap.newKeySet<String>() // connectGatt in flight
    private val serverConnectedDevices = ConcurrentHashMap<String, BluetoothDevice>()
    private val scannedDevicesMap = ConcurrentHashMap<String, BluetoothDevice>()
    private val clientMtuMap = ConcurrentHashMap<String, Int>()
    private val serverMtuMap = ConcurrentHashMap<String, Int>()
    private val sendMutex = Mutex()

    private val targetReconnectAddresses = ConcurrentHashMap.newKeySet<String>()

    // One reassembler per sender and role, so chunks of different peers never mix.
    private val clientReassemblers = ConcurrentHashMap<String, ChunkReassembler>()
    private val serverReassemblers = ConcurrentHashMap<String, ChunkReassembler>()

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.d(TAG, "BLE Advertising active")
            if (_connectedPeers.value.isEmpty()) {
                _state.value = TransportState.SCANNING
            }
        }

        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "BLE Advertising failed: $errorCode")
            if (_connectedPeers.value.isEmpty()) {
                _state.value = TransportState.SCANNING
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
            Log.e(TAG, "BLE Scan failed: $errorCode")
            if (_connectedPeers.value.isEmpty()) {
                _state.value = TransportState.IDLE
            }
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val address = device.address
            val name = getDeviceDisplayName(device, null)
            Log.d(TAG, "GATT Server connection state change: $name ($address) state=$newState")

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                serverConnectedDevices[address] = device
                targetReconnectAddresses.add(address)
                updateConnectedPeers(address, name, true)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                serverConnectedDevices.remove(address)
                serverReassemblers.remove(address)
                serverMtuMap.remove(address)
                updateConnectedPeers(address, name, false)
                triggerAutoReconnect(address)
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

        // Peers subscribe to notifications by writing the CCCD descriptor; accept it.
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
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            Log.d(TAG, "GATT Server MTU changed for ${device.address}: $mtu")
            serverMtuMap[device.address] = mtu
        }
    }

    private fun hasBlePermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val connectGranted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
            val scanGranted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
            val advertiseGranted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_ADVERTISE
            ) == PackageManager.PERMISSION_GRANTED
            return connectGranted && scanGranted && advertiseGranted
        }
        return true
    }

    override fun isAvailable(): Boolean {
        return bluetoothAdapter?.isEnabled == true && hasBlePermissions()
    }

    @Volatile
    private var started = false

    override suspend fun start() = withContext(Dispatchers.IO) {
        // Safe to call repeatedly (e.g. every time the service is started): only does work once,
        // but retries if Bluetooth/permissions were not ready last time.
        if (started) return@withContext
        if (!isAvailable()) {
            _state.value = TransportState.IDLE
            return@withContext
        }
        started = true

        _state.value = TransportState.SCANNING
        setupGattServer()
        startAdvertising()
        startScanning()
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        started = false
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
        targetReconnectAddresses.clear()
        serverConnectedDevices.clear()
        scannedDevicesMap.clear()
        clientMtuMap.clear()
        serverMtuMap.clear()
        clientReassemblers.clear()
        serverReassemblers.clear()
        _discoveredPeers.value = emptyList()
        _connectedPeers.value = emptyList()
        _state.value = TransportState.IDLE
    }

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
            Log.d(TAG, "GATT Server configured successfully")
        } catch (e: Exception) {
            Log.w(TAG, "GATT Server setup error: ${e.message}")
        }
    }

    private fun startAdvertising() {
        if (!hasBlePermissions()) return
        val advertiser = bluetoothAdapter?.bluetoothLeAdvertiser ?: return
        // Use LOW_POWER advertising mode and medium power level to drastically conserve battery
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()

        // Include Service UUID in primary advertisement packet
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        // Include device name in scan response packet so it fits in 31-byte BLE legacy advertising limit
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()

        try {
            advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Start advertising error: ${e.message}")
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

    private fun startScanning() {
        if (!hasBlePermissions()) return
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return

        // Switch to BALANCED or LOW_POWER scan mode when already connected, conserving battery
        val scanMode = if (_connectedPeers.value.isNotEmpty()) {
            ScanSettings.SCAN_MODE_LOW_POWER
        } else {
            ScanSettings.SCAN_MODE_BALANCED
        }

        val settings = ScanSettings.Builder()
            .setScanMode(scanMode)
            .setReportDelay(0)
            .build()

        // Filter by Service UUID
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        try {
            scanner.startScan(listOf(filter), settings, scanCallback)
            Log.d(TAG, "BLE Scanner active with Service UUID filter")
        } catch (e: Exception) {
            Log.w(TAG, "Start scanning error", e)
        }
    }

    private fun stopScanning() {
        if (!hasBlePermissions()) return
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Stop scan error", e)
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

    private fun getDeviceDisplayName(device: BluetoothDevice, scanRecordName: String?): String {
        val hardwareName = try { device.name } catch (e: SecurityException) { null }
        return scanRecordName?.takeIf { it.isNotBlank() }
            ?: hardwareName?.takeIf { it.isNotBlank() }
            ?: "RadioClip (${device.address.takeLast(5)})"
    }

    private fun handleScanResult(result: ScanResult) {
        val device = result.device ?: return
        val record = result.scanRecord

        val hasOurServiceUuid = record?.serviceUuids?.any { it.uuid == SERVICE_UUID } == true
        if (!hasOurServiceUuid) return

        val address = device.address
        val name = getDeviceDisplayName(device, record?.deviceName)
        scannedDevicesMap[address] = device

        val currentDiscovered = _discoveredPeers.value.toMutableList()
        val existingIndex = currentDiscovered.indexOfFirst { it.id == address }
        val peer = PeerDevice(
            id = address,
            name = name,
            transportType = TransportType.BLE_GATT,
            isConnected = _connectedPeers.value.any { it.id == address }
        )

        if (existingIndex == -1) {
            currentDiscovered.add(peer)
            _discoveredPeers.value = currentDiscovered
            Log.d(TAG, "Discovered RadioClip BLE peer: $name ($address)")
        } else if (currentDiscovered[existingIndex] != peer) {
            currentDiscovered[existingIndex] = peer
            _discoveredPeers.value = currentDiscovered
        }

        // Auto-connect if not connected yet. Only paired devices can exchange data (everything is
        // encrypted with the pairing code), so strangers who connect can't do anything useful.
        if (!activeGattClients.containsKey(address) && _connectedPeers.value.none { it.id == address }) {
            Log.d(TAG, "Auto-connecting to RadioClip peer: $name ($address)")
            connectToPeerDevice(device)
        }
    }

    override suspend fun connect(peerId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (!hasBlePermissions()) {
                error("Missing Bluetooth Connect permissions")
            }
            val device = scannedDevicesMap[peerId]
                ?: bluetoothAdapter?.getRemoteDevice(peerId)
                ?: error("Bluetooth device $peerId not found")

            targetReconnectAddresses.add(peerId)
            stopScanning()
            connectToPeerDevice(device)
            delay(1000)
            startScanning()
        }
    }

    private fun connectToPeerDevice(device: BluetoothDevice) {
        if (!hasBlePermissions()) return
        val address = device.address
        // Scan results arrive many times per second: only one connection attempt per peer at a time.
        if (!connectingAddresses.add(address)) return
        _state.value = TransportState.CONNECTING

        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                val name = getDeviceDisplayName(gatt.device, null)

                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    connectingAddresses.remove(address)
                    Log.d(TAG, "Connected to GATT Client: $address. Requesting MTU $REQUESTED_MTU")
                    try {
                        gatt.requestMtu(REQUESTED_MTU)
                    } catch (e: SecurityException) {
                        Log.w(TAG, "SecurityException requesting MTU", e)
                    }
                    targetReconnectAddresses.add(address)
                    updateConnectedPeers(address, name, true)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.d(TAG, "Disconnected from GATT Client: $address")
                    connectingAddresses.remove(address)
                    // Ignore callbacks from a stale connection object.
                    if (activeGattClients[address] === gatt || activeGattClients[address] == null) {
                        activeGattClients.remove(address)
                        readyClients.remove(address)
                        clientMtuMap.remove(address)
                        clientReassemblers.remove(address)
                        updateConnectedPeers(address, name, false)
                    }
                    try { gatt.close() } catch (e: Exception) { Log.w(TAG, "Gatt close error", e) }
                    triggerAutoReconnect(address)
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "Client negotiated MTU $mtu for ${gatt.device.address}")
                    clientMtuMap[gatt.device.address] = mtu
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
                    Log.w(TAG, "RadioClip service not usable on ${gatt.device.address} (status=$status), disconnecting")
                    try { gatt.disconnect() } catch (e: Exception) { Log.w(TAG, "Disconnect error", e) }
                    return
                }

                Log.d(TAG, "Services discovered for ${gatt.device.address}")
                enableNotifications(gatt, characteristic)
                readyClients.add(gatt.device.address)
                _state.value = TransportState.CONNECTED
            }

            // Notifications from the peer's GATT server (API 33+ delivers the value directly).
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                if (characteristic.uuid == CHARACTERISTIC_UUID) {
                    handleIncomingChunk(clientReassemblers, gatt.device.address, value)
                }
            }

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                // Only used below API 33; newer systems call the overload above instead.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
                    characteristic.uuid == CHARACTERISTIC_UUID
                ) {
                    characteristic.value?.let {
                        handleIncomingChunk(clientReassemblers, gatt.device.address, it)
                    }
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
            Log.w(TAG, "Connect to peer device error", e)
            connectingAddresses.remove(address)
            _state.value = TransportState.SCANNING
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
            Log.w(TAG, "Enabling notifications failed: ${e.message}")
        }
    }

    private fun triggerAutoReconnect(address: String) {
        if (!targetReconnectAddresses.contains(address)) return
        scope.launch {
            delay(3000)
            if (activeGattClients.containsKey(address) || _connectedPeers.value.any { it.id == address }) {
                return@launch
            }
            scannedDevicesMap[address]?.let { device ->
                Log.d(TAG, "Triggering auto-reconnect to $address")
                connectToPeerDevice(device)
            }
        }
    }

    private fun updateConnectedPeers(address: String, name: String, isConnected: Boolean) {
        // A peer can be connected in both roles; it only counts as gone when neither is left.
        val stillConnected = isConnected ||
            serverConnectedDevices.containsKey(address) ||
            readyClients.contains(address)

        val current = _connectedPeers.value.toMutableList()
        current.removeAll { it.id == address }
        if (stillConnected) {
            current.add(PeerDevice(id = address, name = name, transportType = TransportType.BLE_GATT, isConnected = true))
        }
        _connectedPeers.value = current

        val discovered = _discoveredPeers.value.map { peer ->
            if (peer.id == address) peer.copy(isConnected = stillConnected) else peer
        }
        _discoveredPeers.value = discovered

        if (_connectedPeers.value.isNotEmpty()) {
            _state.value = TransportState.CONNECTED
            // Battery optimization: when connected, switch scanning to LOW_POWER mode
            adjustScanPower(ScanSettings.SCAN_MODE_LOW_POWER)
        } else {
            _state.value = TransportState.SCANNING
            adjustScanPower(ScanSettings.SCAN_MODE_BALANCED)
        }
    }

    private fun adjustScanPower(mode: Int) {
        if (!hasBlePermissions()) return
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        try {
            scanner.stopScan(scanCallback)
            val settings = ScanSettings.Builder()
                .setScanMode(mode)
                .setReportDelay(0)
                .build()
            val filter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(SERVICE_UUID))
                .build()
            scanner.startScan(listOf(filter), settings, scanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Adjust scan power error: ${e.message}")
        }
    }

    override suspend fun send(payload: Payload, targetPeerId: String?): Result<Unit> = sendMutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                if (payload !is Payload.Text) {
                    error("BleEngine only transfers text payloads")
                }
                val key = keyProvider() ?: error("Not paired: enter the same pairing code on both devices first")

                // Encrypt first; the chunks below only ever carry ciphertext.
                val data = Crypto.encrypt(key, payload.content.toByteArray(Charsets.UTF_8))
                val messageId = payload.id.hashCode()

                val addresses = if (targetPeerId != null) {
                    listOf(targetPeerId)
                } else {
                    (readyClients.toList() + serverConnectedDevices.keys.toList()).distinct()
                }

                var delivered = 0
                var lastError: Throwable? = null
                for (address in addresses) {
                    try {
                        val gatt = if (readyClients.contains(address)) activeGattClients[address] else null
                        val serverDevice = serverConnectedDevices[address]
                        when {
                            gatt != null -> sendOverClient(gatt, messageId, data)
                            serverDevice != null -> sendOverServer(serverDevice, messageId, data)
                            else -> continue
                        }
                        delivered++
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "BLE send to $address failed: ${e.message}")
                        lastError = e
                    }
                }

                if (delivered == 0) {
                    throw (lastError ?: IllegalStateException("No connected BLE peers available to send payload"))
                }
            }
        }
    }

    private suspend fun sendOverClient(gatt: BluetoothGatt, messageId: Int, data: ByteArray) {
        val address = gatt.device.address
        val mtu = clientMtuMap[address] ?: DEFAULT_MTU
        val chunks = ChunkCodec.encode(messageId, data, ChunkCodec.maxChunkData(mtu, MAX_CHUNK_DATA))
        val characteristic = gatt.getService(SERVICE_UUID)?.getCharacteristic(CHARACTERISTIC_UUID)
            ?: error("GATT Characteristic not found on peer $address")

        for (chunk in chunks) {
            retryWhileBusy("write", { activeGattClients[address] === gatt }) {
                writeNoResponse(gatt, characteristic, chunk)
            }
        }
    }

    private suspend fun sendOverServer(device: BluetoothDevice, messageId: Int, data: ByteArray) {
        val address = device.address
        val mtu = serverMtuMap[address] ?: DEFAULT_MTU
        val chunks = ChunkCodec.encode(messageId, data, ChunkCodec.maxChunkData(mtu, MAX_CHUNK_DATA))
        val characteristic = gattServer?.getService(SERVICE_UUID)?.getCharacteristic(CHARACTERISTIC_UUID)
            ?: error("Server characteristic not found")

        for (chunk in chunks) {
            retryWhileBusy("notification", { serverConnectedDevices.containsKey(address) }) {
                notifyChunk(device, characteristic, chunk)
            }
        }
    }

    /**
     * Android's BLE stack accepts one operation at a time and returns false/BUSY otherwise.
     * Instead of guessing a delay between packets, retry until the stack accepts the packet.
     */
    private suspend fun retryWhileBusy(what: String, stillConnected: () -> Boolean, attempt: () -> Boolean) {
        repeat(BUSY_RETRY_LIMIT) {
            if (!stillConnected()) throw IOException("BLE peer disconnected during $what")
            if (attempt()) return
            delay(BUSY_RETRY_DELAY_MS)
        }
        throw IOException("BLE $what timed out (link busy or lost)")
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

    private fun handleIncomingChunk(
        reassemblers: ConcurrentHashMap<String, ChunkReassembler>,
        senderAddress: String,
        packet: ByteArray
    ) {
        val complete = reassemblers.getOrPut(senderAddress) { ChunkReassembler() }.accept(packet) ?: return

        val key = keyProvider()
        if (key == null) {
            Log.w(TAG, "Dropping BLE message from $senderAddress: not paired")
            return
        }
        // Wrong pairing code or tampered data: drop silently.
        val plain = Crypto.decrypt(key, complete)
        if (plain == null) {
            Log.w(TAG, "Dropping BLE message from $senderAddress: authentication failed")
            return
        }
        val text = String(plain, Charsets.UTF_8)

        val senderDevice = scannedDevicesMap[senderAddress] ?: serverConnectedDevices[senderAddress]
        val senderName = if (senderDevice != null) getDeviceDisplayName(senderDevice, null) else "Peer ($senderAddress)"

        scope.launch {
            _incomingPayloads.emit(
                Payload.Text(
                    transportType = TransportType.BLE_GATT,
                    senderDeviceName = senderName,
                    isOutgoing = false,
                    content = text
                )
            )
        }
    }
}
