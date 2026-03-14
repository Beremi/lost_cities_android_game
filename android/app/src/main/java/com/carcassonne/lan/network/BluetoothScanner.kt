package com.carcassonne.lan.network

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class BluetoothScanner(private val context: Context) {
    data class NearbyDevice(
        val address: String,
        val name: String,
        val bonded: Boolean,
    )

    private val bluetoothAdapter: BluetoothAdapter?
        get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    fun isSupported(): Boolean = bluetoothAdapter != null

    fun isEnabled(): Boolean = runCatching { bluetoothAdapter?.isEnabled == true }.getOrDefault(false)

    fun bondedDevices(): List<NearbyDevice> {
        val adapter = bluetoothAdapter ?: return emptyList()
        return runCatching {
            adapter.bondedDevices.orEmpty()
                .map { device -> device.toNearbyDevice(bonded = true) }
                .sortedBy { it.name.lowercase() }
        }.getOrDefault(emptyList())
    }

    suspend fun scanNearby(timeoutMs: Long = DISCOVERY_TIMEOUT_MS): List<NearbyDevice> = withContext(Dispatchers.IO) {
        val adapter = bluetoothAdapter ?: return@withContext emptyList()
        if (runCatching { !adapter.isEnabled }.getOrDefault(true)) {
            return@withContext bondedDevices()
        }

        val discovered = LinkedHashMap<String, NearbyDevice>()
        bondedDevices().forEach { device ->
            discovered[device.address] = device
        }

        val completed = CompletableDeferred<Unit>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        intentDevice(intent)?.let { device ->
                            discovered[device.address] = device.toNearbyDevice(
                                bonded = device.bondState == BluetoothDevice.BOND_BONDED,
                            )
                        }
                    }

                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        completed.complete(Unit)
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }

        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        try {
            runCatching { adapter.cancelDiscovery() }
            val started = runCatching { adapter.startDiscovery() }.getOrDefault(false)
            if (started) {
                withTimeoutOrNull(timeoutMs) { completed.await() }
            }
        } finally {
            runCatching { adapter.cancelDiscovery() }
            runCatching { context.unregisterReceiver(receiver) }
        }

        return@withContext discovered.values
            .sortedWith(compareBy<NearbyDevice> { !it.bonded }.thenBy { it.name.lowercase() })
    }

    private fun intentDevice(intent: Intent): BluetoothDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
    }

    private fun BluetoothDevice.toNearbyDevice(bonded: Boolean): NearbyDevice {
        val label = name?.trim().orEmpty().ifBlank { address ?: "Bluetooth device" }
        return NearbyDevice(
            address = address ?: "",
            name = label,
            bonded = bonded,
        )
    }

    companion object {
        private const val DISCOVERY_TIMEOUT_MS = 12_000L
    }
}
