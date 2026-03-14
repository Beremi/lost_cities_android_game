# Asset notes

## Files

- `assets/cards/png/` contains all card face and card back PNG files.
- `assets/previews/board_preview.png` shows a compact board layout.
- `assets/previews/contact_sheet_base_60.png` shows the base deck.
- `assets/previews/contact_sheet_full_72.png` shows the full deck including purple.

## Readability choices

The cards are optimized for small on-screen presentation:

- high-contrast top band
- corner values framed in dark capsules
- suit icon in the same visible band as the rank
- distinct scene silhouette per suit
- restrained text at the bottom so the center stays uncluttered

## Suggested UI usage

- Use the top ~18 percent of each card as the "must remain visible" overlap area in expedition columns.
- Keep discard piles slightly fanned or offset so players can still identify the top card quickly.
- Use the card back for the draw pile and hidden opponent hand.
- Consider adding your own runtime drop shadow in the app rather than baking heavy shadow into the card sprites.

## Theme summary

- Yellow / Sun Desert
- White / Frost Peaks
- Blue / Tidal Ruins
- Green / Canopy Shrine
- Red / Ember Spire
- Purple / Star Vault

## Wager card convention

All three wager cards within a suit intentionally use the same visual treatment because they are functionally identical.
