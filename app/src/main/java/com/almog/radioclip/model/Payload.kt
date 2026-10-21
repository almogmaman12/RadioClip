package com.almog.radioclip.model

import java.util.UUID

sealed class Payload(
    open val id: String = UUID.randomUUID().toString(),
    open val timestamp: Long = System.currentTimeMillis()
) {
    data class Text(
        override val id: String = UUID.randomUUID().toString(),
        override val timestamp: Long = System.currentTimeMillis(),
        val content: String
    ) : Payload(id, timestamp) {
        val byteSize: Int get() = content.toByteArray(Charsets.UTF_8).size
    }

    data class File(
        override val id: String = UUID.randomUUID().toString(),
        override val timestamp: Long = System.currentTimeMillis(),
        val fileName: String,
        val mimeType: String,
        val sizeBytes: Long,
        val uriString: String? = null,
        val localPath: String? = null
    ) : Payload(id, timestamp)
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
