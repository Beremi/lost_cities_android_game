from __future__ import annotations

from .coach_ai import CoachSearchAI
from .draft_visible_ai import DraftVisibleAI
from .heuristic_ai import HeuristicAI, IMPROVED_CONFIG
from .sampled_ai import SampledHeuristicAI


BOT_KINDS = ("heuristic", "sampled", "coach", "draft_visible")


def build_bot(kind: str):
    if kind == "heuristic":
        return HeuristicAI(IMPROVED_CONFIG)
    if kind == "sampled":
        return SampledHeuristicAI(IMPROVED_CONFIG)
    if kind == "coach":
        return CoachSearchAI()
    if kind == "draft_visible":
        return DraftVisibleAI()
    raise ValueError(f"Unknown bot kind: {kind}")
