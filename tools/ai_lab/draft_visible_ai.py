from __future__ import annotations

import lost_cities_ai as draft

from .heuristic_ai import PlanChoice
from .lost_cities_engine import Card as EngineCard
from .lost_cities_engine import DrawAction, GameState, PlayAction, TurnPlan


ENGINE_TO_VISIBLE_COLOR = {
    "red": "R",
    "green": "G",
    "blue": "B",
    "yellow": "Y",
    "white": "W",
}
VISIBLE_TO_ENGINE_COLOR = {visible: engine for engine, visible in ENGINE_TO_VISIBLE_COLOR.items()}


def engine_card_to_visible(card: EngineCard) -> draft.Card:
    return draft.Card(
        color=ENGINE_TO_VISIBLE_COLOR[card.suit],
        rank=draft.WAGER if card.rank is None else card.rank,
    )


def visible_card_to_engine(card: draft.Card, serial: int = 0) -> EngineCard:
    return EngineCard(
        suit=VISIBLE_TO_ENGINE_COLOR[card.color],
        rank=None if card.rank == draft.WAGER else card.rank,
        serial=serial,
    )


def engine_expedition_to_visible(cards: list[EngineCard]) -> draft.Expedition:
    return draft.Expedition(
        wagers=sum(1 for card in cards if card.rank is None),
        numbers=tuple(card.rank for card in cards if card.rank is not None),
    )


def visible_state_from_engine_state(state: GameState, player: int) -> draft.VisibleState:
    opponent = 2 if player == 1 else 1
    return draft.VisibleState(
        my_hand=[engine_card_to_visible(card) for card in state.players[player].hand],
        my_expeditions={
            ENGINE_TO_VISIBLE_COLOR[color]: engine_expedition_to_visible(cards)
            for color, cards in state.players[player].expeditions.items()
        },
        opp_expeditions={
            ENGINE_TO_VISIBLE_COLOR[color]: engine_expedition_to_visible(cards)
            for color, cards in state.players[opponent].expeditions.items()
        },
        discards={
            ENGINE_TO_VISIBLE_COLOR[color]: [engine_card_to_visible(card) for card in pile]
            for color, pile in state.discard_piles.items()
        },
        deck_size=len(state.deck),
        opp_hand_size=len(state.players[opponent].hand),
        next_player=draft.ME,
    )


def turn_plan_from_draft_move(state: GameState, player: int, move: draft.Move) -> TurnPlan:
    engine_hand = state.players[player].hand
    if move.hand_index < 0 or move.hand_index >= len(engine_hand):
        raise IndexError(f"Hand index {move.hand_index} out of range for player {player}.")

    engine_card = engine_hand[move.hand_index]
    play = PlayAction(kind=move.action, card=engine_card)
    if move.draw_source is None:
        return TurnPlan(play=play, draw=None)
    if move.draw_source == "deck":
        return TurnPlan(play=play, draw=DrawAction(kind="deck"))
    return TurnPlan(
        play=play,
        draw=DrawAction(kind="discard", suit=VISIBLE_TO_ENGINE_COLOR[move.draw_source]),
    )


class DraftVisibleAI:
    """Adapter that runs the root-level visible-state heuristic inside the ai_lab engine."""

    def choose_turn(self, state: GameState, player: int) -> PlanChoice:
        visible_state = visible_state_from_engine_state(state, player)
        best_move, best_value, _ = draft.choose_best_move(visible_state)
        return PlanChoice(
            plan=turn_plan_from_draft_move(state, player, best_move),
            value=best_value,
        )
