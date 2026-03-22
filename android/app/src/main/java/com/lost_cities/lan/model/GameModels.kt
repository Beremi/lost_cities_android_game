package com.lost_cities.lan.model

import kotlinx.serialization.Serializable

@Serializable
enum class MatchStatus {
    WAITING,
    ACTIVE,
    FINISHED,
    ABORTED,
}

@Serializable
data class PlayerSlot(
    val player: Int,
    val name: String,
    val token: String,
    val connected: Boolean = true,
    val lastSeenEpochMs: Long,
)

@Serializable
data class GameRules(
    val usePurple: Boolean = false,
)

enum class PeerTransport {
    LAN,
    BLUETOOTH,
}

data class PeerEndpoint(
    val transport: PeerTransport,
    val address: String,
    val port: Int? = null,
    val displayName: String = "",
    val serverId: String? = null,
)

@Serializable
data class MatchState(
    val id: String,
    val status: MatchStatus = MatchStatus.WAITING,
    val players: Map<Int, PlayerSlot> = emptyMap(),
    val score: Map<Int, Int> = mapOf(1 to 0, 2 to 0),
    val rules: GameRules = GameRules(),
    val lostCities: LostCitiesMatchState = LostCitiesMatchState(),
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val lastEvent: String = "",
    val rematchOfferedByPlayer: Int? = null,
)

@Serializable
data class ClientSession(
    val transport: PeerTransport = PeerTransport.LAN,
    val hostAddress: String,
    val port: Int,
    val token: String,
    val player: Int,
    val playerName: String,
)
