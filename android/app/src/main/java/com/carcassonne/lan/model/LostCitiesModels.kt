package com.carcassonne.lan.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
data class LostCitiesDeckCard(
    val id: String,
    val suit: String,
    val type: String,
    val rank: Int? = null,
    val path: String,
    @SerialName("official_color")
    val officialColor: String? = null,
    @SerialName("theme_title")
    val themeTitle: String? = null,
    @SerialName("included_in_base_game")
    val includedInBaseGame: Boolean = true,
)

@Serializable
data class LostCitiesSuitConfig(
    val id: String,
    @SerialName("official_color")
    val officialColor: String? = null,
    @SerialName("theme_title")
    val themeTitle: String? = null,
    @SerialName("theme_blurb")
    val themeBlurb: String? = null,
    @SerialName("icon")
    val icon: String? = null,
    @SerialName("included_in_base_game")
    val includedInBaseGame: Boolean = true,
)

@Serializable
data class LostCitiesDeckManifest(
    val name: String = "lost cities",
    @SerialName("base_game_card_count")
    val baseGameCardCount: Int = 60,
    @SerialName("long_variant_card_count")
    val longVariantCardCount: Int = 72,
    @SerialName("card_dimensions_px")
    val cardDimensionsPx: Map<String, Int> = emptyMap(),
    val suits: List<LostCitiesSuitConfig> = emptyList(),
    val cards: List<LostCitiesDeckCard> = emptyList(),
)

@Serializable
data class LostCitiesPlayerState(
    val hand: List<String> = emptyList(),
    val expeditions: Map<String, List<String>> = emptyMap(),
)

@Serializable
enum class LostCitiesTurnPhase {
    PLAY,
    DRAW,
}

@Serializable
data class LostCitiesMatchState(
    val turnPlayer: Int = 1,
    val phase: LostCitiesTurnPhase = LostCitiesTurnPhase.PLAY,
    val deck: List<String> = emptyList(),
    val discardPiles: Map<String, List<String>> = emptyMap(),
    val players: Map<Int, LostCitiesPlayerState> = emptyMap(),
    val justDiscardedCardId: String? = null,
    val finalTurnsRemaining: Int = 0,
)
