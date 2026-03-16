# Lost Cities AI Lab Simulator API

This is the minimum API surface you need to plug in another bot and compare it against the existing lab bots.

## Files

- `tools/ai_lab/lost_cities_engine.py`
  Core game state, legal move generation, state transitions, scoring, and text rendering.
- `tools/ai_lab/iteration_runner.py`
  Match and batch runners.
- `tools/ai_lab/heuristic_ai.py`
  Reference bot interface and the default heuristic model.
- `tools/ai_lab/coach_ai.py`
  Stronger comparison bot used as the "coach" opponent.
- `tools/ai_lab/draft_visible_ai.py`
  Adapter that wraps the root-level `lost_cities_ai.py` visible-state bot in the engine API.
- `tools/ai_lab/bot_registry.py`
  Shared bot factory for CLI runners and batch comparisons.
- `tools/ai_lab/single_match_runner.py`
  CLI helper for one full finished-round match.
- `tools/ai_lab/full_round_runner.py`
  CLI helper for full-round batch reports.

## Core Types

Defined in `tools/ai_lab/lost_cities_engine.py`.

### `Card`

Frozen dataclass:

```python
Card(
    suit: str,          # one of: "yellow", "blue", "white", "green", "red"
    rank: int | None,   # None means wager
    serial: int = 0,    # only used to distinguish the 3 wager cards in a suit
)
```

Useful properties:

- `card.is_wager -> bool`
- `card.id -> str`
- `card.short() -> str`

### `PlayerState`

```python
PlayerState(
    hand: list[Card],
    expeditions: dict[str, list[Card]],
)
```

Notes:

- `expeditions[suit]` is ordered in play order.
- hands are usually kept sorted with `hand_sort_key(card)`.

### `PlayAction`

```python
PlayAction(kind: str, card: Card)
```

Allowed `kind` values:

- `"play"`
- `"discard"`

### `DrawAction`

```python
DrawAction(kind: str, suit: str | None = None)
```

Allowed `kind` values:

- `"deck"`
- `"discard"`

If `kind == "discard"`, `suit` must be the discard pile color.

### `TurnPlan`

```python
TurnPlan(
    play: PlayAction,
    draw: DrawAction | None,
)
```

Notes:

- Most turns are `play` then `draw`.
- In the final two play-only turns after the deck empties, `draw` is `None`.

### `GameState`

```python
GameState(
    players: dict[int, PlayerState],     # keys 1 and 2
    deck: list[Card],                    # top of deck is deck[0]
    discard_piles: dict[str, list[Card]],
    turn_player: int = 1,
    phase: str = "play",                 # "play" or "draw"
    just_discarded: Card | None = None,  # prevents immediate redraw
    final_turns_remaining: int = 0,      # becomes 2 when last deck card is drawn
    winner: int | None = None,           # 1, 2, 0 for tie, or None
    history: list[str] = [],
)
```

Useful property:

- `state.finished -> bool`

## Engine Functions

Defined in `tools/ai_lab/lost_cities_engine.py`.

### Setup

- `deal_game(seed: int) -> GameState`
- `build_deck() -> list[Card]`

### Rules / legality

- `legal_play_actions(state: GameState, player: int) -> list[PlayAction]`
- `legal_draw_actions(state: GameState, player: int) -> list[DrawAction]`
- `legal_turn_plans(state: GameState, player: int) -> list[TurnPlan]`
- `can_play_to_expedition(expedition: list[Card], card: Card) -> bool`

### State transitions

- `apply_play_action(state: GameState, player: int, action: PlayAction) -> GameState`
- `apply_draw_action(state: GameState, player: int, action: DrawAction) -> GameState`
- `apply_turn_plan(state: GameState, player: int, plan: TurnPlan) -> GameState`

Important:

- these functions do not mutate the input state in place
- they return a copied next state

### Scoring / helpers

- `score_expedition(cards: Iterable[Card]) -> int`
- `score_player(player_state: PlayerState) -> int`
- `suit_profile(cards: list[Card]) -> dict[str, int | bool]`
- `other_player(player: int) -> int`
- `all_visible_cards(state: GameState, viewer: int) -> set[Card]`
- `unseen_cards(state: GameState, viewer: int) -> list[Card]`
- `render_state(state: GameState, viewer: int) -> str`

## Bot Contract

The runner expects a bot object with:

```python
choose_turn(state: GameState, player: int) -> choice
```

Where `choice` must expose:

```python
choice.plan   # a TurnPlan
```

The simplest way is to return the same `PlanChoice` type used by the built-in heuristic:

```python
from tools.ai_lab.heuristic_ai import PlanChoice
```

Example skeleton:

```python
from tools.ai_lab.heuristic_ai import PlanChoice
from tools.ai_lab.lost_cities_engine import GameState, legal_turn_plans


class MyBot:
    def choose_turn(self, state: GameState, player: int) -> PlanChoice:
        plans = legal_turn_plans(state, player)
        if not plans:
            raise ValueError("No legal plans.")
        plan = plans[0]
        return PlanChoice(plan=plan, value=0.0)
```

If you do not want to import `PlanChoice`, any object with a `.plan` attribute is enough for `play_single_match`.

## Compare Your Bot Against Coach

Use `play_single_match()` from `tools/ai_lab/iteration_runner.py`.

```python
from tools.ai_lab.coach_ai import CoachSearchAI
from tools.ai_lab.iteration_runner import play_single_match

my_bot = MyBot()
opponent = CoachSearchAI()

summary, observations = play_single_match(
    seed=1,
    model_player=1,
    model=my_bot,
    opponent=opponent,
    max_turns=None,            # None = full finished round
    collect_diagnostics=True,  # compare move choices vs coach
)

print(summary)
for note in observations:
    print(note)
```

Arguments:

- `seed`
  Deterministic shuffle seed.
- `model_player`
  `1` or `2`.
- `model`
  Your bot.
- `opponent`
  Opponent bot.
- `max_turns`
  `None` for full round, or an integer cap such as `24`.
- `collect_diagnostics`
  If `True`, the runner compares your move to the coach move on your turns and records mismatches.

Return value:

- `summary: MatchSummary`
- `observations: list[str]`

## Batch Runs

### In Python

Use `run_batch()` from `tools/ai_lab/iteration_runner.py` if you only need the built-in model kinds.

```python
from tools.ai_lab.iteration_runner import run_batch, render_batch_markdown

result = run_batch(
    games=10,
    max_turns=None,
    model_kind="draft_visible",
    opponent_kind="coach",
    collect_diagnostics=False,
)
print(render_batch_markdown(result, "full_round", opponent_label="coach"))
```

### From the CLI

Full-round built-in model batch:

```bash
python -m tools.ai_lab.full_round_runner \
  --games 10 \
  --workers 2 \
  --model-kind draft_visible \
  --opponent-kind coach \
  --output tools/ai_lab/draft_visible_vs_coach_10_games.md
```

Single full-round built-in model match:

```bash
python -m tools.ai_lab.single_match_runner \
  --seed 1 \
  --model-player 1 \
  --model-kind draft_visible \
  --opponent-kind heuristic
```

Supported built-in kinds:

- `heuristic`
- `sampled`
- `coach`
- `draft_visible`

## Notes / Conventions

- Deck top is `state.deck[0]`.
- Discard top is `state.discard_piles[suit][-1]`.
- You cannot redraw `state.just_discarded` on the same turn.
- When the last deck card is drawn, the engine sets `final_turns_remaining = 2`.
- Those last two turns are play-only turns with `draw=None`.
- Expedition scoring follows:

```text
((sum(numbers) - 20) * (1 + wagers)) + 20 if len(expedition) >= 8
```

- The engine is deterministic given `seed` and both bots' choices.

## Fastest Path To Integrate Your Bot

1. Add a new file under `tools/ai_lab/`, for example `my_bot.py`.
2. Implement `choose_turn(state, player)`.
3. Use `legal_turn_plans(state, player)` to get legal moves.
4. Return a choice object with `.plan`.
5. Compare it with `play_single_match(...)` against `CoachSearchAI()` or another registered bot.
