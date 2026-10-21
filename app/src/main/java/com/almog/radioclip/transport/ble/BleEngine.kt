package com.almog.radioclip.transport.ble

import android.content.Context
import com.almog.radioclip.model.Payload
import com.almog.radioclip.model.PeerDevice
import com.almog.radioclip.model.TransportState
import com.almog.radioclip.model.TransportType
import com.almog.radioclip.transport.DeviceTransport
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

class BleEngine(private val context: Context) : DeviceTransport {

    override val transportType: TransportType = TransportType.BLE_GATT

    private val _state = MutableStateFlow(TransportState.IDLE)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    private val _connectedPeers = MutableStateFlow<List<PeerDevice>>(emptyList())
    override val connectedPeers: StateFlow<List<PeerDevice>> = _connectedPeers.asStateFlow()

    private val _incomingPayloads = MutableSharedFlow<Payload>(extraBufferCapacity = 64)
    override val incomingPayloads: SharedFlow<Payload> = _incomingPayloads.asSharedFlow()

    override suspend fun start() {
        _state.value = TransportState.SCANNING
        // Background GATT Server setup, advertising, and scanning loop will be orchestrated here
    }

    override suspend fun stop() {
        _state.value = TransportState.IDLE
    }

    override suspend fun send(payload: Payload, targetPeerId: String?): Result<Unit> {
        return runCatching {
            // GATT Characteristic write logic (chunked packet transfer for MTU size)
        }
    }

    override fun isAvailable(): Boolean {
        return true
    }

    suspend fun emitIncomingPayload(payload: Payload) {
        _incomingPayloads.emit(payload)
    }
}
