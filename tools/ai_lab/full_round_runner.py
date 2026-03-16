from __future__ import annotations

import argparse
import multiprocessing as mp
from concurrent.futures import ProcessPoolExecutor
from dataclasses import asdict
from pathlib import Path

from .bot_registry import BOT_KINDS, build_bot
from .iteration_runner import MatchSummary, play_single_match


def run_one(args: tuple[int, str, str]) -> dict:
    index, model_kind, opponent_kind = args
    seed = index + 1
    model_player = 1 if index % 2 == 0 else 2
    model = build_bot(model_kind)
    opponent = build_bot(opponent_kind)
    summary, _ = play_single_match(
        seed=seed,
        model_player=model_player,
        model=model,
        opponent=opponent,
        max_turns=None,
        collect_diagnostics=False,
    )
    return asdict(summary)


def render_markdown(title: str, summaries: list[MatchSummary], opponent_label: str) -> str:
    model_wins = sum(1 for summary in summaries if summary.winner == summary.model_player)
    opponent_wins = sum(1 for summary in summaries if summary.winner not in (0, summary.model_player))
    ties = sum(1 for summary in summaries if summary.winner == 0)
    avg_model_score = sum(summary.model_score for summary in summaries) / max(1, len(summaries))
    avg_opponent_score = sum(summary.opponent_score for summary in summaries) / max(1, len(summaries))

    lines = [
        f"## {title}",
        "",
        "| metric | value |",
        "| --- | ---: |",
        f"| model_wins | {model_wins} |",
        f"| {opponent_label}_wins | {opponent_wins} |",
        f"| ties | {ties} |",
        f"| avg_model_score | {avg_model_score:.2f} |",
        f"| avg_{opponent_label}_score | {avg_opponent_score:.2f} |",
        "",
        f"| seed | model_player | winner | model_score | {opponent_label}_score | wager_only_endings |",
        "| --- | ---: | ---: | ---: | ---: | ---: |",
    ]
    for summary in summaries:
        lines.append(
            f"| {summary.seed} | {summary.model_player} | {summary.winner} | {summary.model_score} | {summary.opponent_score} | {summary.wager_only_endings} |"
        )
    lines.append("")
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser(description="Run full-round Lost Cities AI lab batches.")
    parser.add_argument("--games", type=int, default=10)
    parser.add_argument("--workers", type=int, default=2)
    parser.add_argument("--model-kind", choices=BOT_KINDS, default="heuristic")
    parser.add_argument("--opponent-kind", choices=BOT_KINDS, default="coach")
    parser.add_argument("--output", type=Path, default=Path("tools/ai_lab/full_round_10_games.md"))
    args = parser.parse_args()

    ctx = mp.get_context("forkserver")
    with ProcessPoolExecutor(
        max_workers=max(1, args.workers),
        mp_context=ctx,
        max_tasks_per_child=1,
    ) as executor:
        raw = list(executor.map(run_one, ((index, args.model_kind, args.opponent_kind) for index in range(args.games))))
    summaries = [MatchSummary(**item) for item in raw]
    summaries.sort(key=lambda summary: summary.seed)
    report = render_markdown(
        title=f"Full-Round {args.games}-Game Batch ({args.model_kind} vs {args.opponent_kind})",
        summaries=summaries,
        opponent_label=args.opponent_kind,
    )
    args.output.write_text(report, encoding="utf-8")
    print(report)


if __name__ == "__main__":
    main()
