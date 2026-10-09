package esw.engine

import esw.model.CardType
import kotlin.random.Random

/**
 * A bot that makes random legal choices. The game is chaotic anyway, so this is a fair opponent
 * and also what the tests use to play thousands of games.
 */
class RandomDecisions(private val random: Random) : Decisions {
    override suspend fun chooseSpell(player: Int, hand: List<CardInstance>, treasures: List<CardInstance>): SpellChoice {
        val gem = treasures.firstOrNull { it.def.id == "proton-gem" }
        val used = mutableSetOf<Int>()
        val slots = listOf(CardType.SOURCE, CardType.QUALITY, CardType.DELIVERY)

        fun candidatesFor(slot: CardType): List<CardInstance> =
            hand.filter { (it.def.type == slot || it.def.type == CardType.WILD_MAGIC) && it.uid !in used } +
                listOfNotNull(gem?.takeIf { it.uid !in used })

        val available = slots.filter { candidatesFor(it).isNotEmpty() }
        if (available.isEmpty()) return SpellChoice(null, null, null)
        val include = available.filter { random.nextDouble() < 0.65 }.ifEmpty { listOf(available[random.nextInt(available.size)]) }
        val chosen = HashMap<CardType, Int>()
        for (slot in include.shuffled(random)) {
            val options = candidatesFor(slot)
            if (options.isEmpty()) continue
            val card = options[random.nextInt(options.size)]
            used.add(card.uid)
            chosen[slot] = card.uid
        }
        return SpellChoice(chosen[CardType.SOURCE], chosen[CardType.QUALITY], chosen[CardType.DELIVERY])
    }

    override suspend fun choosePlayer(player: Int, candidates: List<Int>, reason: String): Int =
        candidates[random.nextInt(candidates.size)]

    override suspend fun chooseTreasure(player: Int, candidates: List<CardInstance>, reason: String): Int =
        candidates[random.nextInt(candidates.size)].uid

    override suspend fun chooseCardFromHand(player: Int, candidates: List<CardInstance>, reason: String): Int =
        candidates[random.nextInt(candidates.size)].uid

    override suspend fun chooseOption(player: Int, prompt: String, options: List<String>): Int =
        random.nextInt(options.size)

    override suspend fun chooseYesNo(player: Int, prompt: String): Boolean = random.nextBoolean()
}
