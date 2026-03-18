from __future__ import annotations

import json
import math
import os
from typing import Any, Dict, Iterable, List, Optional, Sequence, Tuple

import numpy as np


LONG_SUITS: Tuple[str, ...] = ("red", "green", "blue", "yellow", "white")
SHORT_SUITS: Tuple[str, ...] = ("R", "G", "B", "Y", "W")
SUIT_TO_INDEX = {name: idx for idx, name in enumerate(LONG_SUITS)}
SUIT_TO_INDEX.update({name.lower(): idx for idx, name in enumerate(SHORT_SUITS)})
INDEX_TO_SUIT = {idx: name for idx, name in enumerate(LONG_SUITS)}

NUM_COLORS = 5
NUM_RANKS = 10
NUM_CARD_TYPES = NUM_COLORS * NUM_RANKS
HAND_SIZE = 8
INITIAL_DECK_SIZE = 44
TOTAL_CARDS = 60
NUM_DRAW_SOURCES = NUM_COLORS + 1
ACTION_SIZE = NUM_CARD_TYPES * 2 * NUM_DRAW_SOURCES

PLAY_TO_EXPEDITION = 0
PLAY_TO_DISCARD = 1
DRAW_FROM_DECK = 0

RANK_VALUES: Tuple[int, ...] = (0, 2, 3, 4, 5, 6, 7, 8, 9, 10)
COPIES_PER_RANK: Tuple[int, ...] = (3,) + (1,) * (NUM_RANKS - 1)
TOTAL_COPIES = np.array([copies for _ in range(NUM_COLORS) for copies in COPIES_PER_RANK], dtype=np.float32)


def card_type_id(color_idx: int, rank_idx: int) -> int:
    return color_idx * NUM_RANKS + rank_idx


def encode_action(card_id: int, play_mode: int, draw_source: int) -> int:
    return (card_id * 2 + play_mode) * NUM_DRAW_SOURCES + draw_source


def _linear(x: np.ndarray, weight: np.ndarray, bias: np.ndarray) -> np.ndarray:
    return x @ weight.T + bias


def _gelu(x: np.ndarray) -> np.ndarray:
    return 0.5 * x * (1.0 + np.tanh(np.sqrt(2.0 / np.pi) * (x + 0.044715 * x * x * x)))


def _layer_norm(x: np.ndarray, weight: np.ndarray, bias: np.ndarray, eps: float = 1e-5) -> np.ndarray:
    mean = np.mean(x, axis=-1, keepdims=True)
    var = np.var(x, axis=-1, keepdims=True)
    normed = (x - mean) / np.sqrt(var + eps)
    return normed * weight + bias


def _softmax_last(x: np.ndarray) -> np.ndarray:
    shifted = x - np.max(x, axis=-1, keepdims=True)
    ex = np.exp(shifted)
    denom = np.sum(ex, axis=-1, keepdims=True)
    denom = np.where(denom > 0.0, denom, 1.0)
    return ex / denom


def _safe_lower(value: Any) -> str:
    if value is None:
        return ""
    return str(value).strip().lower()


def _normalize_suit_name(value: Any) -> str:
    suit = _safe_lower(value)
    if suit in SUIT_TO_INDEX:
        return LONG_SUITS[SUIT_TO_INDEX[suit]]
    raise ValueError(f"unsupported suit: {value!r}")


def _rank_to_index(rank: int) -> int:
    if rank == 0:
        return 0
    if rank == 1:
        return 0
    if 2 <= rank <= 10:
        return rank - 1
    raise ValueError(f"unsupported rank: {rank!r}")


def _parse_card_entry(card: Any) -> Tuple[int, int, str]:
    if isinstance(card, str):
        card_id = card
        suit = _normalize_suit_name(card.split("_", 1)[0])
        tail = _safe_lower(card.split("_")[-1])
        if "wager" in _safe_lower(card) or tail in {"wager", "investment", "inv", "1"}:
            rank_idx = 0
        else:
            rank_idx = _rank_to_index(int(tail))
        color_idx = SUIT_TO_INDEX[suit]
        return card_type_id(color_idx, rank_idx), color_idx, card_id

    if not isinstance(card, dict):
        raise ValueError(f"unsupported card entry: {card!r}")

    raw_card_id = card.get("id") or card.get("cardId")
    if raw_card_id is None:
        raise ValueError(f"card entry missing id/cardId: {card!r}")
    card_id = str(raw_card_id)
    suit = _normalize_suit_name(card.get("suit") or card_id.split("_", 1)[0])
    color_idx = SUIT_TO_INDEX[suit]

    rank_value = card.get("rank")
    card_type = _safe_lower(card.get("type"))
    tail = _safe_lower(card_id.split("_")[-1])
    if rank_value is None:
        if "wager" in _safe_lower(card_id) or tail in {"wager", "investment", "inv", "1"} or card_type in {"wager", "investment"}:
            rank_idx = 0
        else:
            rank_idx = _rank_to_index(int(tail))
    else:
        rank_idx = _rank_to_index(int(rank_value))

    return card_type_id(color_idx, rank_idx), color_idx, card_id


def _empty_expedition_stats() -> Dict[str, np.ndarray]:
    return {
        "counts_by_type": np.zeros(NUM_CARD_TYPES, dtype=np.float32),
        "wagers": np.zeros(NUM_COLORS, dtype=np.float32),
        "last_rank": np.zeros(NUM_COLORS, dtype=np.float32),
        "exp_sum": np.zeros(NUM_COLORS, dtype=np.float32),
        "exp_count": np.zeros(NUM_COLORS, dtype=np.float32),
    }


def _compute_expedition_stats(expeditions: Dict[str, Sequence[Any]]) -> Dict[str, np.ndarray]:
    stats = _empty_expedition_stats()
    for suit_name in LONG_SUITS:
        cards = expeditions.get(suit_name, []) or expeditions.get(suit_name.capitalize(), []) or []
        color_idx = SUIT_TO_INDEX[suit_name]
        max_rank_idx = 0
        for card in cards:
            type_id, _, _ = _parse_card_entry(card)
            rank_idx = type_id % NUM_RANKS
            stats["counts_by_type"][type_id] += 1.0
            stats["exp_count"][color_idx] += 1.0
            if rank_idx == 0:
                stats["wagers"][color_idx] += 1.0
            else:
                max_rank_idx = max(max_rank_idx, rank_idx)
                stats["exp_sum"][color_idx] += float(RANK_VALUES[rank_idx])
        stats["last_rank"][color_idx] = float(max_rank_idx)
    return stats


def _compute_expedition_scores(wagers: np.ndarray, exp_sum: np.ndarray, exp_count: np.ndarray) -> np.ndarray:
    opened = exp_count > 0.0
    subtotal = (exp_sum - 20.0) * (1.0 + wagers)
    bonus = (exp_count >= 8.0).astype(np.float32) * 20.0
    return np.where(opened, subtotal + bonus, 0.0).astype(np.float32)


def _build_observation(request: Dict[str, Any]) -> Dict[str, np.ndarray]:
    my_hand = np.zeros(NUM_CARD_TYPES, dtype=np.float32)
    for card in request.get("myHand", []) or []:
        type_id, _, _ = _parse_card_entry(card)
        my_hand[type_id] += 1.0

    my_exp = _compute_expedition_stats(request.get("myExpeditions", {}) or {})
    opp_exp = _compute_expedition_stats(request.get("opponentExpeditions", {}) or {})
    my_played = my_exp["counts_by_type"]
    opp_played = opp_exp["counts_by_type"]

    discard_counts = np.zeros(NUM_CARD_TYPES, dtype=np.float32)
    top_onehot = np.zeros(NUM_CARD_TYPES, dtype=np.float32)
    total_discard_cards = 0.0
    discard_piles = request.get("discardPiles", {}) or {}
    for suit_name in LONG_SUITS:
        pile = discard_piles.get(suit_name, []) or discard_piles.get(suit_name.capitalize(), []) or []
        if not pile:
            continue
        total_discard_cards += float(len(pile))
        top_type = None
        for card in pile:
            type_id, _, _ = _parse_card_entry(card)
            discard_counts[type_id] += 1.0
            top_type = type_id
        if top_type is not None:
            top_onehot[top_type] = 1.0

    public_visible = my_played + opp_played + discard_counts
    unseen = np.maximum(TOTAL_COPIES - my_hand - public_visible, 0.0)

    card_color = np.arange(NUM_CARD_TYPES, dtype=np.int32) // NUM_RANKS
    card_rank = np.arange(NUM_CARD_TYPES, dtype=np.int32) % NUM_RANKS
    my_last_per_card = my_exp["last_rank"][card_color]
    opp_last_per_card = opp_exp["last_rank"][card_color]
    is_wager = card_rank == 0
    me_playable = np.where(is_wager, my_last_per_card == 0, card_rank > my_last_per_card).astype(np.float32)
    opp_playable = np.where(is_wager, opp_last_per_card == 0, card_rank > opp_last_per_card).astype(np.float32)

    total_copies = TOTAL_COPIES
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

    my_scores = _compute_expedition_scores(my_exp["wagers"], my_exp["exp_sum"], my_exp["exp_count"])
    opp_scores = _compute_expedition_scores(opp_exp["wagers"], opp_exp["exp_sum"], opp_exp["exp_count"])

    my_exp_feats = np.stack(
        [
            my_exp["wagers"] / 3.0,
            my_exp["last_rank"] / float(NUM_RANKS - 1),
            my_exp["exp_sum"] / 54.0,
            my_exp["exp_count"] / 12.0,
            my_scores / 100.0,
        ],
        axis=-1,
    ).astype(np.float32)

    opp_exp_feats = np.stack(
        [
            opp_exp["wagers"] / 3.0,
            opp_exp["last_rank"] / float(NUM_RANKS - 1),
            opp_exp["exp_sum"] / 54.0,
            opp_exp["exp_count"] / 12.0,
            opp_scores / 100.0,
        ],
        axis=-1,
    ).astype(np.float32)

    exp_feats = np.stack([my_exp_feats, opp_exp_feats], axis=0).astype(np.float32)

    deck_remaining = float(request.get("deckCount", 0))
    history_length = float(request.get("historyLength", max(0.0, INITIAL_DECK_SIZE - deck_remaining)))
    my_score = float(np.sum(my_scores))
    opp_score = float(np.sum(opp_scores))
    visible_delta = my_score - opp_score
    my_hand_size = float(len(request.get("myHand", []) or []))
    global_feats = np.array(
        [
            deck_remaining / float(INITIAL_DECK_SIZE),
            history_length / float(INITIAL_DECK_SIZE),
            my_score / 100.0,
            opp_score / 100.0,
            visible_delta / 100.0,
            my_hand_size / float(HAND_SIZE),
            total_discard_cards / float(TOTAL_CARDS),
            1.0 - deck_remaining / float(INITIAL_DECK_SIZE),
        ],
        dtype=np.float32,
    )

    return {
        "card_feats": card_feats,
        "exp_feats": exp_feats,
        "global_feats": global_feats,
    }


def _match_legal_action(legal_actions: Sequence[Dict[str, Any]], *, action: str, card_id: Optional[str] = None, suit: Optional[str] = None) -> Optional[Dict[str, Any]]:
    norm_suit = None if suit is None else _normalize_suit_name(suit)
    for item in legal_actions:
        if item.get("action") != action:
            continue
        if card_id is not None and item.get("cardId") != card_id:
            continue
        if norm_suit is not None:
            try:
                item_suit = _normalize_suit_name(item.get("suit"))
            except Exception:
                continue
            if item_suit != norm_suit:
                continue
        return dict(item)
    return None


def _enumerate_full_actions_for_play(request: Dict[str, Any]) -> List[Dict[str, Any]]:
    full_actions: List[Dict[str, Any]] = []
    legal_actions = request.get("legalActions", []) or []
    discard_piles = request.get("discardPiles", {}) or {}
    deck_count = int(request.get("deckCount", 0))

    pile_sizes = {
        suit_name: len(discard_piles.get(suit_name, []) or discard_piles.get(suit_name.capitalize(), []) or [])
        for suit_name in LONG_SUITS
    }

    for action in legal_actions:
        action_name = _safe_lower(action.get("action"))
        if action_name not in {"play_expedition", "discard"}:
            continue
        card_id = str(action.get("cardId"))
        type_id, color_idx, _ = _parse_card_entry({"cardId": card_id, "suit": action.get("suit")})
        play_mode = PLAY_TO_EXPEDITION if action_name == "play_expedition" else PLAY_TO_DISCARD

        if deck_count > 0:
            full_actions.append(
                {
                    "combined_action_id": encode_action(type_id, play_mode, DRAW_FROM_DECK),
                    "play_action": dict(action),
                    "draw_action": {"action": "draw_deck"},
                }
            )

        for suit_name in LONG_SUITS:
            pile_size = pile_sizes[suit_name]
            if pile_size <= 0:
                continue
            draw_color = SUIT_TO_INDEX[suit_name]
            if play_mode == PLAY_TO_DISCARD and draw_color == color_idx:
                continue
            full_actions.append(
                {
                    "combined_action_id": encode_action(type_id, play_mode, draw_color + 1),
                    "play_action": dict(action),
                    "draw_action": {"action": "draw_discard", "suit": suit_name},
                }
            )

    return full_actions


class NumpyPolicyValueBeliefNet:
    def __init__(self, weights_path: str) -> None:
        raw = np.load(weights_path)
        self.w = {key: raw[key].astype(np.float32) for key in raw.files}
        self.d_model = int(self.w["encoder.global_token"].shape[-1])
        self.nhead = int(self.w["encoder.encoder.layers.0.self_attn.in_proj_weight"].shape[0] // (3 * self.d_model))
        self.head_dim = self.d_model // self.nhead
        self.num_layers = sum(
            key.startswith("encoder.encoder.layers.") and key.endswith(".norm1.weight")
            for key in self.w.keys()
        )

    def _attention_block(self, x: np.ndarray, layer_idx: int) -> np.ndarray:
        prefix = f"encoder.encoder.layers.{layer_idx}"
        x_norm = _layer_norm(
            x,
            self.w[f"{prefix}.norm1.weight"],
            self.w[f"{prefix}.norm1.bias"],
        )

        in_proj_weight = self.w[f"{prefix}.self_attn.in_proj_weight"]
        in_proj_bias = self.w[f"{prefix}.self_attn.in_proj_bias"]
        qkv = _linear(x_norm, in_proj_weight, in_proj_bias)
        q, k, v = np.split(qkv, 3, axis=-1)

        seq_len = x.shape[0]
        q = q.reshape(seq_len, self.nhead, self.head_dim).transpose(1, 0, 2)
        k = k.reshape(seq_len, self.nhead, self.head_dim).transpose(1, 0, 2)
        v = v.reshape(seq_len, self.nhead, self.head_dim).transpose(1, 0, 2)

        scores = (q @ np.transpose(k, (0, 2, 1))) / math.sqrt(float(self.head_dim))
        probs = _softmax_last(scores)
        ctx = probs @ v
        ctx = ctx.transpose(1, 0, 2).reshape(seq_len, self.d_model)

        out = _linear(
            ctx,
            self.w[f"{prefix}.self_attn.out_proj.weight"],
            self.w[f"{prefix}.self_attn.out_proj.bias"],
        )
        return x + out

    def _ffn_block(self, x: np.ndarray, layer_idx: int) -> np.ndarray:
        prefix = f"encoder.encoder.layers.{layer_idx}"
        x_norm = _layer_norm(
            x,
            self.w[f"{prefix}.norm2.weight"],
            self.w[f"{prefix}.norm2.bias"],
        )
        hidden = _gelu(
            _linear(
                x_norm,
                self.w[f"{prefix}.linear1.weight"],
                self.w[f"{prefix}.linear1.bias"],
            )
        )
        out = _linear(
            hidden,
            self.w[f"{prefix}.linear2.weight"],
            self.w[f"{prefix}.linear2.bias"],
        )
        return x + out

    def _mlp(self, x: np.ndarray, prefix: str) -> np.ndarray:
        h1 = _gelu(_linear(x, self.w[f"{prefix}.net.0.weight"], self.w[f"{prefix}.net.0.bias"]))
        h2 = _gelu(_linear(h1, self.w[f"{prefix}.net.3.weight"], self.w[f"{prefix}.net.3.bias"]))
        return _linear(h2, self.w[f"{prefix}.net.6.weight"], self.w[f"{prefix}.net.6.bias"])

    def forward(self, obs: Dict[str, np.ndarray]) -> Dict[str, np.ndarray]:
        card_ids = np.arange(NUM_CARD_TYPES, dtype=np.int32)
        card_tok = self.w["encoder.card_type_emb.weight"][card_ids] + _linear(
            obs["card_feats"],
            self.w["encoder.card_feat_proj.weight"],
            self.w["encoder.card_feat_proj.bias"],
        )

        exp_feats = obs["exp_feats"].reshape(2 * NUM_COLORS, 5)
        owners = np.array([0] * NUM_COLORS + [1] * NUM_COLORS, dtype=np.int32)
        colors = np.array(list(range(NUM_COLORS)) * 2, dtype=np.int32)
        exp_tok = (
            _linear(exp_feats, self.w["encoder.exp_feat_proj.weight"], self.w["encoder.exp_feat_proj.bias"])
            + self.w["encoder.exp_color_emb.weight"][colors]
            + self.w["encoder.exp_owner_emb.weight"][owners]
        )

        global_tok = self.w["encoder.global_token"][0, 0] + _linear(
            obs["global_feats"],
            self.w["encoder.global_proj.weight"],
            self.w["encoder.global_proj.bias"],
        )
        x = np.concatenate([global_tok[None, :], card_tok, exp_tok], axis=0).astype(np.float32)

        for layer_idx in range(self.num_layers):
            x = self._attention_block(x, layer_idx)
            x = self._ffn_block(x, layer_idx)

        x = _layer_norm(x, self.w["encoder.norm.weight"], self.w["encoder.norm.bias"])
        root = x[0]
        policy_logits = self._mlp(root, "policy_head")
        value = np.tanh(self._mlp(root, "value_head"))[0]
        belief_hand = self._mlp(root, "belief_hand_head").reshape(NUM_CARD_TYPES, 3)
        belief_deck = self._mlp(root, "belief_deck_head").reshape(NUM_CARD_TYPES, 3)
        return {
            "policy_logits": policy_logits.astype(np.float32),
            "value": np.array(value, dtype=np.float32),
            "belief_hand_logits": belief_hand.astype(np.float32),
            "belief_deck_logits": belief_deck.astype(np.float32),
        }


class LostCitiesNumpyAgent:
    def __init__(self, package_root: str) -> None:
        data_dir = os.path.join(package_root, "data")
        weights_path = os.path.join(data_dir, "model.npz")
        meta_path = os.path.join(data_dir, "model_meta.json")
        if not os.path.exists(weights_path):
            raise FileNotFoundError(f"missing exported weights: {weights_path}")
        self.model = NumpyPolicyValueBeliefNet(weights_path)
        self.meta = {}
        if os.path.exists(meta_path):
            with open(meta_path, "r", encoding="utf-8") as f:
                self.meta = json.load(f)
        self.pending_draw: Dict[Tuple[str, int], Dict[str, Any]] = {}

    def _request_key(self, request: Dict[str, Any]) -> Tuple[str, int]:
        return (str(request.get("matchId", "")), int(request.get("seat", 0)))

    def _clear_if_terminal(self, request: Dict[str, Any]) -> None:
        status = _safe_lower(request.get("status"))
        if status in {"finished", "aborted"}:
            self.pending_draw.pop(self._request_key(request), None)

    def _reject_purple_if_needed(self, request: Dict[str, Any]) -> None:
        rules = request.get("rules", {}) or {}
        if bool(rules.get("usePurple")):
            raise ValueError("This exported agent does not support the purple variant.")
        active_suits = [_safe_lower(s) for s in (request.get("activeSuits", []) or [])]
        if "purple" in active_suits:
            raise ValueError("This exported agent does not support the purple variant.")

    def _fallback_draw(self, request: Dict[str, Any]) -> Dict[str, Any]:
        legal_actions = request.get("legalActions", []) or []
        draw_deck = _match_legal_action(legal_actions, action="draw_deck")
        if draw_deck is not None:
            return draw_deck
        if not legal_actions:
            raise ValueError("No legal draw actions available.")
        return dict(legal_actions[0])

    def _choose_play(self, request: Dict[str, Any]) -> Dict[str, Any]:
        legal_actions = request.get("legalActions", []) or []
        if not legal_actions:
            raise ValueError("No legal play actions available.")

        obs = _build_observation(request)
        outputs = self.model.forward(obs)
        logits = outputs["policy_logits"].copy()
        full_actions = _enumerate_full_actions_for_play(request)
        if not full_actions:
            chosen = dict(legal_actions[0])
            self.pending_draw.pop(self._request_key(request), None)
            return chosen

        mask = np.zeros(ACTION_SIZE, dtype=bool)
        for item in full_actions:
            mask[item["combined_action_id"]] = True
        logits[~mask] = -1.0e30

        best_idx = int(np.argmax(logits))
        chosen_full = None
        for item in full_actions:
            if item["combined_action_id"] == best_idx:
                chosen_full = item
                break
        if chosen_full is None:
            chosen_full = max(full_actions, key=lambda item: float(logits[item["combined_action_id"]]))

        self.pending_draw[self._request_key(request)] = dict(chosen_full["draw_action"])

        chosen_play = chosen_full["play_action"]
        matched = _match_legal_action(
            legal_actions,
            action=str(chosen_play["action"]),
            card_id=str(chosen_play.get("cardId")),
            suit=chosen_play.get("suit"),
        )
        if matched is not None:
            return matched
        return dict(chosen_play)

    def _choose_draw(self, request: Dict[str, Any]) -> Dict[str, Any]:
        key = self._request_key(request)
        legal_actions = request.get("legalActions", []) or []
        pending = self.pending_draw.pop(key, None)
        if pending is not None:
            if pending.get("action") == "draw_deck":
                matched = _match_legal_action(legal_actions, action="draw_deck")
            else:
                matched = _match_legal_action(legal_actions, action="draw_discard", suit=pending.get("suit"))
            if matched is not None:
                return matched
        return self._fallback_draw(request)

    def choose_action(self, request: Dict[str, Any]) -> Dict[str, Any]:
        self._clear_if_terminal(request)
        self._reject_purple_if_needed(request)

        legal_actions = request.get("legalActions", []) or []
        if not legal_actions:
            raise ValueError("No legal actions available.")

        phase = _safe_lower(request.get("phase"))
        if phase == "play":
            return self._choose_play(request)
        if phase == "draw":
            return self._choose_draw(request)

        return dict(legal_actions[0])

    def choose_turn(self, request: Dict[str, Any]) -> Dict[str, Any]:
        return self.choose_action(request)


def build_agent(package_root: Optional[str] = None) -> LostCitiesNumpyAgent:
    if package_root is None:
        package_root = os.path.dirname(os.path.abspath(__file__))
    return LostCitiesNumpyAgent(package_root)
