package com.carcassonne.lan.model

data class LostCitiesExpeditionScoreBreakdown(
    val cardIds: List<String> = emptyList(),
    val pointSum: Int = 0,
    val wagerCount: Int = 0,
    val multiplier: Int = 1,
    val baseBeforeMultiplier: Int = 0,
    val multipliedScore: Int = 0,
    val bonus: Int = 0,
    val total: Int = 0,
) {
    val hasCards: Boolean
        get() = cardIds.isNotEmpty()
}

object LostCitiesScoring {
    fun expeditionBreakdown(
        cardIds: List<String>,
        cardById: Map<String, LostCitiesDeckCard>,
    ): LostCitiesExpeditionScoreBreakdown {
        if (cardIds.isEmpty()) {
            return LostCitiesExpeditionScoreBreakdown()
        }

        var wagers = 0
        var pointSum = 0
        cardIds.forEach { cardId ->
            val card = cardById[cardId]
            if (card?.rank == null) {
                wagers += 1
            } else {
                pointSum += card.rank
            }
        }

        val baseBeforeMultiplier = pointSum - 20
        val multiplier = wagers + 1
        val multipliedScore = baseBeforeMultiplier * multiplier
        val bonus = if (cardIds.size >= 8) 20 else 0
        return LostCitiesExpeditionScoreBreakdown(
            cardIds = cardIds,
            pointSum = pointSum,
            wagerCount = wagers,
            multiplier = multiplier,
            baseBeforeMultiplier = baseBeforeMultiplier,
            multipliedScore = multipliedScore,
            bonus = bonus,
            total = multipliedScore + bonus,
        )
    }
}
