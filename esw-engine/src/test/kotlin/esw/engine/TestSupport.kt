package esw.engine

import esw.model.CardType
import kotlin.random.Random

/** Plays back the given dice values first, then rolls randomly. */
class ScriptedDice(values: List<Int>, private val fallback: Random = Random(1)) : Dice {
    private val queue = ArrayDeque(values)
    override fun d6(): Int = queue.removeFirstOrNull() ?: fallback.nextInt(1, 7)
}

/** Everyone makes random choices except for spells, which come from [spells] (by card design id). */
class ScriptedDecisions(
    private val spells: Map<Int, List<String>>,
    random: Random = Random(7),
) : Decisions {
    private val bot = RandomDecisions(random)

    override suspend fun chooseSpell(player: Int, hand: List<CardInstance>, treasures: List<CardInstance>): SpellChoice {
        val wanted = spells[player] ?: return bot.chooseSpell(player, hand, treasures)
        val used = mutableSetOf<Int>()
        fun pick(type: CardType): Int? {
            val id = wanted.firstOrNull { id -> hand.any { it.def.id == id && it.def.type == type && it.uid !in used } } ?: return null
            val card = hand.first { it.def.id == id && it.def.type == type && it.uid !in used }
            used.add(card.uid)
            return card.uid
        }
        val picks = mutableListOf(pick(CardType.SOURCE), pick(CardType.QUALITY), pick(CardType.DELIVERY))
        if ("wild-magic" in wanted) {
            val wild = hand.first { it.def.type == CardType.WILD_MAGIC && it.uid !in used }
            picks[picks.indexOf(null)] = wild.uid
        }
        return SpellChoice(picks[0], picks[1], picks[2])
    }

    override suspend fun choosePlayer(player: Int, candidates: List<Int>, reason: String) = bot.choosePlayer(player, candidates, reason)
    override suspend fun chooseTreasure(player: Int, candidates: List<CardInstance>, reason: String) = bot.chooseTreasure(player, candidates, reason)
    override suspend fun chooseCardFromHand(player: Int, candidates: List<CardInstance>, reason: String) = bot.chooseCardFromHand(player, candidates, reason)
    override suspend fun chooseOption(player: Int, prompt: String, options: List<String>) = bot.chooseOption(player, prompt, options)
    override suspend fun chooseYesNo(player: Int, prompt: String) = false
}
