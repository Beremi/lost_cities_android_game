from __future__ import annotations

from dataclasses import asdict, dataclass, field
import time
from typing import Any


MATCH_WAITING = "waiting"
MATCH_ACTIVE = "active"
MATCH_FINISHED = "finished"
MATCH_ABORTED = "aborted"

PHASE_PLAY = "play"
PHASE_DRAW = "draw"

HUMAN_KIND = "human"
AI_KIND = "ai"


def now_ms() -> int:
    return int(time.time() * 1000)


@dataclass(slots=True)
class GameRules:
    use_purple: bool = False

    def to_dict(self) -> dict[str, Any]:
        return {"usePurple": self.use_purple}


@dataclass(slots=True)
class PlayerSlot:
    player: int
    name: str
    kind: str
    connected: bool = True
    user_id: str | None = None
    agent_id: str | None = None
    last_seen_epoch_ms: int = field(default_factory=now_ms)

    def to_dict(self) -> dict[str, Any]:
        return {
            "player": self.player,
            "name": self.name,
            "kind": self.kind,
            "connected": self.connected,
            "userId": self.user_id,
            "agentId": self.agent_id,
            "lastSeenEpochMs": self.last_seen_epoch_ms,
        }


@dataclass(slots=True)
class PlayerState:
    hand: list[str] = field(default_factory=list)
    expeditions: dict[str, list[str]] = field(default_factory=dict)

    def to_dict(self, reveal_hand: bool, include_hand_count: bool = True) -> dict[str, Any]:
        payload = {
            "expeditions": self.expeditions,
        }
        if reveal_hand:
            payload["hand"] = self.hand
        if include_hand_count:
            payload["handCount"] = len(self.hand)
        return payload


@dataclass(slots=True)
class LostCitiesState:
    turn_player: int = 1
    phase: str = PHASE_PLAY
    deck: list[str] = field(default_factory=list)
    discard_piles: dict[str, list[str]] = field(default_factory=dict)
    players: dict[int, PlayerState] = field(default_factory=dict)
    just_discarded_card_id: str | None = None
    final_turns_remaining: int = 0


@dataclass(slots=True)
class ReplayFrame:
    index: int
    label: str
    event: str
    state: dict[str, Any]

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass(slots=True)
class MatchRecord:
    id: str
    status: str
    players: dict[int, PlayerSlot]
    rules: GameRules
    score: dict[int, int]
    lost_cities: LostCitiesState
    created_at_epoch_ms: int
    updated_at_epoch_ms: int
    last_event: str
    history: list[str] = field(default_factory=list)
    action_log: list[dict[str, Any]] = field(default_factory=list)
    replay_frames: list[ReplayFrame] = field(default_factory=list)
    reveal_all: bool = False
    capture_replay: bool = False


@dataclass(slots=True)
class LobbyUser:
    id: str
    token: str
    name: str
    connected: bool = True
    match_id: str | None = None
    created_at_epoch_ms: int = field(default_factory=now_ms)
    last_seen_epoch_ms: int = field(default_factory=now_ms)

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "name": self.name,
            "connected": self.connected,
            "matchId": self.match_id,
            "createdAtEpochMs": self.created_at_epoch_ms,
            "lastSeenEpochMs": self.last_seen_epoch_ms,
        }


@dataclass(slots=True)
class ChallengeRecord:
    id: str
    challenger_user_id: str
    target_user_id: str
    challenger_name: str
    target_name: str
    rules: GameRules
    status: str
    created_at_epoch_ms: int = field(default_factory=now_ms)
    accepted_match_id: str | None = None

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "challengerUserId": self.challenger_user_id,
            "targetUserId": self.target_user_id,
            "challengerName": self.challenger_name,
            "targetName": self.target_name,
            "rules": self.rules.to_dict(),
            "status": self.status,
            "createdAtEpochMs": self.created_at_epoch_ms,
            "acceptedMatchId": self.accepted_match_id,
        }


@dataclass(slots=True)
class ReplayRecord:
    id: str
    label: str
    left_agent_id: str
    right_agent_id: str
    rules: GameRules
    winner: int
    final_scores: dict[int, int]
    frames: list[ReplayFrame]
    created_at_epoch_ms: int = field(default_factory=now_ms)

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "label": self.label,
            "leftAgentId": self.left_agent_id,
            "rightAgentId": self.right_agent_id,
            "rules": self.rules.to_dict(),
            "winner": self.winner,
            "finalScores": self.final_scores,
            "createdAtEpochMs": self.created_at_epoch_ms,
            "frames": [frame.to_dict() for frame in self.frames],
        }


@dataclass(slots=True)
class SimulationRoundResult:
    replay_id: str
    match_id: str
    round_index: int
    winner: int
    scores: dict[int, int]
    turn_count: int

    def to_dict(self) -> dict[str, Any]:
        return {
            "replayId": self.replay_id,
            "matchId": self.match_id,
            "roundIndex": self.round_index,
            "winner": self.winner,
            "scores": self.scores,
            "turnCount": self.turn_count,
        }


@dataclass(slots=True)
class SimulationRecord:
    id: str
    left_agent_id: str
    right_agent_id: str
    rules: GameRules
    rounds: int
    results: list[SimulationRoundResult]
    created_at_epoch_ms: int = field(default_factory=now_ms)

    def to_dict(self) -> dict[str, Any]:
        left_wins = sum(1 for item in self.results if item.winner == 1)
        right_wins = sum(1 for item in self.results if item.winner == 2)
        ties = sum(1 for item in self.results if item.winner == 0)
        average_left = sum(item.scores.get(1, 0) for item in self.results) / max(1, len(self.results))
        average_right = sum(item.scores.get(2, 0) for item in self.results) / max(1, len(self.results))
        return {
            "id": self.id,
            "leftAgentId": self.left_agent_id,
            "rightAgentId": self.right_agent_id,
            "rules": self.rules.to_dict(),
            "rounds": self.rounds,
            "createdAtEpochMs": self.created_at_epoch_ms,
            "aggregate": {
                "leftWins": left_wins,
                "rightWins": right_wins,
                "ties": ties,
                "averageLeftScore": average_left,
                "averageRightScore": average_right,
            },
            "results": [item.to_dict() for item in self.results],
        }
