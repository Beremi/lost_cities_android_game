from __future__ import annotations

import argparse
from dataclasses import asdict
import json

from .bot_registry import BOT_KINDS, build_bot
from .iteration_runner import play_single_match


def main() -> None:
    parser = argparse.ArgumentParser(description="Run a single finished-round Lost Cities AI lab match.")
    parser.add_argument("--seed", type=int, required=True)
    parser.add_argument("--model-player", type=int, choices=(1, 2), required=True)
    parser.add_argument("--model-kind", choices=BOT_KINDS, default="heuristic")
    parser.add_argument("--opponent-kind", choices=BOT_KINDS, default="coach")
    args = parser.parse_args()

    summary, _ = play_single_match(
        seed=args.seed,
        model_player=args.model_player,
        model=build_bot(args.model_kind),
        opponent=build_bot(args.opponent_kind),
        max_turns=None,
        collect_diagnostics=False,
    )
    print(json.dumps(asdict(summary), sort_keys=True))


if __name__ == "__main__":
    main()
