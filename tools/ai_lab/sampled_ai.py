from __future__ import annotations

import random
from dataclasses import dataclass

from .heuristic_ai import HeuristicAI, HeuristicConfig, IMPROVED_CONFIG, PlanChoice
from .lost_cities_engine import GameState, apply_turn_plan, hand_sort_key, legal_turn_plans, other_player, unseen_cards


@dataclass(frozen=True, slots=True)
class SampledConfig:
    samples: int = 4
    candidate_plans: int = 3
    base_weight: float = 0.35
    enable_from_hidden_cards: int = 6


class SampledHeuristicAI:
    """Top-k determinization wrapper around the heuristic evaluator."""

    def __init__(
        self,
        config: HeuristicConfig = IMPROVED_CONFIG,
        sampled: SampledConfig = SampledConfig(),
    ) -> None:
        self.config = config
        self.sampled = sampled
        self.base = HeuristicAI(config)
        self.reply = HeuristicAI(config)

    def choose_turn(self, state: GameState, player: int) -> PlanChoice:
        plans = legal_turn_plans(state, player)
        if not plans:
            raise ValueError("No legal turn plans available.")

        baseline_choices = [self.score_plan(state, player, plan) for plan in plans]
        baseline_choices.sort(key=lambda choice: choice.value, reverse=True)

        hidden = unseen_cards(state, player)
        if len(hidden) < self.sampled.enable_from_hidden_cards or len(baseline_choices) == 1:
            return baseline_choices[0]

        top_choices = baseline_choices[: self.sampled.candidate_plans]
        best_choice = baseline_choices[0]
        best_value = float("-inf")
        for rank, choice in enumerate(top_choices):
            sampled_total = 0.0
            for sample_index in range(self.sampled.samples):
                sampled_state = self.determinize_state(state, player, rank, sample_index)
                after = apply_turn_plan(sampled_state, player, choice.plan)
                sampled_total += self.sampled_reply_value(after, player)
            sampled_average = sampled_total / float(self.sampled.samples)
            blended_value = (choice.value * self.sampled.base_weight) + (sampled_average * (1.0 - self.sampled.base_weight))
            if blended_value > best_value:
                best_value = blended_value
                best_choice = PlanChoice(plan=choice.plan, value=blended_value)
        return best_choice

    def score_plan(self, state: GameState, player: int, plan) -> PlanChoice:
        after = apply_turn_plan(state, player, plan)
        value = self.base.evaluate_state(after, player)
        value += self.base.plan_adjustment(state, after, player, plan)
        if len(after.deck) <= self.config.reply_threshold or after.final_turns_remaining > 0:
            value = self.base.reply_adjusted_value(after, player, value)
        return PlanChoice(plan=plan, value=value)

    def determinize_state(self, state: GameState, viewer: int, rank: int, sample_index: int) -> GameState:
        sampled = state.copy()
        opponent = other_player(viewer)
        unknown = unseen_cards(state, viewer)
        rng = random.Random(self.seed_string(state, viewer, rank, sample_index))
        rng.shuffle(unknown)
        opponent_hand_count = len(state.players[opponent].hand)
        sampled.players[opponent].hand = sorted(unknown[:opponent_hand_count], key=hand_sort_key)
        sampled.deck = list(unknown[opponent_hand_count:])
        return sampled

    def seed_string(self, state: GameState, viewer: int, rank: int, sample_index: int) -> str:
        history_tail = "|".join(state.history[-12:])
        return f"viewer={viewer};rank={rank};sample={sample_index};turn={state.turn_player};phase={state.phase};deck={len(state.deck)};final={state.final_turns_remaining};history={history_tail}"

    def sampled_reply_value(self, state: GameState, player: int) -> float:
        if state.finished or state.turn_player != other_player(player):
            return self.base.evaluate_state(state, player)
        opponent = other_player(player)
        reply = self.reply.choose_turn(state, opponent)
        after_reply = apply_turn_plan(state, opponent, reply.plan)
        return self.base.evaluate_state(after_reply, player)
