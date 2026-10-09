# Expansion 1 notes (not built yet)

Source: `Expansion 1/ESW2_RULEBOOK_FINAL.pdf` (copyright 2015, local only). My own summary; the rulebook wins.

The expansion rulebook describes a complete second set (40 Source, 40 Quality, 40 Delivery, 8 Wild Magic, 8 Heroes,
25 Treasure, 25 Dead Wizard, 7 Last Wizard Standing tokens, 6 Skull counters, **6 Blood counters, 1 Standee**, 4 dice).
The owner has only scanned its Dead Wizard cards so far (they were mixed into the base set), and the Tower 2 picture.
Tower 1 (in `Base/`) is the same kind of piece for the base set; the base `Game Contents.txt` does not list one, so
whether the base game uses a Standee is unconfirmed.

## New mechanics the engine does not have

- **Blood.** Each wizard has a Blood count (0 to 25), kept on a Blood counter next to the hero board.
  "When you kill a foe, gain 3 Blood." Many cards say "Pay N Blood: (stronger effect)".
- **The Standee** (what the owner called the tower token).
  - It starts each game controlled by no one, in the middle of the table. Nobody gets any benefit from it then.
  - Cards say "Take the Standee" or "gain the Standee" to move it to the caster.
  - If you slay the foe who holds it, you take it.
  - At the end of each round, the holder keeps it and gains 1 Blood from the pool.
  - A card line starting "Standee:" only works if you hold the Standee when you play it. If you do not hold it,
    nobody gets that bonus.
  - At the start of a new game in a match the Standee goes back to the middle.
  - Some cards target "a foe who does not have the Standee".
- **Creatures and KEEP.** Delivery cards marked CREATURE with a KEEP result stay in play after the spell and can
  block incoming damage or be resolved again on a later spell. Some cards "kill all Creatures", count Creatures,
  or search the deck for one.
- **Reactions**, for example "If you die before this card resolves, draw a Treasure and play it at the start of the next game."
- **"Pay X Blood"** with variable X.

## To build this later we would need

1. Scans of the whole expansion deck (about 120 cards), read into JSON the way the base game was.
2. A second rules module next to `esw-engine` (Blood, Standee, Creatures, Reactions), so the base game stays untouched.
3. A lobby setting to choose which set to play.
4. Screens for the Blood counter, the Standee marker (the Tower 2 picture would fit) and Creatures in play.
