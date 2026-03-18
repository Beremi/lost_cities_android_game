# Lost Cities Web Agent API

This document describes the zip package format and the runtime contract for agents that can:

- appear in the permanent AI side panel
- be challenged by a human player
- play in AI-vs-AI replays
- run in batch simulations

The current arena runtime is Python-based. Uploaded agents are loaded from a zip stored in this repository.

## Package layout

Upload a single `.zip` file with this minimum structure:

```text
my_agent.zip
├── agent_manifest.json
├── agent.py
└── data/
    └── optional-model-files.bin
```

Only `agent_manifest.json` and the declared Python entrypoint are required. Extra folders such as `data/`, `reference/`, or `README.md` are allowed.

## Required `agent_manifest.json`

```json
{
  "id": "my_agent",
  "name": "My Agent",
  "description": "Short text shown in the web UI.",
  "entrypoint": "agent.py:build_agent",
  "supports_purple": false
}
```

### Fields

- `id`: stable package identifier.
- `name`: human-readable title for the side panel.
- `description`: short summary for the UI.
- `entrypoint`: Python file and symbol in `path.py:function_name` form.
- `supports_purple`: optional boolean. Set `true` only if the agent supports the purple long-game variant.

## Python entrypoint

The entrypoint function should return either:

- an object with `choose_action(request: dict) -> dict`
- an object with `choose_turn(request: dict) -> dict`
- a callable that accepts `request` directly and returns a dict

The loader will try `build_agent(package_root)` first, then `build_agent()` if the function does not accept the package path.

Example:

```python
class FirstLegalAgent:
    def choose_action(self, request: dict) -> dict:
        actions = list(request.get("legalActions", []))
        if not actions:
            raise ValueError("No legal actions available.")
        return dict(actions[0])


def build_agent(package_root: str) -> FirstLegalAgent:
    return FirstLegalAgent()
```

## Request shape

Agents receive a JSON-like dictionary for the acting seat only.

```json
{
  "apiVersion": 1,
  "matchId": "match-00012",
  "seat": 1,
  "status": "active",
  "rules": {
    "usePurple": false
  },
  "phase": "play",
  "turnPlayer": 1,
  "finalTurnsRemaining": 0,
  "deckCount": 31,
  "activeSuits": ["yellow", "white", "blue", "green", "red"],
  "myHand": [
    {
      "id": "yellow_03",
      "suit": "yellow",
      "type": "expedition",
      "rank": 3,
      "path": "assets/cards/png/yellow_03.png"
    }
  ],
  "myExpeditions": {
    "yellow": [],
    "white": [],
    "blue": [],
    "green": [],
    "red": []
  },
  "opponentExpeditions": {
    "yellow": [],
    "white": [],
    "blue": [],
    "green": [],
    "red": []
  },
  "discardPiles": {
    "yellow": [],
    "white": [],
    "blue": [],
    "green": [],
    "red": []
  },
  "opponentHandCount": 8,
  "justDiscardedCardId": null,
  "legalActions": [
    {
      "action": "play_expedition",
      "cardId": "yellow_03",
      "suit": "yellow"
    },
    {
      "action": "discard",
      "cardId": "yellow_03",
      "suit": "yellow"
    }
  ],
  "historyLength": 0,
  "score": {
    "1": 0,
    "2": 0
  }
}
```

### Notes

- The request mirrors the Android-style match semantics:
  - `status`: `waiting`, `active`, `finished`, or `aborted`
  - `phase`: `play` or `draw`
- Agents receive their full hand, visible discard piles, both expedition columns, and only the opponent hand count.
- Hidden deck order is never exposed.

## Response shape

Return one legal action for the current phase:

```json
{
  "action": "play_expedition",
  "cardId": "yellow_03"
}
```

### Allowed actions

- `play_expedition`
- `discard`
- `draw_deck`
- `draw_discard`

### Required fields by action

- `play_expedition`: include `cardId`
- `discard`: include `cardId`
- `draw_deck`: no extra fields
- `draw_discard`: include `suit`

## Minimal example

```python
class DrawDeckAgent:
    def choose_action(self, request: dict) -> dict:
        actions = list(request.get("legalActions", []))
        for action in actions:
            if action.get("action") == "draw_deck":
                return dict(action)
        return dict(actions[0])


def build_agent(package_root: str):
    return DrawDeckAgent()
```

## Built-in agents in this repository

The arena seeds the side panel with:

- `random`
- `heuristic`
- `sampled`
- `coach`
- `draft_visible`

Each built-in agent can be downloaded as a zip package so users can inspect the expected structure before uploading their own bundle.
