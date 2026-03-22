package com.lost_cities.lan.network

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import com.lost_cities.lan.model.ClientSession
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
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
                connectTimeoutMs = PROBE_TIMEOUT_MS,
                allowBonding = false,
            )
        }.getOrNull()
    }

    suspend fun preparePeer(address: String): PingResponse? = withContext(Dispatchers.IO) {
        ping(address)?.let { return@withContext it }
        runCatching {
            requestOnce(
                address = address,
                method = "GET",
                path = "/api/ping",
                responseSerializer = PingResponse.serializer(),
                connectTimeoutMs = CONNECT_TIMEOUT_MS,
                allowBonding = true,
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
        connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
        allowBonding: Boolean = false,
    ): Res {
        val connection = openConnection(
            address = address,
            connectTimeoutMs = connectTimeoutMs,
            allowBonding = allowBonding,
        )
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
        connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
        allowBonding: Boolean = false,
    ): Res {
        val connection = openConnection(
            address = address,
            connectTimeoutMs = connectTimeoutMs,
            allowBonding = allowBonding,
        )
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

    private suspend fun openConnection(
        address: String,
        connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
        allowBonding: Boolean = false,
    ): MatchConnection = withContext(Dispatchers.IO) {
        val adapter = bluetoothAdapter ?: error("Bluetooth is not supported on this device.")
        val device = adapter.getRemoteDevice(address)
        runCatching { adapter.cancelDiscovery() }
        connectSocket(
            address = address,
            socket = runCatching {
                device.createRfcommSocketToServiceRecord(BluetoothProtocol.SECURE_SERVICE_UUID)
            }.getOrNull(),
            timeoutMs = connectTimeoutMs,
        )?.let { return@withContext it }

        if (allowBonding && device.bondState != BluetoothDevice.BOND_BONDED && ensureBonded(device)) {
            connectSocket(
                address = address,
                socket = runCatching {
                    device.createRfcommSocketToServiceRecord(BluetoothProtocol.SECURE_SERVICE_UUID)
                }.getOrNull(),
                timeoutMs = connectTimeoutMs,
            )?.let { return@withContext it }
        }

        connectSocket(
            address = address,
            socket = runCatching {
                device.createInsecureRfcommSocketToServiceRecord(BluetoothProtocol.INSECURE_SERVICE_UUID)
            }.getOrNull(),
            timeoutMs = connectTimeoutMs,
        ) ?: error("Could not connect to Bluetooth peer $address.")
    }

    private suspend fun connectSocket(
        address: String,
        socket: BluetoothSocket?,
        timeoutMs: Long,
    ): MatchConnection? = withContext(Dispatchers.IO) {
        if (socket == null) return@withContext null
        coroutineScope {
            val connectAttempt = async(Dispatchers.IO) {
                socket.connect()
                MatchConnection(
                    address = address,
                    socket = socket,
                    reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8)),
                    writer = BufferedWriter(OutputStreamWriter(socket.outputStream, Charsets.UTF_8)),
                )
            }
            val connected = withTimeoutOrNull(timeoutMs) {
                runCatching { connectAttempt.await() }.getOrNull()
            }
            if (connected == null) {
                runCatching { socket.close() }
                connectAttempt.cancel()
            }
            connected
        }
    }

    private suspend fun ensureBonded(device: BluetoothDevice): Boolean = withContext(Dispatchers.IO) {
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            return@withContext true
        }

        val completed = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                    return
                }
                val changedDevice = intentDevice(intent) ?: return
                if (changedDevice.address != device.address) {
                    return
                }
                when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                    BluetoothDevice.BOND_BONDED -> if (!completed.isCompleted) completed.complete(true)
                    BluetoothDevice.BOND_NONE -> if (!completed.isCompleted) completed.complete(false)
                }
            }
        }
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        try {
            val started = runCatching { device.createBond() }.getOrDefault(false)
            if (!started) {
                return@withContext device.bondState == BluetoothDevice.BOND_BONDED
            }
            withTimeoutOrNull(BOND_TIMEOUT_MS) { completed.await() } ?: (device.bondState == BluetoothDevice.BOND_BONDED)
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    private fun intentDevice(intent: Intent): BluetoothDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
    }

    companion object {
        private const val PROBE_TIMEOUT_MS = 1_200L
        private const val CONNECT_TIMEOUT_MS = 3_500L
        private const val BOND_TIMEOUT_MS = 20_000L
    }
}
