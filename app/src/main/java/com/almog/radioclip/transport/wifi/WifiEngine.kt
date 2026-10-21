package com.almog.radioclip.transport.wifi

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

class WifiEngine(private val context: Context) : DeviceTransport {

    override val transportType: TransportType = TransportType.WIFI_DIRECT_SOCKET

    private val _state = MutableStateFlow(TransportState.IDLE)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    private val _connectedPeers = MutableStateFlow<List<PeerDevice>>(emptyList())
    override val connectedPeers: StateFlow<List<PeerDevice>> = _connectedPeers.asStateFlow()

    private val _incomingPayloads = MutableSharedFlow<Payload>(extraBufferCapacity = 64)
    override val incomingPayloads: SharedFlow<Payload> = _incomingPayloads.asSharedFlow()

    override suspend fun start() {
        _state.value = TransportState.SCANNING
        // Wi-Fi P2P manager discoverPeers & ServerSocket listener setup
    }

    override suspend fun stop() {
        _state.value = TransportState.IDLE
    }

    override suspend fun send(payload: Payload, targetPeerId: String?): Result<Unit> {
        return runCatching {
            // TCP socket streaming transfer logic for files and large payloads
        }
    }

    override fun isAvailable(): Boolean {
        return true
    }

    suspend fun emitIncomingPayload(payload: Payload) {
        _incomingPayloads.emit(payload)
    }
}
