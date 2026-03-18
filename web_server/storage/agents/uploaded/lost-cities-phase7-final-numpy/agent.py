from __future__ import annotations

import json
import math
import os
from typing import Any, Dict, Iterable, List, Mapping, MutableMapping, Optional, Sequence, Tuple

import numpy as np


INTERNAL_COLORS: Tuple[str, ...] = ("red", "green", "blue", "yellow", "white")
NUM_COLORS = len(INTERNAL_COLORS)
RANK_VALUES: Tuple[int, ...] = (0, 2, 3, 4, 5, 6, 7, 8, 9, 10)
NUM_RANKS = len(RANK_VALUES)
NUM_CARD_TYPES = NUM_COLORS * NUM_RANKS
COPIES_PER_RANK: Tuple[int, ...] = (3,) + (1,) * (NUM_RANKS - 1)
NUM_DRAW_SOURCES = NUM_COLORS + 1
ACTION_SIZE = NUM_CARD_TYPES * 2 * NUM_DRAW_SOURCES
INITIAL_DECK_SIZE = 44
HAND_SIZE = 8
TOTAL_CARDS = 60
PLAY_TO_EXPEDITION = 0
PLAY_TO_DISCARD = 1
DRAW_FROM_DECK = 0
OWNER_ME = 0
OWNER_OPP = 1
WAGER_CARD_TYPES = {"wager", "investment", "inv"}
LAYER_NORM_EPS = 1e-5

SUIT_ALIASES: Dict[str, str] = {
    "r": "red",
    "red": "red",
    "g": "green",
    "green": "green",
    "b": "blue",
    "blue": "blue",
    "y": "yellow",
    "yellow": "yellow",
    "w": "white",
    "white": "white",
}
SUIT_TO_INDEX = {name: idx for idx, name in enumerate(INTERNAL_COLORS)}


def card_type_id(color_idx: int, rank_idx: int) -> int:
    return color_idx * NUM_RANKS + rank_idx


def encode_action(card_id: int, play_mode: int, draw_source: int) -> int:
    return (card_id * 2 + play_mode) * NUM_DRAW_SOURCES + draw_source


def _gelu(x: np.ndarray) -> np.ndarray:
    return 0.5 * x * (1.0 + np.erf(x / math.sqrt(2.0)))


def _linear(x: np.ndarray, weight: np.ndarray, bias: np.ndarray) -> np.ndarray:
    return x @ weight.T + bias


def _layer_norm(x: np.ndarray, weight: np.ndarray, bias: np.ndarray, eps: float = LAYER_NORM_EPS) -> np.ndarray:
    mean = np.mean(x, axis=-1, keepdims=True)
    var = np.mean((x - mean) ** 2, axis=-1, keepdims=True)
    normed = (x - mean) / np.sqrt(var + eps)
    return normed * weight + bias


def _softmax(x: np.ndarray) -> np.ndarray:
    x_max = np.max(x, axis=-1, keepdims=True)
    exp_x = np.exp(x - x_max)
    return exp_x / np.sum(exp_x, axis=-1, keepdims=True)


def _normalize_suit_name(raw: Any) -> str:
    suit = str(raw or "").strip().lower()
    if suit not in SUIT_ALIASES:
        raise ValueError(f"Unsupported suit value: {raw!r}")
    return SUIT_ALIASES[suit]


def _color_index_from_suit(raw: Any) -> int:
    return SUIT_TO_INDEX[_normalize_suit_name(raw)]


def _rank_to_index(rank_value: int) -> int:
    for idx, rank in enumerate(RANK_VALUES):
        if rank == rank_value:
            return idx
    raise ValueError(f"Unsupported rank value: {rank_value!r}")


def _card_id_tokens(card_id: str) -> List[str]:
    return [part for part in card_id.lower().replace("-", "_").split("_") if part]


def _card_id_looks_like_wager(card_id: str) -> bool:
    return any(tok in WAGER_CARD_TYPES for tok in _card_id_tokens(card_id))


def _infer_suit_from_card_id(card_id: str) -> str:
    tokens = _card_id_tokens(card_id)
    if not tokens:
        raise ValueError(f"Cannot infer suit from card id: {card_id!r}")
    return _normalize_suit_name(tokens[0])


def _infer_rank_index_from_card_id(card_id: str) -> int:
    for token in reversed(_card_id_tokens(card_id)):
        if token.isdigit():
            return _rank_to_index(int(token))
    raise ValueError(f"Cannot infer rank from card id: {card_id!r}")


def _parse_card_entry(entry: Any) -> Tuple[str, int, int, int]:
    if isinstance(entry, str):
        card_id = entry
        suit_name = _infer_suit_from_card_id(card_id)
        color_idx = _color_index_from_suit(suit_name)
        rank_idx = 0 if _card_id_looks_like_wager(card_id) else _infer_rank_index_from_card_id(card_id)
        return card_id, color_idx, rank_idx, card_type_id(color_idx, rank_idx)

    if not isinstance(entry, Mapping):
        raise TypeError(f"Unsupported card entry: {entry!r}")

    card_id = str(entry.get("id") or entry.get("cardId") or "")
    suit_name = _normalize_suit_name(entry.get("suit") or _infer_suit_from_card_id(card_id))
    color_idx = _color_index_from_suit(suit_name)
    card_type = str(entry.get("type") or "").strip().lower()
    rank_value = entry.get("rank")
    is_wager = _card_id_looks_like_wager(card_id) or card_type in WAGER_CARD_TYPES
    if is_wager:
        rank_idx = 0
    elif rank_value is None:
        rank_idx = _infer_rank_index_from_card_id(card_id)
    else:
        rank_idx = _rank_to_index(int(rank_value))
    return card_id, color_idx, rank_idx, card_type_id(color_idx, rank_idx)


def _count_cards(entries: Iterable[Any]) -> np.ndarray:
    counts = np.zeros(NUM_CARD_TYPES, dtype=np.float32)
    for entry in entries:
        _, _, _, type_idx = _parse_card_entry(entry)
        counts[type_idx] += 1.0
    return counts


def _group_piles_by_color(mapping: Any) -> List[List[Any]]:
    grouped: List[List[Any]] = [[] for _ in range(NUM_COLORS)]
    if not isinstance(mapping, Mapping):
        return grouped
    for suit, cards in mapping.items():
        try:
            color_idx = _color_index_from_suit(suit)
        except Exception:
            continue
        grouped[color_idx] = list(cards or [])
    return grouped


def _expedition_summary(entries: Sequence[Any]) -> Tuple[np.ndarray, np.ndarray]:
    played = np.zeros(NUM_CARD_TYPES, dtype=np.float32)
    wagers = 0.0
    last_rank_idx = 0.0
    rank_sum = 0.0
    count = 0.0
    for entry in entries:
        _, _, rank_idx, type_idx = _parse_card_entry(entry)
        played[type_idx] += 1.0
        count += 1.0
        if rank_idx == 0:
            wagers += 1.0
        else:
            last_rank_idx = max(last_rank_idx, float(rank_idx))
            rank_sum += float(RANK_VALUES[rank_idx])
    opened = count > 0.0
    score = 0.0
    if opened:
        score = (rank_sum - 20.0) * (1.0 + wagers)
        if count >= 8.0:
            score += 20.0
    feats = np.array(
        [
            wagers / 3.0,
            last_rank_idx / float(NUM_RANKS - 1),
            rank_sum / 54.0,
            count / 12.0,
            score / 100.0,
        ],
        dtype=np.float32,
    )
    return played, feats


class ExportedLostCitiesAgent:
    def __init__(self, package_root: str) -> None:
        data_dir = os.path.join(package_root, "data")
        self.weights = np.load(os.path.join(data_dir, "model.npz"), allow_pickle=False)
        with open(os.path.join(data_dir, "model_meta.json"), "r", encoding="utf-8") as f:
            self.meta = json.load(f)
        self.num_layers = int(self.meta["model_cfg"]["num_layers"])
        self.d_model = int(self.meta["model_cfg"]["d_model"])
        self.nhead = int(self.meta["model_cfg"]["nhead"])
        self.ff_mult = int(self.meta["model_cfg"]["ff_mult"])
        self.pending_draw: Dict[Tuple[str, int], Dict[str, Any]] = {}

    def choose_turn(self, request: Dict[str, Any]) -> Dict[str, Any]:
        return self.choose_action(request)

    def choose_action(self, request: Dict[str, Any]) -> Dict[str, Any]:
        legal_actions = list(request.get("legalActions") or [])
        if not legal_actions:
            raise ValueError("No legal actions available.")
        try:
            if bool(request.get("rules", {}).get("usePurple")):
                return dict(legal_actions[0])
            if any(str(s).strip().lower() == "purple" for s in request.get("activeSuits", []) or []):
                return dict(legal_actions[0])
            phase = str(request.get("phase") or "").strip().lower()
            if phase == "draw":
                return self._choose_draw_action(request, legal_actions)
            return self._choose_play_action(request, legal_actions)
        except Exception:
            return dict(legal_actions[0])

    def _request_key(self, request: Mapping[str, Any]) -> Tuple[str, int]:
        return str(request.get("matchId") or ""), int(request.get("seat") or 0)

    def _choose_draw_action(self, request: Dict[str, Any], legal_actions: Sequence[Dict[str, Any]]) -> Dict[str, Any]:
        key = self._request_key(request)
        cached = self.pending_draw.pop(key, None)
        if cached is not None:
            for action in legal_actions:
                if self._actions_match(action, cached):
                    return dict(action)
        for action in legal_actions:
            if action.get("action") == "draw_deck":
                return dict(action)
        return dict(legal_actions[0])

    def _choose_play_action(self, request: Dict[str, Any], legal_actions: Sequence[Dict[str, Any]]) -> Dict[str, Any]:
        obs = self._build_obs(request)
        logits = self._policy_logits(obs)
        best_score = -np.inf
        best_play: Optional[Dict[str, Any]] = None
        best_draw: Optional[Dict[str, Any]] = None

        for action in legal_actions:
            act_name = str(action.get("action") or "")
            if act_name not in {"play_expedition", "discard"}:
                continue
            card_id, color_idx, rank_idx, type_idx = _parse_card_entry(
                {
                    "id": action.get("cardId"),
                    "suit": action.get("suit"),
                    "type": self._infer_action_card_type(action, request),
                    "rank": self._infer_action_rank(action, request),
                }
            )
            play_mode = PLAY_TO_EXPEDITION if act_name == "play_expedition" else PLAY_TO_DISCARD
            for draw_source, draw_resp in self._candidate_draws(request, color_idx, play_mode):
                action_id = encode_action(type_idx, play_mode, draw_source)
                score = float(logits[action_id])
                if score > best_score:
                    best_score = score
                    best_play = dict(action)
                    best_play["cardId"] = card_id
                    best_draw = draw_resp

        if best_play is None or best_draw is None:
            return dict(legal_actions[0])

        self.pending_draw[self._request_key(request)] = best_draw
        return best_play

    def _actions_match(self, left: Mapping[str, Any], right: Mapping[str, Any]) -> bool:
        if str(left.get("action")) != str(right.get("action")):
            return False
        if left.get("action") == "draw_discard":
            return _normalize_suit_name(left.get("suit")) == _normalize_suit_name(right.get("suit"))
        return True

    def _infer_action_card_type(self, action: Mapping[str, Any], request: Mapping[str, Any]) -> Optional[str]:
        card_id = action.get("cardId")
        for entry in request.get("myHand", []) or []:
            if isinstance(entry, Mapping) and (entry.get("id") == card_id or entry.get("cardId") == card_id):
                return str(entry.get("type") or "")
        return None

    def _infer_action_rank(self, action: Mapping[str, Any], request: Mapping[str, Any]) -> Optional[int]:
        card_id = action.get("cardId")
        for entry in request.get("myHand", []) or []:
            if isinstance(entry, Mapping) and (entry.get("id") == card_id or entry.get("cardId") == card_id):
                return entry.get("rank")
        return None

    def _candidate_draws(self, request: Mapping[str, Any], played_color_idx: int, play_mode: int) -> List[Tuple[int, Dict[str, Any]]]:
        candidates: List[Tuple[int, Dict[str, Any]]] = []
        deck_count = int(request.get("deckCount") or 0)
        if deck_count > 0:
            candidates.append((DRAW_FROM_DECK, {"action": "draw_deck"}))

        discard_piles = _group_piles_by_color(request.get("discardPiles") or {})
        for color_idx, pile in enumerate(discard_piles):
            if not pile:
                continue
            if play_mode == PLAY_TO_DISCARD and color_idx == played_color_idx:
                continue
            suit_name = INTERNAL_COLORS[color_idx]
            candidates.append((color_idx + 1, {"action": "draw_discard", "suit": suit_name}))

        return candidates or [(DRAW_FROM_DECK, {"action": "draw_deck"})]

    def _build_obs(self, request: Mapping[str, Any]) -> Dict[str, np.ndarray]:
        my_hand_entries = list(request.get("myHand") or [])
        my_hand = _count_cards(my_hand_entries)

        my_exps = _group_piles_by_color(request.get("myExpeditions") or {})
        opp_exps = _group_piles_by_color(request.get("opponentExpeditions") or {})
        discard_piles = _group_piles_by_color(request.get("discardPiles") or {})

        my_played = np.zeros(NUM_CARD_TYPES, dtype=np.float32)
        opp_played = np.zeros(NUM_CARD_TYPES, dtype=np.float32)
        my_exp_feats = np.zeros((NUM_COLORS, 5), dtype=np.float32)
        opp_exp_feats = np.zeros((NUM_COLORS, 5), dtype=np.float32)

        for color_idx in range(NUM_COLORS):
            played, feats = _expedition_summary(my_exps[color_idx])
            my_played += played
            my_exp_feats[color_idx] = feats
            played, feats = _expedition_summary(opp_exps[color_idx])
            opp_played += played
            opp_exp_feats[color_idx] = feats

        discard_counts = np.zeros(NUM_CARD_TYPES, dtype=np.float32)
        top_onehot = np.zeros(NUM_CARD_TYPES, dtype=np.float32)
        for color_idx, pile in enumerate(discard_piles):
            if not pile:
                continue
            discard_counts += _count_cards(pile)
            _, _, _, top_type = _parse_card_entry(pile[-1])
            top_onehot[top_type] = 1.0

        total_copies = np.array([COPIES_PER_RANK[r] for _c in range(NUM_COLORS) for r in range(NUM_RANKS)], dtype=np.float32)
        public_visible = my_played + opp_played + discard_counts
        unseen = total_copies - my_hand - public_visible
        unseen = np.maximum(unseen, 0.0)

        my_last = np.array([self._last_rank_idx_for_expedition(cards) for cards in my_exps], dtype=np.int64)
        opp_last = np.array([self._last_rank_idx_for_expedition(cards) for cards in opp_exps], dtype=np.int64)
        card_colors = np.arange(NUM_CARD_TYPES, dtype=np.int64) // NUM_RANKS
        card_ranks = np.arange(NUM_CARD_TYPES, dtype=np.int64) % NUM_RANKS
        my_last_per_card = my_last[card_colors]
        opp_last_per_card = opp_last[card_colors]
        is_wager = card_ranks == 0
        me_playable = np.where(is_wager, my_last_per_card == 0, card_ranks > my_last_per_card).astype(np.float32)
        opp_playable = np.where(is_wager, opp_last_per_card == 0, card_ranks > opp_last_per_card).astype(np.float32)

        card_feats = np.stack(
            [
                my_hand / total_copies,
                my_played / total_copies,
                opp_played / total_copies,
                discard_counts / total_copies,
                top_onehot,
                unseen / total_copies,
                me_playable,
                opp_playable,
            ],
            axis=-1,
        ).astype(np.float32)

        exp_feats = np.stack([my_exp_feats, opp_exp_feats], axis=0).astype(np.float32)
        my_score = float(np.sum(my_exp_feats[:, 4]) * 100.0)
        opp_score = float(np.sum(opp_exp_feats[:, 4]) * 100.0)
        visible_delta = my_score - opp_score
        deck_remaining = float(request.get("deckCount") or 0.0)
        total_discard = float(sum(len(pile) for pile in discard_piles))
        my_hand_size = float(len(my_hand_entries))
        history_length = float(request.get("historyLength") or 0.0)
        global_feats = np.array(
            [
                deck_remaining / float(INITIAL_DECK_SIZE),
                history_length / float(INITIAL_DECK_SIZE),
                my_score / 100.0,
                opp_score / 100.0,
                visible_delta / 100.0,
                my_hand_size / float(HAND_SIZE),
                total_discard / float(TOTAL_CARDS),
                1.0 - deck_remaining / float(INITIAL_DECK_SIZE),
            ],
            dtype=np.float32,
        )

        return {
            "card_feats": card_feats,
            "exp_feats": exp_feats,
            "global_feats": global_feats,
        }

    def _last_rank_idx_for_expedition(self, cards: Sequence[Any]) -> int:
        last = 0
        for entry in cards:
            _, _, rank_idx, _ = _parse_card_entry(entry)
            if rank_idx > 0:
                last = max(last, rank_idx)
        return last

    def _policy_logits(self, obs: Dict[str, np.ndarray]) -> np.ndarray:
        card_ids = np.arange(NUM_CARD_TYPES, dtype=np.int64)
        card_tok = self.weights["encoder.card_type_emb.weight"][card_ids]
        card_tok = card_tok + _linear(
            obs["card_feats"],
            self.weights["encoder.card_feat_proj.weight"],
            self.weights["encoder.card_feat_proj.bias"],
        )

        exp_flat = obs["exp_feats"].reshape(NUM_COLORS * 2, -1)
        exp_tok = _linear(
            exp_flat,
            self.weights["encoder.exp_feat_proj.weight"],
            self.weights["encoder.exp_feat_proj.bias"],
        )
        owners = np.array([OWNER_ME] * NUM_COLORS + [OWNER_OPP] * NUM_COLORS, dtype=np.int64)
        colors = np.array(list(range(NUM_COLORS)) * 2, dtype=np.int64)
        exp_tok = (
            exp_tok
            + self.weights["encoder.exp_color_emb.weight"][colors]
            + self.weights["encoder.exp_owner_emb.weight"][owners]
        )

        global_tok = self.weights["encoder.global_token"][0, 0] + _linear(
            obs["global_feats"],
            self.weights["encoder.global_proj.weight"],
            self.weights["encoder.global_proj.bias"],
        )
        x = np.concatenate([global_tok.reshape(1, -1), card_tok, exp_tok], axis=0).astype(np.float32)

        for layer_idx in range(self.num_layers):
            prefix = f"encoder.encoder.layers.{layer_idx}"
            x = x + self._self_attention_block(_layer_norm(x, self.weights[f"{prefix}.norm1.weight"], self.weights[f"{prefix}.norm1.bias"]), prefix)
            ff_in = _layer_norm(x, self.weights[f"{prefix}.norm2.weight"], self.weights[f"{prefix}.norm2.bias"])
            ff = _linear(ff_in, self.weights[f"{prefix}.linear1.weight"], self.weights[f"{prefix}.linear1.bias"])
            ff = _gelu(ff)
            ff = _linear(ff, self.weights[f"{prefix}.linear2.weight"], self.weights[f"{prefix}.linear2.bias"])
            x = x + ff

        x = _layer_norm(x, self.weights["encoder.norm.weight"], self.weights["encoder.norm.bias"])
        root = x[0]
        logits = root
        for idx in (0, 3, 6):
            logits = _linear(
                logits,
                self.weights[f"policy_head.net.{idx}.weight"],
                self.weights[f"policy_head.net.{idx}.bias"],
            )
            if idx != 6:
                logits = _gelu(logits)
        return logits.astype(np.float32)

    def _self_attention_block(self, x: np.ndarray, prefix: str) -> np.ndarray:
        d_model = self.d_model
        nhead = self.nhead
        head_dim = d_model // nhead
        proj = _linear(
            x,
            self.weights[f"{prefix}.self_attn.in_proj_weight"],
            self.weights[f"{prefix}.self_attn.in_proj_bias"],
        )
        q, k, v = np.split(proj, 3, axis=-1)
        q = q.reshape(x.shape[0], nhead, head_dim).transpose(1, 0, 2)
        k = k.reshape(x.shape[0], nhead, head_dim).transpose(1, 0, 2)
        v = v.reshape(x.shape[0], nhead, head_dim).transpose(1, 0, 2)
        scores = np.matmul(q, np.transpose(k, (0, 2, 1))) / math.sqrt(float(head_dim))
        weights = _softmax(scores)
        attn = np.matmul(weights, v)
        attn = attn.transpose(1, 0, 2).reshape(x.shape[0], d_model)
        return _linear(
            attn,
            self.weights[f"{prefix}.self_attn.out_proj.weight"],
            self.weights[f"{prefix}.self_attn.out_proj.bias"],
        )


def build_agent(package_root: Optional[str] = None) -> ExportedLostCitiesAgent:
    root = package_root or os.path.dirname(os.path.abspath(__file__))
    return ExportedLostCitiesAgent(root)
