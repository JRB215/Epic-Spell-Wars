package esw.model

/** The five kinds of magic. Many effects count or compare glyphs. */
enum class Glyph { ARCANE, DARK, ELEMENTAL, ILLUSION, PRIMAL }

enum class CardType { SOURCE, QUALITY, DELIVERY, WILD_MAGIC, TREASURE, DEAD_WIZARD }

/**
 * One kind of card as printed. [initiative] is only set for Delivery cards.
 * [countsAsGlyph] is only set for Treasures that count as a card of that glyph in each spell.
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
