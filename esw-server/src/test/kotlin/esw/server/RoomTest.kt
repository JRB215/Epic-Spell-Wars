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

    /** A browser connection that just records what the server sends it. */
    private class FakeBrowser(name: String) {
        val received = java.util.concurrent.CopyOnWriteArrayList<kotlinx.serialization.json.JsonObject>()
        val session: org.springframework.web.socket.WebSocketSession = java.lang.reflect.Proxy.newProxyInstance(
            FakeBrowser::class.java.classLoader, arrayOf(org.springframework.web.socket.WebSocketSession::class.java),
        ) { _, method, args ->
            when (method.name) {
                "isOpen" -> true
                "getId" -> name
                "sendMessage" -> {
                    val text = (args[0] as org.springframework.web.socket.TextMessage).payload
                    parseMessage(text)?.let { received.add(it) }
                    null
                }
                else -> null
            }
        } as org.springframework.web.socket.WebSocketSession

        fun lastOfType(t: String) = received.lastOrNull { it.string("t") == t }
        fun count(t: String) = received.count { it.string("t") == t }
    }

    private fun firstSpellPrompt(browser: FakeBrowser): Pair<String, kotlinx.serialization.json.JsonElement> {
        val prompt = browser.received.first { it.string("t") == "prompt" && it.string("kind") == "spell" }
        val hand = (prompt["data"] as kotlinx.serialization.json.JsonObject)["hand"] as kotlinx.serialization.json.JsonArray
        val card = hand.map { it as kotlinx.serialization.json.JsonObject }.first { it.string("type") in setOf("SOURCE", "QUALITY", "DELIVERY") }
        val slot = card.string("type")!!.lowercase()
        return prompt.string("pid")!! to obj(slot to card.int("uid"))
    }

    @Test
    fun lockedInSpellsAreHeldUntilEveryoneIsReadyAndCanBeTakenBack() {
        val services = RoomServices(catalog, Leaderboard(null), pace = 0.0)
        val room = Room("held", "Held spells", "a", services)
        val ann = FakeBrowser("ann")
        val bob = FakeBrowser("bob")
        val annSeat = room.addHuman("a", "Ann", ann.session)!!
        val bobSeat = room.addHuman("b", "Bob", bob.session)!!
        room.addBot()
        assertNull(room.start())
        waitFor(10, "both humans to be asked for a spell") { ann.count("prompt") > 0 && bob.count("prompt") > 0 }

        val (annPid, annSpell) = firstSpellPrompt(ann)
        val (bobPid, bobSpell) = firstSpellPrompt(bob)

        // Ann locks in. Bob has not, so nothing is released and Ann is shown as ready.
        assertNull(room.answer(annSeat, annPid, annSpell))
        Thread.sleep(300)
        assertEquals(0, ann.count("promptDone"), "the game must not start while Bob is still choosing")
        val ready = bob.lastOfType("ready")!!
        val readyByName = (ready["seats"] as kotlinx.serialization.json.JsonArray).map { it as kotlinx.serialization.json.JsonObject }
            .associate { it.string("name")!! to it.bool("ready") }
        assertEquals(true, readyByName["Ann"])
        assertEquals(false, readyByName["Bob"])

        // Ann changes her mind and takes it back; she is no longer ready.
        assertNull(room.unlock(annSeat))
        val afterUnlock = (bob.lastOfType("ready")!!["seats"] as kotlinx.serialization.json.JsonArray)
            .map { it as kotlinx.serialization.json.JsonObject }.first { it.string("name") == "Ann" }
        assertEquals(false, afterUnlock.bool("ready"))

        // Both lock in again: now the spells are released together.
        assertNull(room.answer(bobSeat, bobPid, bobSpell))
        Thread.sleep(200)
        assertEquals(0, bob.count("promptDone"), "Bob alone is not enough")
        assertNull(room.answer(annSeat, annPid, annSpell))
        waitFor(10, "the game to start once everyone is ready") { ann.count("promptDone") > 0 && bob.count("promptDone") > 0 }
        assertTrue(room.unlock(annSeat) != null, "it is too late to take a spell back once everyone is ready")
        room.dispose()
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
