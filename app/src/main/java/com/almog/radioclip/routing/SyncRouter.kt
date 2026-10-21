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
        return when (payload) {
            is Payload.Text -> {
                if (payload.byteSize <= BLE_MAX_PAYLOAD_BYTES && isBleReady()) {
                    bleEngine.send(payload, targetPeerId)
                        .map { TransportType.BLE_GATT }
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
        return bleEngine.isAvailable() &&
                (bleEngine.state.value == TransportState.CONNECTED || bleEngine.connectedPeers.value.isNotEmpty())
    }
}
