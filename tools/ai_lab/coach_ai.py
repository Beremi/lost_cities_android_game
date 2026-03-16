from __future__ import annotations

from dataclasses import replace

from .heuristic_ai import HeuristicAI, IMPROVED_CONFIG, PlanChoice
from .lost_cities_engine import GameState


COACH_CONFIG = replace(
    IMPROVED_CONFIG,
    name="coach",
    opening_margin=16.0,
    wager_open_penalty=22.0,
    unresolved_wager_penalty=28.0,
    lockout_penalty=2.5,
    high_card_penalty=14.0,
    discard_exposure_scale=1.9,
    dangerous_discard_scale=2.4,
    useless_discard_draw_penalty=10.0,
    reply_threshold=18,
    wager_hand_support_target=3,
    wager_hand_support_penalty=16.0,
    deck_pressure_start=28,
    deck_pressure_penalty=6.0,
    progress_play_penalty=22.0,
)


class CoachSearchAI:
    """Fast challenger policy used as the lab stand-in for 'my' side."""

    def __init__(self) -> None:
        self.heuristic = HeuristicAI(COACH_CONFIG)

    def choose_turn(self, state: GameState, player: int) -> PlanChoice:
        return self.heuristic.choose_turn(state, player)
