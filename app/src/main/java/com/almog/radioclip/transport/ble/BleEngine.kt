package com.almog.radioclip.transport.ble

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
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
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
        private const val DEFAULT_CHUNK_SIZE = 500 // 512 MTU - 3 byte GATT header with safety margin
    }

    override val transportType: TransportType = TransportType.BLE_GATT

    private val _state = MutableStateFlow(TransportState.IDLE)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    private val _connectedPeers = MutableStateFlow<List<PeerDevice>>(emptyList())
    override val connectedPeers: StateFlow<List<PeerDevice>> = _connectedPeers.asStateFlow()

    private val _incomingPayloads = MutableSharedFlow<Payload>(extraBufferCapacity = 64)
    override val incomingPayloads: SharedFlow<Payload> = _incomingPayloads.asSharedFlow()

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private var gattServer: BluetoothGattServer? = null
    private val activeGattClients = ConcurrentHashMap<String, BluetoothGatt>()
    private val clientMtuMap = ConcurrentHashMap<String, Int>()
    private val sendMutex = Mutex()

    // For reassembling multi-chunk incoming payloads per device address
    // Map: DeviceAddress -> Map(PayloadId -> ByteArrayOutputStream)
    private val incomingPacketBuffers = ConcurrentHashMap<String, ConcurrentHashMap<String, ByteArrayOutputStream>>()

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.d(TAG, "BLE Advertising started successfully")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "BLE Advertising failed with error: $errorCode")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.device?.let { device ->
                handleDiscoveredDevice(device)
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { result ->
                result.device?.let { handleDiscoveredDevice(it) }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE Scan failed with error: $errorCode")
            _state.value = TransportState.ERROR
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val address = device.address
            val name = device.name ?: "BLE-$address"
            Log.d(TAG, "GATT Server connection state change: device=$address, status=$status, newState=$newState")

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                updatePeerList(address, name, true)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                updatePeerList(address, name, false)
                incomingPacketBuffers.remove(address)
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
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
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

    override fun isAvailable(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }

    override suspend fun start() = withContext(Dispatchers.IO) {
        if (!isAvailable()) {
            _state.value = TransportState.ERROR
            return@withContext
        }

        _state.value = TransportState.ADVERTISING
        setupGattServer()
        startAdvertising()
        startScanning()
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        stopScanning()
        stopAdvertising()
        closeGattServer()
        activeGattClients.values.forEach { it.close() }
        activeGattClients.clear()
        _connectedPeers.value = emptyList()
        _state.value = TransportState.IDLE
    }

    private fun setupGattServer() {
        val manager = bluetoothManager ?: return
        gattServer = manager.openGattServer(context, gattServerCallback)?.apply {
            val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            val characteristic = BluetoothGattCharacteristic(
                CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ or
                        BluetoothGattCharacteristic.PROPERTY_WRITE or
                        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            service.addCharacteristic(characteristic)
            addService(service)
        }
    }

    private fun startAdvertising() {
        val advertiser = bluetoothAdapter?.bluetoothLeAdvertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        advertiser.startAdvertising(settings, data, advertiseCallback)
    }

    private fun stopAdvertising() {
        bluetoothAdapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
    }

    private fun startScanning() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(SERVICE_UUID))
                .build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(filters, settings, scanCallback)
    }

    private fun stopScanning() {
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }

    private fun closeGattServer() {
        gattServer?.close()
        gattServer = null
    }

    private fun handleDiscoveredDevice(device: BluetoothDevice) {
        val address = device.address
        if (!activeGattClients.containsKey(address)) {
            Log.d(TAG, "Connecting to discovered BLE peer: $address")
            connectToPeer(device)
        }
    }

    private fun connectToPeer(device: BluetoothDevice) {
        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                val address = gatt.device.address
                val name = gatt.device.name ?: "BLE-$address"

                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.d(TAG, "Connected to GATT Client: $address. Requesting MTU $REQUESTED_MTU")
                    gatt.requestMtu(REQUESTED_MTU)
                    updatePeerList(address, name, true)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.d(TAG, "Disconnected from GATT Client: $address")
                    activeGattClients.remove(address)
                    updatePeerList(address, name, false)
                    gatt.close()
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "Client negotiated MTU $mtu for ${gatt.device.address}")
                    clientMtuMap[gatt.device.address] = mtu
                }
                gatt.discoverServices()
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "Services discovered for ${gatt.device.address}")
                    _state.value = TransportState.CONNECTED
                }
            }
        }

        val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }

        if (gatt != null) {
            activeGattClients[device.address] = gatt
        }
    }

    private fun updatePeerList(address: String, name: String, isConnected: Boolean) {
        val current = _connectedPeers.value.toMutableList()
        current.removeAll { it.id == address }
        if (isConnected) {
            current.add(PeerDevice(id = address, name = name, transportType = TransportType.BLE_GATT, isConnected = true))
        }
        _connectedPeers.value = current
        if (_connectedPeers.value.isNotEmpty()) {
            _state.value = TransportState.CONNECTED
        } else if (_state.value == TransportState.CONNECTED) {
            _state.value = TransportState.ADVERTISING
        }
    }

    /**
     * Chunk packet protocol:
     * [0..3]: Payload UUID Hash (4 bytes)
     * [4..5]: Total Chunks (2 bytes short)
     * [6..7]: Chunk Index (2 bytes short)
     * [8..]:  Payload Data
     */
    override suspend fun send(payload: Payload, targetPeerId: String?): Result<Unit> = sendMutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                if (payload !is Payload.Text) {
                    error("BleEngine only transfers text payloads")
                }

                val data = payload.content.toByteArray(Charsets.UTF_8)
                val clientsToSend = if (targetPeerId != null) {
                    listOfNotNull(activeGattClients[targetPeerId])
                } else {
                    activeGattClients.values.toList()
                }

                if (clientsToSend.isEmpty()) {
                    error("No connected BLE peers available to send payload")
                }

                val payloadIdHash = payload.id.hashCode()

                clientsToSend.forEach { gatt ->
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

                        // Small throttle delay to allow BLE radio buffers to drain
                        delay(15)
                    }
                }
            }
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

        // When all chunks arrive
        if (chunkIndex == totalChunks - 1) {
            val completeBytes = outputStream.toByteArray()
            val text = String(completeBytes, Charsets.UTF_8)
            deviceBuffers.remove(streamKey)

            scope.launch {
                _incomingPayloads.emit(Payload.Text(content = text))
            }
        }
    }
}
