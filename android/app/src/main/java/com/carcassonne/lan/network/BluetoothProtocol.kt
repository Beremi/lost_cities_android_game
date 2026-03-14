package com.carcassonne.lan.network

import java.util.UUID
import kotlinx.serialization.Serializable

internal object BluetoothProtocol {
    const val SERVICE_NAME = "LostCitiesLanBluetooth"
    val SERVICE_UUID: UUID = UUID.fromString("7f972642-6d9e-4dc0-b4fd-f2d4f0b8a8d9")
}

@Serializable
internal data class BluetoothRpcRequest(
    val method: String,
    val path: String,
    val body: String? = null,
)

@Serializable
internal data class BluetoothRpcResponse(
    val ok: Boolean,
    val payload: String? = null,
    val error: String? = null,
)
