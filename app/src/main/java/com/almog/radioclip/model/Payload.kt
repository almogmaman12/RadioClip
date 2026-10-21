package com.almog.radioclip.model

import java.util.UUID

sealed class Payload(
    open val id: String = UUID.randomUUID().toString(),
    open val timestamp: Long = System.currentTimeMillis(),
    open val transportType: TransportType = TransportType.BLE_GATT,
    open val senderDeviceName: String? = null,
    open val isOutgoing: Boolean = false
) {
    data class Text(
        override val id: String = UUID.randomUUID().toString(),
        override val timestamp: Long = System.currentTimeMillis(),
        override val transportType: TransportType = TransportType.BLE_GATT,
        override val senderDeviceName: String? = null,
        override val isOutgoing: Boolean = false,
        val content: String
    ) : Payload(id, timestamp, transportType, senderDeviceName, isOutgoing) {
        val byteSize: Int get() = content.toByteArray(Charsets.UTF_8).size
    }

    data class File(
        override val id: String = UUID.randomUUID().toString(),
        override val timestamp: Long = System.currentTimeMillis(),
        override val transportType: TransportType = TransportType.WIFI_DIRECT_SOCKET,
        override val senderDeviceName: String? = null,
        override val isOutgoing: Boolean = false,
        val fileName: String,
        val mimeType: String,
        val sizeBytes: Long,
        val uriString: String? = null,
        val localPath: String? = null
    ) : Payload(id, timestamp, transportType, senderDeviceName, isOutgoing)
}

enum class TransportType {
    BLE_GATT,
    WIFI_DIRECT_SOCKET
}

enum class TransportState {
    IDLE,
    SCANNING,
    ADVERTISING,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    ERROR
}

data class PeerDevice(
    val id: String,
    val name: String,
    val transportType: TransportType,
    val isConnected: Boolean = false
)
