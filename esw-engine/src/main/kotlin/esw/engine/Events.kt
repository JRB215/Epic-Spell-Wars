package esw.engine

import esw.model.CardDef

/** A card turned up from the top of the Main Deck, and whether it was kept (added to the spell) or discarded. */
data class RevealedCard(val def: CardDef, val kept: Boolean)

/** Things that happened, in order. The server turns these into screen animations and a game log. */
sealed interface GameEvent {
    // ---- Expansion 1 ----
    /** A wizard's Blood changed by [change] (negative when spent or stolen) and is now [total]. */
    data class BloodChanged(val player: Int, val change: Int, val total: Int, val reason: String) : GameEvent

    /** The Standee moved to [player], or back to the middle of the table when it is null. */
    data class StandeeMoved(val player: Int?) : GameEvent

    /** A wizard discarded a kept Creature to cancel [amount] damage from [from]. */
    data class CreatureBlocked(val player: Int, val creature: String, val amount: Int, val from: Int?) : GameEvent

    /** A Creature stays in play in front of its owner. */
    data class CreatureKept(val player: Int, val creature: String) : GameEvent

    /** A Creature left play (killed, discarded or lost when its owner died). */
    data class CreatureLost(val player: Int, val creature: String, val why: String) : GameEvent

    /** A Reaction card in a dying wizard's spell went off. */
    data class ReactionTriggered(val player: Int, val card: String) : GameEvent

    /** A wizard's turn is over: the cards their spell ended up with (including any added), kept for the round recap. */
    data class SpellFinished(val player: Int, val cards: List<CardDef>) : GameEvent
    /** Cards were turned over from the top of the Main Deck by [by] (a card name), so everyone can see them. */
    data class DeckRevealed(val player: Int, val by: String, val cards: List<RevealedCard>) : GameEvent
    data class MatchStarted(val players: List<String>) : GameEvent
    data class GameStarted(val number: Int) : GameEvent
    data class RoundStarted(val number: Int) : GameEvent
    /** Everyone has drawn their cards for the round. Sent so screens can show the new hands. */
    data object HandsDealt : GameEvent
    data class SpellsLocked(val spells: List<LockedSpell>) : GameEvent
    data class TurnStarted(val player: Int) : GameEvent
    data class SpellRevealed(val player: Int, val cards: List<String>) : GameEvent
    data class WildMagicResolved(val player: Int, val slot: String, val replacement: String?) : GameEvent
    data class CardResolving(val player: Int, val card: String) : GameEvent
    data class DiceRolled(val player: Int, val reason: String, val dice: List<Int>, val total: Int) : GameEvent
    /** A Power Roll came to [total], which falls in result [band]: 1 is 1-4, 2 is 5-9, 3 is 10 or more. */
    data class RollOutcome(val player: Int, val card: String, val total: Int, val band: Int) : GameEvent
    data class DamageDealt(val target: Int, val amount: Int, val source: Int?, val hpAfter: Int) : GameEvent
    data class Healed(val player: Int, val amount: Int, val hpAfter: Int) : GameEvent
    data class TreasureGained(val player: Int, val treasure: String, val from: Int?) : GameEvent
    data class TreasureLost(val player: Int, val treasure: String, val destroyed: Boolean) : GameEvent
    data class CardAddedToSpell(val player: Int, val card: String) : GameEvent
    data class PlayerDied(val player: Int) : GameEvent
    data class DeadWizardDrawn(val player: Int, val card: String) : GameEvent
    data class GameWon(val winner: Int?, val tokens: Int) : GameEvent
    data class MatchWon(val winner: Int) : GameEvent
    data class Info(val text: String) : GameEvent
}

/** One wizard's place in the announced turn order. [tied] means another wizard shares this exact place and they will roll off. */
data class LockedSpell(
    val player: Int,
    val components: Int,
    val initiative: Int,
    val impatient: Boolean = false,
    val actsLast: Boolean = false,
    val tied: Boolean = false,
)
