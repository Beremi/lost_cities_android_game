from __future__ import annotations

from dataclasses import asdict
import random
from typing import Any

from .manifest import ManifestBundle
from .models import (
    MATCH_ACTIVE,
    MATCH_FINISHED,
    PHASE_DRAW,
    PHASE_PLAY,
    GameRules,
    LostCitiesState,
    MatchRecord,
    PlayerSlot,
    PlayerState,
    ReplayFrame,
    now_ms,
)


HAND_SIZE = 8


def other_player(player: int) -> int:
    return 2 if player == 1 else 1


def create_waiting_lost_cities(active_suits: list[str], players: dict[int, PlayerSlot]) -> LostCitiesState:
    return LostCitiesState(
        turn_player=1,
        phase=PHASE_PLAY,
        deck=[],
        discard_piles={suit: [] for suit in active_suits},
        players={player: PlayerState(hand=[], expeditions={suit: [] for suit in active_suits}) for player in players},
        just_discarded_card_id=None,
        final_turns_remaining=0,
    )


def create_deck(bundle: ManifestBundle, rules: GameRules, rng: random.Random | None = None) -> list[str]:
    deck = [card["id"] for card in bundle.active_cards(rules.use_purple)]
    (rng or random).shuffle(deck)
    return deck


def score_breakdown(card_ids: list[str], card_by_id: dict[str, dict]) -> dict[str, Any]:
    if not card_ids:
        return {
            "cardIds": [],
            "pointSum": 0,
            "wagerCount": 0,
            "multiplier": 1,
            "baseBeforeMultiplier": 0,
            "multipliedScore": 0,
            "bonus": 0,
            "total": 0,
            "hasCards": False,
        }
    wagers = 0
    point_sum = 0
    for card_id in card_ids:
        rank = card_by_id[card_id]["rank"]
        if rank is None:
            wagers += 1
        else:
            point_sum += int(rank)
    base_before_multiplier = point_sum - 20
    multiplier = wagers + 1
    multiplied = base_before_multiplier * multiplier
    bonus = 20 if len(card_ids) >= 8 else 0
    return {
        "cardIds": list(card_ids),
        "pointSum": point_sum,
        "wagerCount": wagers,
        "multiplier": multiplier,
        "baseBeforeMultiplier": base_before_multiplier,
        "multipliedScore": multiplied,
        "bonus": bonus,
        "total": multiplied + bonus,
        "hasCards": True,
    }


def compute_scores(state: LostCitiesState, active_suits: list[str], card_by_id: dict[str, dict]) -> dict[int, int]:
    totals: dict[int, int] = {}
    for player, player_state in state.players.items():
        total = 0
        for suit in active_suits:
            total += int(score_breakdown(player_state.expeditions.get(suit, []), card_by_id)["total"])
        totals[player] = total
    return totals


def expedition_breakdowns(state: LostCitiesState, active_suits: list[str], card_by_id: dict[str, dict]) -> dict[int, dict[str, dict[str, Any]]]:
    return {
        player: {suit: score_breakdown(player_state.expeditions.get(suit, []), card_by_id) for suit in active_suits}
        for player, player_state in state.players.items()
    }


def validate_expedition_play(card: dict, existing_column: list[str], card_by_id: dict[str, dict]) -> str | None:
    rank = card["rank"]
    if rank is None:
        for existing_card_id in existing_column:
            if card_by_id[existing_card_id]["rank"] is not None:
                return f"Wagers must be played before number cards in {card['suit']}."
        return None
    current_max = None
    for existing_card_id in reversed(existing_column):
        existing_rank = card_by_id[existing_card_id]["rank"]
        if existing_rank is not None:
            current_max = int(existing_rank)
            break
    if current_max is not None and int(rank) <= int(current_max):
        return f"Card value {rank} must be higher than {current_max} in {card['suit']}."
    return None


def create_active_match(
    *,
    match_id: str,
    players: dict[int, PlayerSlot],
    rules: GameRules,
    bundle: ManifestBundle,
    rng: random.Random | None = None,
    reveal_all: bool = False,
    capture_replay: bool = False,
) -> MatchRecord:
    active_suits = bundle.active_suits(rules.use_purple)
    deck = create_deck(bundle, rules, rng=rng)
    player_states: dict[int, PlayerState] = {}
    for player in sorted(players):
        hand = [deck.pop() for _ in range(HAND_SIZE)]
        player_states[player] = PlayerState(hand=hand, expeditions={suit: [] for suit in active_suits})
    lost_cities = LostCitiesState(
        turn_player=1,
        phase=PHASE_PLAY,
        deck=deck,
        discard_piles={suit: [] for suit in active_suits},
        players=player_states,
        just_discarded_card_id=None,
        final_turns_remaining=0,
    )
    created = now_ms()
    match = MatchRecord(
        id=match_id,
        status=MATCH_ACTIVE,
        players=players,
        rules=rules,
        score=compute_scores(lost_cities, active_suits, bundle.card_by_id),
        lost_cities=lost_cities,
        created_at_epoch_ms=created,
        updated_at_epoch_ms=created,
        last_event="Round started. Player 1 plays first.",
        history=["Round started. Player 1 plays first."],
        reveal_all=reveal_all,
        capture_replay=capture_replay,
    )
    record_frame(match, label="start", reveal_all=True, bundle=bundle)
    return match


def record_frame(match: MatchRecord, *, label: str, reveal_all: bool, bundle: ManifestBundle) -> None:
    if not match.capture_replay:
        return
    frame = ReplayFrame(
        index=len(match.replay_frames),
        label=label,
        event=match.last_event,
        state=build_match_view(
            match,
            viewer_user_id=None,
            bundle=bundle,
            reveal_all=reveal_all,
            include_history=False,
            include_breakdowns=False,
        ),
    )
    match.replay_frames.append(frame)


def legal_actions(match: MatchRecord, player: int, bundle: ManifestBundle) -> list[dict[str, Any]]:
    if match.status != MATCH_ACTIVE or match.lost_cities.turn_player != player:
        return []
    actions: list[dict[str, Any]] = []
    player_state = match.lost_cities.players[player]
    if match.lost_cities.phase == PHASE_PLAY:
        for card_id in player_state.hand:
            card = bundle.card_by_id[card_id]
            expedition = player_state.expeditions.get(card["suit"], [])
            if validate_expedition_play(card, expedition, bundle.card_by_id) is None:
                actions.append({"action": "play_expedition", "cardId": card_id, "suit": card["suit"]})
            actions.append({"action": "discard", "cardId": card_id, "suit": card["suit"]})
        if match.lost_cities.final_turns_remaining > 0:
            return [action for action in actions if action["action"] in {"play_expedition", "discard"}]
        return actions
    if match.lost_cities.final_turns_remaining > 0:
        return []
    if match.lost_cities.deck:
        actions.append({"action": "draw_deck"})
    for suit, pile in match.lost_cities.discard_piles.items():
        if not pile:
            continue
        if match.lost_cities.just_discarded_card_id and pile[-1] == match.lost_cities.just_discarded_card_id:
            continue
        actions.append({"action": "draw_discard", "suit": suit})
    return actions


def apply_action(
    match: MatchRecord,
    *,
    player: int,
    action: str,
    bundle: ManifestBundle,
    card_id: str = "",
    suit: str | None = None,
) -> MatchRecord:
    updated = match
    active_suits = bundle.active_suits(updated.rules.use_purple)
    now_epoch_ms = now_ms()
    normalized = action.strip().lower()
    updated.updated_at_epoch_ms = now_epoch_ms
    lost = updated.lost_cities
    if updated.status != MATCH_ACTIVE:
        raise ValueError("Match is not active.")
    if lost.turn_player != player:
        raise ValueError("Not your turn.")
    player_state = lost.players[player]

    if normalized == "play_expedition":
        if lost.phase != PHASE_PLAY:
            raise ValueError("Draw a card to finish the turn first.")
        card = bundle.card_by_id.get(card_id)
        if card is None:
            raise ValueError(f"Unknown card '{card_id}'.")
        if card_id not in player_state.hand:
            raise ValueError(f"Card '{card_id}' is not in hand.")
        expedition = list(player_state.expeditions.get(card["suit"], []))
        reason = validate_expedition_play(card, expedition, bundle.card_by_id)
        if reason is not None:
            raise ValueError(reason)
        player_state.hand.remove(card_id)
        expedition.append(card_id)
        player_state.expeditions[card["suit"]] = expedition
        updated.last_event = f"Player {player} played {card_id} to {card['suit']}."
        if lost.final_turns_remaining > 0:
            _advance_final_turn(updated, player=player, active_suits=active_suits, bundle=bundle)
        else:
            lost.phase = PHASE_DRAW
            lost.just_discarded_card_id = None
            updated.score = compute_scores(lost, active_suits, bundle.card_by_id)
    elif normalized == "discard":
        if lost.phase != PHASE_PLAY:
            raise ValueError("Draw a card to finish the turn first.")
        card = bundle.card_by_id.get(card_id)
        if card is None:
            raise ValueError(f"Unknown card '{card_id}'.")
        if card_id not in player_state.hand:
            raise ValueError(f"Card '{card_id}' is not in hand.")
        player_state.hand.remove(card_id)
        lost.discard_piles.setdefault(card["suit"], []).append(card_id)
        updated.last_event = f"Player {player} discarded {card_id}."
        if lost.final_turns_remaining > 0:
            _advance_final_turn(updated, player=player, active_suits=active_suits, bundle=bundle)
        else:
            lost.phase = PHASE_DRAW
            lost.just_discarded_card_id = card_id
            updated.score = compute_scores(lost, active_suits, bundle.card_by_id)
    elif normalized == "draw_deck":
        if lost.phase != PHASE_DRAW:
            raise ValueError("Play or discard a card first.")
        if not lost.deck:
            raise ValueError("Draw pile is empty.")
        drawn = lost.deck.pop()
        player_state.hand.append(drawn)
        next_player = other_player(player)
        last_card_drawn = not lost.deck
        lost.turn_player = next_player
        lost.phase = PHASE_PLAY
        lost.just_discarded_card_id = None
        lost.final_turns_remaining = 2 if last_card_drawn else 0
        updated.last_event = (
            f"Player {player} drew the final deck card. Final turns begin: Player {next_player} plays one last card, no draw."
            if last_card_drawn
            else f"Player {player} drew one card from the draw pile."
        )
        updated.score = compute_scores(lost, active_suits, bundle.card_by_id)
    elif normalized == "draw_discard":
        if lost.phase != PHASE_DRAW:
            raise ValueError("Play or discard a card first.")
        target_suit = (suit or "").strip().lower()
        if not target_suit:
            raise ValueError("Suit is required.")
        pile = lost.discard_piles.get(target_suit, [])
        if not pile:
            raise ValueError(f"Discard pile '{target_suit}' is empty.")
        top_card_id = pile[-1]
        if top_card_id == lost.just_discarded_card_id:
            raise ValueError("You cannot take back the same card you just discarded.")
        player_state.hand.append(top_card_id)
        del pile[-1]
        lost.turn_player = other_player(player)
        lost.phase = PHASE_PLAY
        lost.just_discarded_card_id = None
        updated.last_event = f"Player {player} drew {top_card_id} from {target_suit} discard."
        updated.score = compute_scores(lost, active_suits, bundle.card_by_id)
    else:
        raise ValueError(f"Unknown action '{action}'.")

    updated.history.append(updated.last_event)
    updated.action_log.append(
        {
            "index": len(updated.action_log),
            "player": player,
            "action": normalized,
            "cardId": card_id or None,
            "suit": suit,
            "event": updated.last_event,
        }
    )
    record_frame(updated, label=normalized, reveal_all=True, bundle=bundle)
    return updated


def _advance_final_turn(updated: MatchRecord, *, player: int, active_suits: list[str], bundle: ManifestBundle) -> None:
    remaining = max(updated.lost_cities.final_turns_remaining - 1, 0)
    updated.lost_cities.final_turns_remaining = remaining
    updated.lost_cities.turn_player = other_player(player)
    updated.lost_cities.phase = PHASE_PLAY
    updated.lost_cities.just_discarded_card_id = None
    updated.status = MATCH_FINISHED if remaining == 0 else MATCH_ACTIVE
    if remaining == 0:
        updated.last_event = f"{updated.last_event} Final turn complete. Round over."
    else:
        updated.last_event = f"{updated.last_event} Final turn for Player {updated.lost_cities.turn_player}: play one last card, no draw."
    updated.score = compute_scores(updated.lost_cities, active_suits, bundle.card_by_id)


def build_match_view(
    match: MatchRecord,
    *,
    viewer_user_id: str | None,
    bundle: ManifestBundle,
    reveal_all: bool = False,
    include_history: bool = True,
    include_breakdowns: bool = True,
) -> dict[str, Any]:
    active_suits = bundle.active_suits(match.rules.use_purple)
    viewer_player = None
    if viewer_user_id is not None:
        for player, slot in match.players.items():
            if slot.user_id == viewer_user_id:
                viewer_player = player
                break
    lost = match.lost_cities
    players_payload: dict[str, Any] = {}
    for player, slot in match.players.items():
        player_state = lost.players[player]
        can_reveal = reveal_all or match.status == MATCH_FINISHED or match.reveal_all or viewer_player == player
        players_payload[str(player)] = {
            "slot": slot.to_dict(),
            "state": player_state.to_dict(reveal_hand=can_reveal, include_hand_count=True),
        }
    payload = {
        "id": match.id,
        "status": match.status,
        "rules": match.rules.to_dict(),
        "players": players_payload,
        "score": match.score,
        "lostCities": {
            "turnPlayer": lost.turn_player,
            "phase": lost.phase,
            "deckCount": len(lost.deck),
            "discardPiles": lost.discard_piles,
            "finalTurnsRemaining": lost.final_turns_remaining,
            "justDiscardedCardId": lost.just_discarded_card_id,
            "activeSuits": active_suits,
        },
        "createdAtEpochMs": match.created_at_epoch_ms,
        "updatedAtEpochMs": match.updated_at_epoch_ms,
        "lastEvent": match.last_event,
    }
    if include_history:
        payload["history"] = list(match.history)
        payload["actionLog"] = list(match.action_log)
    if include_breakdowns:
        payload["breakdowns"] = expedition_breakdowns(lost, active_suits, bundle.card_by_id)
    return payload


def build_agent_request(match: MatchRecord, *, player: int, bundle: ManifestBundle) -> dict[str, Any]:
    active_suits = bundle.active_suits(match.rules.use_purple)
    player_state = match.lost_cities.players[player]
    opponent_state = match.lost_cities.players[other_player(player)]
    payload = {
        "apiVersion": 1,
        "matchId": match.id,
        "seat": player,
        "status": match.status,
        "rules": match.rules.to_dict(),
        "phase": match.lost_cities.phase,
        "turnPlayer": match.lost_cities.turn_player,
        "finalTurnsRemaining": match.lost_cities.final_turns_remaining,
        "deckCount": len(match.lost_cities.deck),
        "activeSuits": active_suits,
        "myHand": [bundle.card_by_id[card_id] for card_id in player_state.hand],
        "myExpeditions": {
            suit: [bundle.card_by_id[card_id] for card_id in player_state.expeditions.get(suit, [])]
            for suit in active_suits
        },
        "opponentExpeditions": {
            suit: [bundle.card_by_id[card_id] for card_id in opponent_state.expeditions.get(suit, [])]
            for suit in active_suits
        },
        "discardPiles": {
            suit: [bundle.card_by_id[card_id] for card_id in match.lost_cities.discard_piles.get(suit, [])]
            for suit in active_suits
        },
        "opponentHandCount": len(opponent_state.hand),
        "justDiscardedCardId": match.lost_cities.just_discarded_card_id,
        "legalActions": legal_actions(match, player, bundle),
        "historyLength": len(match.action_log),
        "score": dict(match.score),
    }
    return payload


def match_to_dict(match: MatchRecord) -> dict[str, Any]:
    return asdict(match)
