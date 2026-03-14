package com.carcassonne.lan.network

import com.carcassonne.lan.model.ClientSession
import com.carcassonne.lan.model.GameRules
import com.carcassonne.lan.model.GenericOkResponse
import com.carcassonne.lan.model.HeartbeatRequest
import com.carcassonne.lan.model.InviteListResponse
import com.carcassonne.lan.model.InviteRespondRequest
import com.carcassonne.lan.model.InviteSendRequest
import com.carcassonne.lan.model.InviteSendResponse
import com.carcassonne.lan.model.InviteStatusResponse
import com.carcassonne.lan.model.JoinRequest
import com.carcassonne.lan.model.JoinResponse
import com.carcassonne.lan.model.LostCitiesTurnActionRequest
import com.carcassonne.lan.model.PingResponse
import com.carcassonne.lan.model.PollRequest
import com.carcassonne.lan.model.PollResponse
import com.carcassonne.lan.model.SubmitTurnResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class LanClient {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .build()

    suspend fun ping(host: String, port: Int): PingResponse? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("http://$host:$port/api/ping")
            .get()
            .build()

        runCatching {
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@use null
                val body = res.body?.string() ?: return@use null
                json.decodeFromString(PingResponse.serializer(), body)
            }
        }.getOrNull()
    }

    suspend fun join(host: String, port: Int, playerName: String): JoinResponse =
        postJson(
            host = host,
            port = port,
            path = "/api/session/join",
            body = JoinRequest(playerName = playerName),
            serializer = JoinRequest.serializer(),
            responseSerializer = JoinResponse.serializer(),
        )

    suspend fun sendInvite(
        host: String,
        port: Int,
        fromName: String,
        targetServerId: String?,
        rules: GameRules,
    ): InviteSendResponse =
        postJson(
            host = host,
            port = port,
            path = "/api/invite/send",
            body = InviteSendRequest(fromName = fromName, targetServerId = targetServerId, rules = rules),
            serializer = InviteSendRequest.serializer(),
            responseSerializer = InviteSendResponse.serializer(),
        )

    suspend fun inviteStatus(host: String, port: Int, inviteId: String): InviteStatusResponse =
        getJson(
            host = host,
            port = port,
            path = "/api/invite/status?invite_id=$inviteId",
            responseSerializer = InviteStatusResponse.serializer(),
        )

    suspend fun inviteList(host: String, port: Int, serverId: String): InviteListResponse =
        getJson(
            host = host,
            port = port,
            path = "/api/invite/list?server_id=$serverId",
            responseSerializer = InviteListResponse.serializer(),
        )

    suspend fun respondInvite(
        host: String,
        port: Int,
        inviteId: String,
        action: String,
    ): GenericOkResponse =
        postJson(
            host = host,
            port = port,
            path = "/api/invite/respond",
            body = InviteRespondRequest(inviteId = inviteId, action = action),
            serializer = InviteRespondRequest.serializer(),
            responseSerializer = GenericOkResponse.serializer(),
        )

    suspend fun reconnect(host: String, port: Int, playerName: String): JoinResponse =
        postJson(
            host = host,
            port = port,
            path = "/api/session/reconnect",
            body = JoinRequest(playerName = playerName),
            serializer = JoinRequest.serializer(),
            responseSerializer = JoinResponse.serializer(),
        )

    suspend fun poll(session: ClientSession): PollResponse =
        postJson(
            host = session.hostAddress,
            port = session.port,
            path = "/api/match/poll",
            body = PollRequest(token = session.token),
            serializer = PollRequest.serializer(),
            responseSerializer = PollResponse.serializer(),
        )

    suspend fun lostCitiesAction(
        session: ClientSession,
        action: String,
        cardId: String,
        suit: String? = null,
    ): SubmitTurnResponse =
        postJson(
            host = session.hostAddress,
            port = session.port,
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

    suspend fun heartbeat(session: ClientSession): Boolean {
        val response = postJson(
            host = session.hostAddress,
            port = session.port,
            path = "/api/session/heartbeat",
            body = HeartbeatRequest(token = session.token),
            serializer = HeartbeatRequest.serializer(),
            responseSerializer = GenericOkResponse.serializer(),
        )
        return response.ok
    }

    suspend fun leave(session: ClientSession): Boolean {
        val response = postJson(
            host = session.hostAddress,
            port = session.port,
            path = "/api/session/leave",
            body = HeartbeatRequest(token = session.token),
            serializer = HeartbeatRequest.serializer(),
            responseSerializer = GenericOkResponse.serializer(),
        )
        return response.ok
    }

    private suspend fun <Req, Res> postJson(
        host: String,
        port: Int,
        path: String,
        body: Req,
        serializer: KSerializer<Req>,
        responseSerializer: KSerializer<Res>,
    ): Res = withContext(Dispatchers.IO) {
        val payload = json.encodeToString(serializer, body)
        val req = Request.Builder()
            .url("http://$host:$port$path")
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        client.newCall(req).execute().use { res ->
            val raw = res.body?.string().orEmpty()
            if (raw.isBlank()) error("Empty response for $path")
            json.decodeFromString(responseSerializer, raw)
        }
    }

    private suspend fun <Res> getJson(
        host: String,
        port: Int,
        path: String,
        responseSerializer: KSerializer<Res>,
    ): Res = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("http://$host:$port$path")
            .get()
            .build()

        client.newCall(req).execute().use { res ->
            val raw = res.body?.string().orEmpty()
            if (raw.isBlank()) error("Empty response for $path")
            json.decodeFromString(responseSerializer, raw)
        }
    }
}
