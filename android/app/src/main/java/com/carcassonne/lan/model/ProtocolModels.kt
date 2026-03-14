package com.carcassonne.lan.model

import kotlinx.serialization.Serializable

@Serializable
data class PingResponse(
    val ok: Boolean = true,
    val app: String = "lost-cities-lan-android",
    val protocolVersion: Int = 1,
    val serverId: String,
    val hostName: String,
    val matchStatus: MatchStatus,
    val openSlots: Int,
    val rules: GameRules = GameRules(),
    val players: List<PlayerSummary>,
)

@Serializable
data class PlayerSummary(
    val player: Int,
    val name: String,
    val connected: Boolean,
)

@Serializable
data class JoinRequest(
    val playerName: String,
)

@Serializable
data class JoinResponse(
    val ok: Boolean,
    val token: String? = null,
    val player: Int? = null,
    val match: MatchState? = null,
    val error: String? = null,
)

@Serializable
data class PollRequest(
    val token: String,
)

@Serializable
data class PollResponse(
    val ok: Boolean,
    val canAct: Boolean = false,
    val match: MatchState? = null,
    val error: String? = null,
)

@Serializable
data class LostCitiesTurnActionRequest(
    val token: String,
    val action: String,
    val cardId: String,
    val suit: String? = null,
)

@Serializable
data class SubmitTurnResponse(
    val ok: Boolean,
    val match: MatchState? = null,
    val error: String? = null,
)

@Serializable
data class HeartbeatRequest(
    val token: String,
)

@Serializable
data class GenericOkResponse(
    val ok: Boolean,
    val error: String? = null,
)

@Serializable
data class InviteSendRequest(
    val fromName: String,
    val targetServerId: String? = null,
    val rules: GameRules? = null,
)

@Serializable
data class InviteSendResponse(
    val ok: Boolean,
    val inviteId: String? = null,
    val error: String? = null,
)

@Serializable
data class InviteStatusResponse(
    val ok: Boolean,
    val inviteId: String? = null,
    val status: String? = null,
    val error: String? = null,
)

@Serializable
data class InviteRespondRequest(
    val inviteId: String,
    val action: String,
)

@Serializable
data class InviteListItem(
    val id: String,
    val fromName: String,
    val createdAtEpochMs: Long,
    val status: String,
    val targetServerId: String? = null,
    val rules: GameRules? = null,
)

@Serializable
data class InviteListResponse(
    val ok: Boolean,
    val invites: List<InviteListItem> = emptyList(),
    val error: String? = null,
)
