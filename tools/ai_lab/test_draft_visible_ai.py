from __future__ import annotations

import unittest

import lost_cities_ai as draft

from tools.ai_lab.draft_visible_ai import (
    DraftVisibleAI,
    engine_card_to_visible,
    turn_plan_from_draft_move,
    visible_card_to_engine,
    visible_state_from_engine_state,
)
from tools.ai_lab.lost_cities_engine import (
    Card,
    GameState,
    PlayerState,
    SUITS,
    legal_turn_plans,
)


def make_player(*, hand: list[Card], expeditions: dict[str, list[Card]] | None = None) -> PlayerState:
    return PlayerState(
        hand=list(hand),
        expeditions={suit: list((expeditions or {}).get(suit, [])) for suit in SUITS},
    )


class DraftVisibleAiAdapterTest(unittest.TestCase):
    def test_color_mapping_round_trip(self) -> None:
        for suit in SUITS:
            for rank in (None, 2, 10):
                engine_card = Card(suit=suit, rank=rank, serial=2 if rank is None else 0)
                visible_card = engine_card_to_visible(engine_card)
                round_trip = visible_card_to_engine(visible_card)
                self.assertEqual(round_trip.suit, engine_card.suit)
                self.assertEqual(round_trip.rank, engine_card.rank)

    def test_visible_state_maps_both_seats_correctly(self) -> None:
        state = GameState(
            players={
                1: make_player(
                    hand=[Card("red", 4), Card("red", None, 1), Card("white", 7)],
                    expeditions={
                        "red": [Card("red", None, 2), Card("red", 2)],
                        "blue": [Card("blue", 5)],
                    },
                ),
                2: make_player(
                    hand=[Card("green", 3), Card("yellow", 8)],
                    expeditions={
                        "green": [Card("green", None, 1)],
                        "white": [Card("white", 4), Card("white", 6)],
                    },
                ),
            },
            deck=[Card("red", 10), Card("blue", 7)],
            discard_piles={
                "yellow": [Card("yellow", 4)],
                "blue": [Card("blue", None, 1)],
                "white": [],
                "green": [Card("green", 2), Card("green", 6)],
                "red": [Card("red", 8)],
            },
        )

        visible_one = visible_state_from_engine_state(state, 1)
        visible_two = visible_state_from_engine_state(state, 2)

        self.assertEqual([str(card) for card in visible_one.my_hand], ["R4", "RW", "W7"])
        self.assertEqual([str(card) for card in visible_two.my_hand], ["G3", "Y8"])
        self.assertEqual(visible_one.opp_hand_size, 2)
        self.assertEqual(visible_two.opp_hand_size, 3)
        self.assertEqual(visible_one.my_expeditions["R"].wagers, 1)
        self.assertEqual(visible_one.my_expeditions["R"].numbers, (2,))
        self.assertEqual(visible_one.opp_expeditions["W"].numbers, (4, 6))
        self.assertEqual([str(card) for card in visible_one.discards["G"]], ["G2", "G6"])
        self.assertEqual([str(card) for card in visible_two.discards["R"]], ["R8"])

    def test_move_converts_back_to_legal_engine_turn_plan(self) -> None:
        state = GameState(
            players={
                1: make_player(
                    hand=[Card("red", 4), Card("blue", 6), Card("white", None, 1)],
                    expeditions={"red": [Card("red", 2)], "blue": [], "white": []},
                ),
                2: make_player(hand=[Card("green", 3), Card("yellow", 8)]),
            },
            deck=[Card("white", 9), Card("yellow", 2)],
            discard_piles={
                "yellow": [Card("yellow", 4)],
                "blue": [],
                "white": [],
                "green": [Card("green", 6)],
                "red": [],
            },
        )

        visible_state = visible_state_from_engine_state(state, 1)
        move, _, _ = draft.choose_best_move(visible_state)
        plan = turn_plan_from_draft_move(state, 1, move)

        self.assertIn(plan.label(), {candidate.label() for candidate in legal_turn_plans(state, 1)})

    def test_final_turn_visible_moves_are_action_only(self) -> None:
        state = draft.VisibleState(
            my_hand=[draft.parse_card("R2"), draft.parse_card("B3")],
            my_expeditions={color: draft.Expedition() for color in draft.COLORS},
            opp_expeditions={color: draft.Expedition() for color in draft.COLORS},
            discards={
                "R": [draft.parse_card("R6")],
                "G": [draft.parse_card("G4")],
                "B": [],
                "Y": [],
                "W": [],
            },
            deck_size=0,
            next_player=draft.ME,
        )

        moves = draft.legal_moves(state)
        self.assertTrue(moves)
        self.assertTrue(all(move.draw_source is None for move in moves))

    def test_same_color_redraw_is_blocked_but_other_color_draw_is_allowed(self) -> None:
        state = draft.VisibleState(
            my_hand=[draft.parse_card("R2"), draft.parse_card("B3")],
            my_expeditions={color: draft.Expedition() for color in draft.COLORS},
            opp_expeditions={color: draft.Expedition() for color in draft.COLORS},
            discards={
                "R": [draft.parse_card("R6")],
                "G": [draft.parse_card("G4")],
                "B": [],
                "Y": [],
                "W": [],
            },
            deck_size=5,
            next_player=draft.ME,
        )

        moves = draft.legal_moves(state)
        red_discard_moves = [
            move
            for move in moves
            if move.action == "discard" and state.my_hand[move.hand_index].color == "R"
        ]
        self.assertFalse(any(move.draw_source == "R" for move in red_discard_moves))
        self.assertTrue(any(move.draw_source == "G" for move in red_discard_moves))

    def test_adapter_returns_no_draw_plan_in_final_turns(self) -> None:
        state = GameState(
            players={
                1: make_player(hand=[Card("red", 4), Card("blue", 6)], expeditions={"red": [Card("red", 2)]}),
                2: make_player(hand=[Card("green", 3), Card("yellow", 8)]),
            },
            deck=[],
            discard_piles={suit: [] for suit in SUITS},
            final_turns_remaining=2,
        )

        choice = DraftVisibleAI().choose_turn(state, 1)
        self.assertIsNone(choice.plan.draw)
        self.assertIn(choice.plan.label(), {candidate.label() for candidate in legal_turn_plans(state, 1)})


if __name__ == "__main__":
    unittest.main()
