package esw.model

/** The five kinds of magic. Many effects count or compare glyphs. */
enum class Glyph { ARCANE, DARK, ELEMENTAL, ILLUSION, PRIMAL }

enum class CardType { SOURCE, QUALITY, DELIVERY, WILD_MAGIC, TREASURE, DEAD_WIZARD }

/**
 * Which cards are dealt, and which rules apply. The expansion rules (Blood, the Standee, Creatures and Reactions)
 * are on in [EXPANSION1] and [MERGED] and off in [BASE]. In [MERGED] each pair of decks is shuffled together.
 */
enum class GameMode(val label: String, val expansionRules: Boolean, val usesBase: Boolean, val usesExpansion: Boolean) {
    BASE("Base game", expansionRules = false, usesBase = true, usesExpansion = false),
    EXPANSION1("Expansion 1", expansionRules = true, usesBase = false, usesExpansion = true),
    MERGED("Base + Expansion 1", expansionRules = true, usesBase = true, usesExpansion = true),
}

/**
 * One kind of card as printed. [initiative] is only set for Delivery cards.
 * [countsAsGlyph] is only set for Treasures that count as a card of that glyph in each spell.
 *
 * The rest is only used by Expansion 1 cards: [creature] Deliveries can stay in play, [keepBands] are the Power Roll
 * results (1 = 1-4, 2 = 5-9, 3 = 10 or more) that keep the Creature, [payToKeepBlood] lets the owner pay Blood to keep it
 * instead, [resolveNow] Dead Wizard cards act the moment they are drawn, and [countsGlyphOfChosenCard] marks
 * Glutton's Regalia, which counts the glyph of a card put under it.
 */
data class CardDef(
    val id: String,
    val name: String,
    val type: CardType,
    val glyph: Glyph?,
    val initiative: Int?,
    val text: String,
    val scan: String?,
    val countsAsGlyph: Glyph?,
    val set: String = "base",
    val creature: Boolean = false,
    val keepBands: Set<Int> = emptySet(),
    val payToKeepBlood: Int? = null,
    val resolveNow: Boolean = false,
    val countsGlyphOfChosenCard: Boolean = false,
)

data class HeroDef(
    val id: String,
    val name: String,
    val title: String,
    val boardScan: String,
    val artScan: String,
)

/** A card design and how many physical copies exist. */
data class DeckEntry(val def: CardDef, val copies: Int)
