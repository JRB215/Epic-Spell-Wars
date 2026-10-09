package esw.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Everything in one set of cards (for now only the base game). Built from the JSON data files. */
class CardCatalog(
    val source: List<DeckEntry>,
    val quality: List<DeckEntry>,
    val delivery: List<DeckEntry>,
    val wildMagic: DeckEntry,
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
    val allDefs: Map<String, CardDef> =
        (source + quality + delivery + wildMagic + treasures + deadWizards).associate { it.def.id to it.def }

    private fun List<DeckEntry>.expand(): List<CardDef> = flatMap { e -> List(e.copies) { e.def } }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Loads the base game from the card data files bundled in this module. */
        fun loadBase(): CardCatalog = load("cards/base")

        fun load(dir: String): CardCatalog {
            fun read(name: String): String =
                (CardCatalog::class.java.classLoader.getResourceAsStream("$dir/$name.json")
                    ?: error("Missing card data file $dir/$name.json")).use { it.readBytes().toString(Charsets.UTF_8) }

            val source = json.decodeFromString<DeckFile>(read("source")).toEntries(CardType.SOURCE)
            val quality = json.decodeFromString<DeckFile>(read("quality")).toEntries(CardType.QUALITY)
            val delivery = json.decodeFromString<DeckFile>(read("delivery")).toEntries(CardType.DELIVERY)
            val treasures = json.decodeFromString<DeckFile>(read("treasures")).toEntries(CardType.TREASURE)
            val deadWizards = json.decodeFromString<DeckFile>(read("dead-wizards")).toEntries(CardType.DEAD_WIZARD)
            val wild = json.decodeFromString<WildMagicFile>(read("wild-magic"))
            val heroes = json.decodeFromString<HeroFile>(read("heroes"))

            return CardCatalog(
                source = source,
                quality = quality,
                delivery = delivery,
                wildMagic = DeckEntry(wild.card.toDef(CardType.WILD_MAGIC, ""), wild.copies),
                treasures = treasures,
                deadWizards = deadWizards,
                heroes = heroes.heroes.map { HeroDef(it.id, it.name, it.title, it.boardScan, it.artScan) },
                startingHp = heroes.startingHp,
                maxHp = heroes.maxHp,
            )
        }
    }
}

// ---- JSON shapes (private to this file's loader) ----

@Serializable
private class RowJson(val from: Int, val to: Int? = null, val text: String)

@Serializable
private class RollJson(val targetText: String? = null, val rows: List<RowJson> = emptyList())

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
) {
    fun toDef(type: CardType, defaultText: String): CardDef = CardDef(
        id = id,
        name = name,
        type = type,
        glyph = glyph?.let { Glyph.valueOf(it) },
        initiative = initiative,
        text = text ?: roll?.describe() ?: defaultText,
        scan = scan,
        countsAsGlyph = countsAsGlyph?.let { Glyph.valueOf(it) },
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
private class DeckFile(val copiesEach: Int = 1, val cards: List<CardJson>) {
    fun toEntries(type: CardType): List<DeckEntry> =
        cards.map { DeckEntry(it.toDef(type, ""), it.copies ?: copiesEach) }
}

@Serializable
private class WildMagicFile(val copies: Int, val card: CardJson)

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
