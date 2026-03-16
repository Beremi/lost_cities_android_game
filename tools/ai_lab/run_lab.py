from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path
import statistics

from .heuristic_ai import BASELINE_CONFIG, IMPROVED_CONFIG, HeuristicAI
from .lost_cities_engine import (
    Card,
    GameState,
    HAND_SIZE,
    PlayerState,
    SUITS,
    TurnPlan,
    apply_turn_plan,
    deal_game,
    hand_sort_key,
    other_player,
    render_state,
    score_player,
)


@dataclass(slots=True)
class LabMetrics:
    games: int = 0
    p1_scores: list[int] = None
    p2_scores: list[int] = None
    wager_only_endings: int = 0
    dangerous_discards: int = 0
    high_lockout_plays: int = 0
    thin_wagers: int = 0
    stalled_games: int = 0

    def __post_init__(self) -> None:
        if self.p1_scores is None:
            self.p1_scores = []
        if self.p2_scores is None:
            self.p2_scores = []


def dangerous_discard(ai: HeuristicAI, state: GameState, plan: TurnPlan, player: int) -> bool:
    if plan.play.kind != "discard":
        return False
    suit = plan.play.card.suit
    after = apply_turn_plan(state, player, plan)
    threat = ai.top_discard_exposure(after, other_player(player), player, suit)
    return threat >= 12.0


def high_lockout_play(ai: HeuristicAI, state: GameState, plan: TurnPlan, player: int) -> bool:
    if plan.play.kind != "play" or plan.play.card.rank is None or plan.play.card.rank < 8:
        return False
    expedition = state.players[player].expeditions[plan.play.card.suit]
    profile = ai.evaluate_state  # keep local reference out of hot path lint warnings
    _ = profile
    last_rank = 0
    for card in expedition:
        if card.rank is not None:
            last_rank = max(last_rank, card.rank)
    lower_live = [
        rank
        for rank in range(last_rank + 1, plan.play.card.rank)
        if ai.card_still_live(state, player, plan.play.card.suit, rank)
    ]
    return len(lower_live) >= 2


def thin_wager(ai: HeuristicAI, state: GameState, plan: TurnPlan, player: int) -> bool:
    if plan.play.kind != "play" or not plan.play.card.is_wager:
        return False
    support = ai.hand_number_support(state, player, plan.play.card.suit, 0)
    return support < 2


def speculative_opening(ai: HeuristicAI, state: GameState, plan: TurnPlan, player: int) -> bool:
    if plan.play.kind != "play":
        return False
    card = plan.play.card
    if card.rank is None:
        return False
    if state.players[player].expeditions[card.suit]:
        return False
    support_quality = ai.opening_support_quality(state, player, card.suit, card.rank)
    required_quality = ai.required_opening_support_quality(state, player, card)
    return support_quality + 0.15 < required_quality


def count_wager_only_endings(state: GameState) -> int:
    total = 0
    for player in (1, 2):
        for suit in SUITS:
            cards = state.players[player].expeditions[suit]
            if cards and all(card.rank is None for card in cards):
                total += 1
    return total


def play_game(seed: int, ai_one: HeuristicAI, ai_two: HeuristicAI, max_turns: int = 24) -> tuple[GameState, LabMetrics]:
    state = deal_game(seed)
    metrics = LabMetrics(games=1)
    turns = 0
    while not state.finished:
        turns += 1
        if turns > max_turns:
            metrics.stalled_games += 1
            score_one = score_player(state.players[1])
            score_two = score_player(state.players[2])
            state.winner = 1 if score_one > score_two else 2 if score_two > score_one else 0
            state.history.append(f"forced stop {score_one}:{score_two}")
            break
        player = state.turn_player
        ai = ai_one if player == 1 else ai_two
        plan = ai.choose_turn(state, player).plan
        if dangerous_discard(ai, state, plan, player):
            metrics.dangerous_discards += 1
        if high_lockout_play(ai, state, plan, player):
            metrics.high_lockout_plays += 1
        if thin_wager(ai, state, plan, player):
            metrics.thin_wagers += 1
        state = apply_turn_plan(state, player, plan)
    metrics.p1_scores.append(score_player(state.players[1]))
    metrics.p2_scores.append(score_player(state.players[2]))
    metrics.wager_only_endings += count_wager_only_endings(state)
    return state, metrics


def merge_metrics(items: list[LabMetrics]) -> LabMetrics:
    merged = LabMetrics()
    merged.games = sum(item.games for item in items)
    for item in items:
        merged.p1_scores.extend(item.p1_scores)
        merged.p2_scores.extend(item.p2_scores)
        merged.wager_only_endings += item.wager_only_endings
        merged.dangerous_discards += item.dangerous_discards
        merged.high_lockout_plays += item.high_lockout_plays
        merged.thin_wagers += item.thin_wagers
        merged.stalled_games += item.stalled_games
    return merged


def scenario_high_card_after_wager() -> GameState:
    return GameState(
        players={
            1: PlayerState(
                hand=sorted(
                    [Card("yellow", None, 2), Card("yellow", 9), Card("blue", 6)],
                    key=hand_sort_key,
                ),
                expeditions={
                    "yellow": [Card("yellow", None, 1)],
                    "blue": [Card("blue", 4), Card("blue", 5)],
                    "white": [],
                    "green": [],
                    "red": [],
                },
            ),
            2: PlayerState(),
        },
        deck=[Card("yellow", 2), Card("blue", 2), Card("yellow", 3), Card("blue", 3)],
        discard_piles={suit: [] for suit in SUITS},
    )


def scenario_dangerous_discard() -> GameState:
    return GameState(
        players={
            1: PlayerState(
                hand=sorted([Card("blue", 9), Card("yellow", 2)], key=hand_sort_key),
                expeditions={suit: [] for suit in SUITS},
            ),
            2: PlayerState(
                expeditions={
                    "yellow": [],
                    "blue": [Card("blue", None, 1), Card("blue", 5)],
                    "white": [],
                    "green": [],
                    "red": [],
                },
            ),
        },
        deck=[Card("yellow", 3), Card("blue", 2), Card("yellow", 4), Card("blue", 3)],
        discard_piles={suit: [] for suit in SUITS},
    )


def scenario_speculative_opening() -> GameState:
    return GameState(
        players={
            1: PlayerState(
                hand=sorted(
                    [Card("yellow", None, 1), Card("blue", None, 1), Card("blue", 6), Card("white", 5), Card("white", 6), Card("green", 3), Card("red", None, 2), Card("red", 5)],
                    key=hand_sort_key,
                ),
                expeditions={suit: [] for suit in SUITS},
            ),
            2: PlayerState(),
        },
        deck=[Card("green", 7), Card("yellow", 4), Card("green", 8), Card("blue", 8)],
        discard_piles={
            "yellow": [],
            "blue": [Card("blue", 3)],
            "white": [],
            "green": [Card("green", None, 1)],
            "red": [],
        },
    )


def print_scenarios() -> str:
    baseline = HeuristicAI(BASELINE_CONFIG)
    improved = HeuristicAI(IMPROVED_CONFIG)
    sections: list[str] = []
    for name, scenario in (
        ("high_card_after_wager", scenario_high_card_after_wager()),
        ("dangerous_discard", scenario_dangerous_discard()),
        ("speculative_opening", scenario_speculative_opening()),
    ):
        baseline_choice = baseline.choose_turn(scenario, 1)
        improved_choice = improved.choose_turn(scenario, 1)
        sections.append(
            "\n".join(
                [
                    f"## {name}",
                    "```text",
                    render_state(scenario, 1),
                    "```",
                    f"- baseline: `{baseline_choice.plan.label()}` value={baseline_choice.value:.2f}",
                    f"- improved: `{improved_choice.plan.label()}` value={improved_choice.value:.2f}",
                ]
            )
        )
    return "\n\n".join(sections)


def self_play_summary(games: int) -> str:
    baseline_ai = HeuristicAI(BASELINE_CONFIG)
    improved_ai = HeuristicAI(IMPROVED_CONFIG)

    baseline_results = [play_game(seed, baseline_ai, baseline_ai)[1] for seed in range(1, games + 1)]
    improved_results = [play_game(seed, improved_ai, improved_ai)[1] for seed in range(1, games + 1)]

    baseline = merge_metrics(baseline_results)
    improved = merge_metrics(improved_results)
    return "\n".join(
        [
            "## self_play",
            "",
            "These are capped 24-turn scrimmages, used to surface early and midgame decision pathologies quickly.",
            "",
            "| metric | baseline | improved |",
            "| --- | ---: | ---: |",
            f"| games | {baseline.games} | {improved.games} |",
            f"| avg_p1_score | {statistics.mean(baseline.p1_scores):.2f} | {statistics.mean(improved.p1_scores):.2f} |",
            f"| avg_p2_score | {statistics.mean(baseline.p2_scores):.2f} | {statistics.mean(improved.p2_scores):.2f} |",
            f"| wager_only_endings | {baseline.wager_only_endings} | {improved.wager_only_endings} |",
            f"| dangerous_discards | {baseline.dangerous_discards} | {improved.dangerous_discards} |",
            f"| high_lockout_plays | {baseline.high_lockout_plays} | {improved.high_lockout_plays} |",
            f"| thin_wagers | {baseline.thin_wagers} | {improved.thin_wagers} |",
            f"| stalled_games | {baseline.stalled_games} | {improved.stalled_games} |",
        ]
    )


def build_report(games: int) -> str:
    return "\n\n".join(
        [
            "# Lost Cities AI Lab",
            "This file is generated from the local Python lab runner. It compares a baseline heuristic that preserves the current failure tendencies against a stricter variant tuned around wagers, lockouts, and discard danger.",
            print_scenarios(),
            self_play_summary(games),
        ]
    )


def main() -> None:
    parser = argparse.ArgumentParser(description="Lost Cities local AI lab")
    parser.add_argument("--games", type=int, default=40, help="Number of self-play games per configuration.")
    parser.add_argument(
        "--report",
        type=Path,
        default=Path("tools/ai_lab/lab_report.md"),
        help="Where to write the generated report.",
    )
    args = parser.parse_args()

    report = build_report(args.games)
    args.report.write_text(report, encoding="utf-8")
    print(report)


if __name__ == "__main__":
    main()
