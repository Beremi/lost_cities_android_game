from __future__ import annotations

from dataclasses import dataclass
import json
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[1]
ANDROID_ASSET_ROOT = REPO_ROOT / "android" / "app" / "src" / "main" / "assets"
V3_MANIFEST_PATH = ANDROID_ASSET_ROOT / "lost_cities_v3" / "manifests" / "deck_manifest.json"


@dataclass(slots=True)
class ManifestBundle:
    manifest_path: Path
    asset_root: Path
    manifest: dict
    card_by_id: dict[str, dict]
    suit_by_id: dict[str, dict]

    @property
    def suits(self) -> list[dict]:
        return list(self.manifest["suits"])

    @property
    def cards(self) -> list[dict]:
        return list(self.manifest["cards"])

    def active_suits(self, use_purple: bool) -> list[str]:
        if use_purple:
            return [item["id"] for item in self.suits]
        return [item["id"] for item in self.suits if item["id"] != "purple"]

    def active_cards(self, use_purple: bool) -> list[dict]:
        active_suits = set(self.active_suits(use_purple))
        return [card for card in self.cards if card["suit"] in active_suits]

    def card_asset_path(self, card_id: str) -> Path | None:
        card = self.card_by_id.get(card_id)
        if card is None:
            return None
        raw_path = card.get("path", "").lstrip("/")
        if raw_path.startswith("assets/"):
            raw_path = raw_path.removeprefix("assets/")
        return self.asset_root / "lost_cities_v3" / raw_path

    def card_back_path(self) -> Path:
        return self.asset_root / "lost_cities_v3" / "cards" / "png" / "card_back.png"

    def background_path(self) -> Path:
        return self.asset_root / "lost_cities_v3" / "backgrounds" / "dark_stone_portrait_1600x2560.png"


def load_manifest_bundle() -> ManifestBundle:
    manifest = json.loads(V3_MANIFEST_PATH.read_text(encoding="utf-8"))
    return ManifestBundle(
        manifest_path=V3_MANIFEST_PATH,
        asset_root=ANDROID_ASSET_ROOT,
        manifest=manifest,
        card_by_id={card["id"]: card for card in manifest["cards"]},
        suit_by_id={suit["id"]: suit for suit in manifest["suits"]},
    )
