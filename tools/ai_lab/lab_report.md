# Lost Cities AI Lab

This file is generated from the local Python lab runner. It compares a baseline heuristic that preserves the current failure tendencies against a stricter variant tuned around wagers, lockouts, and discard danger.

## high_card_after_wager
```text
turn=P1 phase=play deck=4 final=0
score P1=-51 P2=0
hand YW, Y9, B6
P1 expeditions:
  yellow YW
  blue   B4 B5
  white  -
  green  -
  red    -
P2 expeditions:
  yellow -
  blue   -
  white  -
  green  -
  red    -
discards:
  yellow -
  blue   -
  white  -
  green  -
  red    -
```
- baseline: `discard:yellow_wager_2 -> draw:deck` value=-45.07
- improved: `discard:yellow_wager_2 -> draw:deck` value=-31.30

## dangerous_discard
```text
turn=P1 phase=play deck=4 final=0
score P1=0 P2=-30
hand Y2, B9
P1 expeditions:
  yellow -
  blue   -
  white  -
  green  -
  red    -
P2 expeditions:
  yellow -
  blue   BW B5
  white  -
  green  -
  red    -
discards:
  yellow -
  blue   -
  white  -
  green  -
  red    -
```
- baseline: `discard:yellow_2 -> draw:deck` value=3.07
- improved: `discard:yellow_2 -> draw:deck` value=-9.49

## self_play

These are capped 24-turn scrimmages, used to surface early and midgame decision pathologies quickly.

| metric | baseline | improved |
| --- | ---: | ---: |
| games | 4 | 4 |
| avg_p1_score | -66.00 | -51.50 |
| avg_p2_score | -120.00 | -79.75 |
| wager_only_endings | 8 | 5 |
| dangerous_discards | 0 | 1 |
| high_lockout_plays | 3 | 1 |
| thin_wagers | 11 | 6 |
| stalled_games | 4 | 4 |