package esw.engine

import esw.model.CardCatalog
import esw.model.CardType
import esw.model.GameMode
import esw.model.Glyph
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Answers every question from queues, so a test can say exactly what the player chooses. Anything not queued gets a default. */
private class Script(
    val players: ArrayDeque<Int> = ArrayDeque(),
    val options: ArrayDeque<Int> = ArrayDeque(),
    val yes: ArrayDeque<Boolean> = ArrayDeque(),
    val cards: ArrayDeque<String> = ArrayDeque(),
    val defaultYes: Boolean = false,
)

private class FxDecisions(private val s: Script) : Decisions {
    override suspend fun chooseSpell(player: Int, hand: List<CardInstance>, treasures: List<CardInstance>) = SpellChoice(null, null, null)
    override suspend fun choosePlayer(player: Int, candidates: List<Int>, reason: String): Int {
        val wanted = s.players.removeFirstOrNull()
        return if (wanted != null && wanted in candidates) wanted else candidates.first()
    }
    override suspend fun chooseTreasure(player: Int, candidates: List<CardInstance>, reason: String): Int {
        val wanted = s.cards.firstOrNull()
        val pick = candidates.firstOrNull { it.def.id == wanted }
        if (pick != null) s.cards.removeFirst()
        return (pick ?: candidates.first()).uid
    }
    override suspend fun chooseCardFromHand(player: Int, candidates: List<CardInstance>, reason: String): Int {
        val wanted = s.cards.firstOrNull()
        val pick = candidates.firstOrNull { it.def.id == wanted }
        if (pick != null) s.cards.removeFirst()
        return (pick ?: candidates.first()).uid
    }
    override suspend fun chooseOption(player: Int, prompt: String, options: List<String>) = s.options.removeFirstOrNull() ?: 0
    override suspend fun chooseYesNo(player: Int, prompt: String) = s.yes.removeFirstOrNull() ?: s.defaultYes
}

/**
 * One test for every Expansion 1 card that does not already have its own outcome test elsewhere. Each runs the card's
 * effect directly for wizard 1 (index 0) against wizards 2 and 3, with the dice and the choices fixed in advance.
 * "Left" of wizard 1 is wizard 2 (index 1); "right" is the last wizard.
 */
class ExpansionEveryCardTest {
    private val merged = CardCatalog.load(GameMode.MERGED)
    private val events = mutableListOf<GameEvent>()

    private fun fx(
        dice: List<Int> = emptyList(),
        players: Int = 3,
        script: Script = Script(),
        before: (Game) -> Unit = {},
    ): Game {
        val game = Game(
            merged, List(players) { "Wizard ${it + 1}" }, FxDecisions(script), ScriptedDice(dice), Random(11),
        ) { events.add(it) }
        before(game)
        runBlocking { game.beginGameForTest() }
        for (p in game.players) p.hand.clear()
        game.players[0].spell = Spell()
        return game
    }

    private fun script(
        players: List<Int> = emptyList(), options: List<Int> = emptyList(), yes: List<Boolean> = emptyList(),
        cards: List<String> = emptyList(), defaultYes: Boolean = false,
    ) = Script(ArrayDeque(players), ArrayDeque(options), ArrayDeque(yes), ArrayDeque(cards), defaultYes)

    private fun cast(g: Game, id: String, spellCard: SpellCard? = null) {
        val def = merged.allDefs.getValue(id)
        runBlocking { CardEffects.run(EffectContext(g, g.players[0], def, def.type, spellCard), id) }
    }

    /** Plays a Creature (a Delivery) as part of wizard 1's spell. [band] 1, 2 or 3 sets the Power Roll to 2, 5 or 11. */
    private fun creature(g: Game, id: String, band: Int, extraDice: List<Int> = emptyList()): SpellCard {
        val p = g.players[0]
        val card = g.giveToHand(p, id)
        p.hand.remove(card)
        val sc = SpellCard(card, CardType.DELIVERY, wild = false)
        p.spell!!.cards.add(sc)
        if (band == 3) p.bonusDie = 8
        cast(g, id, sc)
        return sc
    }

    private fun diceFor(band: Int) = listOf(when (band) { 1 -> 2; 2 -> 5; else -> 3 })

    private fun dmg(target: Int) = events.filterIsInstance<GameEvent.DamageDealt>().filter { it.target == target }.map { it.amount }
    private fun healed(player: Int) = events.filterIsInstance<GameEvent.Healed>().filter { it.player == player }.map { it.amount }

    private fun hand(g: Game, player: Int, vararg ids: String) = ids.forEach { g.giveToHand(g.players[player], it) }

    // ------------------------------------------------------------------ SOURCE

    @Test
    fun sirMixsALittlePassesTheChosenTypeLeftAndHurtsTheReceivers() {
        val g = fx(script = script(options = listOf(0)))
        hand(g, 1, "scorchias", "wyrmtors", "mysterious")
        hand(g, 2, "pew-and-pews")
        cast(g, "sir-mixs-a-littles")
        assertEquals(listOf(2), dmg(2), "wizard 3 received two Source cards")
        assertEquals(listOf(1), dmg(0), "wizard 1 received one")
        assertTrue(dmg(1).isEmpty())
        assertEquals(1, g.players[0].hand.size)
        assertEquals(2, g.players[2].hand.count { it.def.type == CardType.SOURCE })
        assertEquals(listOf("mysterious"), g.players[1].hand.map { it.def.id }, "other types stay put")
    }

    @Test
    fun sirMixsALittlePaidDoublesTheDamage() {
        val g = fx(script = script(options = listOf(0), defaultYes = true))
        g.gainBlood(g.players[0], 4, "test")
        hand(g, 1, "scorchias", "wyrmtors")
        cast(g, "sir-mixs-a-littles")
        assertEquals(listOf(4), dmg(2))
        assertEquals(0, g.players[0].blood)
    }

    @Test
    fun goldieLocksTakesACreatureTheFoeAgreesToGive() {
        val g = fx(script = script(players = listOf(1), yes = listOf(true), cards = listOf("bob")))
        hand(g, 1, "bob", "scorchias")
        cast(g, "goldie-locks")
        assertTrue(g.players[0].spell!!.cards.any { it.card.def.id == "bob" })
        assertEquals(listOf("scorchias"), g.players[1].hand.map { it.def.id })
        assertTrue(dmg(1).isEmpty())
    }

    @Test
    fun goldieLocksHurtsAFoeWhoRefusesOrHasNoCreature() {
        val refuse = fx(dice = listOf(4), script = script(players = listOf(1), yes = listOf(false)))
        hand(refuse, 1, "bob")
        cast(refuse, "goldie-locks")
        assertEquals(listOf(4), dmg(1))
        assertEquals(1, refuse.players[1].hand.size)

        events.clear()
        val none = fx(dice = listOf(5), script = script(players = listOf(2)))
        cast(none, "goldie-locks")
        assertEquals(listOf(5), dmg(2))
    }

    @Test
    fun adhDeezisTakesTheStandeeFromALivingFoeAndHurtsADifferentOne() {
        val g = fx()
        g.giveStandeeForTest(g.players[1])
        cast(g, "a-d-h-deezis")
        assertTrue(g.hasStandee(g.players[0]))
        assertEquals(listOf(3), dmg(2))
        assertTrue(dmg(1).isEmpty())
    }

    @Test
    fun adhDeezisDoesNoDamageWhenTheStandeeWasInTheMiddleOrYours() {
        val middle = fx()
        cast(middle, "a-d-h-deezis")
        assertTrue(middle.hasStandee(middle.players[0]))
        assertTrue(dmg(1).isEmpty() && dmg(2).isEmpty())

        val mine = fx()
        mine.giveStandeeForTest(mine.players[0])
        cast(mine, "a-d-h-deezis")
        assertTrue(dmg(1).isEmpty() && dmg(2).isEmpty())
    }

    @Test
    fun baconusHealsOnePerCreatureInPlayAndXMoreForBlood() {
        val g = fx(script = script(options = listOf(2)))
        g.giveCreature(g.players[1], "bob"); g.giveCreature(g.players[1], "cockatwice"); g.giveCreature(g.players[2], "draggen")
        g.gainBlood(g.players[0], 5, "test")
        g.players[0].hp = 10
        cast(g, "baconus-the-butchers")
        assertEquals(listOf(3, 2), healed(0))
        assertEquals(15, g.players[0].hp)
        assertEquals(3, g.players[0].blood)
    }

    @Test
    fun painDemonsSendsOneDieToTheLeftFoeAndOneToYou() {
        val g = fx(dice = listOf(3, 5), script = script(options = listOf(0)))
        cast(g, "the-pain-demons")
        assertEquals(listOf(3), dmg(1))
        assertEquals(listOf(5), dmg(0))
    }

    @Test
    fun painDemonsSparesYouWithTheStandeeOrForOneBlood() {
        val standee = fx(dice = listOf(3, 5), script = script(options = listOf(0)))
        standee.giveStandeeForTest(standee.players[0])
        cast(standee, "the-pain-demons")
        assertEquals(listOf(3), dmg(1)); assertTrue(dmg(0).isEmpty())

        events.clear()
        val paid = fx(dice = listOf(3, 5), script = script(options = listOf(1), yes = listOf(true)))
        paid.gainBlood(paid.players[0], 1, "test")
        cast(paid, "the-pain-demons")
        assertEquals(listOf(5), dmg(1), "the other die goes left this time")
        assertTrue(dmg(0).isEmpty())
        assertEquals(0, paid.players[0].blood)
    }

    @Test
    fun mthgurgleLeachOtepsLeftFoeChoosesBloodOrDamage() {
        val pays = fx(script = script(yes = listOf(true)))
        pays.gainBlood(pays.players[1], 2, "test")
        cast(pays, "mthgurgle-leachoteps")
        assertEquals(2, pays.players[0].blood); assertEquals(0, pays.players[1].blood)
        assertTrue(dmg(1).isEmpty())

        events.clear()
        val refuses = fx(script = script(yes = listOf(false)))
        refuses.gainBlood(refuses.players[1], 4, "test")
        cast(refuses, "mthgurgle-leachoteps")
        assertEquals(listOf(3), dmg(1)); assertEquals(4, refuses.players[1].blood)

        events.clear()
        val poor = fx(script = script(defaultYes = true))
        poor.gainBlood(poor.players[1], 1, "test")
        cast(poor, "mthgurgle-leachoteps")
        assertEquals(listOf(3), dmg(1), "with under 2 Blood the foe has to take the damage")
    }

    @Test
    fun rawHydrasHitsTheStrongestAndPaidHitsEveryFoeWithAToken() {
        val g = fx(script = script(defaultYes = true))
        g.players[1].hp = 25
        g.players[2].tokens = 1
        g.gainBlood(g.players[0], 4, "test")
        cast(g, "raw-hydras")
        assertEquals(listOf(3), dmg(1))
        assertEquals(listOf(7), dmg(2))
        assertEquals(0, g.players[0].blood)

        events.clear()
        val free = fx()
        free.players[1].hp = 25
        free.players[2].tokens = 1
        cast(free, "raw-hydras")
        assertTrue(dmg(2).isEmpty(), "no Blood, no payment")
    }

    @Test
    fun vishaVindasmacksAddsOneCardOrTwoWithTheStandee() {
        val one = fx()
        hand(one, 0, "scorchias", "wyrmtors", "mysterious")
        cast(one, "visha-vindasmacks")
        assertEquals(1, one.players[0].spell!!.cards.size)
        assertEquals(2, one.players[0].hand.size)

        val two = fx()
        two.giveStandeeForTest(two.players[0])
        hand(two, 0, "scorchias", "wyrmtors", "mysterious")
        cast(two, "visha-vindasmacks")
        assertEquals(2, two.players[0].spell!!.cards.size)
    }

    @Test
    fun moochieTakesBloodFromFoesWhoActedAndHurtsThoseWhoHaveNot() {
        val g = fx()
        g.players[1].acted = true
        g.gainBlood(g.players[1], 3, "test")
        cast(g, "moochie-da-magic-missilers")
        assertEquals(1, g.players[0].blood)
        assertEquals(2, g.players[1].blood)
        assertTrue(dmg(1).isEmpty())
        assertEquals(listOf(2), dmg(2))
    }

    @Test
    fun storkloxHitsTheStrongestAndPaidAddsARandomHandCard() {
        val g = fx(script = script(defaultYes = true))
        g.players[2].hp = 25
        g.gainBlood(g.players[0], 3, "test")
        hand(g, 0, "scorchias")
        cast(g, "storklox-the-unmakers")
        assertEquals(listOf(2), dmg(2))
        assertEquals(1, g.players[0].spell!!.cards.size)
        assertEquals(0, g.players[0].blood)

        val broke = fx()
        hand(broke, 0, "scorchias")
        cast(broke, "storklox-the-unmakers")
        assertEquals(0, broke.players[0].spell!!.cards.size)
    }

    @Test
    fun drGraveloversTakesTheStandeeAndPaidHitsPerToken() {
        val g = fx(script = script(defaultYes = true))
        g.players[0].tokens = 1; g.players[1].tokens = 1
        g.gainBlood(g.players[0], 1, "test")
        cast(g, "dr-gravelovers")
        assertTrue(g.hasStandee(g.players[0]))
        assertEquals(listOf(4), dmg(1)); assertEquals(listOf(4), dmg(2))
    }

    @Test
    fun theFlamingPrincesHitsEachFoeForOneOrFiveWhenPaid() {
        val cheap = fx()
        cast(cheap, "the-flaming-princes")
        assertEquals(listOf(1), dmg(1)); assertEquals(listOf(1), dmg(2))

        events.clear()
        val paid = fx(script = script(defaultYes = true))
        paid.gainBlood(paid.players[0], 5, "test")
        cast(paid, "the-flaming-princes")
        assertEquals(listOf(5), dmg(1)); assertEquals(listOf(5), dmg(2))
    }

    @Test
    fun togongoValcandosGivesBloodThenDealsADie() {
        val g = fx(dice = listOf(4, 2))
        g.players[1].hp = 25
        g.gainBlood(g.players[0], 2, "test")
        g.giveStandeeForTest(g.players[0])
        cast(g, "togongo-valcandos")
        assertEquals(1, g.players[0].blood)
        assertEquals(1, g.players[1].blood)
        assertEquals(listOf(4), dmg(1))
        assertEquals(listOf(2), dmg(2), "the Standee gives a second die to a different foe")

        events.clear()
        val noStandee = fx(dice = listOf(4))
        noStandee.players[1].hp = 25
        cast(noStandee, "togongo-valcandos")
        assertEquals(0, noStandee.players[1].blood, "nothing to give with no Blood")
        assertEquals(listOf(4), dmg(1)); assertTrue(dmg(2).isEmpty())
    }

    // ------------------------------------------------------------------ QUALITY

    @Test
    fun grubdiddlingTakesAnArcaneCardOrATreasureFromTheLeftFoe() {
        val card = fx()
        hand(card, 1, "griz-glowballs", "scorchias")
        cast(card, "grubdiddling")
        assertEquals(listOf("griz-glowballs"), card.players[0].hand.map { it.def.id })

        val choice = fx(script = script(options = listOf(1)))
        hand(choice, 1, "griz-glowballs")
        choice.giveTreasure(choice.players[1], "thinking-cap")
        cast(choice, "grubdiddling")
        assertTrue(choice.players[0].has("thinking-cap"), "the foe chose to give a Treasure")
        assertEquals(1, choice.players[1].hand.size)
    }

    @Test
    fun grubdiddlingRepeatsOnTheRightFoeWithTheStandee() {
        val g = fx()
        g.giveStandeeForTest(g.players[0])
        hand(g, 1, "griz-glowballs")
        g.giveTreasure(g.players[2], "thinking-cap")
        cast(g, "grubdiddling")
        assertTrue(g.players[0].hand.any { it.def.id == "griz-glowballs" })
        assertTrue(g.players[0].has("thinking-cap"))
    }

    @Test
    fun attackalancheCountsUpFromTheRight() {
        val g = fx()
        cast(g, "attackalanche")
        assertEquals(listOf(1), dmg(2)); assertEquals(listOf(2), dmg(1))
        val order = events.filterIsInstance<GameEvent.DamageDealt>().map { it.target }
        assertEquals(listOf(2, 1), order, "the right-hand foe is hit first")

        events.clear()
        val paid = fx(script = script(defaultYes = true))
        paid.gainBlood(paid.players[0], 2, "test")
        cast(paid, "attackalanche")
        assertEquals(listOf(2), dmg(2)); assertEquals(listOf(4), dmg(1))
    }

    @Test
    fun inGravenDiscardsMatchingCardsAndHurtsAFoeForEach() {
        val g = fx(script = script(options = listOf(0, 1), players = listOf(1)))
        hand(g, 0, "scorchias", "wyrmtors", "mysterious")
        cast(g, "in-graven")
        assertEquals(listOf(2), dmg(1))
        assertEquals(listOf("mysterious"), g.players[0].hand.map { it.def.id }.filter { it == "mysterious" })
        assertEquals(1, g.players[0].hand.size, "only the Quality card is left")
    }

    @Test
    fun inGravenPaidDoublesTheDamage() {
        val g = fx(script = script(options = listOf(0, 0), players = listOf(2), defaultYes = true))
        g.gainBlood(g.players[0], 4, "test")
        hand(g, 0, "scorchias", "wyrmtors")
        cast(g, "in-graven")
        assertEquals(listOf(2), dmg(2), "one card discarded, doubled")
    }

    @Test
    fun burnzerkingHitsTwicePerCreatureInTheirHand() {
        val g = fx(script = script(players = listOf(1)))
        hand(g, 1, "bob", "bung-burner", "scorchias")
        cast(g, "burnzerking")
        assertEquals(listOf(4), dmg(1))
        assertTrue(dmg(2).isEmpty())
    }

    @Test
    fun burnzerkingPaidChoosesEveryFoe() {
        val g = fx(script = script(defaultYes = true))
        g.gainBlood(g.players[0], 3, "test")
        hand(g, 1, "bob"); hand(g, 2, "bob", "cockatwice")
        cast(g, "burnzerking")
        assertEquals(listOf(2), dmg(1)); assertEquals(listOf(4), dmg(2))
    }

    @Test
    fun giftyAddsADeliveryAndPaidAlsoAddsARevealedCreature() {
        val g = fx(script = script(defaultYes = true))
        g.gainBlood(g.players[0], 4, "test")
        hand(g, 0, "chicken", "scorchias")
        cast(g, "gifty")
        val cards = g.players[0].spell!!.cards.map { it.card.def }
        assertTrue(cards.any { it.id == "chicken" })
        assertTrue(cards.any { it.creature }, "the revealed Creature joins the spell")
        assertEquals(0, g.players[0].blood)

        val plain = fx()
        hand(plain, 0, "chicken")
        cast(plain, "gifty")
        assertEquals(1, plain.players[0].spell!!.cards.size)
    }

    @Test
    fun woodstalkyDiscardsUpToThreeNonPrimalCardsToHeal() {
        val g = fx()
        g.players[0].hp = 10
        hand(g, 0, "scorchias", "wyrmtors", "mysterious", "pew-and-pews", "sphinxions")
        val nonPrimal = g.players[0].hand.count { it.def.glyph != Glyph.PRIMAL }
        cast(g, "woodstalky")
        assertEquals(minOf(3, nonPrimal), g.players[0].hp - 10)

        val stop = fx(script = script(options = listOf(99)))
        stop.players[0].hp = 10
        hand(stop, 0, "scorchias")
        val options = stop.players[0].hand.count { it.def.glyph != Glyph.PRIMAL }
        val s2 = fx(script = script(options = listOf(options)))
        s2.players[0].hp = 10
        hand(s2, 0, "scorchias")
        cast(s2, "woodstalky")
        assertEquals(10, s2.players[0].hp, "choosing Stop heals nothing")
    }

    @Test
    fun pollinatingAddsOneCreatureOrTwoWithTheStandee() {
        val one = fx()
        cast(one, "pollinating")
        assertEquals(1, one.players[0].spell!!.cards.count { it.card.def.creature })

        val two = fx()
        two.giveStandeeForTest(two.players[0])
        cast(two, "pollinating")
        assertEquals(2, two.players[0].spell!!.cards.count { it.card.def.creature })
    }

    @Test
    fun cashedInGivesTreasuresToYouAndPoorFoesAndPaidHurtsPerTreasure() {
        val g = fx(script = script(defaultYes = true))
        repeat(4) { g.stackTreasure("thinking-cap") }
        g.giveTreasure(g.players[2], "kingor-crown")
        g.gainBlood(g.players[0], 8, "test")
        cast(g, "cashed-in")
        val gained = events.filterIsInstance<GameEvent.TreasureGained>()
        assertEquals(1, gained.count { it.player == 0 })
        assertEquals(1, gained.count { it.player == 1 })
        assertEquals(0, gained.count { it.player == 2 }, "wizard 3 already had one")
        // Three Treasures held in total, so 6 damage to each foe.
        assertEquals(listOf(6), dmg(1)); assertEquals(listOf(6), dmg(2))
    }

    @Test
    fun sparklingHitsARandomFoeForADieAndTheStandeeAddsTwoMore() {
        val g = fx(dice = listOf(2, 4))
        g.giveStandeeForTest(g.players[0])
        cast(g, "sparkling")
        assertEquals(listOf(4), dmg(1), "a 2 picks the first foe")
        assertEquals(listOf(2), dmg(2))

        events.clear()
        val plain = fx(dice = listOf(5, 3))
        cast(plain, "sparkling")
        assertEquals(listOf(3), dmg(2), "a 5 picks the second foe")
        assertTrue(dmg(1).isEmpty())
    }

    @Test
    fun infernaluckyGivesTheHighRollerBloodAndDamage() {
        val g = fx(dice = listOf(5, 2))
        cast(g, "infernalucky")
        assertEquals(listOf(5), dmg(1)); assertEquals(1, g.players[1].blood)
        assertTrue(dmg(2).isEmpty())

        events.clear()
        val standee = fx(dice = listOf(5, 2))
        standee.giveStandeeForTest(standee.players[0])
        cast(standee, "infernalucky")
        assertEquals(listOf(2), dmg(2), "the Standee hurts everyone else for their roll")

        events.clear()
        val tie = fx(dice = listOf(4, 4))
        cast(tie, "infernalucky")
        assertEquals(1, tie.players[1].blood); assertEquals(1, tie.players[2].blood)
        assertEquals(listOf(4), dmg(1)); assertEquals(listOf(4), dmg(2))
    }

    // ------------------------------------------------------------------ DELIVERY (Creatures)

    @Test
    fun bobStealsBloodAndHitsByBand() {
        for ((band, damage) in listOf(1 to 0, 2 to 1, 3 to 4)) {
            events.clear()
            val g = fx(dice = diceFor(band))
            g.gainBlood(g.players[1], 4, "test")
            val sc = creature(g, "bob", band)
            assertEquals(if (damage == 0) emptyList() else listOf(damage), dmg(1), "band $band")
            assertEquals(1, g.players[0].blood, "band $band steals 1 Blood")
            assertEquals(band == 3, sc.keep, "KEEP only on 10+")
        }
    }

    @Test
    fun rockNRollerDealsAnotherDieOnTenPlus() {
        val g = fx(dice = listOf(3, 6))
        g.players[1].hp = 25
        val sc = creature(g, "rock-n-roller", 3)
        assertEquals(listOf(6), dmg(1)); assertTrue(sc.keep)
        for ((band, damage) in listOf(1 to 1, 2 to 2)) {
            events.clear()
            val h = fx(dice = diceFor(band)); h.players[1].hp = 25
            creature(h, "rock-n-roller", band)
            assertEquals(listOf(damage), dmg(1))
        }
    }

    @Test
    fun draggenHitsAFoeWhoActedAndTokenHoldersOnTenPlus() {
        for ((band, expected) in listOf(1 to listOf(1), 2 to listOf(3), 3 to listOf(3))) {
            events.clear()
            val g = fx(dice = diceFor(band))
            g.players[1].acted = true; g.players[2].tokens = 1
            val sc = creature(g, "draggen", band)
            assertEquals(expected, dmg(1), "band $band")
            assertEquals(if (band == 3) listOf(3) else emptyList(), dmg(2), "band $band")
            assertEquals(band == 3, sc.keep)
        }
    }

    @Test
    fun landSharkTakesTheStandeeOnFiveOrMore() {
        for ((band, damage) in listOf(1 to 1, 2 to 2, 3 to 5)) {
            events.clear()
            val g = fx(dice = diceFor(band))
            g.giveStandeeForTest(g.players[1])
            val sc = creature(g, "land-shark", band)
            assertEquals(listOf(damage), dmg(1))
            assertEquals(band >= 2, g.hasStandee(g.players[0]), "band $band")
            assertEquals(band == 3, sc.keep)
        }
    }

    @Test
    fun baronSquirrlingtonHealsOnTenPlus() {
        for ((band, damage) in listOf(1 to 0, 2 to 2, 3 to 4)) {
            events.clear()
            val g = fx(dice = diceFor(band))
            g.gainBlood(g.players[2], 5, "test"); g.players[0].hp = 10
            val sc = creature(g, "baron-squirrlington", band)
            assertEquals(if (damage == 0) emptyList() else listOf(damage), dmg(2))
            assertEquals(if (band == 3) 12 else 10, g.players[0].hp)
            assertEquals(band == 3, sc.keep)
        }
    }

    @Test
    fun mechaSatanaHurtsYouOnAMidRollAndTheStrongestOnTenPlus() {
        for ((band, weakDamage) in listOf(1 to 1, 2 to 2, 3 to 4)) {
            events.clear()
            val g = fx(dice = diceFor(band))
            g.players[2].hp = 12
            val sc = creature(g, "mecha-satana", band)
            assertEquals(listOf(weakDamage), dmg(2), "band $band")
            assertEquals(if (band == 2) listOf(1) else emptyList(), dmg(0))
            assertEquals(if (band == 3) listOf(2) else emptyList(), dmg(1), "the strongest foe takes 2 on 10+")
            assertEquals(band == 3, sc.keep)
        }
    }

    @Test
    fun bungBurnerHitsTheFoeWithTheMostBlood() {
        for ((band, damage) in listOf(1 to 2, 2 to 3, 3 to 4)) {
            events.clear()
            val g = fx(dice = diceFor(band))
            g.gainBlood(g.players[1], 6, "test"); g.gainBlood(g.players[2], 2, "test")
            val sc = creature(g, "bung-burner", band)
            assertEquals(listOf(damage), dmg(1)); assertTrue(dmg(2).isEmpty())
            assertEquals(band == 3, sc.keep)
        }
    }

    @Test
    fun sexsquatchSplashesTheOtherFoeOnTenPlus() {
        for ((band, damage) in listOf(1 to 2, 2 to 3, 3 to 4)) {
            events.clear()
            val g = fx(dice = diceFor(band))
            g.players[1].acted = true
            val sc = creature(g, "sexsquatch", band)
            assertEquals(listOf(damage), dmg(1))
            assertEquals(if (band == 3) listOf(1) else emptyList(), dmg(2))
            assertEquals(band == 3, sc.keep)
        }
    }

    @Test
    fun fatculaKeepsOnlyWhenYouPayOneBlood() {
        val paid = fx(dice = diceFor(2), script = script(yes = listOf(true)))
        paid.gainBlood(paid.players[0], 1, "test"); paid.players[1].hp = 25
        val kept = creature(paid, "fatcula", 2)
        assertTrue(kept.keep); assertEquals(listOf(2), dmg(1)); assertEquals(0, paid.players[0].blood)

        events.clear()
        val unpaid = fx(dice = diceFor(3), script = script(yes = listOf(false)))
        unpaid.gainBlood(unpaid.players[0], 1, "test"); unpaid.players[1].hp = 25
        val dropped = creature(unpaid, "fatcula", 3)
        assertFalse(dropped.keep); assertEquals(listOf(4), dmg(1))

        events.clear()
        val broke = fx(dice = diceFor(1)); broke.players[1].hp = 25
        assertFalse(creature(broke, "fatcula", 1).keep)
    }

    @Test
    fun shubNiggurathAddsADieWhenPaidAndPlaysAnotherCreatureOnTenPlus() {
        val plain = fx(dice = listOf(5))
        plain.players[1].acted = true
        creature(plain, "shub-niggurath", 2)
        assertEquals(listOf(1), dmg(1))

        events.clear()
        val paid = fx(dice = listOf(2, 3), script = script(yes = listOf(true)))
        paid.players[1].acted = true
        paid.gainBlood(paid.players[0], 1, "test")
        creature(paid, "shub-niggurath", 1)
        assertEquals(listOf(1), dmg(1), "two dice make 5, which is the 5-9 row")

        events.clear()
        val big = fx(dice = diceFor(3))
        big.players[1].acted = true
        hand(big, 0, "bob")
        val sc = creature(big, "shub-niggurath", 3)
        assertEquals(listOf(2), dmg(1))
        assertTrue(big.players[0].spell!!.cards.any { it.card.def.id == "bob" }, "Bob is played next")
        assertTrue(sc.keep)
    }

    @Test
    fun tentakillerStealsATreasureOnTenPlusAndKeepsOnAMidRoll() {
        for ((band, damage) in listOf(1 to 1, 2 to 2, 3 to 3)) {
            events.clear()
            val g = fx(dice = diceFor(band))
            g.giveCreature(g.players[1], "bob")
            g.giveTreasure(g.players[2], "kingor-crown")
            val sc = creature(g, "tentakiller", band)
            assertEquals(listOf(damage), dmg(2), "wizard 3 is the only foe with no Creature")
            assertTrue(dmg(1).isEmpty())
            assertEquals(band == 2, sc.keep)
            assertEquals(band == 3, g.players[0].has("kingor-crown"))
        }
    }

    @Test
    fun cockatwiceKeepsOnALowRollAndHitsHarderAbove() {
        for ((band, damage) in listOf(1 to 0, 2 to 2, 3 to 4)) {
            events.clear()
            val g = fx(dice = diceFor(band)); g.players[1].hp = 25
            val sc = creature(g, "cockatwice", band)
            assertEquals(if (damage == 0) emptyList() else listOf(damage), dmg(1))
            assertEquals(band == 1, sc.keep)
        }
    }

    @Test
    fun fartKnightResolvesEachDieOnItsOwnWhenPaid() {
        val g = fx(dice = listOf(2, 5), script = script(yes = listOf(true), players = listOf(1, 2)))
        g.gainBlood(g.players[0], 2, "test")
        val extra = g.giveToHand(g.players[0], "a-d-h-deezis")
        g.players[0].hand.remove(extra)
        g.players[0].spell!!.cards.add(SpellCard(extra, CardType.SOURCE, wild = false)) // a second Illusion card, so two dice
        val sc = creature(g, "fart-knight", 2)
        assertEquals(listOf(1), dmg(1), "the 2 does 1 damage to the first foe")
        assertEquals(listOf(2), dmg(2), "the 5 does 2 damage to the second")
        assertTrue(sc.keep, "a die of 1-4 earns KEEP")
        assertEquals(0, g.players[0].blood)
    }

    @Test
    fun fartKnightUnpaidUsesTheTableOnTheTotal() {
        for ((band, damage) in listOf(1 to 1, 2 to 2, 3 to 4)) {
            events.clear()
            val g = fx(dice = diceFor(band)); g.players[1].hp = 25
            val sc = creature(g, "fart-knight", band)
            assertEquals(listOf(damage), dmg(1), "band $band")
            assertEquals(band == 1, sc.keep)
        }
    }

    @Test
    fun shpiderMoonkeyHitsTheRightFoeAndGainsBloodOnTenPlus() {
        for ((band, damage) in listOf(1 to 1, 2 to 2, 3 to 4)) {
            events.clear()
            val g = fx(dice = diceFor(band))
            val sc = creature(g, "shpider-moonkey", band)
            assertEquals(listOf(damage), dmg(2)); assertTrue(dmg(1).isEmpty())
            assertEquals(if (band == 3) 1 else 0, g.players[0].blood)
            assertEquals(band == 3, sc.keep)
        }
    }

    @Test
    fun carnivorousClownsPunishesLowRollsAndKillsACreatureOnAMidRoll() {
        val low = fx(dice = diceFor(1))
        low.giveCreature(low.players[1], "bob")
        creature(low, "carnivorous-clowns", 1)
        assertEquals(listOf(1), dmg(0)); assertTrue(dmg(1).isEmpty())

        events.clear()
        val mid = fx(dice = diceFor(2), script = script(options = listOf(0)))
        mid.giveCreature(mid.players[1], "bob"); mid.giveCreature(mid.players[1], "cockatwice")
        creature(mid, "carnivorous-clowns", 2)
        assertEquals(listOf("Cockatwice"), mid.players[1].creatures.map { it.def.name }, "Bob was killed")
        assertEquals(listOf(3), dmg(1))

        events.clear()
        val high = fx(dice = diceFor(3))
        high.giveCreature(high.players[1], "bob")
        val sc = creature(high, "carnivorous-clowns", 3)
        assertEquals(listOf(4), dmg(1)); assertTrue(sc.keep)
    }

    @Test
    fun pimpedImpsHealsFoesOnALowRollAndSplashHealsOnTenPlus() {
        val low = fx(dice = diceFor(1))
        low.players[1].hp = 10; low.players[2].hp = 10
        creature(low, "pimped-imps", 1)
        assertEquals(listOf(1), healed(1)); assertEquals(listOf(1), healed(2))

        events.clear()
        val mid = fx(dice = diceFor(2))
        mid.players[2].hp = 10
        val kept = creature(mid, "pimped-imps", 2)
        assertEquals(listOf(3), dmg(2)); assertTrue(kept.keep)

        events.clear()
        val high = fx(dice = diceFor(3))
        high.players[2].hp = 10; high.players[1].hp = 12
        val big = creature(high, "pimped-imps", 3)
        assertEquals(listOf(5), dmg(2)); assertEquals(listOf(1), healed(1))
        assertFalse(big.keep, "KEEP is on the 5-9 row only")
    }

    @Test
    fun bitchWitchDrainsBloodOnMidAndHighRolls() {
        for ((band, drain) in listOf(1 to 0, 2 to 1, 3 to 2)) {
            events.clear()
            val g = fx(dice = diceFor(band))
            g.gainBlood(g.players[1], 5, "test")
            val sc = creature(g, "bitch-witch", band)
            assertEquals(listOf(band), dmg(1))
            assertEquals(5 - drain, g.players[1].blood)
            assertEquals(0, g.players[0].blood, "the Blood is lost, not stolen")
            assertEquals(band == 3, sc.keep)
        }
    }

    @Test
    fun jellySquareDrawsATreasureOnTenPlus() {
        for ((band, damage) in listOf(1 to 2, 2 to 3, 3 to 4)) {
            events.clear()
            val g = fx(dice = diceFor(band))
            g.stackTreasure("kingor-crown")
            val sc = creature(g, "jelly-square", band)
            assertEquals(listOf(damage), dmg(1))
            assertEquals(band == 3, g.players[0].has("kingor-crown"))
            assertEquals(band == 3, sc.keep)
        }
    }

    @Test
    fun handOfGodMakesTheTargetDestroyATreasureOnAMidRoll() {
        for ((band, damage) in listOf(1 to 1, 2 to 3, 3 to 7)) {
            events.clear()
            val g = fx(dice = diceFor(band))
            g.giveTreasure(g.players[1], "kingor-crown"); g.giveTreasure(g.players[1], "big-book-of-awesomeness")
            val sc = creature(g, "hand-of-god", band)
            assertEquals(listOf(damage), dmg(1))
            assertEquals(if (band == 2) 1 else 2, g.players[1].treasures.size)
            assertFalse(sc.keep, "Hand of God never keeps")
        }
    }

    @Test
    fun baaZerkerDoublesAgainstTheLastFoe() {
        val three = fx(dice = diceFor(3))
        three.giveStandeeForTest(three.players[1])
        val sc = creature(three, "baa-zerker", 3)
        assertEquals(listOf(3), dmg(2)); assertTrue(dmg(1).isEmpty()); assertTrue(sc.keep)

        events.clear()
        val duel = fx(dice = diceFor(3), players = 2)
        creature(duel, "baa-zerker", 3)
        assertEquals(listOf(6), dmg(1), "6 damage when the target is the last foe")

        events.clear()
        val low = fx(dice = diceFor(1)); low.giveStandeeForTest(low.players[2])
        creature(low, "baa-zerker", 1)
        assertEquals(listOf(1), dmg(1))
    }

    // ------------------------------------------------------------------ Treasures

    @Test
    fun tripleBarrelShotgunGivesBloodPerThreeMatchingGlyphs() {
        val defs = merged.allDefs.values.filter {
            it.glyph == Glyph.ELEMENTAL && (it.type == CardType.SOURCE || it.type == CardType.QUALITY || it.type == CardType.DELIVERY)
        }
        fun withCards(n: Int): Game {
            val g = fx()
            g.giveTreasure(g.players[0], "triple-barrel-shotgun")
            val spell = g.players[0].spell!!
            defs.take(n).forEach { spell.cards.add(SpellCard(g.giveToHand(g.players[0], it.id).also { c -> g.players[0].hand.remove(c) }, it.type, wild = false)) }
            runBlocking { g.endOfTurnTreasures(g.players[0]) }
            return g
        }
        assertEquals(0, withCards(2).players[0].blood)
        assertEquals(1, withCards(3).players[0].blood)
        assertEquals(2, withCards(6).players[0].blood)
    }

    @Test
    fun theICantBelieveThisShitHealsOnAFiveOrSix() {
        val five = fx(dice = listOf(5))
        five.giveTreasure(five.players[0], "the-i-cant-believe-this-shit")
        five.players[0].hp = 10
        runBlocking { five.drawDeadWizardQuietly(five.players[1]) }
        assertEquals(11, five.players[0].hp)

        val four = fx(dice = listOf(4))
        four.giveTreasure(four.players[0], "the-i-cant-believe-this-shit")
        four.players[0].hp = 10
        runBlocking { four.drawDeadWizardQuietly(four.players[1]) }
        assertEquals(10, four.players[0].hp)

        val own = fx(dice = listOf(6))
        own.giveTreasure(own.players[0], "the-i-cant-believe-this-shit")
        own.players[0].hp = 10
        runBlocking { own.drawDeadWizardQuietly(own.players[0]) }
        assertEquals(10, own.players[0].hp, "only foes drawing counts")
    }

    @Test
    fun gangBangAGongHitsOnePerFiveHpForTwoBlood() {
        val g = fx(script = script(players = listOf(1), defaultYes = true))
        g.giveTreasure(g.players[0], "gang-bang-a-gong")
        g.gainBlood(g.players[0], 2, "test")
        g.players[1].hp = 14
        runBlocking { g.startOfTurnTreasures(g.players[0]) }
        assertEquals(listOf(2), dmg(1))
        assertEquals(0, g.players[0].blood)

        events.clear()
        val broke = fx(script = script(players = listOf(1), defaultYes = true))
        broke.giveTreasure(broke.players[0], "gang-bang-a-gong")
        runBlocking { broke.startOfTurnTreasures(broke.players[0]) }
        assertTrue(dmg(1).isEmpty())
    }

    @Test
    fun drShrinkysCouchAddsASecondCardWhenWildMagicIsRevealed() {
        val random = Random(5)
        val game = Game(
            merged, List(2) { "Wizard ${it + 1}" }, ScriptedDecisions(mapOf(0 to listOf("wild-magic"), 1 to listOf("king-oberons")), random),
            ScriptedDice(listOf(6, 1)), random,
        ) { events.add(it) }
        runBlocking { game.beginGameForTest() }
        game.giveTreasure(game.players[0], "dr-shrinkys-couch")
        game.giveToHand(game.players[1], "king-oberons")
        val wild = game.giveToHand(game.players[0], "wild-magic")
        game.stackMainDeck("scorchias"); game.stackMainDeck("wyrmtors")
        runBlocking { game.playRoundForTest() }
        val couch = events.filterIsInstance<GameEvent.DeckRevealed>().filter { it.by == "Dr. Shrinky's Couch" }
        assertEquals(1, couch.size, "the second card was revealed by the Couch")
        assertTrue(events.any { it is GameEvent.WildMagicResolved && it.player == 0 })
        assertTrue(wild.def.type == CardType.WILD_MAGIC)
    }

    @Test
    fun doubleHeadedDingDongerReversesLeftAndRightForOneCard() {
        val flip = fx(script = script(yes = listOf(true)))
        flip.giveTreasure(flip.players[0], "double-headed-ding-donger")
        cast(flip, "mr-nukion-fussions") // 2 damage to the foe on your right
        assertEquals(listOf(2), dmg(1), "reversed, it hits the left foe")
        assertTrue(dmg(2).isEmpty())

        events.clear()
        val keep = fx(script = script(yes = listOf(false)))
        keep.giveTreasure(keep.players[0], "double-headed-ding-donger")
        cast(keep, "mr-nukion-fussions")
        assertEquals(listOf(2), dmg(2))

        events.clear()
        val none = fx()
        cast(none, "mr-nukion-fussions")
        assertEquals(listOf(2), dmg(2), "without the Treasure nothing is asked")
    }

    // ------------------------------------------------------------------ Dead Wizards

    @Test
    fun petFromBeyondStartsTheGameWithACreatureInPlay() {
        val g = fx(before = { it.giveDeadWizard(it.players[0], "pet-from-beyond") })
        assertEquals(1, g.players[0].creatures.size)
        assertTrue(g.players[0].creatures[0].def.creature)
        assertTrue(events.any { it is GameEvent.CreatureKept && it.player == 0 })
        assertTrue(g.players[0].deadWizardCards.isEmpty(), "the card is used up")
    }

    @Test
    fun houndsFromHellAddsOneDieToTheNextCreaturePowerRoll() {
        val g = fx(dice = listOf(3, 2), before = { it.giveDeadWizard(it.players[0], "hounds-from-hell") })
        assertEquals(1, g.players[0].houndsDice)
        g.players[1].hp = 25
        creature(g, "cockatwice", 2)
        // Two dice (3 + 2 = 5) land in the 5-9 row: 2 damage. One die of 3 would have been a 1-4 (no damage).
        assertEquals(listOf(2), dmg(1))
        assertEquals(0, g.players[0].houndsDice, "used up")
    }

    @Test
    fun zapORamaAddsTheTopCardOfTheDeckToTheFirstSpell() {
        val g = fx()
        g.players[0].zapThisGame = 1
        g.stackMainDeck("scorchias")
        g.applyZapORama(g.players[0])
        assertEquals(listOf("scorchias"), g.players[0].spell!!.cards.map { it.card.def.id })
        assertEquals(0, g.players[0].zapThisGame)
    }

    @Test
    fun zapORamaPutsWildMagicInYourHandAndAddsTheNextCard() {
        val g = fx()
        g.players[0].zapThisGame = 1
        g.stackMainDeck("scorchias")
        g.stackMainDeck("wild-magic")
        g.applyZapORama(g.players[0])
        assertEquals(listOf("scorchias"), g.players[0].spell!!.cards.map { it.card.def.id })
        assertTrue(g.players[0].hand.any { it.def.type == CardType.WILD_MAGIC })
    }

    @Test
    fun zapORamaCountsEachCardHeld() {
        val g = fx(before = { it.giveDeadWizard(it.players[0], "zap-o-rama"); it.giveDeadWizard(it.players[0], "zap-o-rama") })
        assertEquals(2, g.players[0].zapThisGame)
        g.stackMainDeck("wyrmtors"); g.stackMainDeck("scorchias")
        g.applyZapORama(g.players[0])
        assertEquals(2, g.players[0].spell!!.cards.size)
    }

    @Test
    fun lingeringCurseCostsTheLastWinnerTwoCardsInTheOpeningHand() {
        val random = Random(9)
        lateinit var game: Game
        var sizes = emptyList<Int>()
        game = Game(merged, List(3) { "Wizard ${it + 1}" }, RandomDecisions(random), RandomDice(random), random) {
            if (it is GameEvent.HandsDealt && sizes.isEmpty()) sizes = game.players.map { p -> p.hand.size }
        }
        game.giveDeadWizard(game.players[0], "lingering-curse")
        game.setLastGameWinnerForTest(game.players[1])
        runBlocking { game.beginGameForTest() }
        assertEquals(2, game.players[1].startHandPenalty)
        runBlocking { game.playRoundForTest() }
        assertEquals(listOf(8, 6, 8), sizes, "the last winner was dealt two fewer cards")
    }
}
