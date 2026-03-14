package com.carcassonne.lan.network

import com.carcassonne.lan.model.PingResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

class LanScanner {
    private data class ProbeTarget(
        val address: String,
        val port: Int,
        val isSelfHint: Boolean,
    )

    data class DiscoveredHost(
        val address: String,
        val port: Int,
        val ping: PingResponse,
        val isSelf: Boolean,
    )

    private val json = Json { ignoreUnknownKeys = true }

    private val fastClient = OkHttpClient.Builder()
        .connectTimeout(500, TimeUnit.MILLISECONDS)
        .readTimeout(650, TimeUnit.MILLISECONDS)
        .writeTimeout(650, TimeUnit.MILLISECONDS)
        .build()

    fun localIPv4Addresses(): Set<String> = queryLocalIPv4Addresses()

    suspend fun scan(port: Int): List<DiscoveredHost> = withContext(Dispatchers.IO) {
        val selfIps = queryLocalIPv4Addresses()
        val prefixes = selfIps
            .mapNotNull { ip ->
                val parts = ip.split('.')
                if (parts.size == 4) "${parts[0]}.${parts[1]}.${parts[2]}" else null
            }
            .distinct()

        val candidates = linkedSetOf<ProbeTarget>()
        if (selfIps.any { it.startsWith("10.0.2.") }) {
            emulatorGatewayPorts(port).forEach { forwardedPort ->
                candidates += ProbeTarget(EmulatorGateway, forwardedPort, isSelfHint = false)
            }
        }
        for (prefix in prefixes) {
            for (last in 1..254) {
                candidates += ProbeTarget("$prefix.$last", port, isSelfHint = false)
            }
        }

        if (selfIps.isNotEmpty()) {
            selfIps.forEach { candidates += ProbeTarget(it, port, isSelfHint = true) }
        } else {
            candidates += ProbeTarget("127.0.0.1", port, isSelfHint = true)
        }

        val semaphore = Semaphore(64)
        coroutineScope {
            candidates.map { target ->
                async {
                    semaphore.withPermit {
                        pingHost(
                            address = target.address,
                            port = target.port,
                            isSelf = target.isSelfHint || target.address in selfIps || target.address == "127.0.0.1",
                        )
                    }
                }
            }.awaitAll()
                .filterNotNull()
                .distinctBy { "${it.address}:${it.port}" }
                .sortedWith(compareBy<DiscoveredHost> { !it.isSelf }.thenBy { it.address }.thenBy { it.port })
        }
    }

    private fun pingHost(address: String, port: Int, isSelf: Boolean): DiscoveredHost? {
        val req = Request.Builder()
            .url("http://$address:$port/api/ping")
            .get()
            .build()

        return runCatching {
            fastClient.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@use null
                val body = res.body?.string() ?: return@use null
                val ping = json.decodeFromString(PingResponse.serializer(), body)
                DiscoveredHost(address = address, port = port, ping = ping, isSelf = isSelf)
            }
        }.getOrNull()
    }

    private fun queryLocalIPv4Addresses(): Set<String> {
        val out = linkedSetOf<String>()
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces().toList() }
            .getOrDefault(emptyList())
        for (iface in interfaces) {
            val isLoopback = runCatching { iface.isLoopback }.getOrDefault(false)
            val isUp = runCatching { iface.isUp }.getOrDefault(false)
            if (isLoopback || !isUp) continue
            val addrs = runCatching { iface.inetAddresses.toList() }.getOrDefault(emptyList())
            for (addr in addrs) {
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    out += addr.hostAddress ?: continue
                }
            }
        }
        return out
    }

    fun hasEmulatorGateway(): Boolean = runCatching { localIPv4Addresses() }.getOrDefault(emptySet())
        .any { it.startsWith("10.0.2.") }

    private fun emulatorGatewayPorts(basePort: Int): IntRange {
        return basePort..(basePort + EMULATOR_FORWARD_PORT_SPAN)
    }

    companion object {
        const val EmulatorGateway = "10.0.2.2"
        private const val EMULATOR_FORWARD_PORT_SPAN = 3
    }
}
