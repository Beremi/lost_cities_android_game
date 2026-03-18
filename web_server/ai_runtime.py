from __future__ import annotations

from dataclasses import dataclass
import importlib.util
import io
import json
from pathlib import Path
import re
import threading
import zipfile
from typing import Any, Callable

import lost_cities_ai
from tools.ai_lab.bot_registry import BOT_KINDS, build_bot
from tools.ai_lab import lost_cities_engine as lab_engine

from .engine import build_agent_request
from .manifest import REPO_ROOT, ManifestBundle
from .models import MATCH_ACTIVE, PHASE_DRAW, PHASE_PLAY, GameRules, MatchRecord


STANDARD_AI_SUITS = ("yellow", "blue", "white", "green", "red")
UPLOADED_ROOT = REPO_ROOT / "web_server" / "storage" / "agents" / "uploaded"


def slugify(value: str) -> str:
    normalized = re.sub(r"[^a-zA-Z0-9]+", "-", value.strip().lower()).strip("-")
    return normalized or "agent"


@dataclass(slots=True)
class AgentDescriptor:
    id: str
    name: str
    description: str
    origin: str
    supports_purple: bool
    download_name: str

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "name": self.name,
            "description": self.description,
            "origin": self.origin,
            "supportsPurple": self.supports_purple,
            "downloadName": self.download_name,
        }


@dataclass(slots=True)
class AgentTurnContext:
    request: dict[str, Any]
    match: MatchRecord
    seat: int


class BaseAgentDriver:
    descriptor: AgentDescriptor

    def choose_action(self, context: AgentTurnContext) -> dict[str, Any]:
        raise NotImplementedError


class RandomAgentDriver(BaseAgentDriver):
    def __init__(self) -> None:
        self.descriptor = AgentDescriptor(
            id="random",
            name="Random AI",
            description="Chooses a random legal move from the current phase.",
            origin="builtin",
            supports_purple=True,
            download_name="lost-cities-random-agent.zip",
        )

    def choose_action(self, context: AgentTurnContext) -> dict[str, Any]:
        actions = list(context.request["legalActions"])
        if not actions:
            raise ValueError("Random AI received no legal actions.")
        import random

        return dict(random.choice(actions))


class UploadedAgentDriver(BaseAgentDriver):
    def __init__(self, *, descriptor: AgentDescriptor, package_root: Path, manifest: dict[str, Any]) -> None:
        self.descriptor = descriptor
        self.package_root = package_root
        self.manifest = manifest
        self._instance: Any = None
        self._lock = threading.Lock()

    def choose_action(self, context: AgentTurnContext) -> dict[str, Any]:
        instance = self._load_instance()
        if hasattr(instance, "choose_action"):
            result = instance.choose_action(context.request)
        elif hasattr(instance, "choose_turn"):
            result = instance.choose_turn(context.request)
        elif callable(instance):
            result = instance(context.request)
        else:
            raise ValueError("Agent entrypoint did not produce a callable chooser.")
        if not isinstance(result, dict):
            raise ValueError("Agent returned a non-dict action.")
        action = result.get("action")
        if not action:
            raise ValueError("Agent action is missing 'action'.")
        return dict(result)

    def _load_instance(self) -> Any:
        with self._lock:
            if self._instance is not None:
                return self._instance
            entrypoint = str(self.manifest.get("entrypoint", "agent.py:build_agent"))
            module_name, _, symbol_name = entrypoint.partition(":")
            module_path = self.package_root / module_name
            if not module_path.suffix:
                module_path = module_path.with_suffix(".py")
            if not module_path.exists():
                raise FileNotFoundError(f"Entrypoint module '{module_path}' does not exist.")
            module_spec = importlib.util.spec_from_file_location(
                f"uploaded_agent_{self.descriptor.id}",
                module_path,
            )
            if module_spec is None or module_spec.loader is None:
                raise ImportError(f"Could not load module '{module_path}'.")
            module = importlib.util.module_from_spec(module_spec)
            module_spec.loader.exec_module(module)
            target = getattr(module, symbol_name or "build_agent", None)
            if target is None:
                raise AttributeError(f"Entrypoint '{entrypoint}' was not found.")
            try:
                self._instance = target(str(self.package_root))
            except TypeError:
                self._instance = target()
            return self._instance


class LabBotDriver(BaseAgentDriver):
    def __init__(self, kind: str, name: str, description: str) -> None:
        if kind not in BOT_KINDS:
            raise ValueError(f"Unknown lab bot kind: {kind}")
        self.kind = kind
        self.descriptor = AgentDescriptor(
            id=kind,
            name=name,
            description=description,
            origin="builtin",
            supports_purple=False,
            download_name=f"lost-cities-{kind}-agent.zip",
        )
        self._bot = None
        self._pending_draws: dict[tuple[str, int, int], dict[str, Any]] = {}

    def choose_action(self, context: AgentTurnContext) -> dict[str, Any]:
        if context.match.rules.use_purple:
            raise ValueError(f"{self.descriptor.name} does not support the purple variant.")
        active_suits = tuple(context.request["activeSuits"])
        if tuple(sorted(active_suits)) != tuple(sorted(STANDARD_AI_SUITS)):
            raise ValueError(f"{self.descriptor.name} only supports the standard 5-suit game.")
        phase = context.request["phase"]
        history_len = int(context.request["historyLength"])
        if phase == PHASE_DRAW:
            pending = self._pending_draws.pop((context.match.id, context.seat, history_len), None)
            if pending is not None:
                return pending
            legal = context.request["legalActions"]
            if not legal:
                raise ValueError("No legal draw actions available.")
            return dict(legal[0])
        if phase != PHASE_PLAY:
            raise ValueError(f"Unsupported phase '{phase}'.")
        bot = self._get_bot()
        lab_state = match_to_lab_state(context.match, context.seat, bundle=context.request["_bundle"])
        choice = bot.choose_turn(lab_state, context.seat)
        play_action = lab_plan_to_phase_action(choice.plan.play)
        if choice.plan.draw is not None:
            self._pending_draws[(context.match.id, context.seat, history_len + 1)] = lab_draw_to_phase_action(choice.plan.draw)
        return play_action

    def _get_bot(self):
        if self._bot is None:
            self._bot = build_bot(self.kind)
        return self._bot


def lab_card_from_repo_card(card_id: str, card_by_id: dict[str, dict]) -> lab_engine.Card:
    card = card_by_id[card_id]
    rank = card["rank"]
    serial = 0
    if rank is None:
        try:
            serial = int(str(card_id).rsplit("_", 1)[-1])
        except ValueError:
            serial = 1
    return lab_engine.Card(suit=card["suit"], rank=rank, serial=serial)


def match_to_lab_state(match: MatchRecord, viewer: int, *, bundle: ManifestBundle) -> lab_engine.GameState:
    players = {}
    for player, player_state in match.lost_cities.players.items():
        expeditions = {
            suit: [lab_card_from_repo_card(card_id, bundle.card_by_id) for card_id in player_state.expeditions.get(suit, [])]
            for suit in lab_engine.SUITS
        }
        hand = [lab_card_from_repo_card(card_id, bundle.card_by_id) for card_id in player_state.hand]
        players[player] = lab_engine.PlayerState(hand=hand, expeditions=expeditions)
    discard_piles = {
        suit: [lab_card_from_repo_card(card_id, bundle.card_by_id) for card_id in match.lost_cities.discard_piles.get(suit, [])]
        for suit in lab_engine.SUITS
    }
    just_discarded = None
    if match.lost_cities.just_discarded_card_id:
        just_discarded = lab_card_from_repo_card(match.lost_cities.just_discarded_card_id, bundle.card_by_id)
    winner = None
    if match.status != MATCH_ACTIVE:
        left = match.score.get(1, 0)
        right = match.score.get(2, 0)
        winner = 1 if left > right else 2 if right > left else 0
    return lab_engine.GameState(
        players=players,
        deck=[lab_card_from_repo_card(card_id, bundle.card_by_id) for card_id in match.lost_cities.deck],
        discard_piles=discard_piles,
        turn_player=match.lost_cities.turn_player,
        phase=match.lost_cities.phase,
        just_discarded=just_discarded,
        final_turns_remaining=match.lost_cities.final_turns_remaining,
        winner=winner,
        history=list(match.history),
    )


def lab_plan_to_phase_action(action: lab_engine.PlayAction) -> dict[str, Any]:
    kind = "play_expedition" if action.kind == "play" else "discard"
    return {"action": kind, "cardId": repo_card_id_from_lab_card(action.card)}


def lab_draw_to_phase_action(action: lab_engine.DrawAction) -> dict[str, Any]:
    if action.kind == "deck":
        return {"action": "draw_deck"}
    return {"action": "draw_discard", "suit": action.suit}


def repo_card_id_from_lab_card(card: lab_engine.Card) -> str:
    if card.rank is None:
        return f"{card.suit}_wager_{card.serial}"
    return f"{card.suit}_{int(card.rank):02d}"


class AgentRegistry:
    def __init__(self, bundle: ManifestBundle) -> None:
        self.bundle = bundle
        self._drivers: dict[str, BaseAgentDriver] = {}
        self._uploaded_zip_paths: dict[str, Path] = {}
        self._uploaded_roots: dict[str, Path] = {}
        self._register_builtin(RandomAgentDriver())
        self._register_builtin(LabBotDriver("heuristic", "Heuristic AI", "The tuned heuristic model from tools/ai_lab."))
        self._register_builtin(LabBotDriver("sampled", "Sampled Heuristic AI", "A bounded determinization wrapper around the heuristic model."))
        self._register_builtin(LabBotDriver("coach", "Coach AI", "A stricter heuristic challenger from tools/ai_lab."))
        self._register_builtin(LabBotDriver("draft_visible", "Draft Visible AI", "The root visible-state heuristic adapted through the lab engine."))
        self.load_uploaded_agents()

    def _register_builtin(self, driver: BaseAgentDriver) -> None:
        self._drivers[driver.descriptor.id] = driver

    def descriptors(self) -> list[dict[str, Any]]:
        return [driver.descriptor.to_dict() for driver in self._drivers.values()]

    def get_driver(self, agent_id: str) -> BaseAgentDriver:
        try:
            return self._drivers[agent_id]
        except KeyError as exc:
            raise KeyError(f"Unknown agent '{agent_id}'.") from exc

    def supports_rules(self, agent_id: str, rules: GameRules) -> bool:
        descriptor = self.get_driver(agent_id).descriptor
        return descriptor.supports_purple or not rules.use_purple

    def build_action(self, agent_id: str, match: MatchRecord, seat: int) -> dict[str, Any]:
        request = build_agent_request(match, player=seat, bundle=self.bundle)
        request["_bundle"] = self.bundle
        driver = self.get_driver(agent_id)
        context = AgentTurnContext(request=request, match=match, seat=seat)
        action = driver.choose_action(context)
        request.pop("_bundle", None)
        return action

    def load_uploaded_agents(self) -> None:
        UPLOADED_ROOT.mkdir(parents=True, exist_ok=True)
        for zip_path in sorted(UPLOADED_ROOT.glob("*.zip")):
            try:
                self._load_uploaded_archive(zip_path)
            except Exception:
                continue

    def install_uploaded_zip(self, *, file_name: str, body: bytes) -> dict[str, Any]:
        archive = zipfile.ZipFile(io.BytesIO(body))
        manifest_name = None
        for name in archive.namelist():
            if name.endswith("agent_manifest.json") and not name.endswith("/agent_manifest.json/"):
                manifest_name = name
                break
        if manifest_name is None:
            raise ValueError("Uploaded zip must include agent_manifest.json.")
        manifest = json.loads(archive.read(manifest_name).decode("utf-8"))
        requested_id = slugify(str(manifest.get("id") or Path(file_name).stem))
        unique_id = requested_id
        counter = 2
        while unique_id in self._drivers:
            unique_id = f"{requested_id}-{counter}"
            counter += 1
        zip_path = UPLOADED_ROOT / f"{unique_id}.zip"
        zip_path.write_bytes(body)
        driver = self._load_uploaded_archive(zip_path, forced_id=unique_id)
        return driver.descriptor.to_dict()

    def _load_uploaded_archive(self, zip_path: Path, forced_id: str | None = None) -> UploadedAgentDriver:
        extract_root = UPLOADED_ROOT / (forced_id or zip_path.stem)
        if extract_root.exists():
            for item in sorted(extract_root.rglob("*"), reverse=True):
                if item.is_file():
                    item.unlink()
                else:
                    item.rmdir()
            extract_root.rmdir()
        extract_root.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(zip_path) as archive:
            _safe_extract(archive, extract_root)
        manifest_path = next(extract_root.rglob("agent_manifest.json"), None)
        if manifest_path is None:
            raise ValueError(f"{zip_path.name} did not contain agent_manifest.json.")
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        agent_id = forced_id or slugify(str(manifest.get("id") or zip_path.stem))
        descriptor = AgentDescriptor(
            id=agent_id,
            name=str(manifest.get("name") or agent_id.replace("-", " ").title()),
            description=str(manifest.get("description") or "Uploaded agent package."),
            origin="uploaded",
            supports_purple=bool(manifest.get("supports_purple", False)),
            download_name=f"{agent_id}.zip",
        )
        driver = UploadedAgentDriver(descriptor=descriptor, package_root=manifest_path.parent, manifest=manifest)
        self._drivers[descriptor.id] = driver
        self._uploaded_zip_paths[descriptor.id] = zip_path
        self._uploaded_roots[descriptor.id] = manifest_path.parent
        return driver

    def download_package(self, agent_id: str) -> tuple[str, bytes]:
        driver = self.get_driver(agent_id)
        if driver.descriptor.origin == "uploaded":
            zip_path = self._uploaded_zip_paths[agent_id]
            return driver.descriptor.download_name, zip_path.read_bytes()
        return driver.descriptor.download_name, build_builtin_agent_zip(agent_id)


def _safe_extract(archive: zipfile.ZipFile, destination: Path) -> None:
    for member in archive.infolist():
        target = destination / member.filename
        resolved = target.resolve()
        if destination.resolve() not in resolved.parents and resolved != destination.resolve():
            raise ValueError("Unsafe path in zip archive.")
        if member.is_dir():
            resolved.mkdir(parents=True, exist_ok=True)
            continue
        resolved.parent.mkdir(parents=True, exist_ok=True)
        resolved.write_bytes(archive.read(member.filename))


def build_builtin_agent_zip(agent_id: str) -> bytes:
    descriptor_map = {
        "random": {
            "description": "Random phase-based agent.",
            "wrapper": _RANDOM_AGENT_CODE,
            "sources": [],
        },
        "heuristic": {
            "description": "Heuristic lab agent adapter.",
            "wrapper": _LAB_AGENT_WRAPPER.format(kind="heuristic"),
            "sources": [
                REPO_ROOT / "tools" / "ai_lab" / "heuristic_ai.py",
                REPO_ROOT / "tools" / "ai_lab" / "lost_cities_engine.py",
            ],
        },
        "sampled": {
            "description": "Sampled heuristic lab agent adapter.",
            "wrapper": _LAB_AGENT_WRAPPER.format(kind="sampled"),
            "sources": [
                REPO_ROOT / "tools" / "ai_lab" / "heuristic_ai.py",
                REPO_ROOT / "tools" / "ai_lab" / "sampled_ai.py",
                REPO_ROOT / "tools" / "ai_lab" / "lost_cities_engine.py",
            ],
        },
        "coach": {
            "description": "Coach lab agent adapter.",
            "wrapper": _LAB_AGENT_WRAPPER.format(kind="coach"),
            "sources": [
                REPO_ROOT / "tools" / "ai_lab" / "coach_ai.py",
                REPO_ROOT / "tools" / "ai_lab" / "heuristic_ai.py",
                REPO_ROOT / "tools" / "ai_lab" / "lost_cities_engine.py",
            ],
        },
        "draft_visible": {
            "description": "Visible-state root heuristic adapter.",
            "wrapper": _LAB_AGENT_WRAPPER.format(kind="draft_visible"),
            "sources": [
                REPO_ROOT / "lost_cities_ai.py",
                REPO_ROOT / "tools" / "ai_lab" / "draft_visible_ai.py",
                REPO_ROOT / "tools" / "ai_lab" / "lost_cities_engine.py",
            ],
        },
    }
    spec = descriptor_map[agent_id]
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr(
            "agent_manifest.json",
            json.dumps(
                {
                    "id": agent_id,
                    "name": agent_id.replace("_", " ").title(),
                    "description": spec["description"],
                    "entrypoint": "agent.py:build_agent",
                    "supports_purple": agent_id == "random",
                },
                indent=2,
            ),
        )
        archive.writestr("agent.py", spec["wrapper"])
        archive.writestr("README.md", _PACKAGE_README)
        for source_path in spec["sources"]:
            archive.writestr(
                f"reference/{source_path.relative_to(REPO_ROOT).as_posix()}",
                source_path.read_text(encoding="utf-8"),
            )
    return output.getvalue()


_PACKAGE_README = """# Lost Cities Agent Package

This zip matches the upload format accepted by the Lost Cities web simulator.

- `agent_manifest.json` declares metadata and the Python entrypoint.
- `agent.py` exposes `build_agent()`.
- Optional `data/` and `reference/` folders may contain model weights or inspectable source.
"""

_RANDOM_AGENT_CODE = """from __future__ import annotations

import random


class RandomAgent:
    def choose_action(self, request: dict) -> dict:
        actions = list(request.get("legalActions", []))
        if not actions:
            raise ValueError("No legal actions available.")
        return dict(random.choice(actions))


def build_agent() -> RandomAgent:
    return RandomAgent()
"""

_LAB_AGENT_WRAPPER = """from __future__ import annotations

from tools.ai_lab.bot_registry import build_bot
from tools.ai_lab import lost_cities_engine as lab_engine


class RepoLabAgent:
    def __init__(self) -> None:
        self.kind = "{kind}"
        self.bot = build_bot(self.kind)
        self.pending_draw = None

    def choose_action(self, request: dict) -> dict:
        phase = request["phase"]
        if phase == "draw":
            action = self.pending_draw
            self.pending_draw = None
            if action is None:
                actions = request.get("legalActions", [])
                if not actions:
                    raise ValueError("No legal draw action available.")
                return dict(actions[0])
            return action
        raise RuntimeError("Downloaded built-in packages are provided for inspection, not standalone execution.")


def build_agent() -> RepoLabAgent:
    return RepoLabAgent()
"""
