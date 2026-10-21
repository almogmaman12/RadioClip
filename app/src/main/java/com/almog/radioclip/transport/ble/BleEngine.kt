package com.almog.radioclip.transport.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
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
import com.almog.radioclip.model.Payload
import com.almog.radioclip.model.PeerDevice
import com.almog.radioclip.model.TransportState
import com.almog.radioclip.model.TransportType
import com.almog.radioclip.transport.DeviceTransport
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
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@SuppressLint("MissingPermission")
class BleEngine(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + Job())
) : DeviceTransport {

    companion object {
        private const val TAG = "BleEngine"
        val SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
        private const val REQUESTED_MTU = 512
        private const val DEFAULT_CHUNK_SIZE = 500
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
    // Both active GATT clients and server-connected devices
    private val activeGattClients = ConcurrentHashMap<String, BluetoothGatt>()
    private val serverConnectedDevices = ConcurrentHashMap<String, BluetoothDevice>()
    private val scannedDevicesMap = ConcurrentHashMap<String, BluetoothDevice>()
    private val clientMtuMap = ConcurrentHashMap<String, Int>()
    private val sendMutex = Mutex()

    private val targetReconnectAddresses = ConcurrentHashMap.newKeySet<String>()
    private val incomingPacketBuffers = ConcurrentHashMap<String, ConcurrentHashMap<String, ByteArrayOutputStream>>()

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
                updateConnectedPeers(address, name, false)
                incomingPacketBuffers.remove(address)
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
                handleIncomingChunk(device.address, value)
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            Log.d(TAG, "GATT Server MTU changed for ${device.address}: $mtu")
            clientMtuMap[device.address] = mtu
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

    override suspend fun start() = withContext(Dispatchers.IO) {
        if (!isAvailable()) {
            _state.value = TransportState.IDLE
            return@withContext
        }

        _state.value = TransportState.SCANNING
        setupGattServer()
        startAdvertising()
        startScanning()
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        stopScanning()
        stopAdvertising()
        closeGattServer()
        activeGattClients.values.forEach { 
            try { it.close() } catch (e: Exception) { Log.w(TAG, "Error closing client", e) }
        }
        activeGattClients.clear()
        serverConnectedDevices.clear()
        scannedDevicesMap.clear()
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

        // Auto-connect if not connected yet
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
        _state.value = TransportState.CONNECTING

        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                val name = getDeviceDisplayName(gatt.device, null)

                if (newState == BluetoothProfile.STATE_CONNECTED) {
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
                    activeGattClients.remove(address)
                    updateConnectedPeers(address, name, false)
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
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "Services discovered for ${gatt.device.address}")
                    _state.value = TransportState.CONNECTED
                }
            }
        }

        try {
            val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, gattCallback)
            }

            if (gatt != null) {
                activeGattClients[device.address] = gatt
            }
        } catch (e: Exception) {
            Log.w(TAG, "Connect to peer device error", e)
            _state.value = TransportState.SCANNING
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
        val current = _connectedPeers.value.toMutableList()
        current.removeAll { it.id == address }
        if (isConnected) {
            current.add(PeerDevice(id = address, name = name, transportType = TransportType.BLE_GATT, isConnected = true))
        }
        _connectedPeers.value = current

        val discovered = _discoveredPeers.value.map { peer ->
            if (peer.id == address) peer.copy(isConnected = isConnected) else peer
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

                val data = payload.content.toByteArray(Charsets.UTF_8)
                val payloadIdHash = payload.id.hashCode()

                // Check active clients
                val clients = if (targetPeerId != null) {
                    listOfNotNull(activeGattClients[targetPeerId])
                } else {
                    activeGattClients.values.toList()
                }

                if (clients.isNotEmpty()) {
                    clients.forEach { gatt ->
                        sendChunksOverClient(gatt, data, payloadIdHash)
                    }
                    return@runCatching
                }

                // If not connected as client, send notification from GATT Server to server-connected devices
                val serverDevices = if (targetPeerId != null) {
                    listOfNotNull(serverConnectedDevices[targetPeerId])
                } else {
                    serverConnectedDevices.values.toList()
                }

                if (serverDevices.isNotEmpty()) {
                    val service = gattServer?.getService(SERVICE_UUID)
                    val characteristic = service?.getCharacteristic(CHARACTERISTIC_UUID)
                        ?: error("Server characteristic not found")

                    serverDevices.forEach { device ->
                        sendChunksOverServer(device, characteristic, data, payloadIdHash)
                    }
                    return@runCatching
                }

                error("No connected BLE peers available to send payload")
            }
        }
    }

    private suspend fun sendChunksOverClient(gatt: BluetoothGatt, data: ByteArray, payloadIdHash: Int) {
        val mtu = clientMtuMap[gatt.device.address] ?: DEFAULT_CHUNK_SIZE
        val effectiveChunkSize = (mtu - 15).coerceIn(20, DEFAULT_CHUNK_SIZE)
        val totalChunks = ((data.size + effectiveChunkSize - 1) / effectiveChunkSize).coerceAtLeast(1)

        val service = gatt.getService(SERVICE_UUID)
        val characteristic = service?.getCharacteristic(CHARACTERISTIC_UUID)
            ?: error("GATT Characteristic not found on peer ${gatt.device.address}")

        for (chunkIndex in 0 until totalChunks) {
            val start = chunkIndex * effectiveChunkSize
            val end = (start + effectiveChunkSize).coerceAtMost(data.size)
            val chunkData = if (start < data.size) data.copyOfRange(start, end) else ByteArray(0)

            val packet = ByteBuffer.allocate(8 + chunkData.size).apply {
                putInt(payloadIdHash)
                putShort(totalChunks.toShort())
                putShort(chunkIndex.toShort())
                put(chunkData)
            }.array()

            characteristic.value = packet
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            gatt.writeCharacteristic(characteristic)
            delay(15)
        }
    }

    private suspend fun sendChunksOverServer(
        device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic,
        data: ByteArray,
        payloadIdHash: Int
    ) {
        val mtu = clientMtuMap[device.address] ?: DEFAULT_CHUNK_SIZE
        val effectiveChunkSize = (mtu - 15).coerceIn(20, DEFAULT_CHUNK_SIZE)
        val totalChunks = ((data.size + effectiveChunkSize - 1) / effectiveChunkSize).coerceAtLeast(1)

        for (chunkIndex in 0 until totalChunks) {
            val start = chunkIndex * effectiveChunkSize
            val end = (start + effectiveChunkSize).coerceAtMost(data.size)
            val chunkData = if (start < data.size) data.copyOfRange(start, end) else ByteArray(0)

            val packet = ByteBuffer.allocate(8 + chunkData.size).apply {
                putInt(payloadIdHash)
                putShort(totalChunks.toShort())
                putShort(chunkIndex.toShort())
                put(chunkData)
            }.array()

            characteristic.value = packet
            gattServer?.notifyCharacteristicChanged(device, characteristic, false)
            delay(15)
        }
    }

    private fun handleIncomingChunk(senderAddress: String, packet: ByteArray) {
        if (packet.size < 8) return

        val buffer = ByteBuffer.wrap(packet)
        val payloadIdHash = buffer.int
        val totalChunks = buffer.short.toInt()
        val chunkIndex = buffer.short.toInt()

        val chunkData = ByteArray(packet.size - 8)
        buffer.get(chunkData)

        val deviceBuffers = incomingPacketBuffers.getOrPut(senderAddress) { ConcurrentHashMap() }
        val streamKey = "$payloadIdHash-$totalChunks"
        val outputStream = deviceBuffers.getOrPut(streamKey) { ByteArrayOutputStream() }

        outputStream.write(chunkData)

        if (chunkIndex == totalChunks - 1) {
            val completeBytes = outputStream.toByteArray()
            val text = String(completeBytes, Charsets.UTF_8)
            deviceBuffers.remove(streamKey)

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
}
