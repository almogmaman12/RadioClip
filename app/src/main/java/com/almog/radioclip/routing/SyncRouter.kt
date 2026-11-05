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
    coroutineScope: CoroutineScope,
    private val isWifiEnabled: () -> Boolean = { true }
) {
    companion object {
        /** Above this, text prefers Wi-Fi Direct (faster) when it is available. */
        const val BLE_MAX_PAYLOAD_BYTES = 20 * 1024
        /** The most Bluetooth can carry in one message (the receiver rejects more). */
        const val BLE_ABSOLUTE_MAX_BYTES = 56 * 1024
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
        CombinedRouterState(TransportState.IDLE, TransportState.IDLE, 0, 0)
    )

    /**
     * Sends [payload] to everyone who can receive it.
     * - Files go over Wi-Fi Direct.
     * - Text goes over Bluetooth to every Bluetooth peer, and also over Wi-Fi Direct when a Wi-Fi
     *   peer is connected, so devices on either link get it. A device reachable both ways receives
     *   it twice; the receiver recognises the shared message id and handles it once.
     * Returns the first transport that delivered, or why nothing could be delivered.
     */
    suspend fun route(payload: Payload, targetPeerId: String? = null): Result<TransportType> {
        return when (payload) {
            is Payload.Text -> routeText(payload, targetPeerId)
            is Payload.File -> {
                if (!isWifiEnabled()) {
                    Result.failure(IllegalStateException("Files need Wi-Fi Direct, which is turned off in Settings"))
                } else {
                    wifiEngine.send(payload, targetPeerId).map { TransportType.WIFI_DIRECT_SOCKET }
                }
            }
        }
    }

    private suspend fun routeText(payload: Payload.Text, targetPeerId: String?): Result<TransportType> {
        val wifiAllowed = isWifiEnabled()
        val size = payload.byteSize
        val bleLimit = if (wifiAllowed) BLE_MAX_PAYLOAD_BYTES else BLE_ABSOLUTE_MAX_BYTES

        var delivered: TransportType? = null
        var lastError: Throwable? = null

        if (size <= bleLimit && bleHasPeers()) {
            bleEngine.send(payload, targetPeerId).fold(
                onSuccess = { delivered = TransportType.BLE_GATT },
                onFailure = { lastError = it }
            )
        }

        // Wi-Fi: always as the fallback, and in addition when a Wi-Fi peer is connected.
        if (wifiAllowed && (delivered == null || wifiHasPeers())) {
            wifiEngine.send(payload, targetPeerId).fold(
                onSuccess = { if (delivered == null) delivered = TransportType.WIFI_DIRECT_SOCKET },
                onFailure = { if (delivered == null) lastError = it }
            )
        }

        delivered?.let { return Result.success(it) }

        return Result.failure(
            when {
                size > bleLimit && !wifiAllowed ->
                    IllegalStateException("Text is too large for Bluetooth (limit ${BLE_ABSOLUTE_MAX_BYTES / 1024} KB). Turn on Wi-Fi Direct in Settings.")
                lastError != null && (bleHasPeers() || wifiHasPeers()) -> lastError!!
                else -> IllegalStateException("No connected device. Open RadioClip on the other phone and check that both use the same pairing code.")
            }
        )
    }

    private fun bleHasPeers(): Boolean = bleEngine.isAvailable() && bleEngine.connectedPeers.value.isNotEmpty()

    private fun wifiHasPeers(): Boolean = wifiEngine.connectedPeers.value.isNotEmpty()
}
