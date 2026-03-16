from __future__ import annotations

from dataclasses import dataclass
from math import prod
from typing import Iterable

from .lost_cities_engine import (
    Card,
    DrawAction,
    FULL_DECK,
    GameState,
    PlayAction,
    SUITS,
    TurnPlan,
    all_visible_cards,
    apply_turn_plan,
    can_play_to_expedition,
    legal_turn_plans,
    other_player,
    score_expedition,
    score_player,
    suit_profile,
)


@dataclass(frozen=True, slots=True)
class HeuristicConfig:
    name: str
    count_wagers_as_support: bool
    extra_wager_weight: float
    opening_margin: float
    commitment_free_expeditions: int
    commitment_penalty: float
    opening_low_rank_support_target: int
    opening_high_rank_support_target: int
    opening_hand_support_penalty: float
    opening_support_quality_target: float
    opening_support_penalty: float
    zero_support_open_penalty: float
    wager_open_penalty: float
    unresolved_wager_penalty: float
    lockout_penalty: float
    high_card_penalty: float
    discard_exposure_scale: float
    dangerous_discard_scale: float
    discard_draw_base_penalty: float
    useless_discard_draw_penalty: float
    reply_threshold: int
    wager_hand_support_target: int
    wager_hand_support_penalty: float
    deck_pressure_start: int
    deck_pressure_penalty: float
    progress_play_penalty: float


BASELINE_CONFIG = HeuristicConfig(
    name="baseline",
    count_wagers_as_support=True,
    extra_wager_weight=0.65,
    opening_margin=6.0,
    commitment_free_expeditions=2,
    commitment_penalty=3.5,
    opening_low_rank_support_target=1,
    opening_high_rank_support_target=1,
    opening_hand_support_penalty=3.5,
    opening_support_quality_target=1.2,
    opening_support_penalty=4.0,
    zero_support_open_penalty=4.0,
    wager_open_penalty=7.0,
    unresolved_wager_penalty=8.0,
    lockout_penalty=0.45,
    high_card_penalty=2.5,
    discard_exposure_scale=0.65,
    dangerous_discard_scale=0.9,
    discard_draw_base_penalty=1.5,
    useless_discard_draw_penalty=3.0,
    reply_threshold=10,
    wager_hand_support_target=2,
    wager_hand_support_penalty=5.0,
    deck_pressure_start=12,
    deck_pressure_penalty=2.0,
    progress_play_penalty=4.0,
)


IMPROVED_CONFIG = HeuristicConfig(
    name="improved",
    count_wagers_as_support=False,
    extra_wager_weight=0.18,
    opening_margin=12.0,
    commitment_free_expeditions=2,
    commitment_penalty=7.0,
    opening_low_rank_support_target=1,
    opening_high_rank_support_target=2,
    opening_hand_support_penalty=11.0,
    opening_support_quality_target=1.5,
    opening_support_penalty=9.5,
    zero_support_open_penalty=9.0,
    wager_open_penalty=16.0,
    unresolved_wager_penalty=22.0,
    lockout_penalty=1.9,
    high_card_penalty=11.0,
    discard_exposure_scale=1.5,
    dangerous_discard_scale=1.8,
    discard_draw_base_penalty=3.5,
    useless_discard_draw_penalty=8.0,
    reply_threshold=14,
    wager_hand_support_target=3,
    wager_hand_support_penalty=15.0,
    deck_pressure_start=24,
    deck_pressure_penalty=5.0,
    progress_play_penalty=16.0,
)


@dataclass(frozen=True, slots=True)
class PlanChoice:
    plan: TurnPlan
    value: float


class HeuristicAI:
    def __init__(self, config: HeuristicConfig) -> None:
        self.config = config
        self._remaining_plays_cache: dict[tuple[int, str, int, int, int], int] = {}
        self._remaining_deck_draws_cache: dict[tuple[int, str, int, int, int], int] = {}
        self._visible_cards_cache: dict[tuple, set[Card]] = {}
        self._expected_total_cache: dict[tuple, float] = {}

    def choose_turn(self, state: GameState, player: int) -> PlanChoice:
        plans = legal_turn_plans(state, player)
        if not plans:
            raise ValueError("No legal turn plans available.")

        best: PlanChoice | None = None
        for plan in plans:
            after = apply_turn_plan(state, player, plan)
            value = self.evaluate_state(after, player)
            value += self.plan_adjustment(state, after, player, plan)
            if len(after.deck) <= self.config.reply_threshold or after.final_turns_remaining > 0:
                value = self.reply_adjusted_value(after, player, value)
            choice = PlanChoice(plan=plan, value=value)
            if best is None or choice.value > best.value:
                best = choice
        assert best is not None
        return best

    def evaluate_state(self, state: GameState, player: int) -> float:
        opponent = other_player(player)
        score_diff = float(score_player(state.players[player]) - score_player(state.players[opponent]))
        future_diff = self.future_potential(state, player, player) - self.future_potential(state, opponent, player)
        reserve_diff = self.reserve_potential(state, player, player) - self.reserve_potential(state, opponent, player)
        exposure_diff = self.unfinished_expedition_penalty(state, opponent, player) - self.unfinished_expedition_penalty(state, player, player)
        discard_threat = self.total_discard_threat(state, opponent, player)
        terminal_bonus = 0.0
        if state.finished:
            terminal_bonus = score_diff * 1.25
        return score_diff + future_diff + reserve_diff + exposure_diff - discard_threat + terminal_bonus

    def reply_adjusted_value(self, state: GameState, player: int, base_value: float) -> float:
        if state.finished or state.turn_player != other_player(player):
            return base_value
        opponent = other_player(player)
        plans = legal_turn_plans(state, opponent)
        if not plans:
            return base_value
        worst_reply = min(
            self._player_perspective_reply_score(state, player, opponent, plan)
            for plan in plans
        )
        return (base_value * 0.45) + (worst_reply * 0.55)

    def _player_perspective_reply_score(self, state: GameState, player: int, opponent: int, plan: TurnPlan) -> float:
        after = apply_turn_plan(state, opponent, plan)
        return self.evaluate_state(after, player) - self.plan_adjustment(state, after, opponent, plan)

    def future_potential(self, state: GameState, player: int, viewer: int) -> float:
        total = 0.0
        for suit in SUITS:
            expedition = state.players[player].expeditions[suit]
            if not expedition:
                continue
            profile = suit_profile(expedition)
            expected_total = self.expected_expedition_total(state, player, viewer, suit, expedition)
            total += max(0.0, expected_total - float(profile["score"]))
        return total

    def reserve_potential(self, state: GameState, player: int, viewer: int) -> float:
        if player != viewer:
            return 0.0
        remaining_plays = self.simulate_remaining_plays(state, player)
        time_factor = max(0.2, min(1.0, remaining_plays / 5.0))
        total = 0.0
        for suit in SUITS:
            if state.players[player].expeditions[suit]:
                continue
            candidates = [card for card in state.players[player].hand if card.suit == suit]
            if not candidates:
                continue
            best = 0.0
            for card in candidates:
                opening = [card]
                expected = self.expected_expedition_total(state, player, viewer, suit, opening)
                margin = self.required_opening_margin(state, player, card, suit, opening)
                best = max(best, max(0.0, expected - margin))
            total += best * 0.22 * time_factor
        return total

    def expected_expedition_total(
        self,
        state: GameState,
        player: int,
        viewer: int,
        suit: str,
        expedition: list[Card],
    ) -> float:
        if not expedition:
            return 0.0
        cache_key = (
            player,
            viewer,
            suit,
            tuple(expedition),
            self.visible_state_key(state, viewer),
        )
        cached = self._expected_total_cache.get(cache_key)
        if cached is not None:
            return cached
        profile = suit_profile(expedition)
        visible = self.visible_cards(state, viewer)
        player_hand = set(state.players[player].hand) if player == viewer else set()
        hidden_hand_count = len(state.players[other_player(player)].hand) if player == viewer else len(state.players[player].hand)
        remaining_draws = self.simulate_remaining_deck_draws(state, player)
        unseen = [card for card in FULL_DECK if card.suit == suit and card not in visible]

        multiplier = 1.0 + int(profile["wagers"])
        if not bool(profile["has_number"]) and player == viewer:
            extra_wagers = sum(
                1
                for card in state.players[player].hand
                if card.suit == suit and card.is_wager and card not in expedition
            )
            support = self.known_support_count(
                state,
                player,
                suit,
                int(profile["last_rank"]),
                count_wagers=self.config.count_wagers_as_support,
            )
            support_factor = min(1.0, support / max(2.0, 2.0 + extra_wagers))
            multiplier += extra_wagers * self.config.extra_wager_weight * support_factor

        expected_sum = float(profile["sum"])
        expected_card_count = float(profile["count"])
        for card in unseen:
            if card.rank is None or card.rank <= int(profile["last_rank"]):
                continue
            p = self.card_play_probability(
                state=state,
                player=player,
                viewer=viewer,
                card=card,
                player_hand=player_hand,
                hidden_hand_count=hidden_hand_count,
                remaining_draws=remaining_draws,
                unseen_count=max(1, len(unseen)),
            )
            expected_sum += p * card.rank
            expected_card_count += p

        if expected_card_count >= 8.0:
            bonus_probability = 1.0
        elif expected_card_count <= 7.0:
            bonus_probability = 0.0
        else:
            bonus_probability = expected_card_count - 7.0
        total = ((expected_sum - 20.0) * multiplier) + (20.0 * bonus_probability)
        self._expected_total_cache[cache_key] = total
        return total

    def card_play_probability(
        self,
        state: GameState,
        player: int,
        viewer: int,
        card: Card,
        player_hand: set[Card],
        hidden_hand_count: int,
        remaining_draws: int,
        unseen_count: int,
    ) -> float:
        if card in player_hand:
            return 1.0
        depth = self.discard_depth(state, card)
        if depth >= 0:
            return max(0.05, 0.72 / (depth + 1))
        deck_count = len(state.deck)
        in_deck = deck_count / unseen_count
        in_hidden = hidden_hand_count / unseen_count
        from_deck = in_deck * min(1.0, remaining_draws / max(1.0, float(deck_count)))
        if player == viewer:
            release = self.opponent_release_chance(state, other_player(player), card)
            return min(1.0, from_deck + (in_hidden * release))
        hidden_play = self.opponent_hidden_hand_play_chance(state, player, card)
        return min(1.0, (in_hidden * hidden_play) + from_deck)

    def plan_adjustment(self, before: GameState, after: GameState, player: int, plan: TurnPlan) -> float:
        adjustment = 0.0
        card = plan.play.card
        if plan.play.kind == "play":
            adjustment += self.opening_adjustment(before, after, player, card)
            adjustment += self.unresolved_wager_adjustment(before, after, player, card)
            adjustment += self.lockout_adjustment(before, player, card)
        else:
            adjustment += self.discard_exposure_adjustment(before, after, player, card)
        if plan.draw and plan.draw.kind == "discard":
            adjustment += self.discard_draw_selection_adjustment(before, player, plan.draw.suit or "")
            adjustment += self.discard_draw_tempo_adjustment(before, player, plan.draw.suit or "")
        elif plan.draw and plan.draw.kind == "deck" and len(before.deck) <= self.config.deck_pressure_start:
            pressure = (self.config.deck_pressure_start - len(before.deck)) + 1
            adjustment += pressure * (self.config.deck_pressure_penalty * 0.45)
        return adjustment

    def opening_adjustment(self, before: GameState, after: GameState, player: int, card: Card) -> float:
        expedition_before = before.players[player].expeditions[card.suit]
        if expedition_before:
            return 0.0
        expedition_after = after.players[player].expeditions[card.suit]
        expected = self.expected_expedition_total(after, player, player, card.suit, expedition_after)
        margin = self.required_opening_margin(after, player, card, card.suit, expedition_after)
        adjustment = 0.0
        if card.rank is not None:
            hand_support = self.hand_number_support(before, player, card.suit, card.rank)
            required_hand_support = (
                self.config.opening_high_rank_support_target
                if card.rank >= 5
                else self.config.opening_low_rank_support_target
            )
            adjustment -= max(0, required_hand_support - hand_support) * self.config.opening_hand_support_penalty
            weak_commitments = self.weak_open_expedition_count(before, player)
            overflow = max(0, weak_commitments - self.config.commitment_free_expeditions + 1)
            adjustment -= overflow * self.config.commitment_penalty
            support_quality = self.opening_support_quality(before, player, card.suit, card.rank)
            required_quality = self.required_opening_support_quality(before, player, card)
            adjustment -= max(0.0, required_quality - support_quality) * self.config.opening_support_penalty
            if support_quality <= 0.0:
                adjustment -= self.config.zero_support_open_penalty
            if self.has_existing_progress_play(before, player):
                adjustment -= self.config.progress_play_penalty * 0.7
        if expected < margin:
            adjustment -= (margin - expected) * 2.2
            return adjustment
        adjustment += min(4.0, (expected - margin) * 0.18)
        return adjustment

    def unresolved_wager_adjustment(self, before: GameState, after: GameState, player: int, card: Card) -> float:
        before_expedition = before.players[player].expeditions[card.suit]
        before_profile = suit_profile(before_expedition)
        if bool(before_profile["has_number"]):
            return 0.0
        if card.rank is not None and int(before_profile["wagers"]) == 0:
            return 0.0

        expedition = after.players[player].expeditions[card.suit]
        profile = suit_profile(expedition)
        expected = self.expected_expedition_total(after, player, player, card.suit, expedition)
        remaining_plays = self.simulate_remaining_plays(after, player)
        support = self.known_support_count(after, player, card.suit, int(profile["last_rank"]), count_wagers=False)
        hand_support = self.hand_number_support(after, player, card.suit, int(profile["last_rank"]))
        late_factor = 2.4 if remaining_plays <= 2 else 1.9 if remaining_plays <= 4 else 1.45 if remaining_plays <= 6 else 1.0

        penalty = 0.0
        if not bool(profile["has_number"]):
            required_support = 2 + (int(profile["wagers"]) - 1)
            penalty += max(0, required_support - support) * self.config.wager_open_penalty
            penalty += int(profile["wagers"]) * self.config.unresolved_wager_penalty
            if card.is_wager:
                hand_shortfall = max(0, self.config.wager_hand_support_target - hand_support)
                penalty += hand_shortfall * self.config.wager_hand_support_penalty
                if self.has_existing_progress_play(before, player):
                    penalty += self.config.progress_play_penalty
            if hand_support == 0:
                penalty += 10.0 + (self.config.wager_hand_support_penalty * 0.75)
        else:
            minimum_expected = 6.0 + (int(profile["wagers"]) * 4.0)
            if card.rank is not None and card.rank >= 8:
                minimum_expected += self.config.high_card_penalty
            penalty += max(0.0, minimum_expected - expected)
            if support == 0:
                penalty += 8.0
            if card.rank is not None and card.rank >= 8:
                penalty += self.config.high_card_penalty
                if support + hand_support <= 1:
                    penalty += 14.0
        return -(penalty * late_factor)

    def lockout_adjustment(self, before: GameState, player: int, card: Card) -> float:
        if card.rank is None:
            return 0.0
        expedition = before.players[player].expeditions[card.suit]
        profile = suit_profile(expedition)
        lower_candidates = [
            rank
            for rank in range(int(profile["last_rank"]) + 1, card.rank)
            if self.card_still_live(before, player, card.suit, rank)
        ]
        if not lower_candidates:
            return 0.0
        lockout_value = 0.0
        for rank in lower_candidates:
            p = self.rank_access_probability(before, player, card.suit, rank)
            lockout_value += p * rank
        penalty = (lockout_value * self.config.lockout_penalty) + (len(lower_candidates) * 1.4)
        if card.rank >= 8:
            penalty += self.config.high_card_penalty
        return -penalty

    def discard_exposure_adjustment(self, before: GameState, after: GameState, player: int, card: Card) -> float:
        opponent = other_player(player)
        before_threat = self.top_discard_exposure(before, opponent, player, card.suit)
        after_threat = self.top_discard_exposure(after, opponent, player, card.suit)
        return (before_threat - after_threat) * self.config.discard_exposure_scale

    def has_existing_progress_play(self, state: GameState, player: int) -> bool:
        for card in state.players[player].hand:
            if card.rank is None:
                continue
            expedition = state.players[player].expeditions[card.suit]
            if expedition and can_play_to_expedition(expedition, card):
                return True
        return False

    def weak_open_expedition_count(self, state: GameState, player: int) -> int:
        count = 0
        for suit in SUITS:
            expedition = state.players[player].expeditions[suit]
            if not expedition:
                continue
            profile = suit_profile(expedition)
            if int(profile["count"]) <= 2 or int(profile["score"]) <= 0:
                count += 1
        return count

    def discard_draw_selection_adjustment(self, before: GameState, player: int, suit: str) -> float:
        pile = before.discard_piles[suit]
        if not pile:
            return 0.0
        card = pile[-1]
        own_expedition = before.players[player].expeditions[suit]
        immediate_use = can_play_to_expedition(own_expedition, card)
        denial = self.opponent_need_value(before, other_player(player), card)
        support = self.card_usefulness_for_hand(before, player, card)
        strength = max(denial, support)
        if immediate_use:
            return 0.0
        penalty = 0.0
        if strength < 0.55:
            penalty += self.config.discard_draw_base_penalty
        elif strength < 0.72 and len(before.deck) > self.config.deck_pressure_start:
            penalty += self.config.discard_draw_base_penalty * 0.5
        if not own_expedition and self.weak_open_expedition_count(before, player) >= self.config.commitment_free_expeditions:
            penalty += self.config.commitment_penalty * 0.6
        return -penalty

    def discard_draw_tempo_adjustment(self, before: GameState, player: int, suit: str) -> float:
        if before.final_turns_remaining > 0 or len(before.deck) > self.config.deck_pressure_start:
            return 0.0
        pile = before.discard_piles[suit]
        if not pile:
            return 0.0
        card = pile[-1]
        own_expedition = before.players[player].expeditions[suit]
        immediate_use = can_play_to_expedition(own_expedition, card)
        denial = self.opponent_need_value(before, other_player(player), card)
        support = self.card_usefulness_for_hand(before, player, card)
        if immediate_use or denial >= 0.72 or support >= 0.72:
            return 0.0
        pressure = (self.config.deck_pressure_start - len(before.deck)) + 1
        return -(pressure * self.config.deck_pressure_penalty)

    def required_opening_margin(self, state: GameState, player: int, card: Card, suit: str, expedition: list[Card]) -> float:
        profile = suit_profile(expedition)
        remaining_plays = self.simulate_remaining_plays(state, player)
        number_support = self.known_support_count(
            state,
            player,
            suit,
            int(profile["last_rank"]),
            count_wagers=self.config.count_wagers_as_support,
        )
        margin = self.config.opening_margin
        if card.rank is None:
            if number_support < 2:
                margin += 10.0
            if number_support < 3:
                margin += 8.0
        else:
            if card.rank >= 8:
                margin += 4.0
            if card.rank >= 9:
                margin += 3.0
            if number_support < 2:
                margin += 5.0
        if remaining_plays <= 3:
            margin += 11.0
        elif remaining_plays <= 5:
            margin += 6.0
        return margin

    def opening_support_quality(self, state: GameState, player: int, suit: str, last_rank: int) -> float:
        quality = 0.0
        for card in state.players[player].hand:
            if card.suit != suit or card.rank is None or card.rank <= last_rank:
                continue
            quality += self.support_card_weight(card.rank - last_rank, card.rank)
        for card in state.discard_piles[suit][-2:]:
            if card.rank is None or card.rank <= last_rank:
                continue
            quality += self.support_card_weight(card.rank - last_rank, card.rank) * 0.35
        return quality

    def required_opening_support_quality(self, state: GameState, player: int, card: Card) -> float:
        target = self.config.opening_support_quality_target
        if card.rank is not None:
            if card.rank >= 5:
                target += 0.45
            if card.rank >= 7:
                target += 0.35
            if card.rank >= 9:
                target += 0.25
        remaining_plays = self.simulate_remaining_plays(state, player)
        if remaining_plays <= 3:
            target += 0.8
        elif remaining_plays <= 5:
            target += 0.45
        return target

    def support_card_weight(self, gap: int, rank: int) -> float:
        if gap <= 2:
            weight = 1.0
        elif gap <= 4:
            weight = 0.72
        elif gap <= 6:
            weight = 0.45
        else:
            weight = 0.28
        if rank >= 9:
            weight *= 0.8
        elif rank >= 8:
            weight *= 0.9
        return weight

    def unfinished_expedition_penalty(self, state: GameState, player: int, viewer: int) -> float:
        remaining_plays = self.simulate_remaining_plays(state, player)
        late_factor = 2.2 if remaining_plays <= 2 else 1.75 if remaining_plays <= 4 else 1.3 if remaining_plays <= 6 else 1.0
        total = 0.0
        for suit in SUITS:
            expedition = state.players[player].expeditions[suit]
            if not expedition:
                continue
            profile = suit_profile(expedition)
            expected = self.expected_expedition_total(state, player, viewer, suit, expedition)
            support = self.known_support_count(state, player, suit, int(profile["last_rank"]), count_wagers=False)
            hand_support = self.hand_number_support(state, player, suit, int(profile["last_rank"]))
            if not bool(profile["has_number"]):
                required_support = 2 + (int(profile["wagers"]) - 1)
                penalty = max(0.0, -float(profile["score"])) * 0.25
                penalty += max(0, required_support - support) * 5.0
                if hand_support == 0:
                    penalty += 8.0
                if expected < 0.0:
                    penalty += (-expected) * 0.35
                total += penalty * late_factor
            elif expected < 0.0:
                total += (-expected) * 0.18 * late_factor
        return total

    def total_discard_threat(self, state: GameState, player: int, viewer: int) -> float:
        return sum(self.top_discard_exposure(state, player, viewer, suit) for suit in SUITS)

    def top_discard_exposure(self, state: GameState, receiver: int, viewer: int, suit: str) -> float:
        pile = state.discard_piles[suit]
        if not pile:
            return 0.0
        card = pile[-1]
        expedition = state.players[receiver].expeditions[suit]
        if not can_play_to_expedition(expedition, card):
            return 0.0
        future_before = self.future_potential(state, receiver, viewer)
        taken = self.state_with_top_discard_in_hand(state, receiver, suit)
        future_after = self.future_potential(taken, receiver, viewer)
        future_gain = max(0.0, future_after - future_before)
        score_gain = self.playable_card_score_gain(state, receiver, card)
        urgency = self.opponent_need_value(state, receiver, card)
        return (future_gain * 4.0) + (score_gain * 0.6) + (((card.rank or 4) * urgency) * self.config.dangerous_discard_scale)

    def playable_card_score_gain(self, state: GameState, player: int, card: Card) -> float:
        expedition = state.players[player].expeditions[card.suit]
        return float(score_expedition(expedition + [card]) - score_expedition(expedition))

    def card_usefulness_for_hand(self, state: GameState, player: int, card: Card) -> float:
        expedition = state.players[player].expeditions[card.suit]
        profile = suit_profile(expedition)
        if card.rank is None:
            if bool(profile["has_number"]):
                return 0.0
            support = self.known_support_count(state, player, card.suit, int(profile["last_rank"]), count_wagers=False)
            return 0.22 if support >= 3 else 0.12 if support >= 2 else 0.03
        if card.rank <= int(profile["last_rank"]):
            return 0.0
        if expedition:
            return 0.78
        if card.rank <= 4:
            return 0.68
        if card.rank <= 7:
            return 0.42
        return 0.18

    def opponent_need_value(self, state: GameState, player: int, card: Card) -> float:
        expedition = state.players[player].expeditions[card.suit]
        profile = suit_profile(expedition)
        if card.rank is None:
            return 0.55 if not bool(profile["has_number"]) else 0.0
        if card.rank <= int(profile["last_rank"]):
            return 0.0
        if int(profile["wagers"]) > 0:
            return 0.95
        if expedition:
            return 0.72
        if card.rank <= 4:
            return 0.42
        return 0.34

    def opponent_release_chance(self, state: GameState, opponent: int, card: Card) -> float:
        expedition = state.players[opponent].expeditions[card.suit]
        profile = suit_profile(expedition)
        if card.rank is None:
            return 0.03
        if int(profile["last_rank"]) >= card.rank:
            return 0.28
        if int(profile["wagers"]) > 0:
            return 0.05
        if expedition:
            return 0.09
        if card.rank >= 8:
            return 0.12
        return 0.08

    def opponent_hidden_hand_play_chance(self, state: GameState, player: int, card: Card) -> float:
        expedition = state.players[player].expeditions[card.suit]
        profile = suit_profile(expedition)
        if card.rank is None:
            return 0.42 if not bool(profile["has_number"]) else 0.0
        if card.rank <= int(profile["last_rank"]):
            return 0.0
        if int(profile["wagers"]) > 0:
            return 0.82
        if expedition:
            return 0.68
        if card.rank <= 4:
            return 0.46
        return 0.32

    def known_support_count(self, state: GameState, player: int, suit: str, last_rank: int, count_wagers: bool) -> int:
        hand_support = sum(
            1
            for card in state.players[player].hand
            if card.suit == suit and (
                (count_wagers and card.rank is None) or
                (card.rank is not None and card.rank > last_rank)
            )
        )
        discard_support = sum(
            1
            for card in state.discard_piles[suit][-2:]
            if (count_wagers and card.rank is None) or (card.rank is not None and card.rank > last_rank)
        )
        return hand_support + discard_support

    def hand_number_support(self, state: GameState, player: int, suit: str, last_rank: int) -> int:
        return sum(1 for card in state.players[player].hand if card.suit == suit and card.rank is not None and card.rank > last_rank)

    def discard_depth(self, state: GameState, card: Card) -> int:
        pile = state.discard_piles[card.suit]
        for index, candidate in enumerate(pile):
            if candidate == card:
                return len(pile) - index - 1
        return -1

    def card_still_live(self, state: GameState, player: int, suit: str, rank: int) -> bool:
        target = Card(suit=suit, rank=rank, serial=0)
        opponent = other_player(player)
        if target in state.players[player].expeditions[suit]:
            return False
        if target in state.players[opponent].expeditions[suit]:
            return False
        expedition = state.players[player].expeditions[suit]
        profile = suit_profile(expedition)
        return rank > int(profile["last_rank"])

    def rank_access_probability(self, state: GameState, player: int, suit: str, rank: int) -> float:
        target = Card(suit=suit, rank=rank, serial=0)
        if target in state.players[player].hand:
            return 1.0
        depth = self.discard_depth(state, target)
        if depth >= 0:
            return max(0.08, 0.7 / (depth + 1))
        visible = self.visible_cards(state, player)
        unseen = [card for card in FULL_DECK if card not in visible]
        if target not in unseen:
            return 0.0
        deck_share = len(state.deck) / max(1.0, float(len(unseen)))
        draw_share = min(1.0, self.simulate_remaining_deck_draws(state, player) / max(1.0, float(len(state.deck) or 1)))
        return deck_share * draw_share

    def visible_state_key(self, state: GameState, viewer: int) -> tuple:
        return (
            viewer,
            tuple(state.players[viewer].hand),
            tuple(tuple(state.players[player].expeditions[suit]) for player in (1, 2) for suit in SUITS),
            tuple(tuple(state.discard_piles[suit]) for suit in SUITS),
            len(state.deck),
            len(state.players[1].hand),
            len(state.players[2].hand),
            state.turn_player,
            state.phase,
            state.final_turns_remaining,
        )

    def visible_cards(self, state: GameState, viewer: int) -> set[Card]:
        key = self.visible_state_key(state, viewer)
        cached = self._visible_cards_cache.get(key)
        if cached is not None:
            return cached
        visible = all_visible_cards(state, viewer)
        self._visible_cards_cache[key] = visible
        return visible

    def simulate_remaining_plays(self, state: GameState, player: int) -> int:
        key = (player, state.phase, state.turn_player, len(state.deck), state.final_turns_remaining)
        cached = self._remaining_plays_cache.get(key)
        if cached is not None:
            return cached
        turn = state.turn_player
        phase = state.phase
        deck_count = len(state.deck)
        final_turns = state.final_turns_remaining
        plays = 0
        guard = (deck_count + final_turns + 8) * 6
        while guard > 0:
            guard -= 1
            if phase == "play":
                if turn == player:
                    plays += 1
                if final_turns > 0:
                    final_turns -= 1
                    if final_turns == 0:
                        break
                    turn = other_player(turn)
                else:
                    phase = "draw"
            else:
                if deck_count > 0:
                    deck_count -= 1
                    turn = other_player(turn)
                    phase = "play"
                    if deck_count == 0:
                        final_turns = 2
                else:
                    turn = other_player(turn)
                    phase = "play"
        self._remaining_plays_cache[key] = plays
        return plays

    def simulate_remaining_deck_draws(self, state: GameState, player: int) -> int:
        key = (player, state.phase, state.turn_player, len(state.deck), state.final_turns_remaining)
        cached = self._remaining_deck_draws_cache.get(key)
        if cached is not None:
            return cached
        turn = state.turn_player
        phase = state.phase
        deck_count = len(state.deck)
        final_turns = state.final_turns_remaining
        draws = 0
        guard = (deck_count + final_turns + 8) * 6
        while guard > 0:
            guard -= 1
            if phase == "play":
                if final_turns > 0:
                    final_turns -= 1
                    if final_turns == 0:
                        break
                    turn = other_player(turn)
                else:
                    phase = "draw"
            else:
                if deck_count <= 0:
                    turn = other_player(turn)
                    phase = "play"
                    continue
                if turn == player:
                    draws += 1
                deck_count -= 1
                turn = other_player(turn)
                phase = "play"
                if deck_count == 0:
                    final_turns = 2
        self._remaining_deck_draws_cache[key] = draws
        return draws

    def state_with_top_discard_in_hand(self, state: GameState, player: int, suit: str) -> GameState:
        pile = state.discard_piles[suit]
        if not pile:
            return state
        next_state = state.copy()
        card = next_state.discard_piles[suit].pop()
        next_state.players[player].hand.append(card)
        return next_state
