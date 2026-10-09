package esw.server

import esw.engine.CardInstance
import esw.engine.Decisions
import esw.engine.Game
import esw.engine.GameEvent
import esw.engine.RandomDecisions
import esw.engine.RandomDice
import esw.engine.SpellChoice
import esw.model.CardCatalog
import esw.model.CardType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.random.Random

enum class RoomState { LOBBY, PLAYING, FINISHED }

/** How long a missing player has to come back before the others are asked about a bot. */
const val DISCONNECT_GRACE_MS = 30_000L

class Seat(val index: Int, var name: String, @Volatile var key: String?, val bot: Boolean) {
    @Volatile var session: WebSocketSession? = null
    @Volatile var disconnectedAt = 0L
    @Volatile var takeover = false
    @Volatile var voteAsked = false
    val yesVotes: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    val connected: Boolean get() = bot || session?.isOpen == true

    fun send(message: JsonObject) {
        val s = session ?: return
        if (!s.isOpen) return
        try {
            s.sendMessage(TextMessage(message.toString()))
        } catch (_: Exception) {
            // The connection died; the disconnect handler will notice.
        }
    }
}

/** A question waiting for a human's answer. [botValue] is what a bot would have answered, used if a bot takes over. */
class Prompt(
    val id: String,
    val seat: Int,
    val kind: String,
    val data: Map<String, Any?>,
    val botValue: Any,
    val parse: (JsonElement) -> Any?,
    val deferred: CompletableDeferred<Any> = CompletableDeferred(),
)

/** Settings shared by every room. */
class RoomServices(val catalog: CardCatalog, val leaderboard: Leaderboard, val pace: Double)

class Room(val id: String, var title: String, var ownerKey: String, private val services: RoomServices) {
    val seats = CopyOnWriteArrayList<Seat>()
    var maxPlayers = 6
    @Volatile var state = RoomState.LOBBY
        private set

    private val prompts = ConcurrentHashMap<String, Prompt>()
    private val lastStates = ConcurrentHashMap<Int, JsonObject>()
    private val log = CopyOnWriteArrayList<String>()
    @Volatile private var game: Game? = null
    @Volatile private var gameThread: Thread? = null

    // ------------------------------------------------------------------ seats

    @Synchronized
    fun addHuman(key: String, name: String, session: WebSocketSession): Seat? {
        if (state != RoomState.LOBBY || seats.size >= maxPlayers) return null
        val seat = Seat(seats.size, name, key, bot = false)
        seat.session = session
        seats.add(seat)
        return seat
    }

    @Synchronized
    fun addBot(): Boolean {
        if (state != RoomState.LOBBY || seats.size >= maxPlayers) return false
        val number = seats.count { it.bot } + 1
        seats.add(Seat(seats.size, "Bot $number", null, bot = true))
        return true
    }

    @Synchronized
    fun removeSeat(index: Int) {
        if (state != RoomState.LOBBY) return
        seats.removeAt(index)
        // Seat numbers are the order at the table, so renumber what is left.
        val rest = seats.toList()
        seats.clear()
        rest.forEachIndexed { i, s ->
            val copy = Seat(i, s.name, s.key, s.bot)
            copy.session = s.session
            seats.add(copy)
        }
    }

    fun seatOf(key: String): Seat? = seats.firstOrNull { it.key == key }

    fun humans(): List<Seat> = seats.filter { !it.bot }

    /** Humans who walked away for good have no key; they no longer count as being at the table. */
    fun presentHumans(): List<Seat> = humans().filter { it.key != null }

    val isEmpty: Boolean get() = humans().isEmpty()

    // ------------------------------------------------------------------ messages

    fun roomMessage(forKey: String?): JsonObject = obj(
        "t" to "room",
        "id" to id,
        "title" to title,
        "state" to state,
        "max" to maxPlayers,
        "owner" to (forKey != null && forKey == ownerKey),
        "you" to seatOf(forKey ?: "")?.index,
        "seats" to seats.map {
            mapOf("index" to it.index, "name" to it.name, "bot" to it.bot, "connected" to it.connected, "away" to it.takeover,
                "host" to (it.key != null && it.key == ownerKey))
        },
    )

    fun broadcastRoom() {
        for (s in humans()) s.send(roomMessage(s.key))
    }

    fun summary(): JsonObject = obj(
        "id" to id, "title" to title, "state" to state, "max" to maxPlayers,
        "players" to seats.map { it.name }, "humans" to humans().size,
    )

    // ------------------------------------------------------------------ the game

    @Synchronized
    fun start(): String? {
        if (state != RoomState.LOBBY) return "The game has already started."
        if (seats.size < 2) return "At least two wizards are needed."
        state = RoomState.PLAYING
        log.clear()
        lastStates.clear()
        val random = Random(System.nanoTime())
        val names = seats.map { it.name }
        val engine = Game(services.catalog, names, HumanDecisions(random), RandomDice(random), random) { event -> onEvent(event, names) }
        game = engine
        broadcastRoom()
        gameThread = thread(name = "game-$id", isDaemon = true) {
            try {
                val winner = runBlocking { engine.playMatch() }
                finish(winner)
            } catch (_: kotlinx.coroutines.CancellationException) {
                // The room was closed while the game was running.
            } catch (e: Exception) {
                e.printStackTrace()
                state = RoomState.FINISHED
                for (s in humans()) s.send(obj("t" to "error", "message" to "The game stopped because of an error: ${e.message}"))
                broadcastRoom()
            }
        }
        return null
    }

    private fun finish(winner: Int?) {
        state = RoomState.FINISHED
        val humans = humans().size
        if (winner != null && humans >= 2) {
            val seat = seats[winner]
            if (!seat.bot) services.leaderboard.recordWin(seat.name)
        }
        broadcastRoom()
    }

    private fun seatInfos() = seats.map { SeatInfo(it.bot, it.connected, it.takeover) }

    private fun onEvent(event: GameEvent, names: List<String>) {
        val engine = game ?: return
        val text = describe(event, names)
        if (text.isNotEmpty()) {
            log.add(text)
            if (log.size > 300) log.removeAt(0)
        }
        val view = eventView(event, names)
        val infos = seatInfos()
        for (s in seats) {
            val state = gameView(engine, s.index, infos)
            lastStates[s.index] = state
            if (!s.bot) s.send(obj("t" to "ev", "event" to view, "state" to state))
        }
        val pause = (pauseFor(event) * services.pace).toLong()
        if (pause > 0) Thread.sleep(pause)
    }

    private fun pauseFor(e: GameEvent): Long = when (e) {
        is GameEvent.MatchStarted -> 1200
        is GameEvent.GameStarted -> 1500
        is GameEvent.RoundStarted -> 900
        is GameEvent.HandsDealt -> 0
        is GameEvent.SpellsLocked -> 1400
        is GameEvent.TurnStarted -> 1100
        is GameEvent.SpellRevealed -> 2800
        is GameEvent.WildMagicResolved -> 1900
        is GameEvent.CardResolving -> 2600
        is GameEvent.DiceRolled -> 2400
        is GameEvent.RollOutcome -> 2900
        is GameEvent.DamageDealt -> 1700
        is GameEvent.Healed -> 1500
        is GameEvent.TreasureGained -> 2900
        is GameEvent.TreasureLost -> 1300
        is GameEvent.CardAddedToSpell -> 1600
        is GameEvent.PlayerDied -> 2200
        is GameEvent.DeadWizardDrawn -> 1200
        is GameEvent.GameWon -> 3500
        is GameEvent.MatchWon -> 4500
        is GameEvent.Info -> 500
    }

    @Synchronized
    fun rematch(): Boolean {
        if (state != RoomState.FINISHED) return false
        for (s in seats.filter { !it.bot && !it.connected }) removeSeat(s.index)
        state = RoomState.LOBBY
        game = null
        prompts.clear()
        lastStates.clear()
        seats.forEach { it.takeover = false; it.voteAsked = false; it.yesVotes.clear() }
        broadcastRoom()
        return true
    }

    fun dispose() {
        for (p in prompts.values) p.deferred.cancel()
        prompts.clear()
        gameThread?.interrupt()
    }

    // ------------------------------------------------------------------ reconnecting and missing players

    /** Called when a connection that sat at this table is back (or has just arrived). Sends everything it missed. */
    fun resync(seat: Seat) {
        seat.send(roomMessage(seat.key))
        if (state == RoomState.LOBBY) return
        seat.send(obj("t" to "log", "lines" to log.toList()))
        lastStates[seat.index]?.let { seat.send(obj("t" to "state", "state" to it)) }
        for (p in prompts.values.filter { it.seat == seat.index }) seat.send(promptMessage(p))
    }

    /** A player chose to walk away in the middle of a game: a bot plays their seat from now on. */
    fun leaveDuringGame(seat: Seat) {
        seat.session = null
        seat.key = null
        seat.disconnectedAt = System.currentTimeMillis()
        startTakeover(seat)
    }

    fun leaveAfterGame(seat: Seat) {
        seat.session = null
        seat.key = null
        seat.disconnectedAt = System.currentTimeMillis()
    }

    fun noOneConnected(): Boolean = humans().none { it.connected }

    fun markDisconnected(seat: Seat) {
        seat.disconnectedAt = System.currentTimeMillis()
        seat.session = null
        if (state == RoomState.LOBBY) removeSeat(seat.index)
        broadcastRoom()
    }

    fun markReturned(seat: Seat, session: WebSocketSession) {
        seat.session = session
        seat.disconnectedAt = 0
        seat.voteAsked = false
        seat.yesVotes.clear()
        if (seat.takeover) seat.takeover = false
        broadcastRoom()
        resync(seat)
    }

    /** Looks for players who have been gone too long and asks the others whether a bot should play for them. */
    fun checkMissingPlayers(now: Long) {
        if (state != RoomState.PLAYING) return
        for (seat in humans()) {
            if (seat.connected || seat.takeover || seat.disconnectedAt == 0L || now - seat.disconnectedAt < DISCONNECT_GRACE_MS) continue
            val others = humans().filter { it !== seat && it.connected }
            if (others.isEmpty()) {
                startTakeover(seat)
            } else if (!seat.voteAsked) {
                seat.voteAsked = true
                for (o in others) o.send(obj("t" to "vote", "seat" to seat.index, "name" to seat.name))
            }
        }
    }

    fun vote(from: Seat, target: Int, yes: Boolean) {
        val seat = seats.getOrNull(target) ?: return
        if (seat.bot || seat.connected || seat.takeover || from === seat) return
        if (!yes) { seat.yesVotes.clear(); return }
        seat.yesVotes.add(from.index)
        val needed = humans().filter { it !== seat && it.connected }.map { it.index }
        if (seat.yesVotes.containsAll(needed)) startTakeover(seat)
    }

    private fun startTakeover(seat: Seat) {
        seat.takeover = true
        for (p in prompts.values.filter { it.seat == seat.index }) p.deferred.complete(p.botValue)
        broadcastRoom()
    }

    // ------------------------------------------------------------------ prompts

    private fun promptMessage(p: Prompt) = obj("t" to "prompt", "pid" to p.id, "kind" to p.kind, "data" to p.data)

    /** Called with a human's answer. Returns an error message if the answer is not allowed. */
    fun answer(seat: Seat, pid: String, value: JsonElement): String? {
        val prompt = prompts[pid] ?: return "That question is no longer open."
        if (prompt.seat != seat.index) return "That question is not for you."
        val parsed = prompt.parse(value) ?: return "That is not a valid answer."
        prompt.deferred.complete(parsed)
        return null
    }

    private inner class HumanDecisions(private val random: Random) : Decisions {
        private val bot = RandomDecisions(random)

        private suspend fun <T : Any> ask(
            player: Int, kind: String, data: Map<String, Any?>, botAnswer: suspend () -> T, parse: (JsonElement) -> T?,
        ): T {
            val seat = seats[player]
            val botValue = botAnswer()
            if (seat.bot || seat.takeover) {
                val pause = (450 * services.pace).toLong()
                if (pause > 0) delay(pause)
                return botValue
            }
            val prompt = Prompt(UUID.randomUUID().toString(), player, kind, data, botValue, parse)
            prompts[prompt.id] = prompt
            seat.send(promptMessage(prompt))
            try {
                @Suppress("UNCHECKED_CAST")
                return prompt.deferred.await() as T
            } finally {
                prompts.remove(prompt.id)
                seat.send(obj("t" to "promptDone", "pid" to prompt.id))
            }
        }

        override suspend fun chooseSpell(player: Int, hand: List<CardInstance>, treasures: List<CardInstance>): SpellChoice =
            ask(
                player, "spell",
                mapOf(
                    "hand" to hand.map { cardView(it) },
                    "gems" to treasures.filter { it.def.id == "proton-gem" }.map { cardView(it) },
                ),
                { bot.chooseSpell(player, hand, treasures) },
            ) { value -> parseSpell(value.asObject(), hand, treasures) }

        override suspend fun choosePlayer(player: Int, candidates: List<Int>, reason: String): Int =
            ask(player, "player", mapOf("candidates" to candidates, "reason" to reason),
                { bot.choosePlayer(player, candidates, reason) }) { v -> v.asInt()?.takeIf { it in candidates } }

        override suspend fun chooseTreasure(player: Int, candidates: List<CardInstance>, reason: String): Int =
            ask(player, "treasure", mapOf("candidates" to candidates.map { cardView(it) }, "reason" to reason),
                { bot.chooseTreasure(player, candidates, reason) }) { v -> v.asInt()?.takeIf { uid -> candidates.any { it.uid == uid } } }

        override suspend fun chooseCardFromHand(player: Int, candidates: List<CardInstance>, reason: String): Int =
            ask(player, "card", mapOf("candidates" to candidates.map { cardView(it) }, "reason" to reason),
                { bot.chooseCardFromHand(player, candidates, reason) }) { v -> v.asInt()?.takeIf { uid -> candidates.any { it.uid == uid } } }

        override suspend fun chooseOption(player: Int, prompt: String, options: List<String>): Int =
            ask(player, "option", mapOf("prompt" to prompt, "options" to options),
                { bot.chooseOption(player, prompt, options) }) { v -> v.asInt()?.takeIf { it in options.indices } }

        override suspend fun chooseYesNo(player: Int, prompt: String): Boolean =
            ask(player, "yesno", mapOf("prompt" to prompt), { bot.chooseYesNo(player, prompt) }) { v -> v.asBool() }
    }
}

/** Checks a human's spell the same way the engine will, so a bad answer is rejected instead of ending the game. */
private fun parseSpell(value: JsonObject?, hand: List<CardInstance>, treasures: List<CardInstance>): SpellChoice? {
    value ?: return null
    val source = value.int("source")
    val quality = value.int("quality")
    val delivery = value.int("delivery")
    val picks = listOf(source to CardType.SOURCE, quality to CardType.QUALITY, delivery to CardType.DELIVERY)
    val used = mutableSetOf<Int>()
    for ((uid, slot) in picks) {
        if (uid == null) continue
        if (!used.add(uid)) return null
        val card = hand.firstOrNull { it.uid == uid }
        if (card != null) {
            if (card.def.type != slot && card.def.type != CardType.WILD_MAGIC) return null
        } else if (treasures.none { it.uid == uid && it.def.id == "proton-gem" }) {
            return null
        }
    }
    if (used.isEmpty() && hand.isNotEmpty()) return null
    return SpellChoice(source, quality, delivery)
}
