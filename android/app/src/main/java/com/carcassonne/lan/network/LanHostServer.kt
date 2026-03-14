package com.carcassonne.lan.network

import com.carcassonne.lan.data.MatchMetadataStore
import com.carcassonne.lan.model.GenericOkResponse
import com.carcassonne.lan.model.InviteListResponse
import com.carcassonne.lan.model.InviteRespondRequest
import com.carcassonne.lan.model.InviteSendRequest
import com.carcassonne.lan.model.InviteSendResponse
import com.carcassonne.lan.model.InviteStatusResponse
import com.carcassonne.lan.model.HeartbeatRequest
import com.carcassonne.lan.model.LostCitiesTurnActionRequest
import com.carcassonne.lan.model.JoinRequest
import com.carcassonne.lan.model.JoinResponse
import com.carcassonne.lan.model.PingResponse
import com.carcassonne.lan.model.PollRequest
import com.carcassonne.lan.model.PollResponse
import com.carcassonne.lan.model.SubmitTurnResponse
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class LanHostServer(
    private val hostGameManager: HostGameManager,
    private val metadataStore: MatchMetadataStore,
) {
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var server: InternalServer? = null

    @Volatile
    var runningPort: Int? = null
        private set

    suspend fun start(port: Int) {
        withContext(Dispatchers.IO) {
            if (runningPort == port && server != null) return@withContext
            stop()
            val created = InternalServer(
                port = port,
                manager = hostGameManager,
                metadataStore = metadataStore,
                ioScope = ioScope,
                json = json,
            )
            created.start(SOCKET_READ_TIMEOUT, false)
            server = created
            runningPort = port
        }
    }

    fun stop() {
        server?.stop()
        server = null
        runningPort = null
    }

    private class InternalServer(
        port: Int,
        private val manager: HostGameManager,
        private val metadataStore: MatchMetadataStore,
        private val ioScope: CoroutineScope,
        private val json: Json,
    ) : NanoHTTPD("0.0.0.0", port) {
        override fun serve(session: IHTTPSession): Response {
            return try {
                when (session.method) {
                    Method.GET -> handleGet(session)
                    Method.POST -> handlePost(session)
                    else -> jsonResponse(
                        status = Response.Status.METHOD_NOT_ALLOWED,
                        serializer = GenericOkResponse.serializer(),
                        payload = GenericOkResponse(ok = false, error = "Method not allowed."),
                    )
                }
            } catch (t: Throwable) {
                jsonResponse(
                    status = Response.Status.INTERNAL_ERROR,
                    serializer = GenericOkResponse.serializer(),
                    payload = GenericOkResponse(ok = false, error = t.message ?: "Internal server error."),
                )
            }
        }

        private fun handleGet(session: IHTTPSession): Response {
            return when (session.uri) {
                "/api/ping" -> {
                    jsonResponse(
                        status = Response.Status.OK,
                        serializer = PingResponse.serializer(),
                        payload = manager.ping(),
                    )
                }

                "/api/health" -> {
                    jsonResponse(
                        status = Response.Status.OK,
                        serializer = GenericOkResponse.serializer(),
                        payload = GenericOkResponse(ok = true),
                    )
                }

                "/api/invite/list" -> {
                    val payload = manager.listInvites(queryServerId(session))
                    jsonResponse(
                        status = Response.Status.OK,
                        serializer = InviteListResponse.serializer(),
                        payload = payload,
                    )
                }

                "/api/invite/status" -> {
                    val inviteId = queryInviteId(session)
                    if (inviteId == null) {
                        return badRequest("Missing invite_id query parameter.")
                    }
                    val payload = manager.inviteStatus(inviteId)
                    jsonResponse(
                        status = if (payload.ok) Response.Status.OK else Response.Status.NOT_FOUND,
                        serializer = InviteStatusResponse.serializer(),
                        payload = payload,
                    )
                }

                else -> jsonResponse(
                    status = Response.Status.NOT_FOUND,
                    serializer = GenericOkResponse.serializer(),
                    payload = GenericOkResponse(ok = false, error = "Not found."),
                )
            }
        }

        private fun handlePost(session: IHTTPSession): Response {
            return when (session.uri) {
                "/api/session/join" -> {
                    val req = decodeBody(session, JoinRequest.serializer())
                        ?: return badRequest("Invalid JSON payload.")
                    val res = manager.joinOrReconnect(req.playerName)
                    if (res.ok) {
                        ioScope.launch {
                            res.match?.let { metadataStore.saveHost(it) }
                        }
                    }
                    jsonResponse(
                        status = if (res.ok) Response.Status.OK else Response.Status.BAD_REQUEST,
                        serializer = JoinResponse.serializer(),
                        payload = res,
                    )
                }

                "/api/invite/send" -> {
                    val req = decodeBody(session, InviteSendRequest.serializer())
                        ?: return badRequest("Invalid JSON payload.")
                    val res = manager.createInvite(req.fromName, req.targetServerId, req.rules)
                    if (res.ok) {
                        ioScope.launch {
                            metadataStore.saveHost(manager.snapshot())
                        }
                    }
                    jsonResponse(
                        status = if (res.ok) Response.Status.OK else Response.Status.BAD_REQUEST,
                        serializer = InviteSendResponse.serializer(),
                        payload = res,
                    )
                }

                "/api/invite/respond" -> {
                    val req = decodeBody(session, InviteRespondRequest.serializer())
                        ?: return badRequest("Invalid JSON payload.")
                    val res = manager.respondToInvite(req.inviteId, req.action)
                    jsonResponse(
                        status = if (res.ok) Response.Status.OK else Response.Status.BAD_REQUEST,
                        serializer = GenericOkResponse.serializer(),
                        payload = res,
                    )
                }

                "/api/session/reconnect" -> {
                    val req = decodeBody(session, JoinRequest.serializer())
                        ?: return badRequest("Invalid JSON payload.")
                    val res = manager.joinOrReconnect(req.playerName)
                    if (res.ok) {
                        ioScope.launch {
                            res.match?.let { metadataStore.saveHost(it) }
                        }
                    }
                    jsonResponse(
                        status = if (res.ok) Response.Status.OK else Response.Status.BAD_REQUEST,
                        serializer = JoinResponse.serializer(),
                        payload = res,
                    )
                }

                "/api/session/heartbeat" -> {
                    val req = decodeBody(session, HeartbeatRequest.serializer())
                        ?: return badRequest("Invalid JSON payload.")
                    val res = manager.heartbeat(req.token)
                    jsonResponse(
                        status = if (res.ok) Response.Status.OK else Response.Status.UNAUTHORIZED,
                        serializer = GenericOkResponse.serializer(),
                        payload = res,
                    )
                }

                "/api/session/leave" -> {
                    val req = decodeBody(session, HeartbeatRequest.serializer())
                        ?: return badRequest("Invalid JSON payload.")
                    val res = manager.removeToken(req.token)
                    if (res.ok) {
                        ioScope.launch {
                            metadataStore.saveHost(manager.snapshot())
                        }
                    }
                    jsonResponse(
                        status = if (res.ok) Response.Status.OK else Response.Status.UNAUTHORIZED,
                        serializer = GenericOkResponse.serializer(),
                        payload = res,
                    )
                }

                "/api/match/poll" -> {
                    val req = decodeBody(session, PollRequest.serializer())
                        ?: return badRequest("Invalid JSON payload.")
                    val res = manager.poll(req.token)
                    jsonResponse(
                        status = if (res.ok) Response.Status.OK else Response.Status.UNAUTHORIZED,
                        serializer = PollResponse.serializer(),
                        payload = res,
                    )
                }

                "/api/match/lost_cities_action" -> {
                    val req = decodeBody(session, LostCitiesTurnActionRequest.serializer())
                        ?: return badRequest("Invalid JSON payload.")
                    val res = manager.applyLostCitiesAction(
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
                    jsonResponse(
                        status = if (res.ok) Response.Status.OK else Response.Status.BAD_REQUEST,
                        serializer = SubmitTurnResponse.serializer(),
                        payload = res,
                    )
                }

                else -> jsonResponse(
                    status = Response.Status.NOT_FOUND,
                    serializer = GenericOkResponse.serializer(),
                    payload = GenericOkResponse(ok = false, error = "Not found."),
                )
            }
        }

        private fun <T> decodeBody(session: IHTTPSession, serializer: KSerializer<T>): T? {
            val body = readBody(session) ?: return null
            return try {
                json.decodeFromString(serializer, body)
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        private fun readBody(session: IHTTPSession): String? {
            return try {
                val files = mutableMapOf<String, String>()
                session.parseBody(files)
                files["postData"]
            } catch (_: Exception) {
                null
            }
        }

        private fun queryInviteId(session: IHTTPSession): String? {
            return session.parameters["invite_id"]?.firstOrNull()?.trim()?.ifBlank { null }
        }

        private fun queryServerId(session: IHTTPSession): String? {
            return session.parameters["server_id"]?.firstOrNull()?.trim()?.ifBlank { null }
        }

        private fun badRequest(message: String): Response {
            return jsonResponse(
                status = Response.Status.BAD_REQUEST,
                serializer = GenericOkResponse.serializer(),
                payload = GenericOkResponse(ok = false, error = message),
            )
        }

        private fun <T> jsonResponse(
            status: Response.IStatus,
            serializer: KSerializer<T>,
            payload: T,
        ): Response {
            val body = json.encodeToString(serializer, payload)
            return newFixedLengthResponse(status, "application/json; charset=utf-8", body).apply {
                addHeader("Cache-Control", "no-store")
            }
        }
    }

    companion object {
        private const val SOCKET_READ_TIMEOUT = 8_000
    }
}
