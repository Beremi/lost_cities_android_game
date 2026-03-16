package com.carcassonne.lan.network

import java.util.UUID
import kotlinx.serialization.Serializable

internal object BluetoothProtocol {
    const val SECURE_SERVICE_NAME = "LostCitiesLanBluetoothSecure"
    const val INSECURE_SERVICE_NAME = "LostCitiesLanBluetoothInsecure"
    val SECURE_SERVICE_UUID: UUID = UUID.fromString("7f972642-6d9e-4dc0-b4fd-f2d4f0b8a8d9")
    val INSECURE_SERVICE_UUID: UUID = UUID.fromString("1149dc86-4e18-4cbf-9763-1c41475c73db")
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
