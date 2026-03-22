package com.lost_cities.lan.network

import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.lost_cities.lan.data.MatchMetadataStore
import com.lost_cities.lan.model.GenericOkResponse
import com.lost_cities.lan.model.HeartbeatRequest
import com.lost_cities.lan.model.InviteListResponse
import com.lost_cities.lan.model.InviteRespondRequest
import com.lost_cities.lan.model.JoinRequest
import com.lost_cities.lan.model.JoinResponse
import com.lost_cities.lan.model.LostCitiesTurnActionRequest
import com.lost_cities.lan.model.PingResponse
import com.lost_cities.lan.model.PollRequest
import com.lost_cities.lan.model.PollResponse
import com.lost_cities.lan.model.SubmitTurnResponse
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class BluetoothHostServer(
    private val context: Context,
    private val hostGameManager: HostGameManager,
    private val metadataStore: MatchMetadataStore,
) {
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val bluetoothAdapter
        get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    @Volatile
    private var serverSockets: List<BluetoothServerSocket> = emptyList()

    @Volatile
    private var acceptJobs: List<Job> = emptyList()

    suspend fun start() {
        withContext(Dispatchers.IO) {
            val adapter = bluetoothAdapter ?: return@withContext
            if (!adapter.isEnabled) return@withContext
            if (acceptJobs.any { it.isActive } && serverSockets.isNotEmpty()) return@withContext
            stop()
            val createdSockets = buildList {
                runCatching {
                    adapter.listenUsingRfcommWithServiceRecord(
                        BluetoothProtocol.SECURE_SERVICE_NAME,
                        BluetoothProtocol.SECURE_SERVICE_UUID,
                    )
                }.getOrNull()?.let(::add)
                runCatching {
                    adapter.listenUsingInsecureRfcommWithServiceRecord(
                        BluetoothProtocol.INSECURE_SERVICE_NAME,
                        BluetoothProtocol.INSECURE_SERVICE_UUID,
                    )
                }.getOrNull()?.let(::add)
            }
            if (createdSockets.isEmpty()) {
                error("Could not open any Bluetooth lobby sockets.")
            }
            serverSockets = createdSockets
            acceptJobs = createdSockets.map { socket ->
                ioScope.launch { acceptLoop(socket) }
            }
        }
    }

    fun stop() {
        acceptJobs.forEach { it.cancel() }
        acceptJobs = emptyList()
        serverSockets.forEach { socket ->
            runCatching { socket.close() }
        }
        serverSockets = emptyList()
    }

    private suspend fun acceptLoop(activeSocket: BluetoothServerSocket) {
        while (acceptJobs.any { it.isActive } && serverSockets.contains(activeSocket)) {
            val socket = runCatching { activeSocket.accept() }.getOrNull() ?: break
            ioScope.launch {
                handleClient(socket)
            }
        }
    }

    private suspend fun handleClient(socket: BluetoothSocket) = withContext(Dispatchers.IO) {
        socket.use { activeSocket ->
            val reader = BufferedReader(InputStreamReader(activeSocket.inputStream, Charsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(activeSocket.outputStream, Charsets.UTF_8))
            while (true) {
                val rawRequest = reader.readLine() ?: break
                val response = runCatching {
                    val request = json.decodeFromString(BluetoothRpcRequest.serializer(), rawRequest)
                    handleRequest(request)
                }.getOrElse { error ->
                    BluetoothRpcResponse(ok = false, error = error.message ?: "Bluetooth server error.")
                }
                writer.write(json.encodeToString(BluetoothRpcResponse.serializer(), response))
                writer.newLine()
                writer.flush()
            }
        }
    }

    private fun handleRequest(request: BluetoothRpcRequest): BluetoothRpcResponse {
        return when (request.method.uppercase()) {
            "GET" -> handleGet(request.path)
            "POST" -> handlePost(request.path, request.body)
            else -> BluetoothRpcResponse(ok = false, error = "Method not allowed.")
        }
    }

    private fun handleGet(rawPath: String): BluetoothRpcResponse {
        val path = rawPath.substringBefore('?')
        return when (path) {
            "/api/ping" -> payload(PingResponse.serializer(), hostGameManager.ping())
            "/api/health" -> payload(GenericOkResponse.serializer(), GenericOkResponse(ok = true))
            "/api/invite/list" -> {
                val response = hostGameManager.listInvites(queryParam(rawPath, "server_id"))
                payload(InviteListResponse.serializer(), response)
            }

            else -> BluetoothRpcResponse(ok = false, error = "Not found.")
        }
    }

    private fun handlePost(rawPath: String, body: String?): BluetoothRpcResponse {
        val path = rawPath.substringBefore('?')
        return when (path) {
            "/api/session/join" -> {
                val req = decodeBody(body, JoinRequest.serializer())
                    ?: return BluetoothRpcResponse(ok = false, error = "Invalid join payload.")
                val res = hostGameManager.joinOrReconnect(req.playerName)
                if (res.ok) {
                    ioScope.launch {
                        res.match?.let { metadataStore.saveHost(it) }
                    }
                }
                payload(JoinResponse.serializer(), res)
            }

            "/api/invite/respond" -> {
                val req = decodeBody(body, InviteRespondRequest.serializer())
                    ?: return BluetoothRpcResponse(ok = false, error = "Invalid invite response payload.")
                val res = hostGameManager.respondToInvite(req.inviteId, req.action)
                payload(GenericOkResponse.serializer(), res)
            }

            "/api/session/leave" -> {
                val req = decodeBody(body, HeartbeatRequest.serializer())
                    ?: return BluetoothRpcResponse(ok = false, error = "Invalid leave payload.")
                val res = hostGameManager.removeToken(req.token)
                if (res.ok) {
                    ioScope.launch {
                        metadataStore.saveHost(hostGameManager.snapshot())
                    }
                }
                payload(GenericOkResponse.serializer(), res)
            }

            "/api/match/poll" -> {
                val req = decodeBody(body, PollRequest.serializer())
                    ?: return BluetoothRpcResponse(ok = false, error = "Invalid poll payload.")
                val res = hostGameManager.poll(req.token)
                payload(PollResponse.serializer(), res)
            }

            "/api/match/lost_cities_action" -> {
                val req = decodeBody(body, LostCitiesTurnActionRequest.serializer())
                    ?: return BluetoothRpcResponse(ok = false, error = "Invalid action payload.")
                val res = hostGameManager.applyLostCitiesAction(
                    token = req.token,
                    action = req.action,
                    cardId = req.cardId,
                    suit = req.suit,
                )
                if (res.ok) {
                    ioScope.launch {
                        res.match?.let { metadataStore.saveHost(it) }
                    }
                }
                payload(SubmitTurnResponse.serializer(), res)
            }

            else -> BluetoothRpcResponse(ok = false, error = "Not found.")
        }
    }

    private fun queryParam(rawPath: String, key: String): String? {
        val query = rawPath.substringAfter('?', missingDelimiterValue = "")
        if (query.isBlank()) return null
        return query.split('&')
            .mapNotNull { part ->
                val left = part.substringBefore('=')
                val right = part.substringAfter('=', missingDelimiterValue = "")
                if (left == key) right else null
            }
            .firstOrNull()
            ?.trim()
            ?.ifBlank { null }
    }

    private fun <T> payload(serializer: KSerializer<T>, value: T): BluetoothRpcResponse {
        return BluetoothRpcResponse(
            ok = true,
            payload = json.encodeToString(serializer, value),
        )
    }

    private fun <T> decodeBody(body: String?, serializer: KSerializer<T>): T? {
        if (body.isNullOrBlank()) return null
        return try {
            json.decodeFromString(serializer, body)
        } catch (_: SerializationException) {
            null
        }
    }
}
