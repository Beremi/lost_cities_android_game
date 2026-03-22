package com.lost_cities.lan.network

import com.lost_cities.lan.data.NameGenerator
import com.lost_cities.lan.model.GameRules
import com.lost_cities.lan.model.GenericOkResponse
import com.lost_cities.lan.model.InviteListItem
import com.lost_cities.lan.model.InviteListResponse
import com.lost_cities.lan.model.InviteSendResponse
import com.lost_cities.lan.model.InviteStatusResponse
import com.lost_cities.lan.model.JoinResponse
import com.lost_cities.lan.model.LostCitiesDeckCard
import com.lost_cities.lan.model.LostCitiesDeckManifest
import com.lost_cities.lan.model.LostCitiesMatchState
import com.lost_cities.lan.model.LostCitiesPlayerState
import com.lost_cities.lan.model.LostCitiesScoring
import com.lost_cities.lan.model.LostCitiesTurnPhase
import com.lost_cities.lan.model.MatchState
import com.lost_cities.lan.model.MatchStatus
import com.lost_cities.lan.model.PingResponse
import com.lost_cities.lan.model.PlayerSlot
import com.lost_cities.lan.model.PollResponse
import com.lost_cities.lan.model.SubmitTurnResponse
import java.util.LinkedHashMap
import java.util.Locale
import java.util.UUID

class HostGameManager(
    private val deckManifest: LostCitiesDeckManifest,
    hostName: String,
    private val serverId: String,
    initialRules: GameRules = GameRules(),
) {
    private data class PendingUndo(
        val player: Int,
        val matchBeforeAction: MatchState,
    )

    private enum class InviteStatusType {
        PENDING,
        ACCEPTED,
        DENIED,
    }

    private data class Invite(
        val id: String,
        val fromName: String,
        val targetServerId: String?,
        val createdAtEpochMs: Long,
        var status: InviteStatusType,
        val rules: GameRules,
    )

    private data class Session(
        val player: Int,
        val name: String,
        val token: String,
        var connected: Boolean,
        var lastSeenEpochMs: Long,
    )

    private val lock = Any()
    private val sessions = LinkedHashMap<String, Session>()
    private val invites = LinkedHashMap<String, Invite>()
    private val cardById = deckManifest.cards.associateBy { it.id }
    private val suitIds = deckManifest.suits.map { it.id.lowercase(Locale.ROOT) }

    private var lobbyHostName: String = sanitizePlayerName(hostName)
    private var rules: GameRules = sanitizeRules(initialRules)
    private var match: MatchState = buildWaitingMatch(now(), createMatchId())
    private var pendingUndo: PendingUndo? = null

    fun updateLobbyHostName(rawName: String) = synchronized(lock) {
        lobbyHostName = sanitizePlayerName(rawName)
        if (match.status != MatchStatus.ACTIVE) {
            pendingUndo = null
            match = buildWaitingMatch(now(), match.id).copy(
                lastEvent = if (sessions.isEmpty()) "Invite a peer to start." else "Waiting for an invited player to join.",
            )
        }
    }

    fun ping(): PingResponse = synchronized(lock) {
        PingResponse(
            serverId = serverId,
            hostName = lobbyHostName,
            matchStatus = match.status,
            openSlots = (2 - sessions.size).coerceAtLeast(0),
            rules = rules,
            players = match.players.values
                .sortedBy { it.player }
                .map { slot ->
                    com.lost_cities.lan.model.PlayerSummary(
                        player = slot.player,
                        name = slot.name,
                        connected = slot.connected,
                    )
                },
        )
    }

    fun snapshot(): MatchState = synchronized(lock) { match }

    fun joinOrReconnect(playerName: String): JoinResponse {
        val safeName = sanitizePlayerName(playerName)
        val nowMs = now()

        synchronized(lock) {
            sessions.values.firstOrNull { it.name == safeName }?.let { existing ->
                existing.connected = true
                existing.lastSeenEpochMs = nowMs
                match = when (match.status) {
                    MatchStatus.ACTIVE -> match.copy(
                        players = matchPlayers(sessions.values.toList()),
                        updatedAtEpochMs = nowMs,
                    )
                    MatchStatus.FINISHED,
                    MatchStatus.ABORTED,
                    MatchStatus.WAITING,
                    -> {
                        pendingUndo = null
                        buildWaitingMatch(nowMs, match.id)
                    }
                }
                return JoinResponse(
                    ok = true,
                    token = existing.token,
                    player = existing.player,
                    match = match,
                )
            }

            if (sessions.size >= 2) {
                return JoinResponse(
                    ok = false,
                    error = "Match is full. Try another peer.",
                )
            }

            val slot = if (sessions.none { it.value.player == 1 }) 1 else 2
            val token = UUID.randomUUID().toString()
            sessions[token] = Session(
                player = slot,
                name = safeName,
                token = token,
                connected = true,
                lastSeenEpochMs = nowMs,
            )

            match = if (sessions.size == 2) {
                pendingUndo = null
                startNewMatch(nowMs)
            } else {
                pendingUndo = null
                buildWaitingMatch(nowMs, match.id)
            }

            return JoinResponse(
                ok = true,
                token = token,
                player = slot,
                match = match,
            )
        }
    }

    fun configureGameRules(candidate: GameRules): GenericOkResponse = synchronized(lock) {
        if (match.status == MatchStatus.ACTIVE) {
            return GenericOkResponse(
                ok = false,
                error = "Finish the active round before changing rules.",
            )
        }

        rules = sanitizeRules(candidate)
        pendingUndo = null
        match = buildWaitingMatch(now(), match.id).copy(
            lastEvent = if (sessions.isEmpty()) "Invite a peer to start." else "Waiting for an invited player to join.",
        )
        GenericOkResponse(ok = true)
    }

    fun heartbeat(token: String): GenericOkResponse = synchronized(lock) {
        val session = sessions[token] ?: return GenericOkResponse(ok = false, error = "Invalid token.")
        session.connected = true
        session.lastSeenEpochMs = now()
        if (match.status != MatchStatus.ACTIVE) {
            pendingUndo = null
            match = buildWaitingMatch(now(), match.id)
        }
        GenericOkResponse(ok = true)
    }

    fun removeToken(token: String): GenericOkResponse = synchronized(lock) {
        val removed = sessions.remove(token) ?: return GenericOkResponse(ok = false, error = "Unknown token.")
        val nowMs = now()

        if (sessions.isEmpty()) {
            invites.clear()
            pendingUndo = null
            match = buildWaitingMatch(nowMs, createMatchId())
            return GenericOkResponse(ok = true)
        }

        pendingUndo = null
        match = if (match.status == MatchStatus.ACTIVE) {
            match.copy(
                status = MatchStatus.ABORTED,
                players = matchPlayers(sessions.values.toList()),
                updatedAtEpochMs = nowMs,
                lastEvent = "Player ${removed.player} disconnected. Match aborted.",
            )
        } else {
            buildWaitingMatch(nowMs, match.id)
        }
        GenericOkResponse(ok = true)
    }

    fun createInvite(
        fromName: String,
        targetServerId: String?,
        inviteRules: GameRules?,
    ): InviteSendResponse = synchronized(lock) {
        pruneExpiredInvites()
        if (match.status == MatchStatus.ACTIVE) {
            return InviteSendResponse(
                ok = false,
                error = "Invites are only available before the round starts.",
            )
        }
        if (sessions.isEmpty()) {
            return InviteSendResponse(
                ok = false,
                error = "Claim your seat before inviting another player.",
            )
        }

        if (match.status != MatchStatus.WAITING) {
            pendingUndo = null
            match = buildWaitingMatch(now(), createMatchId())
        }

        val invite = Invite(
            id = "invite-${UUID.randomUUID()}",
            fromName = sanitizePlayerName(fromName),
            targetServerId = targetServerId?.trim()?.ifBlank { null },
            createdAtEpochMs = now(),
            status = InviteStatusType.PENDING,
            rules = sanitizeRules(inviteRules ?: rules),
        )

        invites[invite.id] = invite
        InviteSendResponse(ok = true, inviteId = invite.id)
    }

    fun listInvites(requestingServerId: String?): InviteListResponse = synchronized(lock) {
        pruneExpiredInvites()
        val filtered = invites.values
            .filter { invite ->
                invite.targetServerId == null || invite.targetServerId == requestingServerId
            }
            .sortedByDescending { it.createdAtEpochMs }
            .map { invite ->
                InviteListItem(
                    id = invite.id,
                    fromName = invite.fromName,
                    createdAtEpochMs = invite.createdAtEpochMs,
                    status = invite.status.name.lowercase(Locale.ROOT),
                    targetServerId = invite.targetServerId,
                    rules = invite.rules,
                )
            }
        InviteListResponse(ok = true, invites = filtered)
    }

    fun inviteStatus(inviteId: String): InviteStatusResponse = synchronized(lock) {
        pruneExpiredInvites()
        val invite = invites[inviteId]
            ?: return InviteStatusResponse(ok = false, error = "Invite not found.")

        InviteStatusResponse(
            ok = true,
            inviteId = invite.id,
            status = invite.status.name.lowercase(Locale.ROOT),
        )
    }

    fun respondToInvite(inviteId: String, action: String): GenericOkResponse = synchronized(lock) {
        pruneExpiredInvites()
        val invite = invites[inviteId]
            ?: return GenericOkResponse(ok = false, error = "Invite not found.")

        if (invite.status != InviteStatusType.PENDING) {
            return GenericOkResponse(ok = false, error = "Invite already handled.")
        }

        invite.status = when (action.trim().lowercase(Locale.ROOT)) {
            "accept", "accepted", "approve", "approved" -> InviteStatusType.ACCEPTED
            "deny", "denied", "reject", "rejected", "decline", "declined" -> InviteStatusType.DENIED
            else -> return GenericOkResponse(ok = false, error = "Unknown invite action '$action'.")
        }

        GenericOkResponse(ok = true)
    }

    fun poll(token: String): PollResponse = synchronized(lock) {
        val session = sessions[token] ?: return PollResponse(ok = false, error = "Unknown token.")
        session.connected = true
        session.lastSeenEpochMs = now()

        PollResponse(
            ok = true,
            canAct = match.status == MatchStatus.ACTIVE && match.lostCities.turnPlayer == session.player,
            match = match,
        )
    }

    fun applyLostCitiesAction(
        token: String,
        action: String,
        cardId: String,
        suit: String? = null,
    ): SubmitTurnResponse = synchronized(lock) {
        val session = sessions[token] ?: return SubmitTurnResponse(ok = false, error = "Invalid token.")
        val normalizedAction = action.trim().lowercase(Locale.ROOT)
        return when (normalizedAction) {
            "request_rematch", "rematch_request", "rematch" -> requestRematch(session.player)
            "accept_rematch", "rematch_accept" -> acceptRematch(session.player)
            "deny_rematch", "decline_rematch", "rematch_deny" -> denyRematch(session.player)
            else -> {
                if (match.status != MatchStatus.ACTIVE) {
                    return SubmitTurnResponse(ok = false, error = "Match is not active.")
                }
                if (match.lostCities.turnPlayer != session.player) {
                    return SubmitTurnResponse(ok = false, error = "Not your turn.")
                }
                when (normalizedAction) {
                    "play_expedition", "play", "play_card" -> playCard(session.player, cardId)
                    "discard", "discard_to_discard" -> discardCard(session.player, cardId)
                    "draw_deck", "draw_from_deck", "draw_pile", "draw_from_draw_pile" -> drawFromDeck(session.player)
                    "draw_discard", "draw_from_discard", "draw_discard_pile" -> drawFromDiscard(session.player, suit)
                    "undo", "undo_turn", "undo_last_move" -> undoLastPendingMove(session.player)
                    else -> SubmitTurnResponse(ok = false, error = "Unknown action '$action'.")
                }
            }
        }
    }

    private fun playCard(player: Int, cardId: String): SubmitTurnResponse {
        if (match.lostCities.phase != LostCitiesTurnPhase.PLAY) {
            return SubmitTurnResponse(ok = false, error = "Draw a card to finish the turn first.")
        }

        val card = cardById[cardId] ?: return SubmitTurnResponse(ok = false, error = "Unknown card '$cardId'.")
        val playerState = match.lostCities.players[player]
            ?: return SubmitTurnResponse(ok = false, error = "Player hand not found.")
        if (!playerState.hand.contains(cardId)) {
            return SubmitTurnResponse(ok = false, error = "Card '$cardId' is not in hand.")
        }

        val expedition = playerState.expeditions[card.suit].orEmpty()
        validateExpeditionPlay(card, expedition)?.let { reason ->
            return SubmitTurnResponse(ok = false, error = reason)
        }

        val nextHand = playerState.hand.toMutableList().apply { remove(cardId) }
        val nextExpeditions = playerState.expeditions.toMutableMap().apply {
            put(card.suit, expedition + cardId)
        }

        val nextPlayers = match.lostCities.players.toMutableMap().apply {
            put(
                player,
                playerState.copy(
                    hand = nextHand,
                    expeditions = nextExpeditions,
                ),
            )
        }

        if (match.lostCities.finalTurnsRemaining > 0) {
            return advanceFinalTurn(
                player = player,
                updatedPlayers = nextPlayers,
                updatedDiscards = match.lostCities.discardPiles,
                actionSummary = "Player $player played ${card.id} to ${card.suit}.",
            )
        }

        val updatedLost = match.lostCities.copy(
            players = nextPlayers,
            phase = LostCitiesTurnPhase.DRAW,
            justDiscardedCardId = null,
        )
        val updatedMatch = match.copy(
            lostCities = updatedLost,
            score = computeScores(updatedLost),
            updatedAtEpochMs = now(),
            lastEvent = "Player $player played ${card.id} to ${card.suit}.",
        )
        pendingUndo = PendingUndo(player = player, matchBeforeAction = match)
        match = updatedMatch
        return SubmitTurnResponse(ok = true, match = updatedMatch)
    }

    private fun discardCard(player: Int, cardId: String): SubmitTurnResponse {
        if (match.lostCities.phase != LostCitiesTurnPhase.PLAY) {
            return SubmitTurnResponse(ok = false, error = "Draw a card to finish the turn first.")
        }

        val card = cardById[cardId] ?: return SubmitTurnResponse(ok = false, error = "Unknown card '$cardId'.")
        val playerState = match.lostCities.players[player]
            ?: return SubmitTurnResponse(ok = false, error = "Player hand not found.")
        if (!playerState.hand.contains(cardId)) {
            return SubmitTurnResponse(ok = false, error = "Card '$cardId' is not in hand.")
        }

        val nextHand = playerState.hand.toMutableList().apply { remove(cardId) }
        val nextDiscards = match.lostCities.discardPiles.toMutableMap().apply {
            put(card.suit, get(card.suit).orEmpty() + cardId)
        }
        val nextPlayers = match.lostCities.players.toMutableMap().apply {
            put(player, playerState.copy(hand = nextHand))
        }

        if (match.lostCities.finalTurnsRemaining > 0) {
            return advanceFinalTurn(
                player = player,
                updatedPlayers = nextPlayers,
                updatedDiscards = nextDiscards,
                actionSummary = "Player $player discarded ${card.id}.",
            )
        }

        val updatedLost = match.lostCities.copy(
            players = nextPlayers,
            discardPiles = nextDiscards,
            phase = LostCitiesTurnPhase.DRAW,
            justDiscardedCardId = cardId,
        )
        val updatedMatch = match.copy(
            lostCities = updatedLost,
            score = computeScores(updatedLost),
            updatedAtEpochMs = now(),
            lastEvent = "Player $player discarded ${card.id}.",
        )
        pendingUndo = PendingUndo(player = player, matchBeforeAction = match)
        match = updatedMatch
        return SubmitTurnResponse(ok = true, match = updatedMatch)
    }

    private fun drawFromDeck(player: Int): SubmitTurnResponse {
        if (match.lostCities.phase != LostCitiesTurnPhase.DRAW) {
            return SubmitTurnResponse(ok = false, error = "Play or discard a card first.")
        }

        val nextDeck = match.lostCities.deck.toMutableList()
        if (nextDeck.isEmpty()) {
            return SubmitTurnResponse(ok = false, error = "Draw pile is empty.")
        }

        val drawnCard = nextDeck.removeAt(0)
        val playerState = match.lostCities.players[player]
            ?: return SubmitTurnResponse(ok = false, error = "Player hand not found.")
        val nextPlayers = match.lostCities.players.toMutableMap().apply {
            put(player, playerState.copy(hand = playerState.hand + drawnCard))
        }

        val lastCardDrawn = nextDeck.isEmpty()
        val nextPlayer = otherPlayer(player)
        val updatedLost = match.lostCities.copy(
            players = nextPlayers,
            deck = nextDeck,
            phase = LostCitiesTurnPhase.PLAY,
            turnPlayer = nextPlayer,
            justDiscardedCardId = null,
            finalTurnsRemaining = if (lastCardDrawn) 2 else 0,
        )
        val updatedMatch = match.copy(
            status = MatchStatus.ACTIVE,
            lostCities = updatedLost,
            score = computeScores(updatedLost),
            updatedAtEpochMs = now(),
            lastEvent = if (lastCardDrawn) {
                "Player $player drew the final deck card. Final turns begin: Player $nextPlayer plays one last card, no draw."
            } else {
                "Player $player drew one card from the draw pile."
            },
        )
        pendingUndo = null
        match = updatedMatch
        return SubmitTurnResponse(ok = true, match = updatedMatch)
    }

    private fun drawFromDiscard(player: Int, suit: String?): SubmitTurnResponse {
        if (match.lostCities.phase != LostCitiesTurnPhase.DRAW) {
            return SubmitTurnResponse(ok = false, error = "Play or discard a card first.")
        }

        val targetSuit = suit?.trim()?.lowercase(Locale.ROOT)?.ifBlank { null }
            ?: return SubmitTurnResponse(ok = false, error = "Suit is required.")
        val pile = match.lostCities.discardPiles[targetSuit].orEmpty()
        if (pile.isEmpty()) {
            return SubmitTurnResponse(ok = false, error = "Discard pile '$targetSuit' is empty.")
        }
        val topCardId = pile.last()
        if (topCardId == match.lostCities.justDiscardedCardId) {
            return SubmitTurnResponse(
                ok = false,
                error = "You cannot take back the same card you just discarded.",
            )
        }

        val remainingPile = pile.dropLast(1)
        val nextDiscards = match.lostCities.discardPiles.toMutableMap().apply {
            if (remainingPile.isEmpty()) {
                remove(targetSuit)
            } else {
                put(targetSuit, remainingPile)
            }
        }

        val playerState = match.lostCities.players[player]
            ?: return SubmitTurnResponse(ok = false, error = "Player hand not found.")
        val nextPlayers = match.lostCities.players.toMutableMap().apply {
            put(player, playerState.copy(hand = playerState.hand + topCardId))
        }

        val updatedLost = match.lostCities.copy(
            players = nextPlayers,
            discardPiles = nextDiscards,
            phase = LostCitiesTurnPhase.PLAY,
            turnPlayer = otherPlayer(player),
            justDiscardedCardId = null,
        )
        val updatedMatch = match.copy(
            lostCities = updatedLost,
            score = computeScores(updatedLost),
            updatedAtEpochMs = now(),
            lastEvent = "Player $player drew $topCardId from $targetSuit discard.",
        )
        pendingUndo = null
        match = updatedMatch
        return SubmitTurnResponse(ok = true, match = updatedMatch)
    }

    private fun validateExpeditionPlay(
        card: LostCitiesDeckCard,
        existingColumn: List<String>,
    ): String? {
        val existingRanks = existingColumn.mapNotNull { existingId ->
            cardById[existingId]?.rank
        }

        if (card.rank == null) {
            return if (existingRanks.isEmpty()) null else "Wagers must be played before number cards in ${card.suit}."
        }

        val currentMax = existingRanks.maxOrNull()
        return if (currentMax != null && card.rank <= currentMax) {
            "Card value ${card.rank} must be higher than $currentMax in ${card.suit}."
        } else {
            null
        }
    }

    private fun startNewMatch(nowMs: Long): MatchState {
        val shuffledDeck = deckForRules(rules)
        val players = matchPlayers(sessions.values.toList())
        val playerStates = players.keys
            .sorted()
            .associateWith { drawOpeningHand(shuffledDeck) }
            .mapValues { (_, hand) -> LostCitiesPlayerState(hand = hand) }

        return MatchState(
            id = createMatchId(),
            status = MatchStatus.ACTIVE,
            players = players,
            score = mapOf(1 to 0, 2 to 0),
            rules = rules,
            lostCities = LostCitiesMatchState(
                turnPlayer = 1,
                phase = LostCitiesTurnPhase.PLAY,
                deck = shuffledDeck,
                discardPiles = emptyMap(),
                players = playerStates,
                justDiscardedCardId = null,
                finalTurnsRemaining = 0,
            ),
            createdAtEpochMs = nowMs,
            updatedAtEpochMs = nowMs,
            lastEvent = "Round started. Player 1 plays first.",
        )
    }

    private fun buildWaitingMatch(nowMs: Long, matchId: String): MatchState {
        val players = matchPlayers(sessions.values.toList())
        val playerStates = players.keys.associateWith { LostCitiesPlayerState() }
        val lastEvent = when (players.size) {
            0 -> "Invite a peer to start."
            1 -> "Waiting for an invited player to join."
            else -> "Ready to start."
        }

        return MatchState(
            id = matchId,
            status = MatchStatus.WAITING,
            players = players,
            score = mapOf(1 to 0, 2 to 0),
            rules = rules,
            lostCities = LostCitiesMatchState(
                turnPlayer = 1,
                phase = LostCitiesTurnPhase.PLAY,
                deck = emptyList(),
                discardPiles = emptyMap(),
                players = playerStates,
                justDiscardedCardId = null,
                finalTurnsRemaining = 0,
            ),
            createdAtEpochMs = nowMs,
            updatedAtEpochMs = nowMs,
            lastEvent = lastEvent,
        )
    }

    private fun matchPlayers(sessionList: List<Session>): Map<Int, PlayerSlot> {
        val nowMs = now()
        return sessionList
            .sortedBy { it.player }
            .associate { session ->
                session.player to PlayerSlot(
                    player = session.player,
                    name = session.name,
                    token = session.token,
                    connected = session.connected,
                    lastSeenEpochMs = nowMs,
                )
            }
    }

    private fun deckForRules(currentRules: GameRules): MutableList<String> {
        val allowedCards = if (currentRules.usePurple) {
            deckManifest.cards
        } else {
            deckManifest.cards.filter { card ->
                card.suit.lowercase(Locale.ROOT) != "purple"
            }
        }
        return allowedCards.map { it.id }.shuffled().toMutableList()
    }

    private fun drawOpeningHand(deck: MutableList<String>): List<String> {
        val hand = deck.take(8)
        repeat(hand.size) {
            deck.removeAt(0)
        }
        return hand
    }

    private fun computeScores(state: LostCitiesMatchState): Map<Int, Int> {
        val activeSuits = if (rules.usePurple) {
            suitIds
        } else {
            suitIds.filterNot { it == "purple" }
        }

        return state.players.entries.associate { (player, playerState) ->
            val total = activeSuits.sumOf { suit ->
                LostCitiesScoring.expeditionBreakdown(
                    cardIds = playerState.expeditions[suit].orEmpty(),
                    cardById = cardById,
                ).total
            }
            player to total
        }
    }

    private fun advanceFinalTurn(
        player: Int,
        updatedPlayers: Map<Int, LostCitiesPlayerState>,
        updatedDiscards: Map<String, List<String>>,
        actionSummary: String,
    ): SubmitTurnResponse {
        val remainingTurns = (match.lostCities.finalTurnsRemaining - 1).coerceAtLeast(0)
        val nextPlayer = otherPlayer(player)
        val updatedLost = match.lostCities.copy(
            players = updatedPlayers,
            discardPiles = updatedDiscards,
            phase = LostCitiesTurnPhase.PLAY,
            turnPlayer = nextPlayer,
            justDiscardedCardId = null,
            finalTurnsRemaining = remainingTurns,
        )
        val updatedMatch = match.copy(
            status = if (remainingTurns == 0) MatchStatus.FINISHED else MatchStatus.ACTIVE,
            lostCities = updatedLost,
            score = computeScores(updatedLost),
            updatedAtEpochMs = now(),
            lastEvent = if (remainingTurns == 0) {
                "$actionSummary Final turn complete. Round over."
            } else {
                "$actionSummary Final turn for Player $nextPlayer: play one last card, no draw."
            },
        )
        pendingUndo = null
        match = updatedMatch
        return SubmitTurnResponse(ok = true, match = updatedMatch)
    }

    private fun undoLastPendingMove(player: Int): SubmitTurnResponse {
        val undo = pendingUndo ?: return SubmitTurnResponse(ok = false, error = "Nothing to undo.")
        if (undo.player != player) {
            return SubmitTurnResponse(ok = false, error = "Only the active player can undo this move.")
        }
        if (match.status != MatchStatus.ACTIVE || match.lostCities.phase != LostCitiesTurnPhase.DRAW) {
            return SubmitTurnResponse(ok = false, error = "Undo is only available before drawing.")
        }
        if (match.lostCities.turnPlayer != player) {
            return SubmitTurnResponse(ok = false, error = "It is not your turn.")
        }

        val restoredMatch = undo.matchBeforeAction.copy(
            updatedAtEpochMs = now(),
            lastEvent = "Player $player undid the pending move.",
        )
        pendingUndo = null
        match = restoredMatch
        return SubmitTurnResponse(ok = true, match = restoredMatch)
    }

    private fun requestRematch(player: Int): SubmitTurnResponse {
        if (match.status != MatchStatus.FINISHED && match.status != MatchStatus.ABORTED) {
            return SubmitTurnResponse(ok = false, error = "Rematch is only available after the round ends.")
        }

        val offeredBy = match.rematchOfferedByPlayer
        return when {
            offeredBy == null -> {
                val updatedMatch = match.copy(
                    updatedAtEpochMs = now(),
                    lastEvent = "Player $player requested a rematch.",
                    rematchOfferedByPlayer = player,
                )
                match = updatedMatch
                SubmitTurnResponse(ok = true, match = updatedMatch)
            }

            offeredBy == player -> SubmitTurnResponse(ok = false, error = "Rematch already requested.")
            else -> startAcceptedRematch()
        }
    }

    private fun acceptRematch(player: Int): SubmitTurnResponse {
        if (match.status != MatchStatus.FINISHED && match.status != MatchStatus.ABORTED) {
            return SubmitTurnResponse(ok = false, error = "Rematch is only available after the round ends.")
        }
        val offeredBy = match.rematchOfferedByPlayer
            ?: return SubmitTurnResponse(ok = false, error = "No rematch offer is pending.")
        if (offeredBy == player) {
            return SubmitTurnResponse(ok = false, error = "Waiting for the opponent to respond.")
        }
        return startAcceptedRematch()
    }

    private fun denyRematch(player: Int): SubmitTurnResponse {
        if (match.status != MatchStatus.FINISHED && match.status != MatchStatus.ABORTED) {
            return SubmitTurnResponse(ok = false, error = "Rematch is only available after the round ends.")
        }
        val offeredBy = match.rematchOfferedByPlayer
            ?: return SubmitTurnResponse(ok = false, error = "No rematch offer is pending.")
        if (offeredBy == player) {
            return SubmitTurnResponse(ok = false, error = "You cannot deny your own rematch request.")
        }
        val updatedMatch = match.copy(
            updatedAtEpochMs = now(),
            lastEvent = "Player $player declined the rematch.",
            rematchOfferedByPlayer = null,
        )
        match = updatedMatch
        return SubmitTurnResponse(ok = true, match = updatedMatch)
    }

    private fun startAcceptedRematch(): SubmitTurnResponse {
        pendingUndo = null
        val restarted = startNewMatch(now()).copy(
            lastEvent = "Rematch accepted. New round started.",
            rematchOfferedByPlayer = null,
        )
        match = restarted
        return SubmitTurnResponse(ok = true, match = restarted)
    }

    private fun sanitizeRules(raw: GameRules): GameRules {
        return raw.copy(usePurple = raw.usePurple)
    }

    private fun sanitizePlayerName(raw: String): String {
        return NameGenerator.ensureNumericSuffix(raw).take(28)
    }

    private fun pruneExpiredInvites() {
        val cutoff = now() - INVITE_TTL_MS
        invites.entries.removeIf { (_, invite) -> invite.createdAtEpochMs < cutoff }
    }

    private fun createMatchId(): String = "lost-cities-${UUID.randomUUID()}"

    private fun otherPlayer(player: Int): Int = if (player == 1) 2 else 1

    private fun now(): Long = System.currentTimeMillis()

    companion object {
        private const val INVITE_TTL_MS = 2 * 60 * 60 * 1000L
    }
}
