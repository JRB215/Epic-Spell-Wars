package esw.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** One player's total wins. [name] keeps the way they last wrote their name. */
@Serializable
data class LeaderboardEntry(val name: String, val wins: Int)

@Serializable
private data class LeaderboardFile(val entries: List<LeaderboardEntry> = emptyList())

/** Match wins by player name (ignoring upper/lower case), saved as JSON in the assets folder. */
class Leaderboard(private val file: File?) {
    private val json = Json { prettyPrint = true }
    private val wins = linkedMapOf<String, LeaderboardEntry>()

    init {
        if (file != null && file.isFile) {
            try {
                json.decodeFromString<LeaderboardFile>(file.readText()).entries.forEach { wins[it.name.lowercase()] = it }
            } catch (_: Exception) {
                // A damaged file is treated as an empty board rather than stopping the server.
            }
        }
    }

    @Synchronized
    fun recordWin(name: String) {
        val key = name.trim().lowercase()
        if (key.isEmpty()) return
        val old = wins[key]
        wins[key] = LeaderboardEntry(name.trim(), (old?.wins ?: 0) + 1)
        save()
    }

    @Synchronized
    fun top(limit: Int = 10): List<LeaderboardEntry> =
        wins.values.sortedWith(compareByDescending<LeaderboardEntry> { it.wins }.thenBy { it.name.lowercase() }).take(limit)

    @Synchronized
    fun reset() {
        wins.clear()
        save()
    }

    private fun save() {
        val target = file ?: return
        try {
            target.parentFile?.mkdirs()
            target.writeText(json.encodeToString(LeaderboardFile(wins.values.toList())))
        } catch (e: Exception) {
            System.err.println("Could not save the leaderboard: ${e.message}")
        }
    }
}
