# Expansion 1: rules and plan

Sources: `Expansion 1/ESW2_RULEBOOK_FINAL.pdf` (copyright 2015, local only) and the owner's card scans. This is my own
summary. If it disagrees with the rulebook, the rulebook wins.

## Game modes the lobby will offer

| Mode | Cards | Rules |
|---|---|---|
| **Base game** | the base set (as it is today) | base rules only, nothing changes |
| **Expansion 1** | the expansion set only | Blood, Standee, Creatures, Reactions |
| **Base + Expansion 1** (merged) | both sets in one pile | the expansion rules; base cards simply ignore Blood |

The owner always played merged, so merged is the one to get right. Merged play shuffles each pair of decks together
(the rulebook, page 19, says that is fine, and on a computer it costs nothing):

| Deck | Base | Expansion | Merged |
|---|---|---|---|
| Main Deck (Source, Quality, Delivery, Wild Magic) | 128 | 128 | 256 |
| Treasures | 25 | 25 | 50 |
| Dead Wizards | 25 | 25 | 50 (the owner's mixed pile) |
| Heroes | 16 | shared | 16 |

Scorchia's (scan 0042) is a (c)2012 base card that was mixed into the expansion pile. It stays in the base set only.

## What the expansion adds

### Blood
- Each wizard has a Blood count, 0 at the start of the match, never more than 25. Blood **carries from game to game**
  within a match and is not reset when you die. You can gain or spend Blood while dead.
- Gain 3 Blood when you kill a foe. Killing yourself gives nothing.
- At the end of every round, the wizard holding the Standee gains 1 Blood "from the pool" (the pool never runs out).
- "Pay N Blood: (effect)": N is 1 to 8. "Pay X Blood" lets you pay as much as you like, all at once.
  - On a spell component it may be paid **once per casting**, and you must decide **before** any damage, dice or other
    effect of that component. You cannot kill a foe, gain 3 Blood, and then pay on the card that killed them.
  - On a Treasure it may be paid **once per round, and only during your own initiative**.
  - If you cannot afford it, you cannot pay it.

### The Standee (the "tower")
- Starts each game controlled by no one, in the middle of the table, giving no benefit.
- Cards say "Take the Standee" or "gain the Standee" to move it to the caster. Slaying the holder also takes it.
- A line starting "Standee:" on a card works only if you hold the Standee when you play it. If you do not hold it,
  nobody gets that bonus.
- At the end of each round the holder keeps it and gains 1 Blood. At the start of each new game it returns to the middle.
- Some cards target "a foe who does not have the Standee" or "the foe with the Standee".

### Creatures and KEEP
- Every expansion Delivery is a Creature. A result marked KEEP leaves the Creature face up in front of you instead of
  discarding it with the rest of the spell. Some cards let you pay to keep it (Fatcula!, Steer-Roids!).
- **Blocking:** whenever you would take damage from a foe, you may instead discard one face-up Creature you have in
  play. That cancels the damage to you only (not to other targets of the same card). Creatures cannot absorb damage you
  do to yourself. A Creature still face down in your unresolved spell cannot block. One Creature blocks one instance of
  damage, however large. For random damage you decide after the amount is known. Zero damage does not kill a Creature.
- **Attacking again:** a kept Creature rolls its Power Roll again when your initiative comes up, and you cannot skip
  it. It still rolls if it has no legal target. It is discarded if it does not roll KEEP again.
- A kept Creature does **not** add to the spell's card count (it does not slow your turn). Its glyph **does** count
  toward Power Roll dice, as if it were part of your spell.
- When you die, or when a game ends, discard all your Creatures.
- "Kill a Creature" effects happen before any damage that follows, so the Creature cannot jump in the way.
  Only cards that mention Creatures can hit them; generic damage cannot.
- If a component copies a Creature Delivery (Beard'o Blasty's, Disco-Mirrored), it cannot KEEP; it reverts to a
  non-Creature at the end of casting (page 19).

### Reactions
- "Reaction:" text on a component in your spell triggers if you die before you have started resolving that component.
  Reactions in your hand do nothing. A Reaction can trigger on self-inflicted damage: if your own Source kills you, a
  Reaction on your Quality or Delivery still triggers, but if your own Delivery kills you, a Reaction on that Delivery
  (or on already-resolved components) does not. Some Reactions save you ("You didn't die").

### Dead Wizard cards that "Resolve now"
Some of the expansion's Dead Wizard cards take effect the moment they are drawn, and stay in front of you until the
game ends (so others can count them). The rest work at the start of the next game, as in the base game.

### A variant the rulebook offers (not planned)
A "Three-Game Match" for 5 to 6 players: score 1 point per kill and 1 point for being Last Wizard Standing, play three
games, highest score wins. We could add this as a lobby option later.

## Decisions I am making unless told otherwise
1. Merged play uses one combined deck of each kind (see the table). Both Wild Magic designs go in: 16 in merged play.
2. Blood, the Standee, Creatures and Reactions are active in both Expansion-only and merged play, and off in the base game.
3. The Blood "pool" is unlimited.
4. A base card in merged play that deals damage is blocked by Creatures like any other damage from a foe.
5. The Standee marker uses the Tower 2 picture, the Blood counter uses the blood-drop marker picture.

## Build plan (the base game stays untouched and tested throughout)
1. **Modes and decks:** the engine builds its decks from one or both card sets; the lobby offers the three modes.
2. **Blood and the Standee:** counters, kill rewards, end-of-round Blood, "Pay N Blood" prompts, Standee bonuses.
3. **Creatures:** KEEP, blocking, kept Creatures rolling each turn, glyph counting, discards.
4. **Reactions and "Resolve now" Dead Wizard cards.**
5. **The 93 expansion cards** (25 Treasures, 60 spell cards, 8 Dead Wizards), each with tests.
6. **Screens:** Blood counter, Standee marker, Creatures in play, payment and block prompts, mode picker.
7. **Tests:** whole random matches for every player count in all three modes, with the card-count audit.
