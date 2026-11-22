package com.almog.radioclip.routing

import com.almog.radioclip.model.Payload
import com.almog.radioclip.model.TransportState
import com.almog.radioclip.model.TransportType
import com.almog.radioclip.transport.DeviceTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn

class SyncRouter(
    val bleEngine: DeviceTransport,
    val wifiEngine: DeviceTransport,
    coroutineScope: CoroutineScope
) {
    companion object {
        const val BLE_MAX_PAYLOAD_BYTES = 20 * 1024 // 20 KB threshold
    }

    // Merge incoming streams from all active transports
    val incomingPayloads: Flow<Payload> = merge(
        bleEngine.incomingPayloads,
        wifiEngine.incomingPayloads
    )

    data class CombinedRouterState(
        val bleState: TransportState,
        val wifiState: TransportState,
        val blePeersCount: Int,
        val wifiPeersCount: Int
    )

    val routerState = combine(
        bleEngine.state,
        wifiEngine.state,
        bleEngine.connectedPeers,
        wifiEngine.connectedPeers
    ) { bleState, wifiState, blePeers, wifiPeers ->
        CombinedRouterState(
            bleState = bleState,
            wifiState = wifiState,
            blePeersCount = blePeers.size,
            wifiPeersCount = wifiPeers.size
        )
    }.stateIn(
        coroutineScope,
        SharingStarted.Eagerly,
        CombinedRouterState(
            TransportState.IDLE,
            TransportState.IDLE,
            0,
            0
        )
    )

    /**
     * Determines best transport route based on payload size & connection state:
     * - Text <= 20 KB prefers BLE GATT if available/connected.
     * - Files or larger text payloads route over Wi-Fi Direct / Local Sockets.
     */
    suspend fun route(payload: Payload, targetPeerId: String? = null): Result<TransportType> {
        // A transport peer ID is not interchangeable between BLE and Wi-Fi Direct.
        // Never silently redirect a targeted message to a different nearby device.
        if (targetPeerId != null) {
            val isBlePeer = bleEngine.connectedPeers.value.any { it.id == targetPeerId }
            val isWifiPeer = wifiEngine.connectedPeers.value.any { it.id == targetPeerId }
            return when {
                payload is Payload.Text && payload.byteSize <= BLE_MAX_PAYLOAD_BYTES && isBlePeer ->
                    bleEngine.send(payload, targetPeerId).map { TransportType.BLE_GATT }
                isWifiPeer ->
                    wifiEngine.send(payload, targetPeerId).map { TransportType.WIFI_DIRECT_SOCKET }
                else -> Result.failure(
                    IllegalStateException("No compatible connection to the selected peer")
                )
            }
        }
        return when (payload) {
            is Payload.Text -> {
                if (payload.byteSize <= BLE_MAX_PAYLOAD_BYTES && isBleReady()) {
                    val bleResult = bleEngine.send(payload, targetPeerId)
                    if (bleResult.isSuccess) {
                        Result.success(TransportType.BLE_GATT)
                    } else {
                        // Fallback to Wi-Fi Direct socket if BLE fails
                        wifiEngine.send(payload, targetPeerId)
                            .map { TransportType.WIFI_DIRECT_SOCKET }
                    }
                } else {
                    wifiEngine.send(payload, targetPeerId)
                        .map { TransportType.WIFI_DIRECT_SOCKET }
                }
            }
            is Payload.File -> {
                wifiEngine.send(payload, targetPeerId)
                    .map { TransportType.WIFI_DIRECT_SOCKET }
            }
        }
    }

    private fun isBleReady(): Boolean {
        val state = bleEngine.state.value
        return bleEngine.isAvailable() &&
                state != TransportState.ERROR &&
                state != TransportState.DISCONNECTED &&
                (state == TransportState.CONNECTED || bleEngine.connectedPeers.value.isNotEmpty())
    }
}
