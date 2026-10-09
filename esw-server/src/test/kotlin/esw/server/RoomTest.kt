package esw.server

import esw.model.CardCatalog
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class RoomTest {
    private val catalog = CardCatalog.loadBase()

    private fun waitFor(seconds: Int, what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + seconds * 1000L
        while (System.currentTimeMillis() < end) {
            if (condition()) return
            Thread.sleep(25)
        }
        fail("Timed out waiting for $what")
    }

    @Test
    fun aRoomOfBotsPlaysAWholeMatchOnItsOwnThread() {
        val services = RoomServices(catalog, Leaderboard(null), pace = 0.0)
        for (bots in 2..6) {
            val room = Room("t$bots", "Bots only", "nobody", services)
            repeat(bots) { assertTrue(room.addBot()) }
            assertNull(room.start())
            waitFor(60, "$bots bots to finish a match") { room.state == RoomState.FINISHED }
            room.dispose()
        }
    }

    @Test
    fun roomRefusesTooManyOrTooFewPlayers() {
        val services = RoomServices(catalog, Leaderboard(null), pace = 0.0)
        val room = Room("r", "Small", "nobody", services)
        room.addBot()
        assertNotNull(room.start(), "one wizard cannot start a game")
        repeat(5) { assertTrue(room.addBot()) }
        assertEquals(false, room.addBot(), "a seventh seat is not allowed")
        assertEquals(6, room.seats.size)
    }

    @Test
    fun seatsAreRenumberedWhenSomeoneIsRemoved() {
        val services = RoomServices(catalog, Leaderboard(null), pace = 0.0)
        val room = Room("r", "Renumber", "nobody", services)
        repeat(3) { room.addBot() }
        room.removeSeat(0)
        assertEquals(listOf(0, 1), room.seats.map { it.index })
        assertEquals(listOf("Bot 2", "Bot 3"), room.seats.map { it.name })
    }

    @Test
    fun leaderboardCountsNamesWithoutCaseAndSurvivesRestart() {
        val file = File.createTempFile("leaderboard", ".json")
        try {
            val board = Leaderboard(file)
            board.recordWin("Ann")
            board.recordWin("ann")
            board.recordWin("Bob")
            assertEquals(listOf("ann" to 2, "Bob" to 1), board.top().map { it.name to it.wins })
            assertEquals(listOf("ann" to 2, "Bob" to 1), Leaderboard(file).top().map { it.name to it.wins })
            board.reset()
            assertTrue(Leaderboard(file).top().isEmpty())
        } finally {
            file.delete()
        }
    }

    @Test
    fun jsonHelpersRoundTrip() {
        val message = obj("t" to "x", "n" to 3, "list" to listOf(1, 2), "nested" to mapOf("a" to true), "none" to null)
        val parsed = parseMessage(message.toString())
        assertNotNull(parsed)
        assertEquals("x", parsed.string("t"))
        assertEquals(3, parsed.int("n"))
        assertNull(parseMessage("not json"))
    }
}
