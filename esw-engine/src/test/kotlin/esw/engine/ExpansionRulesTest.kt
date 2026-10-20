package esw.engine

import esw.model.CardCatalog
import esw.model.GameMode
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tests for the Expansion 1 rules, one situation at a time. */
class ExpansionRulesTest {
    // Merged play has every base card as well as the expansion's, so tests can use simple base cards for damage.
    private val merged = CardCatalog.load(GameMode.MERGED)
    private val events = mutableListOf<GameEvent>()

    private fun setup(
        spells: Map<Int, List<String>>,
        dice: List<Int> = emptyList(),
        yes: Boolean = false,
        catalog: CardCatalog = merged,
    ): Game {
        val game = Game(
            catalog, List(spells.size) { "Wizard ${it + 1}" }, ScriptedDecisions(spells, yes = yes), ScriptedDice(dice), Random(3),
        ) { events.add(it) }
        runBlocking { game.beginGameForTest() }
        for ((player, ids) in spells) ids.forEach { game.giveToHand(game.players[player], it) }
        return game
    }

    private fun round(game: Game) = runBlocking { game.playRoundForTest() }

    @Test
    fun killingAFoeGivesThreeBloodAndTakesTheStandeeFromThem() {
        val game = setup(mapOf(0 to listOf("scorchias"), 1 to listOf("king-oberons")), dice = listOf(6, 1))
        game.giveStandeeForTest(game.players[1])
        game.players[1].hp = 3
        val outcome = round(game)
        assertTrue(outcome.ended)
        assertEquals(0, outcome.winner)
        // 3 Blood for the kill, plus 1 because the Standee ends the round in the winner's hands.
        assertEquals(4, game.players[0].blood)
        // The winner took the Standee from the wizard they slew (it goes back to the middle when the game ends).
        assertTrue(events.any { it is GameEvent.StandeeMoved && it.player == 0 })
        assertNull(game.standeeHolder)
    }

    @Test
    fun theStandeeHolderGainsOneBloodWhenARoundEnds() {
        val game = setup(mapOf(0 to listOf("king-oberons"), 1 to listOf("king-oberons")), dice = listOf(6, 1))
        game.giveStandeeForTest(game.players[1])
        round(game)
        assertEquals(1, game.players[1].blood)
        assertEquals(0, game.players[0].blood)
    }

    @Test
    fun theStandeeStartsEachGameInTheMiddle() {
        val game = setup(mapOf(0 to listOf("king-oberons"), 1 to listOf("king-oberons")))
        assertNull(game.standeeHolder)
    }

    @Test
    fun bloodNeverGoesAboveTwentyFive() {
        val game = setup(mapOf(0 to listOf("king-oberons"), 1 to listOf("king-oberons")))
        game.gainBlood(game.players[0], 20, "test")
        game.gainBlood(game.players[0], 20, "test")
        assertEquals(25, game.players[0].blood)
    }

    @Test
    fun stealingBloodOnlyTakesWhatTheVictimHas() {
        val game = setup(mapOf(0 to listOf("king-oberons"), 1 to listOf("king-oberons")))
        game.gainBlood(game.players[1], 2, "test")
        game.stealBlood(game.players[0], game.players[1], 5)
        assertEquals(2, game.players[0].blood)
        assertEquals(0, game.players[1].blood)
    }

    @Test
    fun theBaseGameHasNoBlood() {
        val base = CardCatalog.loadBase()
        val game = setup(mapOf(0 to listOf("scorchias"), 1 to listOf("king-oberons")), dice = listOf(6, 1), catalog = base)
        game.players[1].hp = 3
        round(game)
        assertEquals(0, game.players[0].blood)
        assertFalse(game.expansion)
    }

    @Test
    fun aKeptCreatureCanBlockOneHitFromAFoe() {
        val game = setup(mapOf(0 to listOf("scorchias"), 1 to listOf("king-oberons")), dice = listOf(6, 1), yes = true)
        game.giveCreature(game.players[1], "bob")
        round(game)
        // The Creature soaked up the 3 damage, so wizard 2 stays at 20 and then heals 2 with King Oberon's.
        assertEquals(22, game.players[1].hp)
        assertTrue(game.players[1].creatures.isEmpty(), "and was discarded")
        assertTrue(events.any { it is GameEvent.CreatureBlocked && it.player == 1 && it.amount == 3 })
    }

    @Test
    fun aWizardCanChooseNotToBlock() {
        val game = setup(mapOf(0 to listOf("scorchias"), 1 to listOf("king-oberons")), dice = listOf(6, 1), yes = false)
        game.giveCreature(game.players[1], "bob")
        round(game)
        // 20 - 3 damage + 2 healed by King Oberon's.
        assertEquals(19, game.players[1].hp)
        assertTrue(events.none { it is GameEvent.CreatureBlocked })
    }

    @Test
    fun aCreatureCannotAbsorbDamageYouDoToYourself() {
        // Old Scratch's: a roll of 1-3 hurts the caster. Even with a Creature in play it must hurt.
        val game = setup(mapOf(0 to listOf("old-scratchs"), 1 to listOf("king-oberons")), dice = listOf(6, 1, 2), yes = true)
        game.giveCreature(game.players[0], "bob")
        round(game)
        assertEquals(18, game.players[0].hp)
        assertTrue(events.none { it is GameEvent.CreatureBlocked })
    }

    @Test
    fun aCreatureIsDiscardedWhenItsOwnerDies() {
        val game = setup(mapOf(0 to listOf("scorchias"), 1 to listOf("king-oberons")), dice = listOf(6, 1), yes = false)
        game.giveCreature(game.players[1], "bob")
        game.players[1].hp = 3
        round(game)
        assertFalse(game.players[1].alive)
        assertTrue(game.players[1].creatures.isEmpty())
        assertTrue(events.any { it is GameEvent.CreatureLost && it.player == 1 })
    }

    @Test
    fun gonzoShroompusssReactionResetsHpToOneInsteadOfDying() {
        val game = setup(mapOf(0 to listOf("scorchias"), 1 to listOf("gonzo-shroompusss")), dice = listOf(6, 1))
        game.players[1].hp = 3
        round(game)
        assertTrue(game.players[1].alive, "the Reaction saved them")
        assertEquals(1, game.players[1].hp)
        assertTrue(events.any { it is GameEvent.ReactionTriggered && it.card == "Gonzo Shroompuss's" })
    }

    @Test
    fun aReactionInHandDoesNothing() {
        // Wizard 2 holds Gonzo in hand but plays a different Source, so there is no Reaction in the spell.
        val game = setup(mapOf(0 to listOf("scorchias"), 1 to listOf("king-oberons")), dice = listOf(6, 1))
        game.giveToHand(game.players[1], "gonzo-shroompusss")
        game.players[1].hp = 3
        val outcome = round(game)
        assertTrue(outcome.ended)
        assertFalse(game.players[1].alive)
    }

    @Test
    fun sadisticGivesBloodWhenACreatureBlocksIt() {
        val game = setup(mapOf(0 to listOf("sadistic"), 1 to listOf("king-oberons")), dice = listOf(6, 1), yes = true)
        game.giveCreature(game.players[1], "bob")
        round(game)
        assertTrue(events.any { it is GameEvent.CreatureBlocked && it.player == 1 })
        assertTrue(events.any { it is GameEvent.BloodChanged && it.player == 0 && it.change == 1 && it.reason == "Sadistic" })
    }

    @Test
    fun grizGlowballsCanPayBloodToHitEveryFoe() {
        val game = setup(
            mapOf(0 to listOf("griz-glowballs"), 1 to listOf("king-oberons"), 2 to listOf("king-oberons")),
            dice = listOf(6, 1, 1), yes = true,
        )
        game.gainBlood(game.players[0], 2, "test")
        round(game)
        val hits = events.filterIsInstance<GameEvent.DamageDealt>().filter { it.source == 0 && it.amount == 3 }
        assertEquals(setOf(1, 2), hits.map { it.target }.toSet())
        assertEquals(0, game.players[0].blood)
    }

    @Test
    fun aCreatureThatRollsItsKeepResultStaysInPlay() {
        // Cockatwice keeps on a Power Roll of 1-4; its one Primal die shows a 2.
        val game = setup(mapOf(0 to listOf("cockatwice"), 1 to listOf("king-oberons")), dice = listOf(2))
        round(game)
        assertEquals(listOf("Cockatwice"), game.players[0].creatures.map { it.def.name })
        assertTrue(events.any { it is GameEvent.CreatureKept && it.player == 0 })
    }

    @Test
    fun wizSleeveStevesLetsACreatureInTheSpellTakeTheFatalDamage() {
        val game = setup(mapOf(0 to listOf("scorchias"), 1 to listOf("wiz-sleeve-steves", "bob")), dice = listOf(6, 1))
        game.players[1].hp = 3
        val outcome = round(game)
        assertFalse(outcome.ended, "the Creature took the hit")
        assertTrue(game.players[1].alive)
        assertEquals(3, game.players[1].hp)
        assertTrue(events.any { it is GameEvent.CreatureLost && it.player == 1 && it.creature == "Bob" })
    }

    @Test
    fun queenOfTheDeadsReactionDrawsTwoExtraDeadWizardCards() {
        val game = setup(mapOf(0 to listOf("scorchias"), 1 to listOf("queen-of-the-deads")), dice = listOf(6, 1))
        game.players[1].hp = 3
        round(game)
        assertFalse(game.players[1].alive)
        assertEquals(3, game.players[1].deadWizardCards.size, "one for dying and two from the Reaction")
    }

    @Test
    fun theSlyRollersReactionDrawsATreasureForTheNextGame() {
        val game = setup(mapOf(0 to listOf("scorchias"), 1 to listOf("the-sly-rollers")), dice = listOf(6, 1))
        game.players[1].hp = 3
        round(game)
        assertEquals(1, game.players[1].treasureNextGame)
    }

    @Test
    fun nukionFussionsReactionHurtsTheKiller() {
        // Wizard 2 dies to wizard 1's Scorchia's; the Reaction deals one die (a 4) to wizard 1.
        val game = setup(mapOf(0 to listOf("scorchias"), 1 to listOf("mr-nukion-fussions")), dice = listOf(6, 1, 4))
        game.players[1].hp = 3
        val outcome = round(game)
        assertTrue(outcome.ended)
        assertEquals(16, game.players[0].hp)
    }
}
