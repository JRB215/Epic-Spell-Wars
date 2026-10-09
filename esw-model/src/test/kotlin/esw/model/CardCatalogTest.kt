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
        assertEquals(8, catalog.wildMagic.copies)
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
