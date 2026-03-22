package com.lost_cities.lan.ai

import com.lost_cities.lan.model.GameRules
import com.lost_cities.lan.model.LostCitiesDeckCard
import com.lost_cities.lan.model.LostCitiesMatchState
import com.lost_cities.lan.model.LostCitiesPlayerState
import com.lost_cities.lan.model.LostCitiesTurnPhase
import com.lost_cities.lan.model.MatchState
import com.lost_cities.lan.model.MatchStatus
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

object LostCitiesSoloAi {
    data class Step(
        val action: String,
        val cardId: String = "",
        val suit: String? = null,
    )

    data class TurnPlan(
        val steps: List<Step>,
        val score: Double,
    )

    private const val ME = 0
    private const val OPP = 1
    private const val WAGER = 0
    private const val TOP_NOW_WEIGHT = 0.95
    private const val TOP_LATER_WEIGHT = 0.22
    private const val UNSEEN_SELF_SCALE = 0.72
    private const val UNSEEN_OPP_SCALE = 0.80
    private const val START_RISK = 5.0
    private const val WAGER_COMMIT_RISK = 3.5
    private const val BONUS_START = 6.0
    private const val BONUS_FULL = 8.0
    private const val DRAW_DECK = "deck"

    private data class CardKey(
        val suit: String,
        val rank: Int,
    )

    private data class VisibleCard(
        val cardId: String,
        val suit: String,
        val rank: Int,
    )

    private data class Expedition(
        val wagers: Int = 0,
        val numbers: List<Int> = emptyList(),
    ) {
        val started: Boolean
            get() = wagers > 0 || numbers.isNotEmpty()

        val last: Int
            get() = numbers.lastOrNull() ?: 0

        val sumNumbers: Int
            get() = numbers.sum()

        val count: Int
            get() = wagers + numbers.size
    }

    private data class VisibleState(
        val myHand: MutableList<VisibleCard>,
        val myExpeditions: MutableMap<String, Expedition>,
        val oppExpeditions: Map<String, Expedition>,
        val discards: MutableMap<String, MutableList<VisibleCard>>,
        val deckSize: Int,
        val oppHandSize: Int,
        val nextPlayer: Int,
        val activeSuits: List<String>,
        val fullDeckCounts: Map<CardKey, Int>,
        val drawAllowed: Boolean,
    ) {
        fun copyDeep(): VisibleState {
            return VisibleState(
                myHand = myHand.toMutableList(),
                myExpeditions = myExpeditions.toMutableMap(),
                oppExpeditions = oppExpeditions.toMap(),
                discards = discards.mapValues { (_, pile) -> pile.toMutableList() }.toMutableMap(),
                deckSize = deckSize,
                oppHandSize = oppHandSize,
                nextPlayer = nextPlayer,
                activeSuits = activeSuits.toList(),
                fullDeckCounts = fullDeckCounts.toMap(),
                drawAllowed = drawAllowed,
            )
        }
    }

    private data class Move(
        val action: String,
        val handIndex: Int,
        val drawSource: String?,
    )

    fun chooseTurn(
        match: MatchState,
        aiPlayer: Int,
        cardById: Map<String, LostCitiesDeckCard>,
        activeSuits: List<String>,
        rules: GameRules,
    ): TurnPlan? {
        if (match.status != MatchStatus.ACTIVE || match.lostCities.turnPlayer != aiPlayer) {
            return null
        }

        val normalizedSuits = if (rules.usePurple) {
            activeSuits
        } else {
            activeSuits.filterNot { it == "purple" }
        }.ifEmpty {
            cardById.values.map { it.suit }.distinct().sorted()
        }

        val visibleState = visibleStateFromMatch(
            lost = match.lostCities,
            aiPlayer = aiPlayer,
            activeSuits = normalizedSuits,
            cardById = cardById,
        ) ?: return null

        return when (match.lostCities.phase) {
            LostCitiesTurnPhase.PLAY -> choosePlayTurn(visibleState)
            LostCitiesTurnPhase.DRAW -> chooseDrawTurn(visibleState, match.lostCities.justDiscardedCardId)
        }
    }

    private fun choosePlayTurn(state: VisibleState): TurnPlan? {
        val scoredMoves = legalMoves(state).map { move ->
            move to moveEv(state, move)
        }.sortedByDescending { (_, score) -> score }
        val (bestMove, bestScore) = scoredMoves.firstOrNull() ?: return null
        val selectedCard = state.myHand.getOrNull(bestMove.handIndex) ?: return null
        val steps = mutableListOf<Step>()
        steps += when (bestMove.action) {
            "play" -> Step(action = "play_expedition", cardId = selectedCard.cardId)
            "discard" -> Step(action = "discard", cardId = selectedCard.cardId)
            else -> return null
        }
        when (bestMove.drawSource) {
            null -> Unit
            DRAW_DECK -> steps += Step(action = "draw_deck")
            else -> steps += Step(action = "draw_discard", suit = bestMove.drawSource)
        }
        return TurnPlan(steps = steps, score = bestScore)
    }

    private fun chooseDrawTurn(
        state: VisibleState,
        justDiscardedCardId: String?,
    ): TurnPlan? {
        val options = mutableListOf<Pair<Step, Double>>()
        if (state.deckSize > 0) {
            options += Step(action = "draw_deck") to deckDrawExpectation(state)
        }
        state.activeSuits.forEach { suit ->
            val pile = state.discards[suit].orEmpty()
            val topCard = pile.lastOrNull() ?: return@forEach
            if (topCard.cardId == justDiscardedCardId) {
                return@forEach
            }
            val resultingState = applyDrawOnly(
                afterAction = state,
                drawSource = suit,
                drawnCard = null,
            )
            options += Step(action = "draw_discard", suit = suit) to evaluateState(resultingState)
        }
        val (bestStep, bestValue) = options.maxByOrNull { (_, value) -> value } ?: return null
        return TurnPlan(steps = listOf(bestStep), score = bestValue)
    }

    private fun visibleStateFromMatch(
        lost: LostCitiesMatchState,
        aiPlayer: Int,
        activeSuits: List<String>,
        cardById: Map<String, LostCitiesDeckCard>,
    ): VisibleState? {
        val myState = lost.players[aiPlayer] ?: return null
        val opponentPlayer = lost.players.keys.firstOrNull { it != aiPlayer } ?: if (aiPlayer == 1) 2 else 1
        val oppState = lost.players[opponentPlayer] ?: LostCitiesPlayerState()
        val fullDeckCounts = fullDeckCounter(activeSuits, cardById)
        val myHand = lookupVisibleCards(myState.hand, cardById)?.toMutableList() ?: return null
        val myExpeditions = buildExpeditionMap(myState, activeSuits, cardById)?.toMutableMap() ?: return null
        val oppExpeditions = buildExpeditionMap(oppState, activeSuits, cardById) ?: return null
        val discards = linkedMapOf<String, MutableList<VisibleCard>>()
        activeSuits.forEach { suit ->
            val pile = lookupVisibleCards(lost.discardPiles[suit].orEmpty(), cardById) ?: return null
            discards[suit] = pile.toMutableList()
        }

        return VisibleState(
            myHand = myHand,
            myExpeditions = myExpeditions,
            oppExpeditions = oppExpeditions,
            discards = discards,
            deckSize = lost.deck.size,
            oppHandSize = oppState.hand.size,
            nextPlayer = ME,
            activeSuits = activeSuits,
            fullDeckCounts = fullDeckCounts,
            drawAllowed = lost.phase == LostCitiesTurnPhase.PLAY && lost.finalTurnsRemaining <= 0,
        )
    }

    private fun lookupVisibleCards(
        cardIds: List<String>,
        cardById: Map<String, LostCitiesDeckCard>,
    ): List<VisibleCard>? {
        val cards = ArrayList<VisibleCard>(cardIds.size)
        for (cardId in cardIds) {
            val card = cardById[cardId] ?: return null
            cards += VisibleCard(
                cardId = card.id,
                suit = card.suit,
                rank = card.rank ?: WAGER,
            )
        }
        return cards
    }

    private fun buildExpeditionMap(
        playerState: LostCitiesPlayerState,
        activeSuits: List<String>,
        cardById: Map<String, LostCitiesDeckCard>,
    ): Map<String, Expedition>? {
        val result = linkedMapOf<String, Expedition>()
        for (suit in activeSuits) {
            val cards = lookupVisibleCards(playerState.expeditions[suit].orEmpty(), cardById) ?: return null
            val wagers = cards.count { it.rank == WAGER }
            val numbers = cards.mapNotNull { card -> card.rank.takeIf { it != WAGER } }
            result[suit] = Expedition(wagers = wagers, numbers = numbers)
        }
        return result
    }

    private fun fullDeckCounter(
        activeSuits: List<String>,
        cardById: Map<String, LostCitiesDeckCard>,
    ): Map<CardKey, Int> {
        val counts = linkedMapOf<CardKey, Int>()
        cardById.values.asSequence()
            .filter { it.suit in activeSuits }
            .forEach { card ->
                val key = CardKey(card.suit, card.rank ?: WAGER)
                counts[key] = (counts[key] ?: 0) + 1
            }
        return counts
    }

    private fun scoreExpedition(expedition: Expedition): Int {
        if (!expedition.started) {
            return 0
        }
        var score = (expedition.sumNumbers - 20) * (1 + expedition.wagers)
        if (expedition.count >= 8) {
            score += 20
        }
        return score
    }

    private fun legalPlay(card: VisibleCard, expedition: Expedition): Boolean {
        if (card.rank == WAGER) {
            return expedition.numbers.isEmpty()
        }
        return card.rank > expedition.last
    }

    private fun visibleCounter(state: VisibleState): Map<CardKey, Int> {
        val seen = linkedMapOf<CardKey, Int>()

        fun increment(key: CardKey, delta: Int = 1) {
            seen[key] = (seen[key] ?: 0) + delta
        }

        state.myHand.forEach { card ->
            increment(CardKey(card.suit, card.rank))
        }

        listOf(state.myExpeditions, state.oppExpeditions).forEach { expeditionMap ->
            expeditionMap.forEach { (suit, expedition) ->
                if (expedition.wagers > 0) {
                    increment(CardKey(suit, WAGER), expedition.wagers)
                }
                expedition.numbers.forEach { rank ->
                    increment(CardKey(suit, rank))
                }
            }
        }

        state.discards.forEach { (_, pile) ->
            pile.forEach { card ->
                increment(CardKey(card.suit, card.rank))
            }
        }

        return seen
    }

    private fun unseenCounter(state: VisibleState): Map<CardKey, Int> {
        val unseen = state.fullDeckCounts.toMutableMap()
        visibleCounter(state).forEach { (card, count) ->
            val remaining = (unseen[card] ?: 0) - count
            if (remaining > 0) {
                unseen[card] = remaining
            } else {
                unseen.remove(card)
            }
        }
        return unseen
    }

    private fun topDiscard(state: VisibleState, suit: String): VisibleCard? {
        return state.discards[suit]?.lastOrNull()
    }

    private fun futureDrawsForPlayer(state: VisibleState, player: Int): Int {
        return if (state.nextPlayer == player) {
            ceil(state.deckSize / 2.0).toInt()
        } else {
            state.deckSize / 2
        }
    }

    private fun unseenAccessProb(
        state: VisibleState,
        player: Int,
        unseen: Map<CardKey, Int>,
    ): Double {
        val totalUnknown = unseen.values.sum()
        if (totalUnknown <= 0) {
            return 0.0
        }
        val futureDraws = futureDrawsForPlayer(state, player)
        return if (player == ME) {
            min(1.0, UNSEEN_SELF_SCALE * futureDraws / totalUnknown.toDouble())
        } else {
            min(0.90, UNSEEN_OPP_SCALE * (state.oppHandSize + futureDraws) / totalUnknown.toDouble())
        }
    }

    private fun myHandAccessWeight(state: VisibleState): Double {
        val futureDraws = futureDrawsForPlayer(state, ME)
        if (futureDraws <= 0) {
            return 0.0
        }
        return 0.45 + 0.50 * min(1.0, futureDraws / 10.0)
    }

    private fun bonusProb(expectedCount: Double): Double {
        if (expectedCount <= BONUS_START) {
            return 0.0
        }
        if (expectedCount >= BONUS_FULL) {
            return 1.0
        }
        return (expectedCount - BONUS_START) / (BONUS_FULL - BONUS_START)
    }

    private fun extraVisibleWagers(
        state: VisibleState,
        player: Int,
        suit: String,
        expedition: Expedition,
    ): Int {
        if (expedition.numbers.isNotEmpty()) {
            return 0
        }

        var count = 0
        if (player == ME) {
            count += state.myHand.count { card ->
                card.suit == suit && card.rank == WAGER
            }
        }

        val topCard = topDiscard(state, suit)
        if (topCard?.rank == WAGER && state.nextPlayer == player) {
            count += 1
        }

        return max(0, min(3 - expedition.wagers, count))
    }

    private fun expectedPlayableNumbers(
        state: VisibleState,
        player: Int,
        suit: String,
        expedition: Expedition,
        unseen: Map<CardKey, Int>,
    ): List<Pair<Int, Double>> {
        val result = mutableListOf<Pair<Int, Double>>()
        val unseenProbability = unseenAccessProb(state, player, unseen)
        val playerMovesNext = state.nextPlayer == player
        val topCard = topDiscard(state, suit)
        val myHandCounter = if (player == ME) {
            state.myHand.groupingBy { CardKey(it.suit, it.rank) }.eachCount()
        } else {
            emptyMap()
        }

        for (rank in max(2, expedition.last + 1)..10) {
            val key = CardKey(suit, rank)
            val weight = when {
                player == ME && (myHandCounter[key] ?: 0) > 0 -> myHandAccessWeight(state)
                topCard?.suit == suit && topCard.rank == rank -> {
                    if (playerMovesNext) TOP_NOW_WEIGHT else TOP_LATER_WEIGHT
                }
                (unseen[key] ?: 0) > 0 -> unseenProbability
                else -> 0.0
            }
            if (weight > 0.0) {
                result += rank to min(1.0, weight)
            }
        }

        return result
    }

    private fun expeditionEv(
        state: VisibleState,
        player: Int,
        suit: String,
        unseen: Map<CardKey, Int>,
    ): Double {
        val expedition = if (player == ME) {
            state.myExpeditions[suit] ?: Expedition()
        } else {
            state.oppExpeditions[suit] ?: Expedition()
        }
        val playable = expectedPlayableNumbers(state, player, suit, expedition, unseen)
        val expectedAddSum = playable.sumOf { (rank, weight) -> rank * weight }
        val expectedAddCount = playable.sumOf { (_, weight) -> weight }
        val currentWagers = expedition.wagers
        val extraWagers = extraVisibleWagers(state, player, suit, expedition)

        if (expedition.numbers.isNotEmpty()) {
            val expectedSum = expedition.sumNumbers + expectedAddSum
            val expectedCount = expedition.count + expectedAddCount
            return ((expectedSum - 20.0) * (1 + currentWagers)) + 20.0 * bonusProb(expectedCount)
        }

        var best = Double.NEGATIVE_INFINITY
        for (additionalWagers in 0..extraWagers) {
            val totalWagers = currentWagers + additionalWagers
            val expectedSum = expedition.sumNumbers + expectedAddSum
            val expectedCount = expedition.count + additionalWagers + expectedAddCount
            var ev = ((expectedSum - 20.0) * (1 + totalWagers)) + 20.0 * bonusProb(expectedCount)
            if (totalWagers > 0) {
                ev -= WAGER_COMMIT_RISK * totalWagers
            }
            if (!expedition.started) {
                ev -= START_RISK
                ev = max(0.0, ev)
            }
            best = max(best, ev)
        }
        return best
    }

    private fun evaluateState(state: VisibleState): Double {
        val unseen = unseenCounter(state)
        val myTotal = state.activeSuits.sumOf { suit ->
            expeditionEv(state, ME, suit, unseen)
        }
        val oppTotal = state.activeSuits.sumOf { suit ->
            expeditionEv(state, OPP, suit, unseen)
        }
        return myTotal - oppTotal
    }

    private fun applyActionOnly(
        state: VisibleState,
        move: Move,
    ): VisibleState {
        val nextState = state.copyDeep()
        val card = nextState.myHand.removeAt(move.handIndex)
        when (move.action) {
            "play" -> {
                val expedition = nextState.myExpeditions[card.suit] ?: Expedition()
                val updated = if (card.rank == WAGER) {
                    expedition.copy(wagers = expedition.wagers + 1)
                } else {
                    expedition.copy(numbers = expedition.numbers + card.rank)
                }
                nextState.myExpeditions[card.suit] = updated
            }
            "discard" -> {
                nextState.discards.getOrPut(card.suit) { mutableListOf() }.add(card)
            }
            else -> error("Unknown action ${move.action}")
        }
        return nextState
    }

    private fun applyDrawOnly(
        afterAction: VisibleState,
        drawSource: String?,
        drawnCard: VisibleCard?,
    ): VisibleState {
        val nextState = afterAction.copyDeep()
        when (drawSource) {
            null -> {
                return nextState.copy(nextPlayer = OPP)
            }
            DRAW_DECK -> {
                requireNotNull(drawnCard) { "deck draw requires drawnCard" }
                nextState.myHand += drawnCard
                return nextState.copy(
                    deckSize = max(0, nextState.deckSize - 1),
                    nextPlayer = OPP,
                )
            }
            else -> {
                val pile = nextState.discards[drawSource] ?: error("unknown discard pile $drawSource")
                require(pile.isNotEmpty()) { "cannot draw from empty discard pile" }
                nextState.myHand += pile.removeAt(pile.lastIndex)
                return nextState.copy(nextPlayer = OPP)
            }
        }
    }

    private fun legalMoves(state: VisibleState): List<Move> {
        require(state.nextPlayer == ME) { "legalMoves expects it to be the AI turn" }

        val discardSources = state.activeSuits.filter { suit ->
            state.discards[suit].orEmpty().isNotEmpty()
        }
        val finalPlayOnlyTurn = !state.drawAllowed || state.deckSize <= 0
        val moves = mutableListOf<Move>()

        state.myHand.forEachIndexed { index, card ->
            val drawSources = if (finalPlayOnlyTurn) {
                listOf<String?>(null)
            } else {
                buildList {
                    if (state.deckSize > 0) {
                        add(DRAW_DECK)
                    }
                    addAll(discardSources)
                }
            }

            if (legalPlay(card, state.myExpeditions[card.suit] ?: Expedition())) {
                drawSources.forEach { drawSource ->
                    moves += Move(action = "play", handIndex = index, drawSource = drawSource)
                }
            }

            if (finalPlayOnlyTurn) {
                moves += Move(action = "discard", handIndex = index, drawSource = null)
            } else {
                if (state.deckSize > 0) {
                    moves += Move(action = "discard", handIndex = index, drawSource = DRAW_DECK)
                }
                discardSources.forEach { suit ->
                    if (suit != card.suit) {
                        moves += Move(action = "discard", handIndex = index, drawSource = suit)
                    }
                }
            }
        }

        return moves
    }

    private fun deckDrawExpectation(afterAction: VisibleState): Double {
        val unseen = unseenCounter(afterAction)
        val totalUnknown = unseen.values.sum()
        if (totalUnknown <= 0 || afterAction.deckSize <= 0) {
            return evaluateState(afterAction.copy(nextPlayer = OPP))
        }

        var total = 0.0
        unseen.forEach { (key, count) ->
            val probability = count / totalUnknown.toDouble()
            val finalState = applyDrawOnly(
                afterAction = afterAction,
                drawSource = DRAW_DECK,
                drawnCard = VisibleCard(
                    cardId = "",
                    suit = key.suit,
                    rank = key.rank,
                ),
            )
            total += probability * evaluateState(finalState)
        }
        return total
    }

    private fun moveEv(
        state: VisibleState,
        move: Move,
    ): Double {
        val afterAction = applyActionOnly(state, move)
        return when (move.drawSource) {
            null -> evaluateState(afterAction.copy(nextPlayer = OPP))
            DRAW_DECK -> deckDrawExpectation(afterAction)
            else -> evaluateState(
                applyDrawOnly(
                    afterAction = afterAction,
                    drawSource = move.drawSource,
                    drawnCard = null,
                ),
            )
        }
    }
}
