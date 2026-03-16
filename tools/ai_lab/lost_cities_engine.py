from __future__ import annotations

from dataclasses import dataclass, field, replace
from functools import lru_cache
import random
from typing import Iterable

SUITS = ("yellow", "blue", "white", "green", "red")
RANKS = tuple(range(2, 11))
WAGERS_PER_SUIT = 3
HAND_SIZE = 8


@dataclass(frozen=True, slots=True)
class Card:
    suit: str
    rank: int | None
    serial: int = 0

    @property
    def is_wager(self) -> bool:
        return self.rank is None

    @property
    def id(self) -> str:
        if self.rank is None:
            return f"{self.suit}_wager_{self.serial}"
        return f"{self.suit}_{self.rank}"

    def short(self) -> str:
        if self.rank is None:
            return f"{self.suit[:1].upper()}W"
        return f"{self.suit[:1].upper()}{self.rank}"


@dataclass(slots=True)
class PlayerState:
    hand: list[Card] = field(default_factory=list)
    expeditions: dict[str, list[Card]] = field(default_factory=lambda: {suit: [] for suit in SUITS})

    def copy(self) -> "PlayerState":
        return PlayerState(
            hand=list(self.hand),
            expeditions={suit: list(cards) for suit, cards in self.expeditions.items()},
        )


@dataclass(frozen=True, slots=True)
class PlayAction:
    kind: str
    card: Card

    def label(self) -> str:
        return f"{self.kind}:{self.card.id}"


@dataclass(frozen=True, slots=True)
class DrawAction:
    kind: str
    suit: str | None = None

    def label(self) -> str:
        if self.kind == "deck":
            return "draw:deck"
        return f"draw:discard:{self.suit}"


@dataclass(frozen=True, slots=True)
class TurnPlan:
    play: PlayAction
    draw: DrawAction | None

    def label(self) -> str:
        if self.draw is None:
            return self.play.label()
        return f"{self.play.label()} -> {self.draw.label()}"


@dataclass(slots=True)
class GameState:
    players: dict[int, PlayerState]
    deck: list[Card]
    discard_piles: dict[str, list[Card]]
    turn_player: int = 1
    phase: str = "play"
    just_discarded: Card | None = None
    final_turns_remaining: int = 0
    winner: int | None = None
    history: list[str] = field(default_factory=list)

    def copy(self) -> "GameState":
        return GameState(
            players={player: state.copy() for player, state in self.players.items()},
            deck=list(self.deck),
            discard_piles={suit: list(cards) for suit, cards in self.discard_piles.items()},
            turn_player=self.turn_player,
            phase=self.phase,
            just_discarded=self.just_discarded,
            final_turns_remaining=self.final_turns_remaining,
            winner=self.winner,
            history=list(self.history),
        )

    @property
    def finished(self) -> bool:
        return self.winner is not None


def build_deck() -> list[Card]:
    deck: list[Card] = []
    for suit in SUITS:
        for serial in range(1, WAGERS_PER_SUIT + 1):
            deck.append(Card(suit=suit, rank=None, serial=serial))
        for rank in RANKS:
            deck.append(Card(suit=suit, rank=rank, serial=0))
    return deck


FULL_DECK = tuple(build_deck())


def deal_game(seed: int) -> GameState:
    rng = random.Random(seed)
    deck = list(FULL_DECK)
    rng.shuffle(deck)
    players = {
        1: PlayerState(hand=sorted(deck[:HAND_SIZE], key=hand_sort_key)),
        2: PlayerState(hand=sorted(deck[HAND_SIZE : HAND_SIZE * 2], key=hand_sort_key)),
    }
    remaining = deck[HAND_SIZE * 2 :]
    return GameState(
        players=players,
        deck=remaining,
        discard_piles={suit: [] for suit in SUITS},
    )


def hand_sort_key(card: Card) -> tuple[int, int]:
    suit_index = SUITS.index(card.suit)
    rank_value = 0 if card.rank is None else card.rank
    return suit_index, rank_value


@lru_cache(maxsize=16384)
def _score_expedition_cached(cards: tuple[Card, ...]) -> int:
    if not cards:
        return 0
    wagers = sum(1 for card in cards if card.rank is None)
    total = sum(card.rank or 0 for card in cards)
    score = (total - 20) * (wagers + 1)
    if len(cards) >= 8:
        score += 20
    return score


def score_expedition(cards: Iterable[Card]) -> int:
    return _score_expedition_cached(tuple(cards))


def score_player(player_state: PlayerState) -> int:
    return sum(score_expedition(cards) for cards in player_state.expeditions.values())


@lru_cache(maxsize=16384)
def _suit_profile_cached(cards: tuple[Card, ...]) -> dict[str, int | bool]:
    wagers = sum(1 for card in cards if card.rank is None)
    numbers = tuple(card.rank for card in cards if card.rank is not None)
    return {
        "wagers": wagers,
        "sum": sum(numbers),
        "count": len(cards),
        "last_rank": max(numbers) if numbers else 0,
        "opened": bool(cards),
        "has_number": bool(numbers),
        "score": score_expedition(cards),
    }


def suit_profile(cards: list[Card]) -> dict[str, int | bool]:
    return _suit_profile_cached(tuple(cards))


def can_play_to_expedition(expedition: list[Card], card: Card) -> bool:
    profile = suit_profile(expedition)
    if card.rank is None:
        return not profile["has_number"]
    return card.rank > profile["last_rank"]


def legal_play_actions(state: GameState, player: int) -> list[PlayAction]:
    if state.finished or state.phase != "play" or state.turn_player != player:
        return []
    actions: list[PlayAction] = []
    player_state = state.players[player]
    for card in player_state.hand:
        expedition = player_state.expeditions[card.suit]
        if can_play_to_expedition(expedition, card):
            actions.append(PlayAction(kind="play", card=card))
        actions.append(PlayAction(kind="discard", card=card))
    return actions


def legal_draw_actions(state: GameState, player: int) -> list[DrawAction]:
    if state.finished or state.phase != "draw" or state.turn_player != player:
        return []
    if state.final_turns_remaining > 0:
        return []
    actions: list[DrawAction] = []
    if state.deck:
        actions.append(DrawAction(kind="deck"))
    for suit in SUITS:
        pile = state.discard_piles[suit]
        if not pile:
            continue
        if state.just_discarded and pile[-1] == state.just_discarded:
            continue
        actions.append(DrawAction(kind="discard", suit=suit))
    return actions


def legal_turn_plans(state: GameState, player: int) -> list[TurnPlan]:
    plans: list[TurnPlan] = []
    for play in legal_play_actions(state, player):
        after_play = apply_play_action(state, player, play)
        if after_play.final_turns_remaining > 0 or after_play.finished:
            plans.append(TurnPlan(play=play, draw=None))
            continue
        for draw in legal_draw_actions(after_play, player):
            plans.append(TurnPlan(play=play, draw=draw))
    return plans


def apply_turn_plan(state: GameState, player: int, plan: TurnPlan) -> GameState:
    after_play = apply_play_action(state, player, plan.play)
    if plan.draw is None:
        return after_play
    return apply_draw_action(after_play, player, plan.draw)


def apply_play_action(state: GameState, player: int, action: PlayAction) -> GameState:
    next_state = state.copy()
    if next_state.turn_player != player or next_state.phase != "play" or next_state.finished:
        raise ValueError("Illegal play phase transition.")

    player_state = next_state.players[player]
    card = remove_card_from_hand(player_state, action.card)
    if action.kind == "play":
        expedition = player_state.expeditions[card.suit]
        if not can_play_to_expedition(expedition, card):
            raise ValueError("Illegal expedition play.")
        expedition.append(card)
        next_state.just_discarded = None
        next_state.history.append(f"P{player} play {card.id}")
    elif action.kind == "discard":
        next_state.discard_piles[card.suit].append(card)
        next_state.just_discarded = card
        next_state.history.append(f"P{player} discard {card.id}")
    else:
        raise ValueError(f"Unknown play action {action.kind}.")

    if next_state.final_turns_remaining > 0:
        next_state.final_turns_remaining -= 1
        if next_state.final_turns_remaining == 0:
            finish_game(next_state)
        else:
            next_state.turn_player = other_player(player)
            next_state.phase = "play"
        return next_state

    next_state.phase = "draw"
    return next_state


def apply_draw_action(state: GameState, player: int, action: DrawAction) -> GameState:
    next_state = state.copy()
    if next_state.turn_player != player or next_state.phase != "draw" or next_state.finished:
        raise ValueError("Illegal draw phase transition.")
    if next_state.final_turns_remaining > 0:
        raise ValueError("No draw allowed during final turns.")

    player_state = next_state.players[player]
    if action.kind == "deck":
        if not next_state.deck:
            raise ValueError("Cannot draw from empty deck.")
        drawn = next_state.deck.pop(0)
        player_state.hand.append(drawn)
        player_state.hand.sort(key=hand_sort_key)
        next_state.history.append(f"P{player} draw deck {drawn.id}")
        if not next_state.deck:
            next_state.final_turns_remaining = 2
    elif action.kind == "discard":
        suit = action.suit or ""
        pile = next_state.discard_piles[suit]
        if not pile:
            raise ValueError("Cannot draw from empty discard.")
        if next_state.just_discarded is not None and pile[-1] == next_state.just_discarded:
            raise ValueError("Cannot redraw the card just discarded.")
        drawn = pile.pop()
        player_state.hand.append(drawn)
        player_state.hand.sort(key=hand_sort_key)
        next_state.history.append(f"P{player} draw discard {drawn.id}")
    else:
        raise ValueError(f"Unknown draw action {action.kind}.")

    next_state.just_discarded = None
    next_state.turn_player = other_player(player)
    next_state.phase = "play"
    return next_state


def finish_game(state: GameState) -> None:
    score_1 = score_player(state.players[1])
    score_2 = score_player(state.players[2])
    if score_1 > score_2:
        state.winner = 1
    elif score_2 > score_1:
        state.winner = 2
    else:
        state.winner = 0
    state.phase = "play"
    state.just_discarded = None
    state.history.append(f"game over {score_1}:{score_2}")


def remove_card_from_hand(player_state: PlayerState, card: Card) -> Card:
    for index, candidate in enumerate(player_state.hand):
        if candidate == card:
            return player_state.hand.pop(index)
    raise ValueError(f"Card {card.id} not found in hand.")


def other_player(player: int) -> int:
    return 2 if player == 1 else 1


def all_visible_cards(state: GameState, viewer: int) -> set[Card]:
    visible: set[Card] = set(state.players[viewer].hand)
    for player_state in state.players.values():
        for cards in player_state.expeditions.values():
            visible.update(cards)
    for pile in state.discard_piles.values():
        visible.update(pile)
    return visible


def unseen_cards(state: GameState, viewer: int) -> list[Card]:
    visible = all_visible_cards(state, viewer)
    return [card for card in FULL_DECK if card not in visible]


def render_state(state: GameState, viewer: int) -> str:
    lines = [
        f"turn=P{state.turn_player} phase={state.phase} deck={len(state.deck)} final={state.final_turns_remaining}",
        f"score P1={score_player(state.players[1])} P2={score_player(state.players[2])}",
        f"hand {', '.join(card.short() for card in sorted(state.players[viewer].hand, key=hand_sort_key))}",
    ]
    for player in (1, 2):
        lines.append(f"P{player} expeditions:")
        for suit in SUITS:
            cards = " ".join(card.short() for card in state.players[player].expeditions[suit]) or "-"
            lines.append(f"  {suit:<6} {cards}")
    lines.append("discards:")
    for suit in SUITS:
        pile = " ".join(card.short() for card in state.discard_piles[suit]) or "-"
        lines.append(f"  {suit:<6} {pile}")
    return "\n".join(lines)
