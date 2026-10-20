package esw.engine

import esw.model.CardCatalog
import esw.model.GameMode
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What individual Expansion 1 cards do, one situation at a time. Dice are scripted so the outcome is known. */
class ExpansionCardEffectsTest {
    private val merged = CardCatalog.load(GameMode.MERGED)
    private val events = mutableListOf<GameEvent>()

    // With two wizards each playing one card, the turn order is decided by a roll-off: [6, 1] makes wizard 1 go first.
    private fun setup(
        spells: Map<Int, List<String>>,
        dice: List<Int> = listOf(6, 1),
        yes: Boolean = false,
        players: Int = spells.size,
    ): Game {
        val game = Game(
            merged, List(players) { "Wizard ${it + 1}" }, ScriptedDecisions(spells, yes = yes), ScriptedDice(dice), Random(3),
        ) { events.add(it) }
        runBlocking { game.beginGameForTest() }
        for ((player, ids) in spells) ids.forEach { game.giveToHand(game.players[player], it) }
        return game
    }

    private fun round(game: Game) = runBlocking { game.playRoundForTest() }

    private fun damageTo(target: Int) = events.filterIsInstance<GameEvent.DamageDealt>().filter { it.target == target }.map { it.amount }

    private val two = mapOf(0 to listOf("king-oberons"), 1 to listOf("king-oberons"))

    @Test
    fun gonzoShroompussHealsByADie() {
        val game = setup(mapOf(0 to listOf("gonzo-shroompusss"), 1 to listOf("king-oberons")), dice = listOf(6, 1, 4))
        game.players[0].hp = 10
        round(game)
        assertEquals(listOf(4), events.filterIsInstance<GameEvent.Healed>().filter { it.player == 0 }.map { it.amount })
    }

    @Test
    fun harvestingWithNoDeadWizardsTakesTheStandeeAndGivesTwoBlood() {
        val game = setup(mapOf(0 to listOf("harvesting"), 1 to listOf("king-oberons")))
        round(game)
        assertTrue(events.any { it is GameEvent.StandeeMoved && it.player == 0 })
        // 2 Blood from the card and 1 at the end of the round for holding the Standee.
        assertEquals(3, game.players[0].blood)
    }

    @Test
    fun nonchalantGivesBloodAndHealsTheWeakestFoeOnALowRoll() {
        val game = setup(mapOf(0 to listOf("nonchalant"), 1 to listOf("king-oberons")), dice = listOf(6, 1, 3))
        game.players[1].hp = 10
        round(game)
        assertEquals(3, game.players[0].blood)
        assertTrue(events.any { it is GameEvent.Healed && it.player == 1 && it.amount == 3 })
    }

    @Test
    fun theSlyRollersOnAFiveTakesTheStandeeAndDrawsATreasure() {
        val game = setup(mapOf(0 to listOf("the-sly-rollers"), 1 to listOf("king-oberons")), dice = listOf(6, 1, 5))
        round(game)
        assertTrue(events.any { it is GameEvent.StandeeMoved && it.player == 0 })
        assertTrue(events.any { it is GameEvent.TreasureGained && it.player == 0 })
    }

    @Test
    fun evenStevenDealsTheLowerDie() {
        val game = setup(mapOf(0 to listOf("even-steven"), 1 to listOf("king-oberons")), dice = listOf(6, 1, 5, 2))
        round(game)
        assertTrue(2 in damageTo(1), "the lower die, 2, hits the foe: ${damageTo(1)}")
    }

    @Test
    fun motherForkingGivesTheStandeeToAFoeAndHurtsThem() {
        val game = setup(mapOf(0 to listOf("mother-forking"), 1 to listOf("king-oberons")))
        round(game)
        assertTrue(events.any { it is GameEvent.StandeeMoved && it.player == 1 })
        assertTrue(3 in damageTo(1))
    }

    @Test
    fun inflameratingHitsEveryoneWhenNobodyHasTheStandee() {
        val game = setup(mapOf(0 to listOf("inflamerating"), 1 to listOf("king-oberons")))
        round(game)
        assertTrue(4 in damageTo(0) && 4 in damageTo(1))
    }

    @Test
    fun inflameratingHitsOnlyTheStandeeHolder() {
        val game = setup(mapOf(0 to listOf("inflamerating"), 1 to listOf("king-oberons")))
        game.giveStandeeForTest(game.players[1])
        round(game)
        assertTrue(4 in damageTo(1))
        assertTrue(damageTo(0).isEmpty(), "the caster is untouched: ${damageTo(0)}")
    }

    @Test
    fun burninBangersPaidDoesBothHalves() {
        val game = setup(mapOf(0 to listOf("burnin-bangers"), 1 to listOf("king-oberons")), yes = true)
        game.gainBlood(game.players[0], 3, "test")
        round(game)
        assertEquals(listOf(1, 3), damageTo(1))
        assertTrue(events.any { it is GameEvent.StandeeMoved && it.player == 0 })
        // The 3 Blood were spent, then 1 came back for holding the Standee.
        assertEquals(1, game.players[0].blood)
    }

    @Test
    fun lowBallingGivesAPoorFoeATreasureThenHurtsThem() {
        val game = setup(mapOf(0 to listOf("low-balling"), 1 to listOf("king-oberons")))
        round(game)
        assertTrue(events.any { it is GameEvent.TreasureGained && it.player == 1 })
        assertTrue(2 in damageTo(1))
    }

    @Test
    fun greedyGivesTwoTreasuresToYouAndOneToAFoeWhoHasNotActed() {
        val game = setup(mapOf(0 to listOf("greedy"), 1 to listOf("king-oberons")))
        round(game)
        val gained = events.filterIsInstance<GameEvent.TreasureGained>()
        assertEquals(2, gained.count { it.player == 0 })
        assertEquals(1, gained.count { it.player == 1 })
    }

    @Test
    fun cloneliserWithTheStandeeDoesTheEvenHalfThenTheOddHalf() {
        val game = setup(mapOf(0 to listOf("clonelising"), 1 to listOf("king-oberons")))
        game.giveStandeeForTest(game.players[0])
        round(game)
        // 20 HP is even: 2 damage leaves 18, still even, so the odd half finds nobody.
        assertEquals(listOf(2), damageTo(1))
    }

    @Test
    fun autoEroticTradesDamageEqualToBloodWhenPaid() {
        val game = setup(mapOf(0 to listOf("auto-erotic"), 1 to listOf("king-oberons")), yes = true)
        game.gainBlood(game.players[0], 2, "test")
        game.gainBlood(game.players[1], 3, "test")
        round(game)
        // 1 Blood is paid first, so 1 vs 3: the foe takes 1 and 1, the caster takes 3.
        assertTrue(3 in damageTo(0), "caster takes the foe's Blood: ${damageTo(0)}")
        assertEquals(listOf(1, 1), damageTo(1))
    }

    @Test
    fun codPieceOfCthulhuStealsBloodAtTheStartOfTheTurn() {
        val game = setup(two)
        game.giveTreasure(game.players[0], "cod-piece-of-cthulhu")
        game.gainBlood(game.players[1], 3, "test")
        round(game)
        assertTrue(events.any { it is GameEvent.BloodChanged && it.player == 0 && it.change == 1 && it.reason.startsWith("stolen from") })
    }

    @Test
    fun theGoldenHandcuffsDrawATreasureForTheLastToAct() {
        val game = setup(two, dice = listOf(1, 6)) // wizard 2 goes first, so wizard 1 is last
        game.giveTreasure(game.players[0], "the-golden-handcuffs")
        round(game)
        assertTrue(events.any { it is GameEvent.TreasureGained && it.player == 0 })
    }

    @Test
    fun snerdsRageHealsTwoForThreeBlood() {
        val game = setup(two, yes = true)
        game.giveTreasure(game.players[0], "snerds-rage")
        game.gainBlood(game.players[0], 3, "test")
        game.players[0].hp = 10
        round(game)
        assertTrue(events.any { it is GameEvent.Healed && it.player == 0 && it.amount == 2 })
        assertEquals(0, game.players[0].blood)
    }

    @Test
    fun grabbelSnaksTapeWormWinsATieForOneBlood() {
        val game = setup(two, dice = listOf(1, 6), yes = true) // the dice would put wizard 2 first
        game.giveTreasure(game.players[0], "grabbel-snaks-6-headed-tape-worm")
        game.gainBlood(game.players[0], 1, "test")
        round(game)
        assertEquals(0, events.filterIsInstance<GameEvent.TurnStarted>().first().player)
    }

    @Test
    fun fishHandsSwapsForAFoesTreasureWhenYouActFirst() {
        val game = setup(two)
        game.giveTreasure(game.players[0], "fish-hands")
        game.giveTreasure(game.players[1], "thinking-cap")
        round(game)
        assertTrue(game.players[0].has("thinking-cap"))
        assertTrue(game.players[1].has("fish-hands"))
    }

    @Test
    fun theSausageMastersSteedGivesBloodOnAHighRoll() {
        // Wizard 1 rolls Chicken's single Primal die... a big total needs several dice, so use the bonus die instead.
        val game = setup(mapOf(0 to listOf("chicken"), 1 to listOf("king-oberons")), dice = listOf(6, 1, 6))
        game.giveTreasure(game.players[0], "the-sausage-masters-steed")
        game.players[0].bonusDie = 8
        round(game)
        // 6 + 8 = 14, which is 13 or more.
        assertEquals(1, game.players[0].blood)
    }

    @Test
    fun deadmansSwitchPointsASingleTargetedCardAtSomeoneElse() {
        val game = setup(
            mapOf(0 to listOf("chicken"), 1 to listOf("king-oberons"), 2 to listOf("king-oberons")),
            dice = listOf(6, 1, 1), yes = true,
        )
        game.giveTreasure(game.players[1], "deadmans-switch")
        game.gainBlood(game.players[1], 3, "test")
        game.players[1].hp = 25 // Chicken aims at the strongest foe
        round(game)
        assertTrue(damageTo(1).isEmpty(), "wizard 2 paid to pass the hit on: ${damageTo(1)}")
        assertTrue(damageTo(2).isNotEmpty(), "wizard 3 took it instead")
        assertEquals(0, game.players[1].blood)
    }

    @Test
    fun aCreatureBlockIsOnlyOfferedAgainstFoes() {
        // Wizard 1 hurts themselves with Old Scratch's on a low roll: the Creature never gets a say.
        val game = setup(mapOf(0 to listOf("old-scratchs"), 1 to listOf("king-oberons")), dice = listOf(6, 1, 2), yes = true)
        game.giveCreature(game.players[0], "bob")
        round(game)
        assertTrue(events.none { it is GameEvent.CreatureBlocked })
    }

    @Test
    fun wolfCookieAddsOneToCreatureDamage() {
        // Rock-N-Roller (Elemental): a 1-4 roll deals 1 damage, +1 with the Wolf Cookie. Give it three Elemental dice' worth of 1s.
        val game = setup(mapOf(0 to listOf("rock-n-roller"), 1 to listOf("king-oberons")), dice = listOf(6, 1, 1))
        game.giveTreasure(game.players[0], "wolf-cookie")
        game.giveCreature(game.players[0], "bung-burner") // a kept Creature to fill the spell isn't needed; this one just sits
        round(game)
        assertTrue(damageTo(1).isNotEmpty())
        assertTrue(damageTo(1).any { it >= 2 }, "the Wolf Cookie raises 1 damage to 2: ${damageTo(1)}")
    }
}
