package esw.engine

/** Things that happened, in order. The server turns these into screen animations and a game log. */
sealed interface GameEvent {
    data class MatchStarted(val players: List<String>) : GameEvent
    data class GameStarted(val number: Int) : GameEvent
    data class RoundStarted(val number: Int) : GameEvent
    data class SpellsLocked(val spells: List<LockedSpell>) : GameEvent
    data class TurnStarted(val player: Int) : GameEvent
    data class SpellRevealed(val player: Int, val cards: List<String>) : GameEvent
    data class WildMagicResolved(val player: Int, val slot: String, val replacement: String?) : GameEvent
    data class CardResolving(val player: Int, val card: String) : GameEvent
    data class DiceRolled(val player: Int, val reason: String, val dice: List<Int>, val total: Int) : GameEvent
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

data class LockedSpell(val player: Int, val components: Int, val initiative: Int)
