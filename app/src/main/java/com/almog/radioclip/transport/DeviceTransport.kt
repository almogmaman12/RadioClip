package com.almog.radioclip.transport

import com.almog.radioclip.model.Payload
import com.almog.radioclip.model.PeerDevice
import com.almog.radioclip.model.TransportState
import com.almog.radioclip.model.TransportType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Unified abstraction layer for peer-to-peer device transports (BLE GATT, Wi-Fi Direct).
 */
interface DeviceTransport {
    val transportType: TransportType
    val state: StateFlow<TransportState>
    val connectedPeers: StateFlow<List<PeerDevice>>
    val incomingPayloads: Flow<Payload>

    suspend fun start()
    suspend fun stop()
    suspend fun send(payload: Payload, targetPeerId: String? = null): Result<Unit>
    fun isAvailable(): Boolean
}
