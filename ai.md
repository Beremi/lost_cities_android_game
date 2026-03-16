Assuming the standard 2-player card game: the active deck is **60 cards** in the base game (five colors, number cards **2–10**, plus **three wager cards per color**); each player has **8 cards**; on each turn you **play one card, then draw one card**; number cards in an expedition must **strictly increase**; wager cards can only be played **before any number card** in that expedition; you draw from the deck or the **top** of a discard pile, but not the exact card you just discarded; and an expedition scores as `((sum of numbers − 20) × (1 + wagers)) + 20 if the expedition has at least 8 cards`, with that +20 bonus **not multiplied**. Newer editions also have an optional sixth-expedition long-game variant, but the same AI logic applies. 

The right way to think about the AI is this:

**Do not optimize the immediate play. Optimize expected final point differential.**

So the objective is:

`best move = argmax E[ my_final_score − opponent_final_score | visible_state, move ]`

Because opponent hand and deck order are hidden, this is an **imperfect-information** game. A good fit for that class of problem is **determinization / rollout search** or, more formally, **Information Set Monte Carlo Tree Search (ISMCTS)**, which was designed for hidden-information games and searches over information sets rather than fully revealed states. ([White Rose Research Online][1])

## 1. What state the AI must store

To choose well, the AI should track more than “what’s on the table.”

Store:

* your current hand
* your expeditions, by color:

  * number of wagers
  * last played number
  * sum of played numbers
  * card count in the expedition
* opponent expeditions, same fields
* **full discard stack order** for each color, not just the top card
* draw-pile size
* whose turn it is
* the set of unseen cards

That full discard order matters because only the **top** discard is available, and when someone takes it, the next card underneath becomes relevant. So discard stacks are not just “top card values”; they are mini future queues. 

A useful internal representation is:

`status[color][rank] ∈ {my_hand, my_board, opp_board, discard_depth_d, unseen}`

That is especially important in Lost Cities because each color contains one copy of each number 2–10, so once a specific card is used by one player, that exact card is unavailable to the other. That makes denial and dead-card accounting central to strong play. 

## 2. The core expedition evaluator

For one expedition of color `c`, define:

* `w` = wagers already played there
* `S` = sum of your number cards there
* `n` = total cards in that expedition
* `L` = last number played there, or `0` if only wagers / unopened

Its current locked score is:

`ScoreNow(c) = 0` if unopened
otherwise
`ScoreNow(c) = (S - 20) * (1 + w) + 20 * I[n >= 8]`

Now define the **eligible future cards** for that expedition:

`F = {k > L in this color that are not already impossible}`

A card is impossible if, for example, the opponent already played it, you already played it, or your current `L` has passed below it.

For each eligible future number `k`, estimate a probability `p_k` that **you will eventually get to play it**.

Then a good expected-value approximation for that expedition is:

`EVexp(c) = ((S + Σ p_k * k) - 20) * (1 + w) + 20 * P(reach at least 8 cards total)`

That last probability is the chance that you will pick up enough additional cards to hit the +20 bonus. Since a color has only nine number cards, computing that exactly with a tiny DP is cheap.

This one formula is the heart of the bot.

## 3. The most important insight: every number play has a **lockout cost**

When you play a higher number too early, you permanently forfeit lower numbers in that color.

So if your current last card is `L`, and you are considering playing `k > L`, the naive immediate gain is:

`ImmediateGain = k * (1 + w) + 20 if n was 7 before the play`

But that is not the true value.

The true value should subtract the **expected value of all lower still-playable numbers you just locked out**:

`LockoutCost = (1 + w) * Σ [ p_j * j ] over all j with L < j < k`
plus any lost probability of reaching the 8-card bonus.

So the real move signal is:

`NetPlayValue(k) ≈ ImmediateGain - LockoutCost - BonusProbLoss`

This is why an AI that just “plays the biggest playable card” will be bad.

Example: if your yellow expedition has a wager and last card `4`, then playing `8` now is not worth “8×2 = 16” in isolation. You also have to subtract the expected value of yellow `5`, `6`, and `7` if any of those are still realistically obtainable.

That lockout calculation is the single best heuristic for “when to set up expeditions via played cards.”

## 4. How to estimate `p_k`

You do not need perfect Bayes to get decent play. A practical model is enough.

Use these buckets:

* `p_k = 1.0` if the card is already in your hand
* `p_k = 1.0` if it is the discard top and your candidate move is to take it now
* `p_k = 0` if opponent already played it, or it is below your current last card
* buried in discard: assign a medium probability based on depth and whether the top card is likely to be removed
* unseen: split probability between deck and opponent hand

For unseen cards, a simple prior is:

`P(card is in deck) = deck_count / total_unknown`
`P(card is in opp hand) = opp_hand_unknown / total_unknown`

Then:

`p_k ≈ P(in deck) * P(I eventually receive it from deck) + P(in opp hand) * P(opponent eventually releases it and I can take it)`

You can refine those terms from behavior:

* if opponent has wagers in that color, cards helping that color are **less likely to be released**
* if opponent repeatedly ignores a useful top discard, it becomes safer
* if opponent has already pushed that color high, low cards in that color are dead to them

That refinement matters most for discard decisions.

## 5. How to decide whether to open a new expedition

A new expedition starts at `-20` before it earns anything. The official rules themselves explicitly warn that it is better not to start an expedition you cannot finish well, and that wagers should only be played when you have enough cards and enough time left. ([Thames & Kosmos][2])

So for an unopened color, do this:

1. Compute `EVexp` if you open it now with the candidate card.
2. Compare that to leaving the color unopened, which is worth `0`.
3. Only open if the new `EVexp` is clearly positive.

In practice, use a **margin**, not just `> 0`, because your `p_k` estimates are noisy. A margin of a few points is sensible.

Useful intuition:

* opening with a **low card** is good early because it preserves future flexibility
* opening with a **high card** is only good when many lower cards are already dead or when the game is late
* opening with **9** or **10** is often weak unless the color is nearly exhausted and you are just cashing visible points

## 6. How to decide whether to play a wager

A wager multiplies both upside and downside. The rules do not change the `-20` expedition cost; they multiply the subtotal after that. So a wager does **not** make a weak expedition safer — it makes it more volatile. ([Thames & Kosmos][2])

The clean way to evaluate a wager is:

`Value(wager now) = EVexp(with one more wager) - EVexp(without that wager)`

Because wagers can only be played before any number in that expedition, this is a commitment decision.

As a rule of thumb, a wager should usually require one of these:

* several supporting cards of that color already in your hand
* strong visible discard support
* enough game time left
* a realistic 8-card bonus path

Without that, the wager is usually a points trap.

## 7. Discard decisions: this is where a lot of strong Lost Cities play lives

For every discard candidate `x`, score:

`DiscardValue(x) = HandRelief - SelfFutureLoss - OppImmediateGain + BuryOrRevealEffect`

Where:

* `HandRelief`: how useful it is to get this card out of your hand
* `SelfFutureLoss`: how much your own EV drops if you give up this card
* `OppImmediateGain`: how much opponent EV rises if they can take and use it
* `BuryOrRevealEffect`: whether discarding it hides a dangerous current top card or exposes something underneath later

The biggest tactical rule is:

**A discard is safe if opponent cannot legally use it.**

So if opponent’s current last card in a color is `L_opp`, then discarding any number `<= L_opp` in that color is safe for them from a playability standpoint.

Danger rises sharply if:

* opponent has wagers in that color
* opponent’s current last card is below the discard
* the discard is exactly the kind of mid/high value they want now
* the discard lets them hit or approach the 8-card bonus
* the discard also reveals another useful buried card

Also remember: sometimes the best discard is not the “least useful card to you,” but the card that **buries** a discard the opponent wants.

## 8. Draw decisions: compare known discard value against deck EV

Treat the draw as part of the move, not as an afterthought.

For a visible discard-top card `x`, use:

`TakeValue(x) = MyGainFromHavingX + OpponentDenial + RevealEffect - DeckDrawEV`

Where `DeckDrawEV` is the expected marginal value of a random unseen deck card.

So you take a discard when:

`MyGain + Denial + RevealEffect > DeckDrawEV`

This captures all the classic Lost Cities tactics:

* take a card because you need it
* take a card because opponent needs it more
* take a card because removing it reveals an even better buried card
* ignore a discard because the random deck is better on average

## 9. Search layer: the practical AI setup

There are two good implementations.

### Fast heuristic bot

Generate all legal **(play/discard, then draw source)** pairs.
For each move, apply it and evaluate:

`V(state) = Σ EVexp(my colors) - Σ EVexp(opp colors)`

Choose the move with highest `V(after_move)`.

This is easy and already decent.

### Stronger bot

Because hidden cards matter, sample full hidden states consistent with what is visible.

For each legal move:

1. sample `N` hidden deals (opponent hand + deck order)
2. apply the move
3. let opponent respond with the same evaluator, or run a short rollout
4. average the resulting score differential

Pseudo-structure:

```text
best_move = None
best_value = -∞

for move in legal_moves(info_state):
    total = 0
    for h in sample_hidden_states(info_state, N):
        s = apply(move, h)
        total += search_or_rollout(s, depth=2 or more)
    value = total / N
    if value > best_value:
        best_value = value
        best_move = move

return best_move
```

If you want the cleaner theoretical version, use ISMCTS rather than plain determinization. ISMCTS was developed specifically for hidden-information games and avoids some determinization pathologies like treating each hidden-state sample as a separate perfect-information tree. ([White Rose Research Online][1])

## 10. Practical decision rules the AI should learn

These rules emerge naturally from the evaluator:

1. **Do not start junk expeditions.**
   If an unopened color is not clearly positive in EV, leave it at zero.

2. **Low early, high late.**
   Early in the game, low starts preserve upside. Late in the game, high cards become better because flexibility matters less.

3. **Wagers need support.**
   The rulebook’s own tip is exactly right here. ([Thames & Kosmos][2])

4. **Track dead cards relentlessly.**
   A color becomes bad when too many of its needed low/mid cards are already gone or locked out.

5. **Safe discards beat random “bad card” discards.**
   A card that is weak for you may still be terrible to feed the opponent.

6. **Denial is real EV.**
   Taking a discard just so the opponent cannot take it is often correct.

7. **Late game becomes cash-out mode.**
   When the draw pile gets short, speculative openings drop in value, and sure extensions / denial rise in value. The rules explicitly note that players may even count the remaining draw pile near the end to gauge time left. ([Thames & Kosmos][2])

If you build exactly this — expedition EV, lockout penalty, discard safety, draw denial, and sampled hidden-state search — you get a Lost Cities AI that actually thinks in points instead of just following canned strategy. The next useful step would be turning this into concrete pseudocode or Python.

[1]: https://eprints.whiterose.ac.uk/id/eprint/75048/1/CowlingPowleyWhitehouse2012.pdf "https://eprints.whiterose.ac.uk/id/eprint/75048/1/CowlingPowleyWhitehouse2012.pdf"
[2]: https://www.thamesandkosmos.com/manuals/full/691821_LC_Card_Game.pdf "https://www.thamesandkosmos.com/manuals/full/691821_LC_Card_Game.pdf"
