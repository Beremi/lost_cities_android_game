package com.carcassonne.lan.network

import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.carcassonne.lan.model.ClientSession
import com.carcassonne.lan.model.GenericOkResponse
import com.carcassonne.lan.model.HeartbeatRequest
import com.carcassonne.lan.model.InviteListResponse
import com.carcassonne.lan.model.InviteRespondRequest
import com.carcassonne.lan.model.JoinRequest
import com.carcassonne.lan.model.JoinResponse
import com.carcassonne.lan.model.LostCitiesTurnActionRequest
import com.carcassonne.lan.model.PingResponse
import com.carcassonne.lan.model.PollRequest
import com.carcassonne.lan.model.PollResponse
import com.carcassonne.lan.model.SubmitTurnResponse
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class BluetoothClient(private val context: Context) {
    private data class MatchConnection(
        val address: String,
        val socket: BluetoothSocket,
        val reader: BufferedReader,
        val writer: BufferedWriter,
        val lock: Mutex = Mutex(),
    ) : Closeable {
        override fun close() {
            runCatching { reader.close() }
            runCatching { writer.close() }
            runCatching { socket.close() }
        }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val bluetoothAdapter
        get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    @Volatile
    private var matchConnection: MatchConnection? = null

    suspend fun ping(address: String): PingResponse? = withContext(Dispatchers.IO) {
        runCatching {
            requestOnce(
                address = address,
                method = "GET",
                path = "/api/ping",
                responseSerializer = PingResponse.serializer(),
            )
        }.getOrNull()
    }

    suspend fun inviteList(address: String, serverId: String): InviteListResponse =
        requestOnce(
            address = address,
            method = "GET",
            path = "/api/invite/list?server_id=$serverId",
            responseSerializer = InviteListResponse.serializer(),
        )

    suspend fun respondInvite(address: String, inviteId: String, action: String): GenericOkResponse =
        requestOnce(
            address = address,
            method = "POST",
            path = "/api/invite/respond",
            body = InviteRespondRequest(inviteId = inviteId, action = action),
            serializer = InviteRespondRequest.serializer(),
            responseSerializer = GenericOkResponse.serializer(),
        )

    suspend fun join(address: String, playerName: String): JoinResponse {
        closeMatchConnection()
        val connection = openConnection(address)
        return try {
            val response = requestOnConnection(
                connection = connection,
                method = "POST",
                path = "/api/session/join",
                body = JoinRequest(playerName = playerName),
                serializer = JoinRequest.serializer(),
                responseSerializer = JoinResponse.serializer(),
            )
            if (!response.ok) {
                connection.close()
            } else {
                matchConnection = connection
            }
            response
        } catch (t: Throwable) {
            connection.close()
            throw t
        }
    }

    suspend fun poll(session: ClientSession): PollResponse =
        withMatchConnection(session) { connection ->
            requestOnConnection(
                connection = connection,
                method = "POST",
                path = "/api/match/poll",
                body = PollRequest(token = session.token),
                serializer = PollRequest.serializer(),
                responseSerializer = PollResponse.serializer(),
            )
        }

    suspend fun lostCitiesAction(
        session: ClientSession,
        action: String,
        cardId: String,
        suit: String? = null,
    ): SubmitTurnResponse =
        withMatchConnection(session) { connection ->
            requestOnConnection(
                connection = connection,
                method = "POST",
                path = "/api/match/lost_cities_action",
                body = LostCitiesTurnActionRequest(
                    token = session.token,
                    action = action,
                    cardId = cardId,
                    suit = suit,
                ),
                serializer = LostCitiesTurnActionRequest.serializer(),
                responseSerializer = SubmitTurnResponse.serializer(),
            )
        }

    suspend fun leave(session: ClientSession): Boolean {
        val response = withMatchConnection(session) { connection ->
            requestOnConnection(
                connection = connection,
                method = "POST",
                path = "/api/session/leave",
                body = HeartbeatRequest(token = session.token),
                serializer = HeartbeatRequest.serializer(),
                responseSerializer = GenericOkResponse.serializer(),
            )
        }
        closeMatchConnection()
        return response.ok
    }

    fun closeMatchConnection() {
        matchConnection?.close()
        matchConnection = null
    }

    private suspend fun <Res> requestOnce(
        address: String,
        method: String,
        path: String,
        responseSerializer: KSerializer<Res>,
    ): Res {
        val connection = openConnection(address)
        return try {
            requestOnConnection(
                connection = connection,
                method = method,
                path = path,
                responseSerializer = responseSerializer,
            )
        } finally {
            connection.close()
        }
    }

    private suspend fun <Req, Res> requestOnce(
        address: String,
        method: String,
        path: String,
        body: Req,
        serializer: KSerializer<Req>,
        responseSerializer: KSerializer<Res>,
    ): Res {
        val connection = openConnection(address)
        return try {
            requestOnConnection(
                connection = connection,
                method = method,
                path = path,
                body = body,
                serializer = serializer,
                responseSerializer = responseSerializer,
            )
        } finally {
            connection.close()
        }
    }

    private suspend fun <Res> requestOnConnection(
        connection: MatchConnection,
        method: String,
        path: String,
        responseSerializer: KSerializer<Res>,
    ): Res {
        return requestRaw(
            connection = connection,
            method = method,
            path = path,
            bodyJson = null,
            responseSerializer = responseSerializer,
        )
    }

    private suspend fun <Req, Res> requestOnConnection(
        connection: MatchConnection,
        method: String,
        path: String,
        body: Req,
        serializer: KSerializer<Req>,
        responseSerializer: KSerializer<Res>,
    ): Res {
        return requestRaw(
            connection = connection,
            method = method,
            path = path,
            bodyJson = json.encodeToString(serializer, body),
            responseSerializer = responseSerializer,
        )
    }

    private suspend fun <Res> requestRaw(
        connection: MatchConnection,
        method: String,
        path: String,
        bodyJson: String?,
        responseSerializer: KSerializer<Res>,
    ): Res = withContext(Dispatchers.IO) {
        connection.lock.withLock {
            val request = BluetoothRpcRequest(
                method = method,
                path = path,
                body = bodyJson,
            )
            connection.writer.write(json.encodeToString(BluetoothRpcRequest.serializer(), request))
            connection.writer.newLine()
            connection.writer.flush()

            val rawResponse = connection.reader.readLine()
                ?: error("Bluetooth peer closed the connection.")
            val envelope = json.decodeFromString(BluetoothRpcResponse.serializer(), rawResponse)
            if (!envelope.ok || envelope.payload.isNullOrBlank()) {
                error(envelope.error ?: "Bluetooth request failed for $path.")
            }
            json.decodeFromString(responseSerializer, envelope.payload)
        }
    }

    private suspend fun <Res> withMatchConnection(
        session: ClientSession,
        block: suspend (MatchConnection) -> Res,
    ): Res {
        val firstConnection = ensureMatchConnection(session.hostAddress)
        return try {
            block(firstConnection)
        } catch (t: Throwable) {
            synchronized(this) {
                if (matchConnection === firstConnection) {
                    firstConnection.close()
                    matchConnection = null
                }
            }
            val secondConnection = ensureMatchConnection(session.hostAddress)
            block(secondConnection)
        }
    }

    private suspend fun ensureMatchConnection(address: String): MatchConnection {
        matchConnection?.takeIf { it.address == address }?.let { return it }
        val created = openConnection(address)
        matchConnection?.close()
        matchConnection = created
        return created
    }

    private suspend fun openConnection(address: String): MatchConnection = withContext(Dispatchers.IO) {
        val adapter = bluetoothAdapter ?: error("Bluetooth is not supported on this device.")
        val device = adapter.getRemoteDevice(address)
        runCatching { adapter.cancelDiscovery() }
        val socket = device.createInsecureRfcommSocketToServiceRecord(BluetoothProtocol.SERVICE_UUID)
        socket.connect()
        MatchConnection(
            address = address,
            socket = socket,
            reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8)),
            writer = BufferedWriter(OutputStreamWriter(socket.outputStream, Charsets.UTF_8)),
        )
    }
}
