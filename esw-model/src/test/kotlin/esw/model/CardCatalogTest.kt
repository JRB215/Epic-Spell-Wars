package esw.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CardCatalogTest {
    private val catalog = CardCatalog.loadBase()

    @Test
    fun deckSizesMatchTheBox() {
        assertEquals(40, catalog.source.sumOf { it.copies })
        assertEquals(40, catalog.quality.sumOf { it.copies })
        assertEquals(40, catalog.delivery.sumOf { it.copies })
        assertEquals(8, catalog.wildMagic.sumOf { it.copies })
        assertEquals(25, catalog.treasures.sumOf { it.copies })
        assertEquals(25, catalog.deadWizards.sumOf { it.copies })
        assertEquals(40 + 40 + 40 + 8, catalog.mainDeckCards().size)
    }

    @Test
    fun spellCardsHaveAGlyphAndDeliveriesHaveDistinctInitiative() {
        (catalog.source + catalog.quality + catalog.delivery).forEach { assertNotNull(it.def.glyph, it.def.name) }
        val initiatives = catalog.delivery.map { it.def.initiative }
        assertEquals((1..20).toList(), initiatives.filterNotNull().sorted())
    }

    @Test
    fun cardIdsAreUnique() {
        val all = catalog.source + catalog.quality + catalog.delivery + catalog.treasures + catalog.deadWizards
        assertEquals(all.size, all.map { it.def.id }.toSet().size)
    }

    @Test
    fun baseModeIsUnchangedByTheExpansion() {
        assertEquals(GameMode.BASE, catalog.mode)
        assertTrue(catalog.allDefs.values.all { it.set == "base" && !it.creature })
    }

    @Test
    fun expansionOnlyDecksMatchTheBox() {
        val c = CardCatalog.load(GameMode.EXPANSION1)
        assertEquals(40, c.source.sumOf { it.copies })
        assertEquals(40, c.quality.sumOf { it.copies })
        assertEquals(40, c.delivery.sumOf { it.copies })
        assertEquals(8, c.wildMagic.sumOf { it.copies })
        assertEquals(25, c.treasures.sumOf { it.copies })
        assertEquals(25, c.deadWizards.sumOf { it.copies })
        assertEquals(128, c.mainDeckCards().size)
        assertTrue(c.allDefs.values.all { it.set == "expansion1" })
        // The expansion has its own Wild Magic design and reuses the shared heroes.
        assertEquals(16, c.heroes.size)
    }

    @Test
    fun mergedDecksAreBothSetsShuffledTogether() {
        val c = CardCatalog.load(GameMode.MERGED)
        assertEquals(256, c.mainDeckCards().size)
        assertEquals(50, c.treasureCards().size)
        assertEquals(50, c.deadWizardCards().size)
        assertEquals(16, c.wildMagic.sumOf { it.copies })
        assertEquals(setOf("base", "expansion1"), c.allDefs.values.map { it.set }.toSet())
        assertEquals(CardCatalog.loadBase().allDefs.size + CardCatalog.load(GameMode.EXPANSION1).allDefs.size, c.allDefs.size)
    }

    @Test
    fun everyExpansionDeliveryIsACreatureWithItsKeepResultsRead() {
        val c = CardCatalog.load(GameMode.EXPANSION1)
        assertTrue(c.delivery.all { it.def.creature && it.def.initiative != null })
        fun keep(id: String) = c.allDefs.getValue(id).keepBands
        assertEquals(setOf(3), keep("bob"))
        assertEquals(setOf(1), keep("cockatwice"))
        assertEquals(setOf(2), keep("tentakiller"))
        assertEquals(setOf(2), keep("pimped-imps"))
        assertEquals(setOf(1), keep("fart-knight"))
        assertEquals(emptySet(), keep("hand-of-god"))
        assertEquals(1, c.allDefs.getValue("fatcula").payToKeepBlood)
        assertEquals(emptySet(), keep("fatcula"))
    }

    @Test
    fun expansionDeadWizardCardsKnowWhichOnesResolveNow() {
        val c = CardCatalog.load(GameMode.EXPANSION1)
        val now = c.deadWizards.filter { it.def.resolveNow }.map { it.def.id }.toSet()
        assertEquals(setOf("ex-exsanguination", "grave-terrain", "classy-in-repose", "just-rewards"), now)
    }

    @Test
    fun heroesAreLoaded() {
        assertEquals(16, catalog.heroes.size)
        assertEquals(20, catalog.startingHp)
        assertEquals(25, catalog.maxHp)
        assertTrue(catalog.heroes.all { it.name.isNotBlank() })
    }

    @Test
    fun deliveryTextIsBuiltFromTheRollTable() {
        val chicken = catalog.allDefs.getValue("chicken")
        assertTrue(chicken.text.contains("Your strongest foe"), chicken.text)
        assertTrue(chicken.text.contains("10+: 7 damage"), chicken.text)
    }
}
