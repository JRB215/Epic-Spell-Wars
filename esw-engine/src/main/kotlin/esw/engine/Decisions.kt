package esw.engine

import kotlin.random.Random

/** Rolls a six-sided die. Replaceable so tests can script the dice. */
fun interface Dice {
    fun d6(): Int
}

class RandomDice(private val random: Random) : Dice {
    override fun d6(): Int = random.nextInt(1, 7)
}

/**
 * Everything the engine needs a player to decide. Humans answer through the server,
 * bots answer immediately. Every answer is checked by the engine, so a wrong answer is an error.
 */
interface Decisions {
    /** Called for every living wizard at the same time, each in secret. */
    suspend fun chooseSpell(player: Int, hand: List<CardInstance>, treasures: List<CardInstance>): SpellChoice

    /** Pick one player id out of [candidates]. */
    suspend fun choosePlayer(player: Int, candidates: List<Int>, reason: String): Int

    /** Pick the uid of one of [candidates] (Treasures). */
    suspend fun chooseTreasure(player: Int, candidates: List<CardInstance>, reason: String): Int

    /** Pick the uid of one of [candidates] (cards in hand). */
    suspend fun chooseCardFromHand(player: Int, candidates: List<CardInstance>, reason: String): Int

    /** Pick the index of one of [options]. */
    suspend fun chooseOption(player: Int, prompt: String, options: List<String>): Int

    suspend fun chooseYesNo(player: Int, prompt: String): Boolean
}
