package com.lost_cities.lan

import com.lost_cities.lan.model.LostCitiesDeckCard
import com.lost_cities.lan.model.LostCitiesDeckManifest
import com.lost_cities.lan.model.LostCitiesSuitConfig
import com.lost_cities.lan.model.MatchStatus
import com.lost_cities.lan.network.HostGameManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test

class HostGameManagerTest {
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

    @Test
    fun reconnectByNameKeepsSameSeat() {
        val manager = HostGameManager(
            deckManifest = manifestWithSeventeenCards(),
            hostName = "Player1001",
            serverId = "server-a",
        )

        val first = manager.joinOrReconnect("Player1001")
        val second = manager.joinOrReconnect("Player1001")

        assertTrue(first.ok)
        assertTrue(second.ok)
        assertEquals(first.player, second.player)
        assertEquals(first.token, second.token)
        assertEquals(1, manager.snapshot().players.size)
    }

    @Test
    fun inviteListIsFilteredByTargetServerId() {
        val manager = HostGameManager(
            deckManifest = manifestWithSeventeenCards(),
            hostName = "Player1001",
            serverId = "server-a",
        )
        assertTrue(manager.joinOrReconnect("Player1001").ok)

        val invite = manager.createInvite(
            fromName = "Player1001",
            targetServerId = "server-b",
            inviteRules = null,
        )
        assertTrue(invite.ok)

        val otherPeerList = manager.listInvites("server-c")
        val targetPeerList = manager.listInvites("server-b")

        assertTrue(otherPeerList.ok)
        assertTrue(targetPeerList.ok)
        assertTrue(otherPeerList.invites.isEmpty())
        assertEquals(1, targetPeerList.invites.size)
        assertEquals("server-b", targetPeerList.invites.single().targetServerId)
    }

    @Test
    fun cannotDrawBackSameDiscardedCard() {
        val manager = HostGameManager(
            deckManifest = manifestWithSeventeenCards(),
            hostName = "Player1001",
            serverId = "server-a",
        )
        val p1 = manager.joinOrReconnect("Player1001")
        val p2 = manager.joinOrReconnect("Player2002")
        assertTrue(p1.ok)
        assertTrue(p2.ok)

        val token = assertNotNull(p1.token)
        val match = manager.snapshot()
        val discardedCard = assertNotNull(match.lostCities.players[1]?.hand?.firstOrNull())
        val suit = discardedCard.substringBefore('_')

        val discard = manager.applyLostCitiesAction(token, "discard", discardedCard)
        assertTrue(discard.ok)

        val takeBack = manager.applyLostCitiesAction(token, "draw_discard", "", suit)
        assertFalse(takeBack.ok)
        assertTrue(takeBack.error?.contains("cannot take back", ignoreCase = true) == true)
    }

    @Test
    fun undoRestoresPreDrawState() {
        val manager = HostGameManager(
            deckManifest = manifestWithSeventeenCards(),
            hostName = "Player1001",
            serverId = "server-a",
        )
        val p1 = manager.joinOrReconnect("Player1001")
        val p2 = manager.joinOrReconnect("Player2002")
        assertTrue(p1.ok)
        assertTrue(p2.ok)

        val token = assertNotNull(p1.token)
        val beforeDiscard = manager.snapshot()
        val cardToDiscard = assertNotNull(beforeDiscard.lostCities.players[1]?.hand?.firstOrNull())

        val discard = manager.applyLostCitiesAction(token, "discard", cardToDiscard)
        assertTrue(discard.ok)
        val afterDiscard = assertNotNull(discard.match)
        assertEquals(cardToDiscard, afterDiscard.lostCities.discardPiles[cardToDiscard.substringBefore('_')]?.lastOrNull())

        val undo = manager.applyLostCitiesAction(token, "undo", "")
        assertTrue(undo.ok)
        val restored = assertNotNull(undo.match)
        assertEquals(beforeDiscard.lostCities.players[1]?.hand, restored.lostCities.players[1]?.hand)
        assertEquals(beforeDiscard.lostCities.discardPiles, restored.lostCities.discardPiles)
        assertEquals(beforeDiscard.lostCities.phase, restored.lostCities.phase)
        assertEquals(beforeDiscard.lostCities.turnPlayer, restored.lostCities.turnPlayer)
    }

    @Test
    fun drawingFinalDeckCardStartsTwoPlayOnlyFinalTurns() {
        val manager = HostGameManager(
            deckManifest = manifestWithSeventeenCards(),
            hostName = "Player1001",
            serverId = "server-a",
        )
        val p1 = manager.joinOrReconnect("Player1001")
        val p2 = manager.joinOrReconnect("Player2002")
        assertTrue(p1.ok)
        assertTrue(p2.ok)

        val p1Token = assertNotNull(p1.token)
        val p2Token = assertNotNull(p2.token)
        val match = manager.snapshot()
        assertEquals(1, match.lostCities.deck.size)

        val cardToDiscard = assertNotNull(match.lostCities.players[1]?.hand?.firstOrNull())
        assertTrue(manager.applyLostCitiesAction(p1Token, "discard", cardToDiscard).ok)

        val draw = manager.applyLostCitiesAction(p1Token, "draw_deck", "")
        assertTrue(draw.ok)
        val afterFinalDraw = assertNotNull(draw.match)
        assertEquals(MatchStatus.ACTIVE, afterFinalDraw.status)
        assertTrue(afterFinalDraw.lostCities.deck.isEmpty())
        assertEquals(2, afterFinalDraw.lostCities.finalTurnsRemaining)
        assertEquals(2, afterFinalDraw.lostCities.turnPlayer)
        assertEquals(8, afterFinalDraw.lostCities.players[1]?.hand?.size)
        assertEquals(8, afterFinalDraw.lostCities.players[2]?.hand?.size)
        assertTrue(afterFinalDraw.lastEvent.contains("final", ignoreCase = true))

        val illegalDraw = manager.applyLostCitiesAction(p2Token, "draw_deck", "")
        assertFalse(illegalDraw.ok)

        val p2FinalCard = assertNotNull(afterFinalDraw.lostCities.players[2]?.hand?.firstOrNull())
        val p2FinalTurn = manager.applyLostCitiesAction(p2Token, "discard", p2FinalCard)
        assertTrue(p2FinalTurn.ok)
        val afterP2FinalTurn = assertNotNull(p2FinalTurn.match)
        assertEquals(MatchStatus.ACTIVE, afterP2FinalTurn.status)
        assertEquals(1, afterP2FinalTurn.lostCities.finalTurnsRemaining)
        assertEquals(1, afterP2FinalTurn.lostCities.turnPlayer)
        assertEquals(7, afterP2FinalTurn.lostCities.players[2]?.hand?.size)

        val p1FinalCard = assertNotNull(afterP2FinalTurn.lostCities.players[1]?.hand?.firstOrNull())
        val finish = manager.applyLostCitiesAction(p1Token, "discard", p1FinalCard)
        assertTrue(finish.ok)
        val finalMatch = assertNotNull(finish.match)
        assertEquals(MatchStatus.FINISHED, finalMatch.status)
        assertEquals(0, finalMatch.lostCities.finalTurnsRemaining)
        assertEquals(7, finalMatch.lostCities.players[1]?.hand?.size)
        assertEquals(7, finalMatch.lostCities.players[2]?.hand?.size)
    }

    @Test
    fun finishedRoundCanReturnToWaitingAndCreateRematchInvite() {
        val manager = HostGameManager(
            deckManifest = manifestWithSeventeenCards(),
            hostName = "Player1001",
            serverId = "server-a",
        )
        val p1 = manager.joinOrReconnect("Player1001")
        val p2 = manager.joinOrReconnect("Player2002")
        assertTrue(p1.ok)
        assertTrue(p2.ok)

        val p1Token = assertNotNull(p1.token)
        val p2Token = assertNotNull(p2.token)
        val match = manager.snapshot()
        val cardToDiscard = assertNotNull(match.lostCities.players[1]?.hand?.firstOrNull())
        assertTrue(manager.applyLostCitiesAction(p1Token, "discard", cardToDiscard).ok)
        assertTrue(manager.applyLostCitiesAction(p1Token, "draw_deck", "").ok)
        val p2FinalCard = assertNotNull(manager.snapshot().lostCities.players[2]?.hand?.firstOrNull())
        assertTrue(manager.applyLostCitiesAction(p2Token, "discard", p2FinalCard).ok)
        val p1FinalCard = assertNotNull(manager.snapshot().lostCities.players[1]?.hand?.firstOrNull())
        val finished = manager.applyLostCitiesAction(p1Token, "discard", p1FinalCard)
        assertTrue(finished.ok)
        assertEquals(MatchStatus.FINISHED, assertNotNull(finished.match).status)

        val invite = manager.createInvite(
            fromName = "Player1001",
            targetServerId = "server-b",
            inviteRules = null,
        )
        assertTrue(invite.ok)
        val snapshot = manager.snapshot()
        assertEquals(MatchStatus.WAITING, snapshot.status)
        assertEquals(2, snapshot.players.size)
        assertEquals(1, manager.listInvites("server-b").invites.size)
    }

    @Test
    fun rematchRequestStartsNewRoundWhenOpponentAccepts() {
        val manager = HostGameManager(
            deckManifest = manifestWithSeventeenCards(),
            hostName = "Player1001",
            serverId = "server-a",
        )
        val p1 = manager.joinOrReconnect("Player1001")
        val p2 = manager.joinOrReconnect("Player2002")
        assertTrue(p1.ok)
        assertTrue(p2.ok)

        val p1Token = assertNotNull(p1.token)
        val p2Token = assertNotNull(p2.token)
        val opening = manager.snapshot()
        val p1Card = assertNotNull(opening.lostCities.players[1]?.hand?.firstOrNull())
        assertTrue(manager.applyLostCitiesAction(p1Token, "discard", p1Card).ok)
        assertTrue(manager.applyLostCitiesAction(p1Token, "draw_deck", "").ok)
        val p2Card = assertNotNull(manager.snapshot().lostCities.players[2]?.hand?.firstOrNull())
        assertTrue(manager.applyLostCitiesAction(p2Token, "discard", p2Card).ok)
        val p1FinalCard = assertNotNull(manager.snapshot().lostCities.players[1]?.hand?.firstOrNull())
        assertTrue(manager.applyLostCitiesAction(p1Token, "discard", p1FinalCard).ok)
        assertEquals(MatchStatus.FINISHED, manager.snapshot().status)

        val request = manager.applyLostCitiesAction(p1Token, "request_rematch", "")
        assertTrue(request.ok)
        assertEquals(1, assertNotNull(request.match).rematchOfferedByPlayer)
        assertEquals(MatchStatus.FINISHED, request.match?.status)

        val accept = manager.applyLostCitiesAction(p2Token, "accept_rematch", "")
        assertTrue(accept.ok)
        val restarted = assertNotNull(accept.match)
        assertEquals(MatchStatus.ACTIVE, restarted.status)
        assertEquals(null, restarted.rematchOfferedByPlayer)
        assertTrue(restarted.lastEvent.contains("Rematch accepted", ignoreCase = true))
        assertEquals(8, restarted.lostCities.players[1]?.hand?.size)
        assertEquals(8, restarted.lostCities.players[2]?.hand?.size)
    }
}
