from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

from .bot_registry import build_bot
from .lost_cities_engine import (
    GameState,
    TurnPlan,
    apply_turn_plan,
    deal_game,
    other_player,
    render_state,
    score_player,
)
from .run_lab import count_wager_only_endings, dangerous_discard, high_lockout_play, speculative_opening, thin_wager


@dataclass(slots=True)
class MatchSummary:
    seed: int
    model_player: int
    winner: int
    model_score: int
    opponent_score: int
    stalled: bool
    thin_wagers: int
    speculative_openings: int
    high_lockout_plays: int
    dangerous_discards: int
    wager_only_endings: int

    @property
    def coach_score(self) -> int:
        return self.opponent_score


@dataclass(slots=True)
class BatchResult:
    model_wins: int = 0
    opponent_wins: int = 0
    ties: int = 0
    model_total_score: int = 0
    opponent_total_score: int = 0
    thin_wagers: int = 0
    speculative_openings: int = 0
    high_lockout_plays: int = 0
    dangerous_discards: int = 0
    wager_only_endings: int = 0
    stalled_games: int = 0
    summaries: list[MatchSummary] = field(default_factory=list)
    observations: list[str] = field(default_factory=list)

    @property
    def coach_wins(self) -> int:
        return self.opponent_wins

    @property
    def coach_total_score(self) -> int:
        return self.opponent_total_score


def run_batch(
    games: int = 10,
    max_turns: int | None = 24,
    model_kind: str = "heuristic",
    opponent_kind: str = "coach",
    collect_diagnostics: bool = True,
) -> BatchResult:
    model = build_bot(model_kind)
    opponent = build_bot(opponent_kind)
    result = BatchResult()

    for index in range(games):
        seed = index + 1
        model_player = 1 if index % 2 == 0 else 2
        summary, observations = play_single_match(
            seed=seed,
            model_player=model_player,
            model=model,
            opponent=opponent,
            max_turns=max_turns,
            collect_diagnostics=collect_diagnostics,
        )
        result.summaries.append(summary)
        result.model_total_score += summary.model_score
        result.opponent_total_score += summary.opponent_score
        result.stalled_games += int(summary.stalled)
        result.thin_wagers += summary.thin_wagers
        result.speculative_openings += summary.speculative_openings
        result.high_lockout_plays += summary.high_lockout_plays
        result.dangerous_discards += summary.dangerous_discards
        result.wager_only_endings += summary.wager_only_endings
        if summary.winner == model_player:
            result.model_wins += 1
        elif summary.winner == 0:
            result.ties += 1
        else:
            result.opponent_wins += 1
        for note in observations:
            if len(result.observations) >= 8:
                break
            result.observations.append(note)

    return result


def play_single_match(
    seed: int,
    model_player: int,
    model,
    opponent,
    max_turns: int | None,
    collect_diagnostics: bool,
) -> tuple[MatchSummary, list[str]]:
    state = deal_game(seed)
    turn_count = 0
    observations: list[str] = []
    pathology_ai = model.base if collect_diagnostics and hasattr(model, "base") else model
    supports_pathology = collect_diagnostics and all(
        hasattr(pathology_ai, attr)
        for attr in (
            "top_discard_exposure",
            "card_still_live",
            "hand_number_support",
            "opening_support_quality",
            "required_opening_support_quality",
        )
    )
    model_pathologies = {
        "thin_wagers": 0,
        "speculative_openings": 0,
        "high_lockout_plays": 0,
        "dangerous_discards": 0,
    }

    while not state.finished:
        turn_count += 1
        if max_turns is not None and turn_count > max_turns:
            score_one = score_player(state.players[1])
            score_two = score_player(state.players[2])
            state.winner = 1 if score_one > score_two else 2 if score_two > score_one else 0
            break

        player = state.turn_player
        if player == model_player:
            model_choice = model.choose_turn(state, player)
            plan = model_choice.plan
            if collect_diagnostics:
                opponent_choice = opponent.choose_turn(state, player)
                if supports_pathology and thin_wager(pathology_ai, state, plan, player):
                    model_pathologies["thin_wagers"] += 1
                    maybe_add_observation(
                        observations,
                        seed,
                        state,
                        plan,
                        opponent_choice.plan,
                        "thin wager",
                    )
                if supports_pathology and speculative_opening(pathology_ai, state, plan, player):
                    model_pathologies["speculative_openings"] += 1
                    maybe_add_observation(
                        observations,
                        seed,
                        state,
                        plan,
                        opponent_choice.plan,
                        "speculative opening",
                    )
                if supports_pathology and high_lockout_play(pathology_ai, state, plan, player):
                    model_pathologies["high_lockout_plays"] += 1
                    maybe_add_observation(
                        observations,
                        seed,
                        state,
                        plan,
                        opponent_choice.plan,
                        "high-card lockout",
                    )
                if supports_pathology and dangerous_discard(pathology_ai, state, plan, player):
                    model_pathologies["dangerous_discards"] += 1
                    maybe_add_observation(
                        observations,
                        seed,
                        state,
                        plan,
                        opponent_choice.plan,
                        "dangerous discard",
                    )
        else:
            plan = opponent.choose_turn(state, player).plan
        state = apply_turn_plan(state, player, plan)

    summary = MatchSummary(
        seed=seed,
        model_player=model_player,
        winner=state.winner if state.winner is not None else 0,
        model_score=score_player(state.players[model_player]),
        opponent_score=score_player(state.players[other_player(model_player)]),
        stalled=bool(max_turns is not None and turn_count > max_turns),
        thin_wagers=model_pathologies["thin_wagers"],
        speculative_openings=model_pathologies["speculative_openings"],
        high_lockout_plays=model_pathologies["high_lockout_plays"],
        dangerous_discards=model_pathologies["dangerous_discards"],
        wager_only_endings=count_wager_only_endings(state),
    )
    if collect_diagnostics and not observations:
        summary_notes = [
            f"thin_wagers={summary.thin_wagers}",
            f"speculative_openings={summary.speculative_openings}",
            f"high_lockout_plays={summary.high_lockout_plays}",
            f"dangerous_discards={summary.dangerous_discards}",
            f"wager_only_endings={summary.wager_only_endings}",
        ]
        observations.append(f"seed={seed} summary {' '.join(summary_notes)}")
    return summary, observations


def maybe_add_observation(
    observations: list[str],
    seed: int,
    state: GameState,
    model_plan: TurnPlan,
    opponent_plan: TurnPlan,
    reason: str,
) -> None:
    if len(observations) >= 8:
        return
    if model_plan.label() == opponent_plan.label():
        return
    observations.append(
        "\n".join(
            [
                f"seed={seed} reason={reason}",
                f"model={model_plan.label()} opponent={opponent_plan.label()}",
                "```text",
                render_state(state, state.turn_player),
                "```",
            ]
        )
    )


def render_batch_markdown(result: BatchResult, title: str, opponent_label: str = "opponent") -> str:
    lines = [
        f"## {title}",
        "",
        "| metric | value |",
        "| --- | ---: |",
        f"| model_wins | {result.model_wins} |",
        f"| {opponent_label}_wins | {result.opponent_wins} |",
        f"| ties | {result.ties} |",
        f"| avg_model_score | {result.model_total_score / max(1, len(result.summaries)):.2f} |",
        f"| avg_{opponent_label}_score | {result.opponent_total_score / max(1, len(result.summaries)):.2f} |",
        f"| stalled_games | {result.stalled_games} |",
        f"| thin_wagers | {result.thin_wagers} |",
        f"| speculative_openings | {result.speculative_openings} |",
        f"| high_lockout_plays | {result.high_lockout_plays} |",
        f"| dangerous_discards | {result.dangerous_discards} |",
        f"| wager_only_endings | {result.wager_only_endings} |",
        "",
        "### Game Results",
        "",
        f"| seed | model_player | winner | model_score | {opponent_label}_score | stalled | thin_wagers | speculative_openings | lockouts | dangerous_discards | wager_only_endings |",
        "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
    ]
    for summary in result.summaries:
        lines.append(
            f"| {summary.seed} | {summary.model_player} | {summary.winner} | {summary.model_score} | {summary.opponent_score} | {int(summary.stalled)} | {summary.thin_wagers} | {summary.speculative_openings} | {summary.high_lockout_plays} | {summary.dangerous_discards} | {summary.wager_only_endings} |"
        )
    if result.observations:
        lines.extend(["", "### Observations", ""])
        for note in result.observations:
            lines.append(note)
            lines.append("")
    return "\n".join(lines)


def write_report(path: Path, sections: list[str]) -> None:
    path.write_text("\n\n".join(sections), encoding="utf-8")
