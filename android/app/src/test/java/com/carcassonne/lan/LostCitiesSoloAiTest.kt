package com.carcassonne.lan

import com.carcassonne.lan.ai.LostCitiesSoloAi
import com.carcassonne.lan.model.GameRules
import com.carcassonne.lan.model.LostCitiesDeckCard
import com.carcassonne.lan.model.LostCitiesDeckManifest
import com.carcassonne.lan.model.LostCitiesMatchState
import com.carcassonne.lan.model.LostCitiesPlayerState
import com.carcassonne.lan.model.LostCitiesSuitConfig
import com.carcassonne.lan.model.LostCitiesTurnPhase
import com.carcassonne.lan.model.MatchState
import com.carcassonne.lan.model.MatchStatus
import com.carcassonne.lan.model.PlayerSlot
import com.carcassonne.lan.network.HostGameManager
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test

class LostCitiesSoloAiTest {
    private fun manifestWithSeventeenCards(): LostCitiesDeckManifest {
        val suits = listOf(
            LostCitiesSuitConfig(id = "yellow", officialColor = "Yellow"),
            LostCitiesSuitConfig(id = "blue", officialColor = "Blue"),
        )
        val yellowCards = (2..10).map { rank ->
            LostCitiesDeckCard(
                id = "yellow_$rank",
                suit = "yellow",
                type = "expedition",
                rank = rank,
                path = "cards/png/yellow_$rank.png",
            )
        }
        val blueCards = (2..9).map { rank ->
            LostCitiesDeckCard(
                id = "blue_$rank",
                suit = "blue",
                type = "expedition",
                rank = rank,
                path = "cards/png/blue_$rank.png",
            )
        }
        return LostCitiesDeckManifest(
            suits = suits,
            cards = yellowCards + blueCards,
        )
    }

    private fun fullTwoSuitManifest(): LostCitiesDeckManifest {
        val suits = listOf(
            LostCitiesSuitConfig(id = "yellow", officialColor = "Yellow"),
            LostCitiesSuitConfig(id = "blue", officialColor = "Blue"),
        )
        val cards = buildList {
            suits.forEach { suit ->
                repeat(3) { wagerIndex ->
                    add(
                        LostCitiesDeckCard(
                            id = "${suit.id}_wager_${wagerIndex + 1}",
                            suit = suit.id,
                            type = "wager",
                            rank = null,
                            path = "cards/png/${suit.id}_wager_${wagerIndex + 1}.png",
                        ),
                    )
                }
                addAll(
                    (2..10).map { rank ->
                        LostCitiesDeckCard(
                            id = "${suit.id}_$rank",
                            suit = suit.id,
                            type = "expedition",
                            rank = rank,
                            path = "cards/png/${suit.id}_$rank.png",
                        )
                    },
                )
            }
        }
        return LostCitiesDeckManifest(
            suits = suits,
            cards = cards,
        )
    }

    private fun activeMatch(
        hand1: List<String>,
        hand2: List<String> = emptyList(),
        expeditions1: Map<String, List<String>> = emptyMap(),
        expeditions2: Map<String, List<String>> = emptyMap(),
        discardPiles: Map<String, List<String>> = emptyMap(),
        deck: List<String>,
        turnPlayer: Int = 1,
        phase: LostCitiesTurnPhase = LostCitiesTurnPhase.PLAY,
        finalTurnsRemaining: Int = 0,
        justDiscardedCardId: String? = null,
    ): MatchState {
        return MatchState(
            id = "solo-test",
            status = MatchStatus.ACTIVE,
            players = mapOf(
                1 to PlayerSlot(player = 1, name = "AI", token = "ai-token", lastSeenEpochMs = 0L),
                2 to PlayerSlot(player = 2, name = "Human", token = "human-token", lastSeenEpochMs = 0L),
            ),
            lostCities = LostCitiesMatchState(
                turnPlayer = turnPlayer,
                phase = phase,
                deck = deck,
                discardPiles = discardPiles,
                players = mapOf(
                    1 to LostCitiesPlayerState(hand = hand1, expeditions = expeditions1),
                    2 to LostCitiesPlayerState(hand = hand2, expeditions = expeditions2),
                ),
                justDiscardedCardId = justDiscardedCardId,
                finalTurnsRemaining = finalTurnsRemaining,
            ),
            createdAtEpochMs = 0L,
            updatedAtEpochMs = 0L,
        )
    }

    @Test
    fun aiGeneratesLegalFullTurnFromOpeningState() {
        val manifest = manifestWithSeventeenCards()
        val manager = HostGameManager(
            deckManifest = manifest,
            hostName = "Stone1001",
            serverId = "server-a",
        )
        val aiJoin = manager.joinOrReconnect("Stone1001")
        val humanJoin = manager.joinOrReconnect("Human2002")

        assertTrue(aiJoin.ok)
        assertTrue(humanJoin.ok)

        val token = assertNotNull(aiJoin.token)
        val plan = assertNotNull(
            LostCitiesSoloAi.chooseTurn(
                match = manager.snapshot(),
                aiPlayer = 1,
                cardById = manifest.cards.associateBy { it.id },
                activeSuits = listOf("yellow", "blue"),
                rules = GameRules(),
            ),
        )

        assertTrue(plan.steps.isNotEmpty())
        plan.steps.forEach { step ->
            val result = manager.applyLostCitiesAction(
                token = token,
                action = step.action,
                cardId = step.cardId,
                suit = step.suit,
            )
            assertTrue(result.ok, result.error ?: "AI step ${step.action} should be legal.")
        }
    }

    @Test
    fun aiAvoidsOpeningUnsupportedLateExpedition() {
        val manifest = fullTwoSuitManifest()
        val match = activeMatch(
            hand1 = listOf("yellow_wager_1", "yellow_9", "blue_7"),
            expeditions2 = mapOf("blue" to listOf("blue_8")),
            deck = listOf("yellow_2", "blue_2", "yellow_3", "blue_3"),
        )

        val plan = assertNotNull(
            LostCitiesSoloAi.chooseTurn(
                match = match,
                aiPlayer = 1,
                cardById = manifest.cards.associateBy { it.id },
                activeSuits = listOf("yellow", "blue"),
                rules = GameRules(),
            ),
        )

        assertEquals("discard", plan.steps.first().action, "plan=${plan.steps}")
    }

    @Test
    fun aiPrefersDeckDrawOverPointlessLateDiscardPickup() {
        val manifest = fullTwoSuitManifest()
        val match = activeMatch(
            hand1 = listOf("yellow_5"),
            expeditions1 = mapOf("blue" to listOf("blue_8")),
            expeditions2 = mapOf("blue" to listOf("blue_9")),
            discardPiles = mapOf("blue" to listOf("blue_7")),
            deck = listOf("yellow_2", "yellow_3", "blue_2", "blue_3", "yellow_4"),
            phase = LostCitiesTurnPhase.DRAW,
        )

        val plan = assertNotNull(
            LostCitiesSoloAi.chooseTurn(
                match = match,
                aiPlayer = 1,
                cardById = manifest.cards.associateBy { it.id },
                activeSuits = listOf("yellow", "blue"),
                rules = GameRules(),
            ),
        )

        assertEquals("draw_deck", plan.steps.single().action)
    }

    @Test
    fun aiUsesLastPlayToImproveExistingExpedition() {
        val manifest = fullTwoSuitManifest()
        val match = activeMatch(
            hand1 = listOf("blue_6", "yellow_wager_1", "yellow_9"),
            expeditions1 = mapOf("blue" to listOf("blue_4", "blue_5")),
            deck = emptyList(),
            finalTurnsRemaining = 1,
        )

        val plan = assertNotNull(
            LostCitiesSoloAi.chooseTurn(
                match = match,
                aiPlayer = 1,
                cardById = manifest.cards.associateBy { it.id },
                activeSuits = listOf("yellow", "blue"),
                rules = GameRules(),
            ),
        )

        assertEquals("play_expedition", plan.steps.first().action, "plan=${plan.steps}")
        assertEquals("blue_6", plan.steps.first().cardId, "plan=${plan.steps}")
    }

    @Test
    fun aiTurnsExistingWagerColumnIntoARealExpedition() {
        val manifest = fullTwoSuitManifest()
        val match = activeMatch(
            hand1 = listOf("yellow_wager_2", "yellow_9", "blue_7"),
            expeditions1 = mapOf("yellow" to listOf("yellow_wager_1")),
            expeditions2 = mapOf("blue" to listOf("blue_8")),
            deck = listOf("yellow_2", "blue_2", "yellow_3", "blue_3"),
        )

        val plan = assertNotNull(
            LostCitiesSoloAi.chooseTurn(
                match = match,
                aiPlayer = 1,
                cardById = manifest.cards.associateBy { it.id },
                activeSuits = listOf("yellow", "blue"),
                rules = GameRules(),
            ),
        )

        assertEquals("play_expedition", plan.steps.first().action)
        assertEquals("yellow_9", plan.steps.first().cardId)
    }

    @Test
    fun aiKeepsPushingItsCommittedWagerColumnWhenThatEvaluatesBest() {
        val manifest = fullTwoSuitManifest()
        val match = activeMatch(
            hand1 = listOf("yellow_wager_2", "yellow_9", "blue_6"),
            expeditions1 = mapOf(
                "yellow" to listOf("yellow_wager_1"),
                "blue" to listOf("blue_4", "blue_5"),
            ),
            deck = listOf("yellow_2", "blue_2", "yellow_3", "blue_3"),
        )

        val plan = assertNotNull(
            LostCitiesSoloAi.chooseTurn(
                match = match,
                aiPlayer = 1,
                cardById = manifest.cards.associateBy { it.id },
                activeSuits = listOf("yellow", "blue"),
                rules = GameRules(),
            ),
        )

        assertEquals("play_expedition", plan.steps.first().action)
        assertEquals("yellow_9", plan.steps.first().cardId)
    }

    @Test
    fun aiAvoidsDiscardingBigPlayableCardToOpponent() {
        val manifest = fullTwoSuitManifest()
        val match = activeMatch(
            hand1 = listOf("blue_9", "yellow_2"),
            expeditions2 = mapOf("blue" to listOf("blue_wager_1", "blue_5")),
            deck = listOf("yellow_3", "blue_2", "yellow_4", "blue_3"),
        )

        val plan = assertNotNull(
            LostCitiesSoloAi.chooseTurn(
                match = match,
                aiPlayer = 1,
                cardById = manifest.cards.associateBy { it.id },
                activeSuits = listOf("yellow", "blue"),
                rules = GameRules(),
            ),
        )

        assertEquals("discard", plan.steps.first().action)
        assertEquals("yellow_2", plan.steps.first().cardId)
    }

    @Test
    fun aiPrefersSafeDiscardOverDangerousHigherCardInSameSuit() {
        val manifest = fullTwoSuitManifest()
        val match = activeMatch(
            hand1 = listOf("blue_7", "blue_9"),
            expeditions2 = mapOf("blue" to listOf("blue_8")),
            deck = listOf("yellow_2", "blue_2", "yellow_3", "blue_3"),
        )

        val plan = assertNotNull(
            LostCitiesSoloAi.chooseTurn(
                match = match,
                aiPlayer = 1,
                cardById = manifest.cards.associateBy { it.id },
                activeSuits = listOf("yellow", "blue"),
                rules = GameRules(),
            ),
        )

        assertEquals("discard", plan.steps.first().action)
        assertEquals("blue_7", plan.steps.first().cardId)
    }
}
