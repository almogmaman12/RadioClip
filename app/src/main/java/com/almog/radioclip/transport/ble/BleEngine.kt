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
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothStatusCodes
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
import com.almog.radioclip.security.SecureLink
import com.almog.radioclip.model.Payload
import com.almog.radioclip.model.PeerDevice
import com.almog.radioclip.model.TransportState
import com.almog.radioclip.model.TransportType
import com.almog.radioclip.transport.DeviceTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
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
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@SuppressLint("MissingPermission")
class BleEngine(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + Job()),
    private val secureLink: SecureLink
) : DeviceTransport {

    companion object {
        private const val TAG = "BleEngine"
        val SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
        private const val REQUESTED_MTU = 512
        private const val DEFAULT_CHUNK_SIZE = 500
        private const val DEFAULT_MTU = 23
        private const val MAX_MESSAGE_BYTES = 20 * 1024
        private const val MAX_CHUNKS = 2048
        private const val ASSEMBLY_TIMEOUT_MS = 30_000L
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
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
    private class IncomingMessage(val totalChunks: Int, val createdAt: Long) {
        val parts = arrayOfNulls<ByteArray>(totalChunks)
        var received = 0
        var byteCount = 0
    }

    private val incomingPacketBuffers = ConcurrentHashMap<String, ConcurrentHashMap<String, IncomingMessage>>()
    private val subscribedServerDevices = ConcurrentHashMap.newKeySet<String>()
    private val pendingClientWrites = ConcurrentHashMap<String, CompletableDeferred<Int>>()
    private val pendingServerNotifications = ConcurrentHashMap<String, CompletableDeferred<Int>>()
    private val readyClientAddresses = ConcurrentHashMap.newKeySet<String>()
    private val clientOperationLocks = ConcurrentHashMap<String, Mutex>()
    private val serverOperationLocks = ConcurrentHashMap<String, Mutex>()
    private val pendingDeliveryAcks = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val recentReceivedHashes = ConcurrentHashMap<String, Long>()
    private val reconnectAttempts = ConcurrentHashMap<String, Int>()

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
                updateConnectedPeers(address, name, true)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                serverConnectedDevices.remove(address)
                subscribedServerDevices.remove(address)
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
            val valid = !preparedWrite && offset == 0 &&
                characteristic.uuid == CHARACTERISTIC_UUID && value != null
            if (responseNeeded) {
                try {
                    gattServer?.sendResponse(
                        device, requestId,
                        if (valid) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE,
                        offset, null
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed sending GATT response", e)
                }
            }

            if (valid) {
                handleIncomingChunk(device.address, value!!)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            val valid = descriptor.uuid == CCCD_UUID && !preparedWrite && offset == 0
            if (valid && value != null) {
                if (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                    subscribedServerDevices.add(device.address)
                } else if (value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)) {
                    subscribedServerDevices.remove(device.address)
                }
            }
            if (responseNeeded) {
                gattServer?.sendResponse(
                    device, requestId,
                    if (valid) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE,
                    offset, null
                )
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            pendingServerNotifications[device.address]?.complete(status)
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
        subscribedServerDevices.clear()
        pendingClientWrites.clear()
        pendingServerNotifications.clear()
        readyClientAddresses.clear()
        pendingDeliveryAcks.clear()
        clientOperationLocks.clear()
        serverOperationLocks.clear()
        recentReceivedHashes.clear()
        reconnectAttempts.clear()
        incomingPacketBuffers.clear()
        targetReconnectAddresses.clear()
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

        // Connection requires a deliberate tap: never auto-connect to unknown peers.
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
            try {
                connectToPeerDevice(device)
                val ready = withTimeoutOrNull(15_000L) {
                    connectedPeers.first { peers -> peers.any { it.id == peerId } }
                }
                check(ready != null) { "BLE peer did not complete GATT connection within 15 seconds" }
            } finally {
                startScanning()
            }
        }
    }

    private fun connectToPeerDevice(device: BluetoothDevice) {
        if (!hasBlePermissions()) return
        val address = device.address
        _state.value = TransportState.CONNECTING

        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                val name = getDeviceDisplayName(gatt.device, null)

                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "Connected to GATT Client: $address. Requesting MTU $REQUESTED_MTU")
                    try {
                        if (!gatt.requestMtu(REQUESTED_MTU)) {
                            gatt.discoverServices()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "MTU request failed; attempting service discovery", e)
                        gatt.discoverServices()
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED ||
                    status != BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "Disconnected from GATT Client: $address")
                    activeGattClients.remove(address)
                    readyClientAddresses.remove(address)
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
                    val characteristic = gatt.getService(SERVICE_UUID)
                        ?.getCharacteristic(CHARACTERISTIC_UUID)
                    if (characteristic == null) {
                        Log.w(TAG, "Peer lacks the RadioClip GATT characteristic")
                        return
                    }
                    // Do not report a ready client link until notifications are subscribed.
                    if (!gatt.setCharacteristicNotification(characteristic, true)) {
                        Log.w(TAG, "Could not enable RadioClip notifications")
                        return
                    }
                    val descriptor = characteristic.getDescriptor(CCCD_UUID)
                    if (descriptor == null) {
                        Log.w(TAG, "Peer has no notification descriptor")
                        return
                    }
                    val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeDescriptor(
                            descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        ) == BluetoothStatusCodes.SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(descriptor)
                    }
                    if (!accepted) Log.w(TAG, "Peer rejected notification subscription")
                }
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int
            ) {
                if (descriptor.uuid == CCCD_UUID && status == BluetoothGatt.GATT_SUCCESS) {
                    readyClientAddresses.add(gatt.device.address)
                    reconnectAttempts.remove(gatt.device.address)
                    updateConnectedPeers(
                        gatt.device.address, getDeviceDisplayName(gatt.device, null), true
                    )
                }
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                if (characteristic.uuid == CHARACTERISTIC_UUID) {
                    pendingClientWrites[gatt.device.address]?.complete(status)
                }
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                if (characteristic.uuid == CHARACTERISTIC_UUID) {
                    handleIncomingChunk(gatt.device.address, value)
                }
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
                    characteristic.uuid == CHARACTERISTIC_UUID
                ) {
                    handleIncomingChunk(gatt.device.address, characteristic.value ?: return)
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
        val attempts = reconnectAttempts.merge(address, 1) { old, one -> old + one } ?: 1
        if (attempts > 3) {
            Log.w(TAG, "Stopped automatic BLE reconnection after 3 unsuccessful attempts")
            targetReconnectAddresses.remove(address)
            return
        }
        scope.launch {
            delay(3000L * attempts)
            if (activeGattClients.containsKey(address) || _connectedPeers.value.any { it.id == address }) {
                return@launch
            }
            scannedDevicesMap[address]?.let { device ->
                Log.d(TAG, "Retry BLE connection attempt $attempts")
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

                val payloadIdHash = payload.id.hashCode()
                val aad = ByteBuffer.allocate(4).putInt(payloadIdHash).array()
                val data = secureLink.seal(payload.content.toByteArray(Charsets.UTF_8), aad)
                require(data.size <= MAX_MESSAGE_BYTES) { "Encrypted BLE message exceeds 20 KB" }

                // Check active clients
                val clients = if (targetPeerId != null) {
                    listOfNotNull(activeGattClients[targetPeerId])
                } else {
                    activeGattClients.values.toList()
                }.filter { readyClientAddresses.contains(it.device.address) }

                // A device may connect as a GATT client or as a server. Broadcast to
                // both kinds, but avoid sending the same text twice to one address.
                val clientAddresses = clients.map { it.device.address }.toSet()
                val serverDevices = if (targetPeerId != null) {
                    listOfNotNull(serverConnectedDevices[targetPeerId])
                } else {
                    serverConnectedDevices.values.toList()
                }.filter {
                    subscribedServerDevices.contains(it.address) &&
                        it.address !in clientAddresses
                }
                check(clients.isNotEmpty() || serverDevices.isNotEmpty()) {
                    "No connected BLE peers with a ready data channel"
                }

                clients.forEach { gatt ->
                    sendWithDeliveryAck(gatt.device.address, payloadIdHash) {
                        sendChunksOverClient(gatt, data, payloadIdHash)
                    }
                }
                if (serverDevices.isNotEmpty()) {
                    val characteristic = gattServer?.getService(SERVICE_UUID)
                        ?.getCharacteristic(CHARACTERISTIC_UUID)
                        ?: error("Server characteristic not found")
                    serverDevices.forEach { device ->
                        sendWithDeliveryAck(device.address, payloadIdHash) {
                            sendChunksOverServer(device, characteristic, data, payloadIdHash)
                        }
                    }
                }
            }
        }
    }

    private suspend fun sendWithDeliveryAck(
        address: String,
        hash: Int,
        transfer: suspend () -> Unit
    ) {
        val key = "$address-$hash"
        val acknowledgement = CompletableDeferred<Unit>()
        check(pendingDeliveryAcks.putIfAbsent(key, acknowledgement) == null) {
            "This BLE transfer is already in progress"
        }
        try {
            repeat(3) { attempt ->
                transfer()
                try {
                    withTimeout(7_000L) { acknowledgement.await() }
                    return
                } catch (e: TimeoutCancellationException) {
                    if (attempt == 2) {
                        throw IllegalStateException("Peer did not acknowledge BLE delivery", e)
                    }
                    delay((attempt + 1) * 300L)
                }
            }
        } finally {
            pendingDeliveryAcks.remove(key, acknowledgement)
        }
    }

    private suspend fun writeClientPacket(gatt: BluetoothGatt, packet: ByteArray) {
        val address = gatt.device.address
        val characteristic = gatt.getService(SERVICE_UUID)
            ?.getCharacteristic(CHARACTERISTIC_UUID)
            ?: error("GATT characteristic unavailable for $address")
        clientOperationLocks.getOrPut(address) { Mutex() }.withLock {
            val ack = CompletableDeferred<Int>()
            pendingClientWrites[address] = ack
            try {
                val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeCharacteristic(
                        characteristic, packet, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    ) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    characteristic.value = packet
                    characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    @Suppress("DEPRECATION")
                    gatt.writeCharacteristic(characteristic)
                }
                check(accepted) { "BLE write rejected before transmission" }
                check(withTimeout(5_000L) { ack.await() } == BluetoothGatt.GATT_SUCCESS) {
                    "BLE write failed"
                }
            } finally {
                pendingClientWrites.remove(address, ack)
            }
        }
    }

    private suspend fun notifyServerPacket(
        device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic,
        packet: ByteArray
    ) {
        val address = device.address
        val server = gattServer ?: error("BLE GATT server is closed")
        serverOperationLocks.getOrPut(address) { Mutex() }.withLock {
            val ack = CompletableDeferred<Int>()
            pendingServerNotifications[address] = ack
            try {
                val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    server.notifyCharacteristicChanged(device, characteristic, false, packet) ==
                        BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    characteristic.value = packet
                    @Suppress("DEPRECATION")
                    server.notifyCharacteristicChanged(device, characteristic, false)
                }
                check(accepted) { "BLE notification rejected before transmission" }
                check(withTimeout(5_000L) { ack.await() } == BluetoothGatt.GATT_SUCCESS) {
                    "BLE notification failed"
                }
            } finally {
                pendingServerNotifications.remove(address, ack)
            }
        }
    }

    private suspend fun sendChunksOverClient(gatt: BluetoothGatt, data: ByteArray, hash: Int) {
        val mtu = clientMtuMap[gatt.device.address] ?: DEFAULT_MTU
        val chunkSize = (mtu - 11).coerceIn(1, DEFAULT_CHUNK_SIZE)
        val count = ((data.size + chunkSize - 1) / chunkSize).coerceAtLeast(1)
        for (index in 0 until count) {
            val start = index * chunkSize
            val end = (start + chunkSize).coerceAtMost(data.size)
            writeClientPacket(gatt, makePacket(hash, count, index, data.copyOfRange(start, end)))
        }
    }

    private suspend fun sendChunksOverServer(
        device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic,
        data: ByteArray,
        hash: Int
    ) {
        val mtu = clientMtuMap[device.address] ?: DEFAULT_MTU
        val chunkSize = (mtu - 11).coerceIn(1, DEFAULT_CHUNK_SIZE)
        val count = ((data.size + chunkSize - 1) / chunkSize).coerceAtLeast(1)
        for (index in 0 until count) {
            val start = index * chunkSize
            val end = (start + chunkSize).coerceAtMost(data.size)
            notifyServerPacket(device, characteristic,
                makePacket(hash, count, index, data.copyOfRange(start, end)))
        }
    }

    private fun ackInput(hash: Int): ByteArray =
        ByteBuffer.allocate(18).apply {
            put("RadioClip ACK v2".toByteArray(Charsets.UTF_8))
            putInt(hash)
        }.array()

    private suspend fun sendAcknowledgement(address: String, hash: Int) {
        val signature = secureLink.authenticationTag(ackInput(hash))
        val packet = makePacket(hash, 0, 0, signature)
        val device = serverConnectedDevices[address]
        val characteristic = gattServer?.getService(SERVICE_UUID)
            ?.getCharacteristic(CHARACTERISTIC_UUID)
        try {
            when {
                device != null && subscribedServerDevices.contains(address) && characteristic != null ->
                    notifyServerPacket(device, characteristic, packet)
                activeGattClients[address] != null && readyClientAddresses.contains(address) ->
                    writeClientPacket(activeGattClients[address]!!, packet)
                else -> Log.w(TAG, "No reverse BLE channel for delivery acknowledgement")
            }
        } catch (e: Exception) {
            Log.w(TAG, "BLE delivery acknowledgement could not be sent", e)
        }
    }

    private fun makePacket(hash: Int, total: Int, index: Int, bytes: ByteArray): ByteArray =
        ByteBuffer.allocate(8 + bytes.size).apply {
            putInt(hash)
            putShort(total.toShort())
            putShort(index.toShort())
            put(bytes)
        }.array()

    private fun handleIncomingChunk(senderAddress: String, packet: ByteArray) {
        if (packet.size < 8) return
        val buffer = ByteBuffer.wrap(packet)
        val hash = buffer.int
        val total = buffer.short.toInt() and 0xFFFF
        val index = buffer.short.toInt() and 0xFFFF
        val bytes = ByteArray(packet.size - 8)
        buffer.get(bytes)

        if (total == 0 && index == 0) {
            // Delivery acknowledgments are small enough for the minimum GATT MTU.
            try {
                val expected = secureLink.authenticationTag(ackInput(hash))
                if (MessageDigest.isEqual(expected, bytes)) {
                    pendingDeliveryAcks["$senderAddress-$hash"]?.complete(Unit)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Rejected unauthenticated BLE acknowledgement", e)
            }
            return
        }
        if (total !in 1..MAX_CHUNKS || index >= total) return
        val buffers = incomingPacketBuffers.getOrPut(senderAddress) { ConcurrentHashMap() }
        val now = System.currentTimeMillis()
        buffers.entries.removeIf { now - it.value.createdAt > ASSEMBLY_TIMEOUT_MS }
        val key = "$hash-$total"
        if (buffers.size >= 16 && !buffers.containsKey(key)) return
        val message = buffers.getOrPut(key) { IncomingMessage(total, now) }
        val complete: ByteArray? = synchronized(message) {
            if (message.parts[index] != null) {
                null // Duplicated packet
            } else if (message.byteCount + bytes.size > MAX_MESSAGE_BYTES) {
                buffers.remove(key, message)
                null
            } else {
                message.parts[index] = bytes
                message.received++
                message.byteCount += bytes.size
                if (message.received != total) {
                    null
                } else {
                    val result = ByteArrayOutputStream(message.byteCount)
                    message.parts.forEach { result.write(it!!) }
                    buffers.remove(key, message)
                    result.toByteArray()
                }
            }
        }

        if (complete != null) {
            val device = scannedDevicesMap[senderAddress] ?: serverConnectedDevices[senderAddress]
            val name = if (device != null) getDeviceDisplayName(device, null) else "Peer ($senderAddress)"
            scope.launch {
                try {
                    val aad = ByteBuffer.allocate(4).putInt(hash).array()
                    val cleartext = secureLink.open(complete, aad)
                    val receivedKey = "$senderAddress-$hash"
                    val now = System.currentTimeMillis()
                    recentReceivedHashes.entries.removeIf { now - it.value > 10 * 60_000L }
                    if (recentReceivedHashes.putIfAbsent(receivedKey, now) == null) {
                        _incomingPayloads.emit(
                            Payload.Text(
                                transportType = TransportType.BLE_GATT,
                                senderDeviceName = name,
                                content = String(cleartext, Charsets.UTF_8)
                            )
                        )
                    }
                    sendAcknowledgement(senderAddress, hash)
                } catch (e: Exception) {
                    Log.w(TAG, "Rejected unauthenticated or damaged BLE message", e)
                }
            }
        }
    }

}
