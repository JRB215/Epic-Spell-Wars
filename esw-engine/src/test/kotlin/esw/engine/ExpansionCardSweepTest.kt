package esw.engine

import esw.model.CardCatalog
import esw.model.CardType
import esw.model.GameMode
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Plays every Expansion 1 card on its own in a three-wizard game, many times over with different dice, both saying yes to
 * every "Pay Blood" question and no. Nothing may crash, HP and Blood must stay in range, and every Treasure and Dead
 * Wizard card must survive being held for a few rounds.
 */
class ExpansionCardSweepTest {
    private val merged = CardCatalog.load(GameMode.MERGED)
    private val expansionOnly = CardCatalog.load(GameMode.EXPANSION1)

    private fun playOne(
        catalog: CardCatalog,
        seed: Int,
        yes: Boolean,
        spells: Map<Int, List<String>>,
        prepare: (Game) -> Unit = {},
        rounds: Int = 1,
    ): Game {
        val random = Random(seed)
        val game = Game(catalog, List(3) { "Wizard ${it + 1}" }, ScriptedDecisions(spells, random, yes), ScriptedDice(emptyList(), random), random)
        runBlocking { game.beginGameForTest() }
        for ((player, ids) in spells) ids.forEach { game.giveToHand(game.players[player], it) }
        game.players[0].blood = 25
        game.players[1].blood = 3
        game.giveCreature(game.players[1], "bob")
        game.giveCreature(game.players[2], "cockatwice")
        prepare(game)
        repeat(rounds) {
            val outcome = runBlocking { game.playRoundForTest() }
            if (outcome.ended) return game
        }
        for (p in game.players) {
            assertTrue(p.hp in 0..game.maxHp, "${p.name} has ${p.hp} HP")
            assertTrue(p.blood in 0..25, "${p.name} has ${p.blood} Blood")
        }
        return game
    }

    private val spellCards = expansionOnly.allDefs.values.filter {
        it.type == CardType.SOURCE || it.type == CardType.QUALITY || it.type == CardType.DELIVERY
    }

    @Test
    fun everyExpansionSpellCardPlaysWithoutProblemsWhoeverIsHoldingWhat() {
        assertEquals(60, spellCards.size)
        for (card in spellCards) {
            for (yes in listOf(true, false)) {
                for (seed in 1..12) {
                    try {
                        playOne(merged, seed, yes, mapOf(0 to listOf(card.id), 1 to listOf("king-oberons"), 2 to listOf("chicken")))
                    } catch (e: Throwable) {
                        throw AssertionError("${card.id} (yes=$yes, seed=$seed): ${e.message}", e)
                    }
                }
            }
        }
    }

    @Test
    fun everyExpansionSpellCardPlaysWhenItIsCastOnTheStandeeHolder() {
        for (card in spellCards) {
            for (seed in 1..6) {
                try {
                    playOne(merged, seed, true, mapOf(0 to listOf(card.id), 1 to listOf("king-oberons"), 2 to listOf("chicken")),
                        prepare = { it.giveStandeeForTest(it.players[0]) })
                } catch (e: Throwable) {
                    throw AssertionError("${card.id} with the Standee (seed=$seed): ${e.message}", e)
                }
            }
        }
    }

    @Test
    fun everyExpansionTreasureCanBeHeldAcrossSeveralRounds() {
        val treasures = expansionOnly.allDefs.values.filter { it.type == CardType.TREASURE }
        assertEquals(25, treasures.size)
        for (t in treasures) {
            for (yes in listOf(true, false)) {
                for (seed in 1..8) {
                    try {
                        playOne(
                            merged, seed, yes,
                            mapOf(0 to listOf("wyrmtors"), 1 to listOf("old-scratchs", "chicken"), 2 to listOf("scorchias")),
                            prepare = { g ->
                                g.giveTreasure(g.players[0], t.id)
                                g.giveTreasure(g.players[2], t.id)
                            },
                            rounds = 4,
                        )
                    } catch (e: Throwable) {
                        throw AssertionError("treasure ${t.id} (yes=$yes, seed=$seed): ${e.message}", e)
                    }
                }
            }
        }
    }

    @Test
    fun everyExpansionDeadWizardCardWorksWhenHeld() {
        val dead = expansionOnly.allDefs.values.filter { it.type == CardType.DEAD_WIZARD }
        assertEquals(8, dead.size)
        for (d in dead) {
            for (seed in 1..8) {
                try {
                    val random = Random(seed)
                    val game = Game(expansionOnly, List(3) { "Wizard ${it + 1}" }, RandomDecisions(random), RandomDice(random), random)
                    runBlocking {
                        game.giveDeadWizard(game.players[0], d.id)
                        game.beginGameForTest()
                        repeat(3) { if (!game.playRoundForTest().ended) Unit }
                    }
                } catch (e: Throwable) {
                    throw AssertionError("dead wizard ${d.id} (seed=$seed): ${e.message}", e)
                }
            }
        }
    }
}
