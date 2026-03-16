from dataclasses import dataclass
from collections import Counter
from typing import Dict, List, Optional, Tuple
import math

"""
Lost Cities heuristic AI (fast visible-state evaluator)

Idea
----
For each legal move:
    1) apply the play/discard action
    2) if drawing from deck, compute the exact mean heuristic value over all unseen card copies
       (uniform over unknown copies)
    3) if drawing from discard, evaluate the exact resulting visible state
    4) choose the move with the highest expected value

This is NOT a full search. It is a zero-ply / one-state evaluator with hidden-information
approximation. The opponent is modeled by:
    - their visible expeditions
    - their likely access to top discard cards
    - a conservative probability that unseen useful cards are already in their hand or will be drawn

That makes it much faster than rollouts, while still capturing the main Lost Cities ideas:
    - opening risk
    - wager risk
    - lockout cost (naturally, because playing a high card removes lower cards from future EV)
    - discard danger for the opponent
    - denial value when taking a discard they want
"""

# ---------------------------------------------------------------------------
# Game constants
# ---------------------------------------------------------------------------

COLORS: Tuple[str, ...] = ("R", "G", "B", "Y", "W")
WAGER = 0

ME = 0
OPP = 1

# Heuristic constants. These are the first numbers to tune if you want different style.
TOP_NOW_WEIGHT = 0.95     # top discard, and this player moves next
TOP_LATER_WEIGHT = 0.22   # top discard, but opponent moves before this player

UNSEEN_SELF_SCALE = 0.72  # how much to trust "I may still draw this unseen card"
UNSEEN_OPP_SCALE = 0.80   # opponent unseen access; slightly higher due hidden hand

START_RISK = 5.0          # penalty for starting a brand-new expedition
WAGER_COMMIT_RISK = 3.5   # extra penalty per wager before numbers are actually there

BONUS_START = 6.0         # expected card count where 8-card bonus starts to matter
BONUS_FULL = 8.0


# ---------------------------------------------------------------------------
# Basic model
# ---------------------------------------------------------------------------

@dataclass(frozen=True, order=True)
class Card:
    color: str
    rank: int  # 0 = wager, 2..10 = number

    def __str__(self) -> str:
        return f"{self.color}{'W' if self.rank == 0 else self.rank}"


@dataclass
class Expedition:
    wagers: int = 0
    numbers: Tuple[int, ...] = ()

    @property
    def started(self) -> bool:
        return self.wagers > 0 or bool(self.numbers)

    @property
    def last(self) -> int:
        return self.numbers[-1] if self.numbers else 0

    @property
    def sum_numbers(self) -> int:
        return sum(self.numbers)

    @property
    def count(self) -> int:
        return self.wagers + len(self.numbers)

    def copy(self) -> "Expedition":
        return Expedition(self.wagers, tuple(self.numbers))


@dataclass
class VisibleState:
    """
    Visible information from *my* perspective.

    next_player:
        ME  -> I am about to move
        OPP -> opponent is about to move
    """
    my_hand: List[Card]
    my_expeditions: Dict[str, Expedition]
    opp_expeditions: Dict[str, Expedition]
    discards: Dict[str, List[Card]]   # top card is discards[color][-1]
    deck_size: int
    opp_hand_size: int = 8
    next_player: int = ME

    def copy(self) -> "VisibleState":
        return VisibleState(
            my_hand=list(self.my_hand),
            my_expeditions={c: e.copy() for c, e in self.my_expeditions.items()},
            opp_expeditions={c: e.copy() for c, e in self.opp_expeditions.items()},
            discards={c: list(pile) for c, pile in self.discards.items()},
            deck_size=self.deck_size,
            opp_hand_size=self.opp_hand_size,
            next_player=self.next_player,
        )


@dataclass(frozen=True)
class Move:
    action: str          # "play" or "discard"
    hand_index: int
    draw_source: Optional[str]  # "deck", one of COLORS, or None for final play-only turns

    def __str__(self) -> str:
        return f"{self.action}[{self.hand_index}] then draw {self.draw_source}"


# ---------------------------------------------------------------------------
# Rules / deck helpers
# ---------------------------------------------------------------------------

def full_deck_counter() -> Counter[Card]:
    deck = Counter()
    for color in COLORS:
        deck[Card(color, WAGER)] = 3
        for rank in range(2, 11):
            deck[Card(color, rank)] += 1
    return deck


def score_expedition(exp: Expedition) -> int:
    if not exp.started:
        return 0
    score = (exp.sum_numbers - 20) * (1 + exp.wagers)
    if exp.count >= 8:
        score += 20
    return score


def exact_score_diff(state: VisibleState) -> int:
    my_score = sum(score_expedition(exp) for exp in state.my_expeditions.values())
    opp_score = sum(score_expedition(exp) for exp in state.opp_expeditions.values())
    return my_score - opp_score


def legal_play(card: Card, exp: Expedition) -> bool:
    if card.rank == WAGER:
        return len(exp.numbers) == 0
    return card.rank > exp.last


def visible_counter(state: VisibleState) -> Counter[Card]:
    seen = Counter(state.my_hand)

    for exps in (state.my_expeditions, state.opp_expeditions):
        for color, exp in exps.items():
            if exp.wagers:
                seen[Card(color, WAGER)] += exp.wagers
            for rank in exp.numbers:
                seen[Card(color, rank)] += 1

    for pile in state.discards.values():
        seen.update(pile)

    return seen


def unseen_counter(state: VisibleState) -> Counter[Card]:
    unseen = full_deck_counter()
    seen = visible_counter(state)

    for card, n in seen.items():
        unseen[card] -= n
        if unseen[card] <= 0:
            unseen.pop(card, None)

    return unseen


def top_discard(state: VisibleState, color: str) -> Optional[Card]:
    pile = state.discards[color]
    return pile[-1] if pile else None


# ---------------------------------------------------------------------------
# Heuristic evaluator
# ---------------------------------------------------------------------------

def future_draws_for_player(state: VisibleState, player: int) -> int:
    """
    Approximation: remaining deck draws split almost evenly.
    The next player gets the extra draw when deck_size is odd.
    """
    if state.next_player == player:
        return math.ceil(state.deck_size / 2)
    return math.floor(state.deck_size / 2)


def unseen_access_prob(state: VisibleState, player: int, unseen: Counter[Card]) -> float:
    """
    Approximate probability that a *single unseen specific card* becomes available
    to the given player before the game ends.

    For ME:
        only future deck draws matter, because my hand is already known separately.

    For OPP:
        the card might already be in their hidden hand OR they may draw it later.
    """
    total_unknown = sum(unseen.values())
    if total_unknown <= 0:
        return 0.0

    future_draws = future_draws_for_player(state, player)

    if player == ME:
        return min(1.0, UNSEEN_SELF_SCALE * future_draws / total_unknown)

    return min(0.90, UNSEEN_OPP_SCALE * (state.opp_hand_size + future_draws) / total_unknown)


def my_hand_access_weight(state: VisibleState) -> float:
    """
    A useful card in hand is valuable, but slightly less valuable than being already
    locked onto the board. This softly rewards actually playing strong cards instead
    of treating hand and board as identical.
    """
    future_draws = future_draws_for_player(state, ME)
    if future_draws <= 0:
        return 0.0
    return 0.45 + 0.50 * min(1.0, future_draws / 10.0)


def bonus_prob(expected_count: float) -> float:
    """
    Smooth approximation of 'how likely is the 8-card bonus'.
    """
    if expected_count <= BONUS_START:
        return 0.0
    if expected_count >= BONUS_FULL:
        return 1.0
    return (expected_count - BONUS_START) / (BONUS_FULL - BONUS_START)


def extra_visible_wagers(state: VisibleState, player: int, color: str, exp: Expedition) -> int:
    """
    Count only wagers that are already fairly accessible.
    We intentionally ignore fully unseen wagers so the bot stays conservative.
    """
    if exp.numbers:
        return 0

    count = 0

    if player == ME:
        count += sum(1 for c in state.my_hand if c == Card(color, WAGER))

    top = top_discard(state, color)
    if top == Card(color, WAGER) and state.next_player == player:
        count += 1

    return max(0, min(3 - exp.wagers, count))


def expected_playable_numbers(
    state: VisibleState,
    player: int,
    color: str,
    exp: Expedition,
    unseen: Counter[Card],
) -> List[Tuple[int, float]]:
    """
    Returns [(rank, accessibility_weight), ...] for ranks still playable in this expedition.

    Important property:
    if you play a high card now, lower numbers drop out of the future EV automatically.
    That means lockout cost is handled naturally by the evaluator.
    """
    result: List[Tuple[int, float]] = []

    p_unseen = unseen_access_prob(state, player, unseen)
    player_moves_next = (state.next_player == player)
    top = top_discard(state, color)
    my_hand_counter = Counter(state.my_hand) if player == ME else Counter()

    for rank in range(max(2, exp.last + 1), 11):
        card = Card(color, rank)
        weight = 0.0

        if player == ME and my_hand_counter[card] > 0:
            weight = my_hand_access_weight(state)
        elif top == card:
            weight = TOP_NOW_WEIGHT if player_moves_next else TOP_LATER_WEIGHT
        elif unseen[card] > 0:
            weight = p_unseen

        if weight > 0:
            result.append((rank, min(1.0, weight)))

    return result


def expedition_ev(state: VisibleState, player: int, color: str, unseen: Counter[Card]) -> float:
    """
    Expected final value of a single expedition under the visible-state heuristic.
    """
    exp = state.my_expeditions[color] if player == ME else state.opp_expeditions[color]

    playable = expected_playable_numbers(state, player, color, exp, unseen)
    expected_add_sum = sum(rank * w for rank, w in playable)
    expected_add_count = sum(w for _, w in playable)

    current_wagers = exp.wagers
    extra_wagers = extra_visible_wagers(state, player, color, exp)

    # If numbers have started, wagers are fixed.
    if exp.numbers:
        expected_sum = exp.sum_numbers + expected_add_sum
        expected_count = exp.count + expected_add_count
        return ((expected_sum - 20) * (1 + current_wagers)) + 20 * bonus_prob(expected_count)

    # Expedition unopened or only wagers so far:
    # try adding 0..extra_visible_wagers more wagers, keep the best.
    best = -1e18

    for add_w in range(extra_wagers + 1):
        total_wagers = current_wagers + add_w
        expected_sum = exp.sum_numbers + expected_add_sum
        expected_count = exp.count + add_w + expected_add_count

        ev = ((expected_sum - 20) * (1 + total_wagers)) + 20 * bonus_prob(expected_count)

        if total_wagers > 0:
            ev -= WAGER_COMMIT_RISK * total_wagers

        if not exp.started:
            ev -= START_RISK
            ev = max(0.0, ev)

        best = max(best, ev)

    return best


def evaluate_state(state: VisibleState) -> float:
    """
    Heuristic score from MY perspective:
        my expedition EV total - opponent expedition EV total
    """
    unseen = unseen_counter(state)
    my_total = sum(expedition_ev(state, ME, color, unseen) for color in COLORS)
    opp_total = sum(expedition_ev(state, OPP, color, unseen) for color in COLORS)
    return my_total - opp_total


# ---------------------------------------------------------------------------
# Move generation / application
# ---------------------------------------------------------------------------

def apply_action_only(state: VisibleState, move: Move) -> VisibleState:
    """
    Apply only the play/discard action, not the draw yet.
    Assumes move is legal.
    """
    s = state.copy()
    card = s.my_hand.pop(move.hand_index)

    if move.action == "play":
        exp = s.my_expeditions[card.color]
        if card.rank == WAGER:
            exp.wagers += 1
        else:
            exp.numbers = tuple(list(exp.numbers) + [card.rank])

    elif move.action == "discard":
        s.discards[card.color].append(card)

    else:
        raise ValueError(f"Unknown action: {move.action}")

    return s


def apply_draw_only(after_action: VisibleState, draw_source: Optional[str], drawn_card: Optional[Card]) -> VisibleState:
    """
    Apply only the draw after a play/discard has already been applied.
    If draw_source == 'deck', drawn_card must be provided.
    If draw_source is None, this is a final play-only turn and no card is drawn.
    """
    s = after_action.copy()

    if draw_source is None:
        s.next_player = OPP
        return s

    if draw_source == "deck":
        if drawn_card is None:
            raise ValueError("deck draw requires drawn_card")
        s.my_hand.append(drawn_card)
        s.deck_size -= 1
    else:
        pile = s.discards[draw_source]
        if not pile:
            raise ValueError("cannot draw from an empty discard pile")
        taken = pile.pop()
        s.my_hand.append(taken)

    s.next_player = OPP
    return s


def legal_moves(state: VisibleState) -> List[Move]:
    if state.next_player != ME:
        raise ValueError("legal_moves() expects it to be MY turn")

    moves: List[Move] = []
    final_play_only_turn = state.deck_size <= 0
    discard_sources = [color for color in COLORS if state.discards[color]]

    for i, card in enumerate(state.my_hand):
        draw_sources: List[Optional[str]]
        if final_play_only_turn:
            draw_sources = [None]
        else:
            draw_sources = ["deck", *discard_sources]

        # play + draw
        if legal_play(card, state.my_expeditions[card.color]):
            for draw_source in draw_sources:
                moves.append(Move("play", i, draw_source))

        # discard + draw
        if final_play_only_turn:
            moves.append(Move("discard", i, None))
        else:
            moves.append(Move("discard", i, "deck"))
            for color in discard_sources:
                # You may not take the card you just discarded.
                if color != card.color:
                    moves.append(Move("discard", i, color))

    return moves


# ---------------------------------------------------------------------------
# Move evaluation
# ---------------------------------------------------------------------------

def deck_draw_expectation(after_action: VisibleState) -> float:
    """
    Exact mean heuristic value for drawing the next deck card, where the next card
    is assumed uniform over all unseen card copies.
    """
    unseen = unseen_counter(after_action)
    total_unknown = sum(unseen.values())

    if total_unknown <= 0:
        final_state = after_action.copy()
        final_state.next_player = OPP
        return evaluate_state(final_state)

    ev = 0.0
    for card, count in unseen.items():
        p = count / total_unknown
        final_state = apply_draw_only(after_action, "deck", card)
        ev += p * evaluate_state(final_state)

    return ev


def move_ev(state: VisibleState, move: Move) -> float:
    """
    Expected value of a move, from MY perspective.
    """
    after_action = apply_action_only(state, move)

    if move.draw_source is None:
        final_state = after_action.copy()
        final_state.next_player = OPP
        return evaluate_state(final_state)

    if move.draw_source == "deck":
        return deck_draw_expectation(after_action)

    final_state = apply_draw_only(after_action, move.draw_source, drawn_card=None)
    return evaluate_state(final_state)


def choose_best_move(state: VisibleState) -> Tuple[Move, float, List[Tuple[Move, float]]]:
    """
    Returns:
        best_move,
        best_value,
        all_moves_sorted_desc
    """
    scored: List[Tuple[Move, float]] = []

    for mv in legal_moves(state):
        scored.append((mv, move_ev(state, mv)))

    scored.sort(key=lambda x: x[1], reverse=True)
    best_move, best_value = scored[0]
    return best_move, best_value, scored


# ---------------------------------------------------------------------------
# Convenience helpers
# ---------------------------------------------------------------------------

def parse_card(text: str) -> Card:
    """
    Examples:
        'R2', 'G10', 'BW', 'YW'
    """
    text = text.strip().upper()
    color = text[0]
    value = text[1:]
    if value == "W":
        return Card(color, WAGER)
    return Card(color, int(value))


def move_description(state: VisibleState, move: Move) -> str:
    card = state.my_hand[move.hand_index]
    draw_label = "no draw" if move.draw_source is None else f"draw {move.draw_source}"
    return f"{move.action.upper()} {card} / {draw_label}"


def print_top_moves(state: VisibleState, top_n: int = 10) -> None:
    best, best_value, scored = choose_best_move(state)
    print(f"Best: {move_description(state, best)}  ->  {best_value:.3f}")
    print(f"Exact score diff now: {exact_score_diff(state)}")
    print("-" * 60)
    for mv, value in scored[:top_n]:
        print(f"{move_description(state, mv):25s} {value:8.3f}")


# ---------------------------------------------------------------------------
# Example
# ---------------------------------------------------------------------------

if __name__ == "__main__":
    state = VisibleState(
        my_hand=[
            parse_card("R8"),
            parse_card("R6"),
            parse_card("G5"),
            parse_card("B9"),
            parse_card("Y2"),
            parse_card("W10"),
            parse_card("GW"),
            parse_card("B3"),
        ],
        my_expeditions={
            "R": Expedition(numbers=(4,)),
            "G": Expedition(wagers=1, numbers=(2, 4)),
            "B": Expedition(),
            "Y": Expedition(),
            "W": Expedition(),
        },
        opp_expeditions={
            "R": Expedition(wagers=1, numbers=(2, 5)),
            "G": Expedition(),
            "B": Expedition(numbers=(3, 7)),
            "Y": Expedition(),
            "W": Expedition(),
        },
        discards={c: [] for c in COLORS},
        deck_size=24,
        next_player=ME,
    )

    print_top_moves(state, top_n=12)
