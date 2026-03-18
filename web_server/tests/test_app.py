from __future__ import annotations

import io
import json
import shutil
import unittest
import uuid
import zipfile

from web_server.app import LostCitiesWebApp
from web_server.ai_runtime import UPLOADED_ROOT, slugify
from web_server.engine import create_active_match
from web_server.models import GameRules, MATCH_FINISHED, PlayerSlot


class LostCitiesWebAppTest(unittest.TestCase):
    def setUp(self) -> None:
        self.app = LostCitiesWebApp()
        self.created_agent_ids: list[str] = []

    def tearDown(self) -> None:
        for agent_id in self.created_agent_ids:
            zip_path = UPLOADED_ROOT / f"{agent_id}.zip"
            extracted_root = UPLOADED_ROOT / agent_id
            if zip_path.exists():
                zip_path.unlink()
            if extracted_root.exists():
                shutil.rmtree(extracted_root)

    def test_human_match_hides_opponent_hand(self) -> None:
        alice = self.app.connect_user("Alice")
        bob = self.app.connect_user("Bob")
        created = self.app.create_human_match(
            token=alice["userToken"],
            target_user_id=bob["userId"],
            use_purple=False,
        )
        match = created["match"]
        self.assertEqual(match["players"]["1"]["state"]["handCount"], 8)
        self.assertIn("hand", match["players"]["1"]["state"])
        self.assertNotIn("hand", match["players"]["2"]["state"])

    def test_random_replay_and_simulation_complete(self) -> None:
        replay = self.app.create_ai_replay(
            left_agent_id="random",
            right_agent_id="random",
            use_purple=False,
        )["replay"]
        self.assertGreaterEqual(len(replay["frames"]), 2)
        simulation = self.app.create_simulation(
            left_agent_id="random",
            right_agent_id="random",
            rounds=1,
            use_purple=False,
        )["simulation"]
        self.assertEqual(len(simulation["results"]), 1)

    def test_retention_caps_prune_replays_simulations_and_announcements(self) -> None:
        self.app.max_replays = 2
        self.app.max_simulations = 2
        self.app.max_announcements = 3

        for index in range(5):
            self.app.create_ai_replay(
                left_agent_id="random",
                right_agent_id="random",
                use_purple=False,
            )
            self.app.create_simulation(
                left_agent_id="random",
                right_agent_id="random",
                rounds=1,
                use_purple=False,
            )
            self.app._announce(f"announcement {index}")

        self.assertEqual(list(self.app.replays), ["replay-00004", "replay-00005"])
        self.assertEqual(list(self.app.simulations), ["simulation-00004", "simulation-00005"])
        self.assertEqual(
            [item["message"] for item in self.app.announcements],
            ["announcement 2", "announcement 3", "announcement 4"],
        )

    def test_finished_matches_are_pruned(self) -> None:
        self.app.max_finished_matches = 2

        for index in range(4):
            match = create_active_match(
                match_id=f"finished-{index}",
                players={
                    1: PlayerSlot(player=1, name="Left", kind="ai", agent_id="random"),
                    2: PlayerSlot(player=2, name="Right", kind="ai", agent_id="random"),
                },
                rules=GameRules(use_purple=False),
                bundle=self.app.bundle,
                capture_replay=False,
            )
            match.status = MATCH_FINISHED
            match.updated_at_epoch_ms = index
            self.app.matches[match.id] = match

        self.app._prune_matches()
        self.assertEqual(list(self.app.matches), ["finished-2", "finished-3"])

    def test_stale_users_and_challenges_are_pruned(self) -> None:
        alice = self.app.connect_user("Alice")
        bob = self.app.connect_user("Bob")
        self.app.create_human_challenge(
            token=alice["userToken"],
            target_user_id=bob["userId"],
            use_purple=False,
        )

        self.app.user_ttl_ms = 0
        self.app.users[alice["userId"]].last_seen_epoch_ms = 0
        self.app.users[bob["userId"]].last_seen_epoch_ms = 0

        lobby = self.app.lobby_snapshot()
        self.assertEqual(lobby["users"], [])
        self.assertEqual(lobby["challenges"], [])
        self.assertEqual(self.app.tokens, {})

    def test_phase6_numpy_uploaded_agent_can_play_replay(self) -> None:
        from web_server.engine import apply_action

        match = create_active_match(
            match_id="test-phase6",
            players={
                1: PlayerSlot(player=1, name="Phase6", kind="ai", agent_id="lost-cities-phase6-numpy"),
                2: PlayerSlot(player=2, name="Random", kind="ai", agent_id="random"),
            },
            rules=GameRules(use_purple=False),
            bundle=self.app.bundle,
            capture_replay=False,
        )
        play_action = self.app.agents.build_action("lost-cities-phase6-numpy", match, 1)
        self.assertIn(play_action["action"], {"play_expedition", "discard"})
        self.assertIn(play_action["cardId"], match.lost_cities.players[1].hand)
        match = apply_action(
            match,
            player=1,
            action=play_action["action"],
            card_id=play_action["cardId"],
            suit=play_action.get("suit"),
            bundle=self.app.bundle,
        )
        draw_action = self.app.agents.build_action("lost-cities-phase6-numpy", match, 1)
        self.assertIn(draw_action["action"], {"draw_deck", "draw_discard"})

    def test_human_challenge_requires_acceptance(self) -> None:
        alice = self.app.connect_user("Alice")
        bob = self.app.connect_user("Bob")
        challenge = self.app.create_human_challenge(
            token=alice["userToken"],
            target_user_id=bob["userId"],
            use_purple=True,
        )["challenge"]
        self.assertEqual(challenge["status"], "pending")
        self.assertEqual(len(self.app.matches), 0)
        lobby = self.app.lobby_snapshot(bob["userToken"])
        self.assertEqual(len(lobby["challenges"]), 1)
        accepted = self.app.accept_human_challenge(
            token=bob["userToken"],
            challenge_id=challenge["id"],
        )
        self.assertEqual(self.app.challenges[challenge["id"]].accepted_match_id, accepted["matchId"])
        self.assertEqual(self.app.challenges[challenge["id"]].status, "accepted")
        self.assertEqual(self.app.users[alice["userId"]].match_id, accepted["matchId"])
        self.assertEqual(self.app.users[bob["userId"]].match_id, accepted["matchId"])

    def test_uploaded_agent_appears_in_registry(self) -> None:
        agent_id = f"first_legal_test_{uuid.uuid4().hex[:8]}"
        archive = io.BytesIO()
        with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED) as bundle:
            bundle.writestr(
                "agent_manifest.json",
                json.dumps(
                    {
                        "id": agent_id,
                        "name": "First Legal Test",
                        "entrypoint": "agent.py:build_agent",
                        "description": "Always picks the first legal action.",
                    }
                ),
            )
            bundle.writestr(
                "agent.py",
                """
class FirstLegalAgent:
    def choose_action(self, request):
        actions = list(request.get("legalActions", []))
        if not actions:
            raise ValueError("no legal actions")
        return dict(actions[0])


def build_agent():
    return FirstLegalAgent()
""".strip(),
            )
        uploaded = self.app.upload_agent_zip(file_name=f"{agent_id}.zip", body=archive.getvalue())
        normalized_id = slugify(agent_id)
        self.created_agent_ids.append(normalized_id)
        self.assertEqual(uploaded["agent"]["id"], normalized_id)
        ids = [item["id"] for item in self.app.agents.descriptors()]
        self.assertIn(normalized_id, ids)


if __name__ == "__main__":
    unittest.main()
