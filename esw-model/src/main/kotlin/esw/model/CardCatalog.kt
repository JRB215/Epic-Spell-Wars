package esw.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Everything that can be dealt in one game: the base set, Expansion 1, or both shuffled together. Built from the JSON
 * data files. [mode] says which rules apply as well as which cards are in the decks.
 */
class CardCatalog(
    val mode: GameMode,
    val source: List<DeckEntry>,
    val quality: List<DeckEntry>,
    val delivery: List<DeckEntry>,
    val wildMagic: List<DeckEntry>,
    val treasures: List<DeckEntry>,
    val deadWizards: List<DeckEntry>,
    val heroes: List<HeroDef>,
    val startingHp: Int,
    val maxHp: Int,
) {
    /** Source, Quality, Delivery and Wild Magic, one entry per physical card. */
    fun mainDeckCards(): List<CardDef> =
        (source + quality + delivery + wildMagic).expand()

    fun treasureCards(): List<CardDef> = treasures.expand()

    fun deadWizardCards(): List<CardDef> = deadWizards.expand()

    /** Every distinct card design, for lookups by id. */
    val allDefs: Map<String, CardDef>

    init {
        val everything = source + quality + delivery + wildMagic + treasures + deadWizards
        val duplicates = everything.groupBy { it.def.id }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "Card ids used more than once: $duplicates" }
        allDefs = everything.associate { it.def.id to it.def }
    }

    private fun List<DeckEntry>.expand(): List<CardDef> = flatMap { e -> List(e.copies) { e.def } }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Loads the base game from the card data files bundled in this module. */
        fun loadBase(): CardCatalog = load(GameMode.BASE)

        fun load(mode: GameMode): CardCatalog {
            val base = if (mode.usesBase) loadSet("cards/base") else null
            val expansion = if (mode.usesExpansion) loadSet("cards/expansion1") else null
            val sets = listOfNotNull(base, expansion)
            // Heroes are shared: every hero plays the same, so they come from the base set in every mode.
            val heroes = json.decodeFromString<HeroFile>(read("cards/base", "heroes"))
            return CardCatalog(
                mode = mode,
                source = sets.flatMap { it.source },
                quality = sets.flatMap { it.quality },
                delivery = sets.flatMap { it.delivery },
                wildMagic = sets.flatMap { it.wildMagic },
                treasures = sets.flatMap { it.treasures },
                deadWizards = sets.flatMap { it.deadWizards },
                heroes = heroes.heroes.map { HeroDef(it.id, it.name, it.title, it.boardScan, it.artScan) },
                startingHp = heroes.startingHp,
                maxHp = heroes.maxHp,
            )
        }

        private fun read(dir: String, name: String): String =
            (CardCatalog::class.java.classLoader.getResourceAsStream("$dir/$name.json")
                ?: error("Missing card data file $dir/$name.json")).use { it.readBytes().toString(Charsets.UTF_8) }

        /** The decks of one card set (without heroes). */
        private class SetDecks(
            val source: List<DeckEntry>, val quality: List<DeckEntry>, val delivery: List<DeckEntry>,
            val wildMagic: List<DeckEntry>, val treasures: List<DeckEntry>, val deadWizards: List<DeckEntry>,
        )

        private fun loadSet(dir: String): SetDecks {
            val wild = json.decodeFromString<WildMagicFile>(read(dir, "wild-magic"))
            return SetDecks(
                source = json.decodeFromString<DeckFile>(read(dir, "source")).toEntries(CardType.SOURCE),
                quality = json.decodeFromString<DeckFile>(read(dir, "quality")).toEntries(CardType.QUALITY),
                delivery = json.decodeFromString<DeckFile>(read(dir, "delivery")).toEntries(CardType.DELIVERY),
                wildMagic = listOf(DeckEntry(wild.card.toDef(CardType.WILD_MAGIC, "", wild.set), wild.copies)),
                treasures = json.decodeFromString<DeckFile>(read(dir, "treasures")).toEntries(CardType.TREASURE),
                deadWizards = json.decodeFromString<DeckFile>(read(dir, "dead-wizards")).toEntries(CardType.DEAD_WIZARD),
            )
        }
    }
}

// ---- JSON shapes (private to this file's loader) ----

@Serializable
private class RowJson(val from: Int, val to: Int? = null, val text: String, val keep: Boolean = false)

@Serializable
private class RollJson(val targetText: String? = null, val rows: List<RowJson> = emptyList())

@Serializable
private class PayToKeepJson(val blood: Int)

@Serializable
private class CardJson(
    val id: String,
    val name: String,
    val glyph: String? = null,
    val initiative: Int? = null,
    val scan: String? = null,
    val text: String? = null,
    val copies: Int? = null,
    val countsAsGlyph: String? = null,
    val roll: RollJson? = null,
    val creature: Boolean = false,
    val payToKeep: PayToKeepJson? = null,
    val resolveNow: Boolean = false,
    val countsGlyphOfChosenCard: Boolean = false,
) {
    fun toDef(type: CardType, defaultText: String, set: String): CardDef = CardDef(
        id = id,
        name = name,
        type = type,
        glyph = glyph?.let { Glyph.valueOf(it) },
        initiative = initiative,
        text = text ?: roll?.describe() ?: defaultText,
        scan = scan,
        countsAsGlyph = countsAsGlyph?.let { Glyph.valueOf(it) },
        set = set,
        creature = creature,
        // Row 0 is the 1-4 result, row 1 is 5-9 and row 2 is 10+.
        keepBands = roll?.rows?.mapIndexedNotNull { i, r -> if (r.keep) i + 1 else null }?.toSet().orEmpty(),
        payToKeepBlood = payToKeep?.blood,
        resolveNow = resolveNow,
        countsGlyphOfChosenCard = countsGlyphOfChosenCard,
    )
}

private fun RollJson.describe(): String {
    val bands = rows.joinToString(" ") { r ->
        val range = when {
            r.to == null -> "${r.from}+"
            r.from == r.to -> "${r.from}"
            else -> "${r.from}-${r.to}"
        }
        "$range: ${r.text}"
    }
    return "Target: ${targetText ?: "?"}. Roll Power. $bands"
}

@Serializable
private class DeckFile(val copiesEach: Int = 1, val set: String = "base", val cards: List<CardJson>) {
    fun toEntries(type: CardType): List<DeckEntry> =
        cards.map { DeckEntry(it.toDef(type, "", set), it.copies ?: copiesEach) }
}

@Serializable
private class WildMagicFile(val copies: Int, val set: String = "base", val card: CardJson)

@Serializable
private class HeroJson(
    val id: String,
    val name: String,
    val title: String = "",
    val boardScan: String,
    val artScan: String,
)

@Serializable
private class HeroFile(val startingHp: Int, val maxHp: Int, val heroes: List<HeroJson>)
