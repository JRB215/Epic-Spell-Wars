# Epic Spell Wars: rules summary for the engine

Source: the owner's rulebook scans (Part 1 and Part 2). Local only, not in the repo.
This is a working summary in our own words. If it disagrees with the rulebook, the rulebook wins.

## Components

| Item | Count |
|---|---|
| Source cards | 40 |
| Quality cards | 40 |
| Delivery cards | 40 |
| Wild Magic cards (shuffled into the Main Deck) | 8 |
| Treasure cards | 25 |
| Dead Wizard cards | 25 (the owner's merged set may hold up to 50) |
| Hero cards | 8 |
| Last Wizard Standing tokens | 7 |
| Skull life counters | 6 |
| Six-sided dice | 4 |

- **Main Deck** = Source + Quality + Delivery + Wild Magic. Treasure and Dead Wizard decks are separate.
- The owner has 2 copies of every Source, Quality and Delivery card, and 8 Wild Magic.

## Setup

- Shuffle each deck separately.
- Each player picks a Hero. Everyone starts with **20 HP**. HP can go up to a **maximum of 25**.
- 2-6 players (the owner's rule; the rulebook shows a 4-player table).

## Winning

- A **game** is played in rounds until only one wizard is left alive. That wizard earns a **Last Wizard Standing token**.
- **Two tokens wins the match.**
- If a wizard kills themselves and nobody is left alive, that wizard still gets the token.
- HP carries from round to round inside a game. At the start of each new game, everyone is back to 20 HP, hands and Treasures are discarded, and Dead Wizard bonuses are applied.

## A round

1. **Start of round.** Each wizard draws from the Main Deck up to a full hand of **8**. Each *dead* wizard draws one Dead Wizard card.
2. **Create your spell.** Each wizard secretly places up to 3 cards face down, at most one of each type (Source, Quality, Delivery). A Source is always leftmost, Quality in the middle, Delivery rightmost.
3. **Turn order.** Everyone announces how many components they played (1, 2 or 3).
   - Fewer components go **first**: one-card spells, then two, then three.
   - Ties are broken by the **Initiative** number printed on the Delivery card. Higher goes first.
   - No Delivery, or a Wild Magic card in the Delivery slot, counts as Initiative **0**.
   - If Initiative also ties, each tied wizard rolls a die and the high roller goes first.
   - If a spell's component count changes before its turn, its place in the order changes.
4. **Reveal and resolve**, one wizard at a time in turn order:
   - Reveal the components. Read the spell name aloud (cosmetic).
   - Resolve the cards in order: **Source, then Quality, then Delivery**.
   - Then discard the resolved components. The next wizard goes.
5. When only one wizard is alive, the round ends and that wizard gets a token.

### Illegal spells

- A spell may hold at most one card of each type. If a revealed spell is illegal, the owner chooses cards to remove until it is legal. Removed cards go to the discard pile.
- Cards **added** to a spell by an effect are placed next to the same type. This does not make the spell illegal. Multiple unresolved components of the same type resolve in the order the owner chooses.

### Missing parts (cosmetic)

- Missing Source: use the Hero's name. Missing Quality: no adjective needed. Missing Delivery: any magical word. This only affects the spell name.

## Dice and targets

- **Power Roll:** look at the glyph named on the card. Roll one d6 for **each card in your spell** that has that glyph, and add them up. The card's roll table says what the total does. Some Treasures add extra dice or allow rerolls. A Treasure like Fool's Gold takes effect only after all rerolls are done.
- **Target** must be chosen **before** any dice are rolled.
- Targets can be: random foe, foe on your left or right, strongest or weakest foe, a stronger or weaker foe, or yourself (healing).
- **Strongest** = most HP. **Weakest** = fewest HP. On a tie, the casting wizard chooses. You are never your own foe.
- **Stronger/weaker foe** = any foe with more/fewer HP than you. An equal-HP foe is neither.
- **Random foe:** starting with the player on your left, give each legal target values on one d6 (the example: 3 foes get 2 numbers each: left 1-2, next 3-4, last 5-6). Roll to see the victim. The dice roll for the target is done by the server.

## Glyphs (magic types)

Every component has one: **Arcane, Dark, Elemental, Illusion, Primal**. Many effects count or match glyphs. Some Treasures count as a glyph in every spell (for example, Demon Shoes counts as a Dark card).

## Wild Magic

- A Wild Magic card in your hand takes the place of any one missing component.
- When your spell is revealed, immediately reveal cards from the top of the Main Deck until you find the needed component type. Add it, discard the Wild Magic card and the other revealed cards. Repeat for each Wild Magic card played.
- The spell's name is read only after all Wild Magic cards are replaced.
- A Wild Magic card in the Delivery slot counts as Initiative 0 (this is decided at turn order, before replacement).

## Treasures

- When you gain a Treasure, draw the top Treasure card and put it **face up** in front of you. Treasures are never put into your hand. Other wizards may steal them.

## Dead wizards

- When slain, a wizard discards their hand and Treasures, and immediately draws one Dead Wizard card from the Dead Wizard deck.
- Being dead does not remove the player. At the start of each new round, each dead wizard draws a Dead Wizard card. These help the **next** game or round.
- At the end of a game, wizards with Dead Wizard cards gather their bonuses (extra HP, Treasure and so on) and then discard those cards.

## End of a game / reshuffling

- All Treasures and all hand cards are discarded at the end of each game.
- Decks are **not** reshuffled until they run out, so wizards see a wide variety of cards over a match.

## Worked example (rulebook page 7-9)

A 4-player game. One player plays 2 components. The other three each play 3 and announce Initiatives 18, 14 and 14. The 2-component player acts first. Then 18 acts. Then the two 14s roll a die to break the tie.

The 18 player's spell: Source "Bleemax Brainiac's" reveals the top 2 cards of the Main Deck and adds any matching the spell's glyphs. This adds a Quality ("Mysterious") and a Delivery ("Fist o' Nature"). Two Qualities resolve in the player's chosen order. Mind-Altering deals 3 to a random foe and gives both players a Treasure. Mysterious deals 1 per different glyph in the spell plus 1 per Treasure. Pact with the Devil targets the strongest foe and makes a Dark Power Roll.

## Interpretations the engine makes

The rulebook and cards leave these open. The engine picks the simplest reading. Tell the owner if one is wrong.

**Seats and targets**
- "Left" is the next living seat after you; "right" is the previous one. A foe is any living wizard other than you.
- Strongest = most HP, weakest = fewest HP. A tie among foes is broken by the caster's choice.
- Random foe: foes are lined up from the caster's left and share the numbers 1-6 equally (3 foes get two numbers each). If 6 does not divide evenly (4 or 5 foes), re-roll the leftover numbers.
- Choices (target, which Treasure) are made before dice are rolled.

**Power Rolls**
- Roll one die for each card in your spell with the **glyph of the card that is rolling**, counting Treasures that "count as a card" of that glyph. Then add extra dice and flat bonuses from Treasures and effects. Bands are 1-4, 5-9, 10+.
- A card that copies another uses its own glyph for the dice, and the copied card's type for "Delivery/Quality" Treasure bonuses.
- Lady Luck's Panties add 2 to any roll of a single die, including a Power Roll of one die. Cheater's Handbook rerolls one die; Lady Luck's Brassiere rerolls the whole roll afterwards.
- Fool's Gold and Amulet of Maneg are checked against the final dice after all rerolls.

**Spells and turn order**
- Wild Magic (and a Proton Gem) fills any one slot. It is replaced at the wizard's turn, before anything resolves; if the deck has no card of that type, the slot is simply empty.
- Cards that come into a spell during a turn resolve next, in order Source, then Quality, then Delivery, whichever is lowest unresolved.
- Impatient (Quality) moves your spell to the front of the order. Its 1 damage to each foe still happens when the card resolves.
- Order is re-checked before every turn, so effects that change component counts change the order.
- Methy-Ion's Backpack: +10 Initiative. At the start of the round its holder may discard it to act last.
- A wizard who dies mid-spell stops casting; the rest of their spell is discarded.

**Decks and rounds**
- A deck that runs out is reshuffled from its discard pile. If both are empty, nothing is drawn.
- Treasure and Dead Wizard cards are reshuffled the same way.
- Each round every living wizard draws back up to 8 cards (9 with the Thinking Cap). Dead wizards draw one Dead Wizard card each round.
- Slag Shangri-La: at the start of the next game the die is rolled once (everyone sees the roll) and its number stays as a marker on the wizard's profile. That number is added to every Power Roll the wizard makes on their first turn (not rolled again each time), then the marker is removed. Two Slag cards give two dice, added together.
- Backlash from Beyond happens the moment it is drawn: 2 damage to a foe of the drawer's choice, then it is discarded.
- Wild Furicorn Meadow: at the end of the game, a Wild Magic card is taken out of the Main Deck (or discard) and goes into that wizard's first hand next game.
- A game that lasts 300 rounds without a winner is a draw. No token is given.

**Known gaps**
- If one effect would defeat two wizards at the same moment (for example Walker Time Ranger's with tied low rolls), damage is applied one wizard at a time and the game ends as soon as one is left. A true simultaneous kill, where the casting wizard still earns the token, is not modelled.
- Pact with the Devil moves the foe's Delivery into your spell only if it is still in their spell. If they have already cast it, nothing is stolen.

## Open questions (rules gaps to settle with the owner)

1. ~~Dead Wizard deck size~~ settled: base set only, 25 cards. The (c)2015 cards are in `Expansion 1`.
2. ~~Hero cards~~ settled: all 8 Heroes play identically (20 HP, no abilities). They differ only in character art and name. Scans are needed for the art only.
3. **Dead Wizard cards drawn mid-match:** the rulebook says each dead wizard draws one at the start of each new round. Confirm that they are *kept* until the end of the whole *game* (all rounds), not the round.
4. **Deck reshuffling:** the rulebook says decks are shuffled only when they run out. Confirm the Main Deck's discard pile is reshuffled in that case.
5. **Card text:** every card's effect must be read from the scans (about 120 unique cards) before the engine can be finished.
