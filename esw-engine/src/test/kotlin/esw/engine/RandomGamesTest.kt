package esw.engine

import esw.model.CardCatalog
import esw.model.CardType
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RandomGamesTest {
    private val catalog = CardCatalog.loadBase()

    @Test
    fun everySpellCardHasAnEffect() {
        val spellCards = catalog.allDefs.values.filter {
            it.type == CardType.SOURCE || it.type == CardType.QUALITY || it.type == CardType.DELIVERY
        }
        val missing = spellCards.map { it.id }.filter { it !in CardEffects.ids }
        assertEquals(emptyList(), missing, "cards with no effect written")
        assertEquals(60, spellCards.size)
    }

    private fun playMatch(seed: Int, players: Int, events: MutableList<GameEvent>? = null): Pair<Int?, Game> {
        val random = Random(seed)
        lateinit var game: Game
        var problem: String? = null
        game = Game(
            catalog, List(players) { "Wizard ${it + 1}" }, RandomDecisions(random), RandomDice(random), random,
        ) { event ->
            events?.add(event)
            if (event is GameEvent.RoundStarted) problem = problem ?: game.audit()?.let { "seed $seed, round ${event.number}: $it" }
        }
        val winner = runBlocking { game.playMatch() }
        assertNull(problem, problem)
        assertNull(game.audit(), "cards lost or duplicated at the end of seed $seed")
        return winner to game
    }

    @Test
    fun randomMatchesFinishForEveryPlayerCount() {
        for (players in 2..6) {
            var finished = 0
            for (seed in 1..60) {
                val (winner, game) = playMatch(seed * 31 + players, players)
                if (winner != null) {
                    finished++
                    assertEquals(2, game.players[winner].tokens)
                }
            }
            assertTrue(finished >= 58, "only $finished of 60 matches with $players players produced a winner")
        }
    }

    @Test
    fun sameSeedPlaysTheSameMatch() {
        val a = mutableListOf<GameEvent>()
        val b = mutableListOf<GameEvent>()
        playMatch(99, 4, a)
        playMatch(99, 4, b)
        assertEquals(a, b)
        assertTrue(a.size > 100)
    }

    @Test
    fun matchesEndWithAWinnerWhoHasTwoTokens() {
        val events = mutableListOf<GameEvent>()
        val (winner, _) = playMatch(5, 3, events)
        assertNotNull(winner)
        assertEquals(winner, events.filterIsInstance<GameEvent.MatchWon>().single().winner)
    }
}
