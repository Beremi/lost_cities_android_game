# Lost Cities AI Lab Report

## Protocol

- Early iterations used `10` games to surface obvious pathologies quickly.
- The later validation pass used `100` games.
- Seats alternate every seed so the model is tested as both player `1` and player `2`.
- All batches are capped at `24` turns. A real dealt round is about `46` turns long after the initial deal: `44` draws to empty the deck plus `2` final play-only turns. So the current batch runner is judging roughly half-finished rounds. That makes the scorelines useful for relative comparison, but not a good proxy for final round quality.
- The "coach" is the lab stand-in for my side, implemented in `coach_ai.py`.

## Current conclusion

The old `24`-turn batches were materially understating both policies, but your criticism was still directionally right: the model is not good enough yet.

The new finished-round run in [full_round_10_games.md](/home/ber0061/Repositories/lost_cities/tools/ai_lab/full_round_10_games.md) shows:

- `1` win
- `9` losses
- `0` ties
- average score: model `-23.70`, coach `3.30`

So the cap was hiding two different facts at once:

- it made both sides look more negative than they really were
- but it also masked that the coach can already finish rounds at roughly break-even or better while the model still cannot

The best capped model is still the heuristic model in [heuristic_ai.py](/home/ber0061/Repositories/lost_cities/tools/ai_lab/heuristic_ai.py), and on the older capped 100-game validation it improved from:

- `43` wins / `55` losses / `2` ties with average `-41.50` vs `-36.24`

to:

- `46` wins / `52` losses / `2` ties with average `-38.80` vs `-35.32`

But the first real finished-round batch shows the remaining strength gap much more honestly than the capped metric.

## What I built in `ai_lab`

- [lost_cities_engine.py](/home/ber0061/Repositories/lost_cities/tools/ai_lab/lost_cities_engine.py): text-mode Lost Cities engine with legal move generation, scoring, and deterministic rendering.
- [heuristic_ai.py](/home/ber0061/Repositories/lost_cities/tools/ai_lab/heuristic_ai.py): heuristic AI with `baseline` and `improved` configurations.
- [run_lab.py](/home/ber0061/Repositories/lost_cities/tools/ai_lab/run_lab.py): scenario checks and short self-play tooling.
- [iteration_runner.py](/home/ber0061/Repositories/lost_cities/tools/ai_lab/iteration_runner.py): coach-vs-model batch runner.
- [full_round_runner.py](/home/ber0061/Repositories/lost_cities/tools/ai_lab/full_round_runner.py): safe low-concurrency full-round batch runner.
- [single_match_runner.py](/home/ber0061/Repositories/lost_cities/tools/ai_lab/single_match_runner.py): one-seed finished-round runner for reliable subprocess-based batching.
- [sampled_ai.py](/home/ber0061/Repositories/lost_cities/tools/ai_lab/sampled_ai.py): bounded determinization experiment.

## Fixes that held up

### Structural fix

- Preserved candidate-specific penalties through reply lookahead instead of letting the reply score wash them out.

### Heuristic fixes

- Count only future number cards as real wager support.
- Penalize unresolved wager-only columns explicitly.
- Price high-card lockouts much harder.
- Penalize dangerous discard gifts more consistently.
- Penalize late useless discard draws.
- Penalize speculative fresh openings using:
  - support quality
  - hand support count
  - opportunity cost when a safe progress play already exists

### Validated 100-game correction

After the first 100-game pass, the remaining real divergences against the coach clustered mostly in:

- `play` where the coach would still `discard`
- `discard`-draw where the coach would choose `deck`

So I added one narrow follow-up pass in [heuristic_ai.py](/home/ber0061/Repositories/lost_cities/tools/ai_lab/heuristic_ai.py):

- a weak-commitment penalty when the model keeps opening too many low-confidence expeditions
- a low-value discard-draw penalty when taking a discard does not create immediate use or strong denial

That was the only post-100 change that I kept, because it improved the full 100-game validation instead of just moving a few hand-picked seeds.

### Runner split for real-round scoring

I updated [iteration_runner.py](/home/ber0061/Repositories/lost_cities/tools/ai_lab/iteration_runner.py) so it now supports:

- `collect_diagnostics=True`: the old comparison mode for pathology analysis
- `collect_diagnostics=False`: a faster score-only mode that skips "what would the coach have done on the model turn?" checks
- `max_turns=None`: let a round run to completion instead of forcing the old `24`-turn cutoff

## 10-game iteration history

| iteration | model | main change | wins | losses | avg model | avg coach | thin wagers | speculative openings | dangerous discards | wager-only endings |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | heuristic | initial wager/lockout fixes | 2 | 8 | -81.20 | -58.80 | 8 | - | 0 | 14 |
| 2 | heuristic | stronger wager support gate | 1 | 9 | -77.30 | -43.70 | 6 | - | 0 | 9 |
| 3 | heuristic | speculative-wager opportunity cost | 1 | 9 | -64.60 | -41.50 | 3 | - | 0 | 6 |
| 4 | heuristic | opening support-quality pass | 3 | 7 | -42.50 | -29.70 | 1 | 16 | 2 | 3 |
| 5 | heuristic | hand-support gate for fresh openings | 3 | 7 | -39.20 | -18.70 | 1 | 17 | 3 | 2 |
| 6 | sampled | bounded determinization wrapper | 2 | 8 | -45.60 | -27.30 | 1 | 16 | 1 | 2 |

## 100-game validation

Raw reports:

- baseline: [heuristic_100_games.md](/home/ber0061/Repositories/lost_cities/tools/ai_lab/heuristic_100_games.md)
- post-fix: [heuristic_100_games_post_fix.md](/home/ber0061/Repositories/lost_cities/tools/ai_lab/heuristic_100_games_post_fix.md)

| model | wins | losses | ties | avg model | avg coach | thin wagers | speculative openings | lockouts | dangerous discards | wager-only endings |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| heuristic baseline | 43 | 55 | 2 | -41.50 | -36.24 | 10 | 173 | 4 | 10 | 22 |
| heuristic post-fix | 46 | 52 | 2 | -38.80 | -35.32 | 10 | 156 | 5 | 7 | 22 |

Net effect of the post-100 fix:

- `+3` wins
- `-3` losses
- model average improved by `2.70`
- coach average improved by `0.92`
- speculative openings down by `17`
- dangerous discards down by `3`
- wager-only endings unchanged
- lockouts slightly worse by `1`

## What the 100-game pass showed

### 1. The 10-game slices were directionally useful, but too noisy

On 10 games the model looked clearly behind. On 100 games it is much closer to the coach than that suggested. The real gap is moderate, not massive.

It also means the raw scores staying negative is not, by itself, evidence that both policies are "discard everything" bad. With the current `24`-turn cutoff, the runner is mostly scoring unfinished expeditions before a normal round has enough time to cash them in.

### 1b. Full-round scoring is now possible, but still expensive

With the new fast mode in [iteration_runner.py](/home/ber0061/Repositories/lost_cities/tools/ai_lab/iteration_runner.py) and the safer batch wrapper in [full_round_runner.py](/home/ber0061/Repositories/lost_cities/tools/ai_lab/full_round_runner.py):

- one capped fast game took about `0.98s`
- one full-round fast game took about `80-85s` in the later safe-run path

So the tooling can now run true end-of-round evaluation, but the heuristic evaluator is still too expensive for large full-round batches. In a quick seed-1 comparison, the full-round result was less negative than the capped result:

- capped fast: model `-33`, coach `0`
- full fast: model `-25`, coach `-7`

That is exactly the point: the old cap exaggerated how bad the policies looked. It did not prove they were good, but it did overstate the negativity.

The first 10-game finished-round batch is now available in [full_round_10_games.md](/home/ber0061/Repositories/lost_cities/tools/ai_lab/full_round_10_games.md):

- model `1-9`
- average score: model `-23.70`
- average score: coach `3.30`

That batch is small, but it is enough to show that the model's weakness is still very real even after removing the half-round scoring distortion.

### 1c. Follow-up experiment: late dead-end / high-card lockout fix

After the first finished-round batch, I inspected two of the bad seeds directly:

- seed `10`: the model was still playing `yellow 9` into a weak `yellow 2` expedition late, and later `blue 10` instead of `blue 7`
- seed `3`: the model was still playing `yellow 9` onto `yellow 4` in a badly overcommitted position

I tried a new penalty aimed at "high card into weak, still-negative expedition" and then validated it against finished-round runs.

What happened:

- targeted seeds improved
- but the aggregate did not hold up

Exploratory outputs:

- aggressive pass: [full_round_4_games_post_fix.md](/home/ber0061/Repositories/lost_cities/tools/ai_lab/full_round_4_games_post_fix.md)
- focused pass: [full_round_4_games_post_fix_v2.md](/home/ber0061/Repositories/lost_cities/tools/ai_lab/full_round_4_games_post_fix_v2.md)

Comparison on seeds `1-4` only:

| variant | wins | losses | avg model | avg coach |
| --- | ---: | ---: | ---: | ---: |
| baseline finished-round subset | 0 | 4 | -28.00 | -0.75 |
| aggressive late dead-end patch | 1 | 3 | -31.00 | -4.25 |
| focused late dead-end patch | 0 | 4 | -29.25 | -3.00 |

So the late dead-end idea was directionally real for the exact failure cases, but not yet a validated net improvement. I reverted the heuristic change and kept only the tooling and report additions from this iteration.

### 2. Thin wagers are no longer the main problem

That work mostly held.

- early weak version: thin wagers all over the place
- 100-game best version: `10` thin wagers in `100` games

Wager-only columns still happen, but far less often than in the original weak model.

### 3. The main remaining weakness is still commitment timing

The model still commits to expeditions a bit too freely. The 100-game divergence scan on the baseline run showed that the dominant model-vs-coach mismatch classes were:

- `49` cases of `play` where the coach would `discard`
- `37` cases where the model took a `discard` draw and the coach would take `deck`
- `16` cases of choosing a different discard suit
- `11` cases of choosing a different play suit

That is why the final retained correction targeted weak commitments and low-value discard draws rather than wagers or high-card lockouts.

### 4. Discard decisions improved, but are not solved

Dangerous discard counts dropped from `10` to `7` over the 100-game validation after the final retained fix, but the category still exists.

### 5. High-card lockouts are now rare

They did not disappear entirely, but they are no longer the dominant failure mode.

## What I tried and rejected

I built a bounded determinization wrapper in [sampled_ai.py](/home/ber0061/Repositories/lost_cities/tools/ai_lab/sampled_ai.py). It samples hidden opponent-hand/deck assignments and re-scores the top heuristic candidates.

It was not good enough to keep as the main model:

- slower by a large margin
- weaker on the same 10-game comparison
- still prone to many of the same speculative commitment choices

So I kept it as an experiment, not as the default model.

## Best current model

The best current lab model is:

```python
HeuristicAI(IMPROVED_CONFIG)
```

with the latest weak-commitment and low-value discard-draw penalties included in [heuristic_ai.py](/home/ber0061/Repositories/lost_cities/tools/ai_lab/heuristic_ai.py).

## Remaining problems

After the 100-game capped pass and the first finished-round batch, the remaining issues look like this:

- expedition commitment is still slightly too eager in mixed-support hands
- discard draws are still somewhat too "sticky" compared with random deck flexibility
- the model is still not converting middling positions into finished positive rounds
- full-round measurement now exists, but only in small batches so far because it is still expensive
- the next late-game fix needs to be more local than global
  The rejected experiment improved the exact high-card dead-end cases, but it also distorted other finished-round seeds

## Why I stopped here

I do not see another obvious cheap heuristic patch with the same confidence level as the last retained fix.

The next likely gains are structural rather than another weight tweak:

1. a faster runner that can afford full-round or much deeper capped validation
   The runner split is done; the remaining bottleneck is evaluator speed.
2. a stronger explicit commitment-budget model
3. a better determinization or rollout search than the current lightweight sampled wrapper

## How to rerun

10-game heuristic batch:

```bash
python - <<'PY'
from tools.ai_lab.iteration_runner import run_batch, render_batch_markdown
result = run_batch(games=10, max_turns=24, model_kind="heuristic")
print(render_batch_markdown(result, "heuristic"))
PY
```

100-game heuristic batch:

```bash
python - <<'PY'
from tools.ai_lab.iteration_runner import run_batch, render_batch_markdown
result = run_batch(games=100, max_turns=24, model_kind="heuristic")
print(render_batch_markdown(result, "heuristic_100"))
PY
```

10-game full-round heuristic batch:

```bash
python -m tools.ai_lab.full_round_runner --games 10 --workers 2 --output tools/ai_lab/full_round_10_games.md
```

10-game sampled experiment:

```bash
python - <<'PY'
from tools.ai_lab.iteration_runner import run_batch, render_batch_markdown
result = run_batch(games=10, max_turns=24, model_kind="sampled")
print(render_batch_markdown(result, "sampled"))
PY
```
