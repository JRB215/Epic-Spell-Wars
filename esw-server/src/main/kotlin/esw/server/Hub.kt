package esw.server

import esw.model.CardCatalog
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.socket.WebSocketSession
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One browser tab. [key] is chosen by the tab, so a refreshed tab gets its seat back. */
class Client(val session: WebSocketSession) {
    @Volatile var name = ""
    @Volatile var key = ""
    @Volatile var room: Room? = null
}

private const val MAX_NAME_LENGTH = 20

/** Knows every connected tab and every table, and decides what each message from a tab does. */
@Component
class Hub(
    @Value("\${esw.assets:./assets}") assetsPath: String,
    @Value("\${esw.pace:1.0}") pace: Double,
) {
    val assets = File(assetsPath)
    private val services = RoomServices(CardCatalog.loadBase(), Leaderboard(File(assets, "leaderboard.json")), pace)
    private val rooms = ConcurrentHashMap<String, Room>()
    private val clients = ConcurrentHashMap<String, Client>()
    private val watcher = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "esw-watcher").apply { isDaemon = true } }

    /** Only this name may reset the leaderboard. It is never sent to any browser. */
    private val adminName = "knil" + "admin"

    init {
        watcher.scheduleWithFixedDelay({ tick() }, 3, 3, TimeUnit.SECONDS)
    }

    private fun tick() {
        try {
            val now = System.currentTimeMillis()
            synchronized(this) {
                for (room in rooms.values) {
                    room.checkMissingPlayers(now)
                    if (room.humans().none { it.connected } && room.humans().all { it.disconnectedAt != 0L && now - it.disconnectedAt > 10 * 60_000L }) closeRoom(room)
                }
            }
        } catch (e: Exception) {
            System.err.println("Watcher problem: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ connections

    fun connected(session: WebSocketSession) {
        clients[session.id] = Client(session)
    }

    @Synchronized
    fun closed(session: WebSocketSession) {
        val client = clients.remove(session.id) ?: return
        val room = client.room ?: return
        val seat = room.seatOf(client.key) ?: return
        if (seat.session?.id != session.id) return // the tab already came back on a new connection
        room.markDisconnected(seat)
        if (room.isEmpty || room.humans().none { it.connected } && room.state == RoomState.LOBBY) closeRoom(room)
        broadcastLobby()
    }

    private fun send(client: Client, message: JsonObject) {
        try {
            client.session.sendMessage(org.springframework.web.socket.TextMessage(message.toString()))
        } catch (_: Exception) {
            // Closed connections are cleaned up by closed().
        }
    }

    private fun error(client: Client, text: String) = send(client, obj("t" to "error", "message" to text))

    private fun lobbyMessage(): JsonObject = obj(
        "t" to "lobby",
        "games" to rooms.values.filter { it.humans().any { s -> s.connected } || it.state == RoomState.PLAYING }.map { it.summary() },
        "leaderboard" to services.leaderboard.top().map { mapOf("name" to it.name, "wins" to it.wins) },
    )

    private fun broadcastLobby() {
        val message = lobbyMessage()
        for (c in clients.values) if (c.room == null && c.name.isNotEmpty()) send(c, message)
    }

    private fun closeRoom(room: Room) {
        rooms.remove(room.id)
        room.dispose()
    }

    // ------------------------------------------------------------------ messages from a tab

    @Synchronized
    fun message(session: WebSocketSession, text: String) {
        val client = clients[session.id] ?: return
        val msg = parseMessage(text) ?: return
        val type = msg.string("t") ?: return
        if (type == "hello") return hello(client, msg)
        if (client.name.isEmpty()) return error(client, "Please enter your name first.")
        val room = client.room
        val seat = room?.seatOf(client.key)
        when (type) {
            "createGame" -> createGame(client, msg.string("title"))
            "joinGame" -> joinGame(client, msg.string("id"))
            "leaveGame" -> leaveGame(client)
            "addBot" -> if (room != null && room.ownerKey == client.key && room.addBot()) room.broadcastRoom().also { broadcastLobby() }
            "removeSeat" -> if (room != null && room.ownerKey == client.key) {
                val index = msg.int("seat")
                if (index != null && index in room.seats.indices && room.seats[index].key != client.key) {
                    room.removeSeat(index)
                    room.broadcastRoom()
                    broadcastLobby()
                }
            }
            "start" -> if (room != null && room.ownerKey == client.key) {
                room.start()?.let { error(client, it) }
                broadcastLobby()
            }
            "rematch" -> if (room != null && room.ownerKey == client.key && room.rematch()) broadcastLobby()
            "answer" -> if (room != null && seat != null) {
                val pid = msg.string("pid")
                val value = msg["value"] ?: JsonNull
                if (pid != null) room.answer(seat, pid, value)?.let { error(client, it) }
            }
            "voteBot" -> if (room != null && seat != null) {
                val target = msg.int("seat")
                if (target != null) room.vote(seat, target, msg.bool("yes") == true)
            }
            "resetLeaderboard" -> if (client.name.equals(adminName, ignoreCase = true)) {
                services.leaderboard.reset()
                broadcastLobby()
            }
        }
    }

    private fun hello(client: Client, msg: JsonObject) {
        val name = msg.string("name")?.trim()?.take(MAX_NAME_LENGTH).orEmpty()
        val key = msg.string("key")?.take(80).orEmpty()
        if (name.isEmpty() || key.isEmpty()) return error(client, "Please enter a name.")
        client.name = name
        client.key = key
        send(client, obj("t" to "welcome", "name" to name))
        val room = rooms.values.firstOrNull { it.seatOf(key) != null }
        val seat = room?.seatOf(key)
        if (room != null && seat != null) {
            client.room = room
            room.markReturned(seat, client.session)
        } else {
            send(client, lobbyMessage())
        }
    }

    private fun createGame(client: Client, title: String?) {
        if (client.room != null) return error(client, "You are already at a table.")
        val room = Room(UUID.randomUUID().toString().take(6), title?.trim()?.take(30).orEmpty().ifEmpty { "${client.name}'s game" }, client.key, services)
        rooms[room.id] = room
        room.addHuman(client.key, client.name, client.session)
        client.room = room
        room.broadcastRoom()
        broadcastLobby()
    }

    private fun joinGame(client: Client, id: String?) {
        if (client.room != null) return error(client, "You are already at a table.")
        val room = id?.let { rooms[it] } ?: return error(client, "That game is gone.")
        if (room.addHuman(client.key, client.name, client.session) == null) return error(client, "That game is full or already started.")
        client.room = room
        room.broadcastRoom()
        broadcastLobby()
    }

    private fun leaveGame(client: Client) {
        val room = client.room ?: return
        val seat = room.seatOf(client.key)
        client.room = null
        if (seat != null) {
            when (room.state) {
                RoomState.LOBBY -> {
                    room.removeSeat(seat.index)
                    if (room.ownerKey == client.key) room.humans().firstOrNull()?.key?.let { room.ownerKey = it }
                }
                RoomState.PLAYING -> room.leaveDuringGame(seat)
                RoomState.FINISHED -> room.leaveAfterGame(seat)
            }
            if (room.noOneConnected()) closeRoom(room) else room.broadcastRoom()
        }
        send(client, lobbyMessage())
        broadcastLobby()
    }
}
