package esw.engine

import esw.model.CardCatalog
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Exact-situation tests: each sets up hands and dice, plays one round and checks what the rules say should happen. */
class RulesTest {
    private val catalog = CardCatalog.loadBase()
    private val events = mutableListOf<GameEvent>()

    /** Sets up a game where each player has been handed the cards they are going to play. */
    private fun setup(
        spells: Map<Int, List<String>>,
        dice: List<Int> = emptyList(),
        treasures: Map<Int, List<String>> = emptyMap(),
    ): Game {
        val game = Game(
            catalog, List(spells.size) { "Wizard ${it + 1}" }, ScriptedDecisions(spells), ScriptedDice(dice), Random(3),
        ) { events.add(it) }
        runBlocking { game.beginGameForTest() }
        for ((player, ids) in spells) ids.forEach { game.giveToHand(game.players[player], it) }
        for ((player, ids) in treasures) ids.forEach { game.giveTreasure(game.players[player], it) }
        return game
    }

    private fun round(game: Game) = runBlocking { game.playRoundForTest() }

    private fun turnOrder() = events.filterIsInstance<GameEvent.TurnStarted>().map { it.player }

    @Test
    fun fewerComponentsGoFirstThenHigherInitiative() {
        // p0 plays one Delivery (Initiative 14), p1 one Delivery (Initiative 2), p2 two cards.
        val game = setup(mapOf(0 to listOf("fist-o-nature"), 1 to listOf("gore-nado"), 2 to listOf("king-oberons", "lightning-bolt")))
        round(game)
        assertEquals(listOf(0, 1, 2), turnOrder())
    }

    @Test
    fun higherInitiativeBeatsLowerWithTheSameNumberOfComponents() {
        val game = setup(mapOf(0 to listOf("gore-nado"), 1 to listOf("fist-o-nature")))
        round(game)
        assertEquals(listOf(1, 0), turnOrder())
    }

    @Test
    fun impatientActsFirstEvenWithMoreComponents() {
        val game = setup(mapOf(0 to listOf("king-oberons", "impatient", "fist-o-nature"), 1 to listOf("pam-and-hecubas")))
        round(game)
        assertEquals(listOf(0, 1), turnOrder())
    }

    @Test
    fun tiedWizardsRollADieAndTheHighRollerGoesFirst() {
        val game = setup(mapOf(0 to listOf("king-oberons"), 1 to listOf("pam-and-hecubas")), dice = listOf(2, 5))
        round(game)
        assertEquals(listOf(1, 0), turnOrder())
    }

    @Test
    fun powerRollBandsPickTheDamage() {
        // Mercy-Killing: weakest foe takes 2 / 3 / 4. One Dark card, so one die.
        val low = setup(mapOf(0 to listOf("mercy-killing"), 1 to listOf("pam-and-hecubas")), dice = listOf(3))
        round(low)
        assertEquals(18, low.players[1].hp)
        events.clear()
        val mid = setup(mapOf(0 to listOf("mercy-killing"), 1 to listOf("pam-and-hecubas")), dice = listOf(6))
        round(mid)
        assertEquals(17, mid.players[1].hp)
    }

    @Test
    fun bigBookOfAwesomenessAddsTwoToPowerRolls() {
        // A roll of 3 would be 2 damage; with +2 it is 5, which is the middle band (3 damage).
        val game = setup(
            mapOf(0 to listOf("mercy-killing"), 1 to listOf("pam-and-hecubas")), dice = listOf(3),
            treasures = mapOf(0 to listOf("big-book-of-awesomeness")),
        )
        round(game)
        assertEquals(17, game.players[1].hp)
    }

    @Test
    fun extraDiceAreRolledForEachCardOfTheSameGlyph() {
        // Mercy-Killing and Maggoty are both Dark, and Demon Shoes counts as a Dark card: 3 dice, all 6s = 18, top band.
        val game = setup(
            mapOf(0 to listOf("maggoty", "mercy-killing"), 1 to listOf("pam-and-hecubas")), dice = listOf(6, 6, 6),
            treasures = mapOf(0 to listOf("demon-shoes")),
        )
        round(game)
        val roll = events.filterIsInstance<GameEvent.DiceRolled>().first { it.reason == "Power Roll" }
        assertEquals(listOf(6, 6, 6), roll.dice)
    }

    @Test
    fun ladyLucksPantiesAddsTwoToASingleDie() {
        // Old Scratch's: roll 1-3 and you suffer that much. A 1 becomes 3, so 3 damage instead of 1.
        val game = setup(
            mapOf(0 to listOf("old-scratchs"), 1 to listOf("pam-and-hecubas")), dice = listOf(6, 1, 1),
            treasures = mapOf(0 to listOf("lady-lucks-panties")),
        )
        round(game)
        assertEquals(17, game.players[0].hp)
    }

    @Test
    fun plinkCannonDealsOneDamageAtTheStartOfYourTurn() {
        val game = setup(mapOf(0 to listOf("pam-and-hecubas"), 1 to listOf("pam-and-hecubas")), dice = listOf(6, 1), treasures = mapOf(0 to listOf("plink-cannon")))
        round(game)
        assertEquals(19, game.players[1].hp)
    }

    @Test
    fun lastWizardStandingWinsTheGameAndGetsAToken() {
        val game = setup(mapOf(0 to listOf("scorchias"), 1 to listOf("king-oberons")), dice = listOf(6, 1))
        game.players[1].hp = 3
        val outcome = round(game)
        assertTrue(outcome.ended)
        assertEquals(0, outcome.winner)
        assertEquals(1, game.players[0].tokens)
        assertFalse(game.players[1].alive)
        assertTrue(events.any { it is GameEvent.PlayerDied && it.player == 1 })
    }

    @Test
    fun wildMagicIsReplacedByACardOfTheSlotItFills() {
        val game = setup(mapOf(0 to listOf("wild-magic"), 1 to listOf("pam-and-hecubas")), dice = listOf(6, 1))
        round(game)
        val resolved = events.filterIsInstance<GameEvent.WildMagicResolved>().single()
        assertEquals("SOURCE", resolved.slot)
        val sources = catalog.source.map { it.def.name }
        assertTrue(resolved.replacement in sources, "replacement was ${resolved.replacement}")
    }

    @Test
    fun deadWizardCardsGiveTheirBonusAtTheStartOfTheNextGame() {
        val game = setup(mapOf(0 to listOf("pam-and-hecubas"), 1 to listOf("pam-and-hecubas")))
        game.giveDeadWizard(game.players[0], "the-big-house")
        game.giveDeadWizard(game.players[0], "afterlife-artifact")
        game.giveDeadWizard(game.players[1], "giant-space-kingdom")
        runBlocking { game.beginGameForTest() }
        assertEquals(23, game.players[0].hp)
        assertEquals(1, game.players[0].treasures.size)
        assertEquals(22, game.players[1].hp)
        assertTrue(game.players[0].deadWizardCards.isEmpty())
    }

    @Test
    fun hitPointsNeverGoAboveTheMaximum() {
        val game = setup(mapOf(0 to listOf("king-oberons"), 1 to listOf("pam-and-hecubas")), dice = listOf(6, 1))
        game.giveDeadWizard(game.players[0], "the-big-house")
        game.giveDeadWizard(game.players[0], "the-big-house")
        runBlocking { game.beginGameForTest() }
        assertEquals(25, game.players[0].hp)
    }

    @Test
    fun treasureBonusesStackWithTreasuresThatCountAsGlyphs() {
        // Snakedance (Primal) at 10+ deals 2 per Primal card; Wood Lord's Clogs counts as one more.
        val game = setup(
            mapOf(0 to listOf("snakedance", "king-oberons"), 1 to listOf("pam-and-hecubas")), dice = listOf(6, 6, 6),
            treasures = mapOf(0 to listOf("wood-lords-clogs")),
        )
        round(game)
        val roll = events.filterIsInstance<GameEvent.DiceRolled>().first { it.reason == "Power Roll" }
        assertEquals(3, roll.dice.size)
        assertEquals(18, roll.total)
        // 3 Primal cards in all: 2 x 3 = 6 damage to the foe on the left (and right, the same player).
        // Wizard 2 plays a single card and so goes first, then wizard 1 casts.
        assertEquals(14, game.players[1].hp)
    }
}
