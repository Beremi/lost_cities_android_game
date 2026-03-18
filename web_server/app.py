from __future__ import annotations

import itertools
import socket
import threading
from typing import Any
from uuid import uuid4

from .ai_runtime import AgentRegistry
from .engine import apply_action, build_match_view, create_active_match
from .manifest import ManifestBundle, load_manifest_bundle
from .models import (
    AI_KIND,
    HUMAN_KIND,
    MATCH_ACTIVE,
    MATCH_FINISHED,
    ChallengeRecord,
    GameRules,
    LobbyUser,
    MatchRecord,
    PlayerSlot,
    ReplayRecord,
    SimulationRecord,
    SimulationRoundResult,
    now_ms,
)


class LostCitiesWebApp:
    def __init__(self) -> None:
        self.bundle: ManifestBundle = load_manifest_bundle()
        self.agents = AgentRegistry(self.bundle)
        self.lock = threading.RLock()
        self.user_ttl_ms = 30 * 60 * 1000
        self.max_finished_matches = 24
        self.max_replays = 24
        self.max_simulations = 16
        self.max_resolved_challenges = 48
        self.max_announcements = 128
        self.users: dict[str, LobbyUser] = {}
        self.tokens: dict[str, str] = {}
        self.challenges: dict[str, ChallengeRecord] = {}
        self.matches: dict[str, MatchRecord] = {}
        self.replays: dict[str, ReplayRecord] = {}
        self.simulations: dict[str, SimulationRecord] = {}
        self.announcements: list[dict[str, Any]] = []
        self._match_ids = itertools.count(1)
        self._replay_ids = itertools.count(1)
        self._simulation_ids = itertools.count(1)
        self.server_name = "Lost Cities LAN Web"

    def server_info(self, *, port: int) -> dict[str, Any]:
        with self.lock:
            self._prune_state()
            return {
                "name": self.server_name,
                "port": port,
                "localAddresses": local_ipv4_addresses(),
                "cardDesign": "v3",
                "discoverUdpPort": port + 1,
            }

    def connect_user(self, name: str) -> dict[str, Any]:
        with self.lock:
            self._prune_state()
            safe_name = sanitize_name(name)
            user_id = f"user-{uuid4().hex[:12]}"
            token = uuid4().hex
            user = LobbyUser(id=user_id, token=token, name=safe_name)
            self.users[user_id] = user
            self.tokens[token] = user_id
            self._announce(f"{safe_name} joined the lobby.")
            return {"ok": True, "userId": user_id, "userToken": token, "name": safe_name}

    def heartbeat(self, token: str) -> dict[str, Any]:
        with self.lock:
            self._prune_state()
            user = self._require_user(token)
            user.connected = True
            user.last_seen_epoch_ms = now_ms()
            return {"ok": True, "user": user.to_dict()}

    def lobby_snapshot(self, token: str | None = None) -> dict[str, Any]:
        with self.lock:
            self._prune_state()
            user = None
            if token:
                user = self._require_user(token)
                user.connected = True
                user.last_seen_epoch_ms = now_ms()
            return {
                "ok": True,
                "user": user.to_dict() if user is not None else None,
                "users": [item.to_dict() for item in self.users.values()],
                "challenges": [item.to_dict() for item in self.challenges.values()],
                "agents": self.agents.descriptors(),
                "announcements": list(self.announcements[-12:]),
                "matches": [
                    {
                        "id": match.id,
                        "status": match.status,
                        "players": {str(slot.player): slot.to_dict() for slot in match.players.values()},
                        "lastEvent": match.last_event,
                        "updatedAtEpochMs": match.updated_at_epoch_ms,
                    }
                    for match in self.matches.values()
                ],
            }

    def list_agents(self) -> dict[str, Any]:
        with self.lock:
            self._prune_state()
            return {"ok": True, "agents": self.agents.descriptors()}

    def upload_agent_zip(self, *, file_name: str, body: bytes) -> dict[str, Any]:
        with self.lock:
            self._prune_state()
            descriptor = self.agents.install_uploaded_zip(file_name=file_name, body=body)
            self._announce(f"New agent uploaded: {descriptor['name']}.")
            return {"ok": True, "agent": descriptor}

    def download_agent_zip(self, agent_id: str) -> tuple[str, bytes]:
        with self.lock:
            self._prune_state()
            return self.agents.download_package(agent_id)

    def create_human_challenge(self, *, token: str, target_user_id: str, use_purple: bool) -> dict[str, Any]:
        with self.lock:
            self._prune_state()
            challenger = self._require_user(token)
            target = self.users.get(target_user_id)
            if target is None:
                raise ValueError("Target user not found.")
            if target.id == challenger.id:
                raise ValueError("You cannot challenge yourself.")
            if self._user_has_active_match(challenger.id):
                raise ValueError("You are already in an active match.")
            if self._user_has_active_match(target.id):
                raise ValueError(f"{target.name} is already in an active match.")
            for existing in self.challenges.values():
                same_pair = {
                    existing.challenger_user_id,
                    existing.target_user_id,
                } == {challenger.id, target.id}
                if same_pair and existing.status == "pending":
                    raise ValueError("A pending challenge already exists between these players.")
            challenge = ChallengeRecord(
                id=f"challenge-{uuid4().hex[:10]}",
                challenger_user_id=challenger.id,
                target_user_id=target.id,
                challenger_name=challenger.name,
                target_name=target.name,
                rules=GameRules(use_purple=bool(use_purple)),
                status="pending",
            )
            self.challenges[challenge.id] = challenge
            self._announce(f"{challenger.name} challenged {target.name} to a human match.")
            return {"ok": True, "challenge": challenge.to_dict()}

    def create_human_match(self, *, token: str, target_user_id: str, use_purple: bool) -> dict[str, Any]:
        with self.lock:
            self._prune_state()
            challenger = self._require_user(token)
            target = self.users.get(target_user_id)
            if target is None:
                raise ValueError("Target user not found.")
            if target.id == challenger.id:
                raise ValueError("You cannot start a match against yourself.")
            if self._user_has_active_match(challenger.id):
                raise ValueError("You are already in an active match.")
            if self._user_has_active_match(target.id):
                raise ValueError(f"{target.name} is already in an active match.")
            players = {
                1: PlayerSlot(player=1, name=challenger.name, kind=HUMAN_KIND, user_id=challenger.id),
                2: PlayerSlot(player=2, name=target.name, kind=HUMAN_KIND, user_id=target.id),
            }
            match = create_active_match(
                match_id=self._new_match_id(),
                players=players,
                rules=GameRules(use_purple=bool(use_purple)),
                bundle=self.bundle,
                reveal_all=False,
                capture_replay=False,
            )
            self.matches[match.id] = match
            challenger.match_id = match.id
            target.match_id = match.id
            self._announce(f"{challenger.name} started a human match with {target.name}.")
            return {"ok": True, "matchId": match.id, "match": build_match_view(match, viewer_user_id=challenger.id, bundle=self.bundle)}

    def accept_human_challenge(self, *, token: str, challenge_id: str) -> dict[str, Any]:
        with self.lock:
            self._prune_state()
            accepter = self._require_user(token)
            challenge = self.challenges.get(challenge_id)
            if challenge is None:
                raise ValueError("Challenge not found.")
            if challenge.target_user_id != accepter.id:
                raise ValueError("Only the targeted user can accept this challenge.")
            if challenge.status != "pending":
                raise ValueError("This challenge is no longer pending.")
            if self._user_has_active_match(challenge.challenger_user_id):
                raise ValueError(f"{challenge.challenger_name} is already in an active match.")
            if self._user_has_active_match(challenge.target_user_id):
                raise ValueError(f"{challenge.target_name} is already in an active match.")
            players = {
                1: PlayerSlot(player=1, name=challenge.challenger_name, kind=HUMAN_KIND, user_id=challenge.challenger_user_id),
                2: PlayerSlot(player=2, name=challenge.target_name, kind=HUMAN_KIND, user_id=challenge.target_user_id),
            }
            match = create_active_match(
                match_id=self._new_match_id(),
                players=players,
                rules=challenge.rules,
                bundle=self.bundle,
                reveal_all=False,
                capture_replay=False,
            )
            self.matches[match.id] = match
            self.users[challenge.challenger_user_id].match_id = match.id
            self.users[challenge.target_user_id].match_id = match.id
            challenge.status = "accepted"
            challenge.accepted_match_id = match.id
            self._announce(f"{challenge.target_name} accepted {challenge.challenger_name}'s challenge.")
            return {"ok": True, "matchId": match.id, "match": build_match_view(match, viewer_user_id=accepter.id, bundle=self.bundle)}

    def create_human_vs_ai_match(self, *, token: str, agent_id: str, use_purple: bool) -> dict[str, Any]:
        with self.lock:
            self._prune_state()
            human = self._require_user(token)
            if self._user_has_active_match(human.id):
                raise ValueError("You are already in an active match.")
            rules = GameRules(use_purple=bool(use_purple))
            if not self.agents.supports_rules(agent_id, rules):
                raise ValueError("Selected AI does not support the requested rules.")
            agent_meta = self.agents.get_driver(agent_id).descriptor
            players = {
                1: PlayerSlot(player=1, name=human.name, kind=HUMAN_KIND, user_id=human.id),
                2: PlayerSlot(player=2, name=agent_meta.name, kind=AI_KIND, agent_id=agent_meta.id),
            }
            match = create_active_match(
                match_id=self._new_match_id(),
                players=players,
                rules=rules,
                bundle=self.bundle,
                reveal_all=False,
                capture_replay=False,
            )
            self.matches[match.id] = match
            human.match_id = match.id
            self._announce(f"{human.name} started a match against {agent_meta.name}.")
            self._run_ai_until_human_turn(match.id)
            current = self.matches[match.id]
            return {"ok": True, "matchId": current.id, "match": build_match_view(current, viewer_user_id=human.id, bundle=self.bundle)}

    def get_match_view(self, *, token: str | None, match_id: str) -> dict[str, Any]:
        with self.lock:
            self._prune_state()
            user = self._require_user(token) if token else None
            match = self._require_match(match_id)
            return {
                "ok": True,
                "match": build_match_view(
                    match,
                    viewer_user_id=user.id if user is not None else None,
                    bundle=self.bundle,
                ),
            }

    def submit_match_action(
        self,
        *,
        token: str,
        match_id: str,
        action: str,
        card_id: str = "",
        suit: str | None = None,
    ) -> dict[str, Any]:
        with self.lock:
            self._prune_state()
            user = self._require_user(token)
            match = self._require_match(match_id)
            viewer_seat = self._seat_for_user(match, user.id)
            if viewer_seat is None:
                raise ValueError("You are not seated in this match.")
            updated = apply_action(
                match,
                player=viewer_seat,
                action=action,
                card_id=card_id,
                suit=suit,
                bundle=self.bundle,
            )
            self.matches[match_id] = updated
            self._run_ai_until_human_turn(match_id)
            current = self.matches[match_id]
            return {"ok": True, "match": build_match_view(current, viewer_user_id=user.id, bundle=self.bundle)}

    def create_ai_replay(self, *, left_agent_id: str, right_agent_id: str, use_purple: bool) -> dict[str, Any]:
        with self.lock:
            self._prune_state()
            rules = GameRules(use_purple=bool(use_purple))
            self._ensure_agent_rules(left_agent_id, rules)
            self._ensure_agent_rules(right_agent_id, rules)
            finished = self._play_ai_match(
                left_agent_id=left_agent_id,
                right_agent_id=right_agent_id,
                rules=rules,
                capture_replay=True,
            )
            replay = ReplayRecord(
                id=self._new_replay_id(),
                label=f"{left_agent_id} vs {right_agent_id}",
                left_agent_id=left_agent_id,
                right_agent_id=right_agent_id,
                rules=rules,
                winner=_winner_from_scores(finished.score),
                final_scores=finished.score,
                frames=list(finished.replay_frames),
            )
            self.replays[replay.id] = replay
            self._prune_replays()
            return {"ok": True, "replay": replay.to_dict()}

    def get_replay(self, replay_id: str) -> dict[str, Any]:
        with self.lock:
            self._prune_state()
            replay = self.replays.get(replay_id)
            if replay is None:
                raise ValueError("Replay not found.")
            return {"ok": True, "replay": replay.to_dict()}

    def create_simulation(self, *, left_agent_id: str, right_agent_id: str, rounds: int, use_purple: bool) -> dict[str, Any]:
        with self.lock:
            self._prune_state()
            rules = GameRules(use_purple=bool(use_purple))
            self._ensure_agent_rules(left_agent_id, rules)
            self._ensure_agent_rules(right_agent_id, rules)
            results: list[SimulationRoundResult] = []
            for index in range(max(1, rounds)):
                finished = self._play_ai_match(
                    left_agent_id=left_agent_id,
                    right_agent_id=right_agent_id,
                    rules=rules,
                    capture_replay=False,
                )
                results.append(
                    SimulationRoundResult(
                        replay_id="",
                        match_id=finished.id,
                        round_index=index + 1,
                        winner=_winner_from_scores(finished.score),
                        scores=dict(finished.score),
                        turn_count=len(finished.action_log),
                    )
                )
            record = SimulationRecord(
                id=self._new_simulation_id(),
                left_agent_id=left_agent_id,
                right_agent_id=right_agent_id,
                rules=rules,
                rounds=max(1, rounds),
                results=results,
            )
            self.simulations[record.id] = record
            self._prune_simulations()
            return {"ok": True, "simulation": record.to_dict()}

    def get_simulation(self, simulation_id: str) -> dict[str, Any]:
        with self.lock:
            self._prune_state()
            record = self.simulations.get(simulation_id)
            if record is None:
                raise ValueError("Simulation not found.")
            return {"ok": True, "simulation": record.to_dict()}

    def _run_ai_until_human_turn(self, match_id: str) -> None:
        while True:
            match = self.matches[match_id]
            if match.status != MATCH_ACTIVE:
                self._finalize_human_links(match)
                return
            seat = match.lost_cities.turn_player
            slot = match.players[seat]
            if slot.kind != AI_KIND or not slot.agent_id:
                return
            action = self.agents.build_action(slot.agent_id, match, seat)
            updated = apply_action(
                match,
                player=seat,
                action=str(action["action"]),
                card_id=str(action.get("cardId") or ""),
                suit=action.get("suit"),
                bundle=self.bundle,
            )
            self.matches[match_id] = updated

    def _play_ai_match(self, *, left_agent_id: str, right_agent_id: str, rules: GameRules, capture_replay: bool) -> MatchRecord:
        match = create_active_match(
            match_id=self._new_match_id(),
            players={
                1: PlayerSlot(player=1, name=self.agents.get_driver(left_agent_id).descriptor.name, kind=AI_KIND, agent_id=left_agent_id),
                2: PlayerSlot(player=2, name=self.agents.get_driver(right_agent_id).descriptor.name, kind=AI_KIND, agent_id=right_agent_id),
            },
            rules=rules,
            bundle=self.bundle,
            reveal_all=True,
            capture_replay=capture_replay,
        )
        while match.status == MATCH_ACTIVE:
            seat = match.lost_cities.turn_player
            slot = match.players[seat]
            if slot.kind != AI_KIND or not slot.agent_id:
                break
            action = self.agents.build_action(slot.agent_id, match, seat)
            match = apply_action(
                match,
                player=seat,
                action=str(action["action"]),
                card_id=str(action.get("cardId") or ""),
                suit=action.get("suit"),
                bundle=self.bundle,
            )
        return match

    def _finalize_human_links(self, match: MatchRecord) -> None:
        if match.status != MATCH_FINISHED:
            return
        for slot in match.players.values():
            if slot.user_id and slot.user_id in self.users:
                self.users[slot.user_id].match_id = None
        self._prune_matches()

    def _user_has_active_match(self, user_id: str) -> bool:
        user = self.users.get(user_id)
        if user is None or not user.match_id:
            return False
        match = self.matches.get(user.match_id)
        return match is not None and match.status == MATCH_ACTIVE

    def _seat_for_user(self, match: MatchRecord, user_id: str) -> int | None:
        for player, slot in match.players.items():
            if slot.user_id == user_id:
                return player
        return None

    def _require_user(self, token: str) -> LobbyUser:
        user_id = self.tokens.get(token)
        if user_id is None or user_id not in self.users:
            raise ValueError("Unknown user token.")
        return self.users[user_id]

    def _require_match(self, match_id: str) -> MatchRecord:
        match = self.matches.get(match_id)
        if match is None:
            raise ValueError("Match not found.")
        return match

    def _ensure_agent_rules(self, agent_id: str, rules: GameRules) -> None:
        if not self.agents.supports_rules(agent_id, rules):
            raise ValueError(f"Agent '{agent_id}' does not support the requested rules.")

    def _new_match_id(self) -> str:
        return f"match-{next(self._match_ids):05d}"

    def _new_replay_id(self) -> str:
        return f"replay-{next(self._replay_ids):05d}"

    def _new_simulation_id(self) -> str:
        return f"simulation-{next(self._simulation_ids):05d}"

    def _announce(self, message: str) -> None:
        self.announcements.append({"message": message, "createdAtEpochMs": now_ms()})
        self._prune_announcements()

    def _prune_state(self) -> None:
        current_ms = now_ms()
        self._prune_matches()
        self._prune_users(current_ms)
        self._prune_challenges()
        self._prune_replays()
        self._prune_simulations()
        self._prune_announcements()

    def _prune_users(self, current_ms: int) -> None:
        stale_user_ids: list[str] = []
        for user_id, user in self.users.items():
            if self._user_has_active_match(user_id):
                continue
            if current_ms - user.last_seen_epoch_ms <= self.user_ttl_ms:
                continue
            stale_user_ids.append(user_id)
        for user_id in stale_user_ids:
            user = self.users.pop(user_id, None)
            if user is None:
                continue
            self.tokens.pop(user.token, None)

    def _prune_matches(self) -> None:
        finished_match_ids = [
            match_id
            for match_id, match in self.matches.items()
            if match.status != MATCH_ACTIVE
        ]
        overflow = len(finished_match_ids) - self.max_finished_matches
        if overflow <= 0:
            return
        ordered_ids = sorted(finished_match_ids, key=lambda match_id: self.matches[match_id].updated_at_epoch_ms)
        for match_id in ordered_ids[:overflow]:
            del self.matches[match_id]

    def _prune_challenges(self) -> None:
        removable_ids = [
            challenge_id
            for challenge_id, challenge in self.challenges.items()
            if challenge.challenger_user_id not in self.users or challenge.target_user_id not in self.users
        ]
        for challenge_id in removable_ids:
            del self.challenges[challenge_id]
        resolved_ids = [
            challenge_id
            for challenge_id, challenge in self.challenges.items()
            if challenge.status != "pending"
        ]
        overflow = len(resolved_ids) - self.max_resolved_challenges
        if overflow <= 0:
            return
        ordered_ids = sorted(resolved_ids, key=lambda challenge_id: self.challenges[challenge_id].created_at_epoch_ms)
        for challenge_id in ordered_ids[:overflow]:
            del self.challenges[challenge_id]

    def _prune_replays(self) -> None:
        overflow = len(self.replays) - self.max_replays
        if overflow <= 0:
            return
        ordered_ids = sorted(self.replays, key=lambda replay_id: self.replays[replay_id].created_at_epoch_ms)
        for replay_id in ordered_ids[:overflow]:
            del self.replays[replay_id]

    def _prune_simulations(self) -> None:
        overflow = len(self.simulations) - self.max_simulations
        if overflow <= 0:
            return
        ordered_ids = sorted(self.simulations, key=lambda simulation_id: self.simulations[simulation_id].created_at_epoch_ms)
        for simulation_id in ordered_ids[:overflow]:
            del self.simulations[simulation_id]

    def _prune_announcements(self) -> None:
        overflow = len(self.announcements) - self.max_announcements
        if overflow <= 0:
            return
        del self.announcements[:overflow]


def sanitize_name(raw: str) -> str:
    value = " ".join(raw.strip().split())
    if not value:
        return "Explorer"
    return value[:40]


def local_ipv4_addresses() -> list[str]:
    found = {"127.0.0.1"}
    try:
        hostname = socket.gethostname()
        for item in socket.getaddrinfo(hostname, None, socket.AF_INET, socket.SOCK_STREAM):
            address = item[4][0]
            if address:
                found.add(address)
    except OSError:
        pass
    return sorted(found)


def _winner_from_scores(scores: dict[int, int]) -> int:
    left = scores.get(1, 0)
    right = scores.get(2, 0)
    if left > right:
        return 1
    if right > left:
        return 2
    return 0
