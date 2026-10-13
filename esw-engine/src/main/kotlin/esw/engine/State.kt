package esw.engine

import esw.model.CardDef
import esw.model.CardType
import esw.model.HeroDef

/** One physical card. [uid] is unique across all decks, so two copies of a card can be told apart. */
class CardInstance(val uid: Int, val def: CardDef) {
    override fun toString() = "${def.name}#$uid"
}

/**
 * A card sitting in a spell. [slot] is the position it fills (Source, Quality or Delivery).
 * A [wild] card is a Wild Magic card (or a Proton Gem) that is replaced when the spell is revealed.
 */
class SpellCard(var card: CardInstance, val slot: CardType, var wild: Boolean, val protonGem: Boolean = false) {
    var resolved = false

    /**
     * Expansion 1: a Creature kept in play from an earlier turn, brought back into the spell to attack again. It does
     * not count toward the number of cards in the spell (so it does not slow the turn down).
     */
    var fromPlay = false

    /** Expansion 1: set by a Creature's Power Roll when it should stay in play after this turn. */
    var keep = false
}

class Spell {
    val cards = mutableListOf<SpellCard>()

    /** True once any Wild Magic card (or Proton Gem) in this spell has been revealed. */
    var usedWildMagic = false

    /** Set by Pam and Hecuba's: the Delivery targets each foe instead. */
    var deliveryEachFoe = false

    /** True once the spell has been turned face up for everyone to see. */
    var revealed = false

    /** How many cards count toward the turn order: kept Creatures brought back into play do not. */
    val countedCards: Int get() = cards.count { !it.fromPlay }
}

class PlayerState(val id: Int, val name: String, val hero: HeroDef) {
    var hp = 0
    var alive = true
    var tokens = 0
    val hand = mutableListOf<CardInstance>()
    val treasures = mutableListOf<CardInstance>()

    /** Dead Wizard cards held. They give bonuses at the start of the next game. */
    val deadWizardCards = mutableListOf<CardInstance>()

    /** Wild Magic cards set aside by Wild Furicorn Meadow, added to the hand next game. */
    val reservedWildMagic = mutableListOf<CardInstance>()

    var spell: Spell? = null
    var acted = false
    var actLast = false
    var extraDiceThisRound = 0
    /** Slag Shangri-La: a die rolled at the start of the game, added to every Power Roll on this wizard's first turn. */
    var bonusDie: Int? = null

    /** Extra cards drawn after the starting hand this game (Pixie Paradise). */
    var extraStartingCards = 0

    // ---- Expansion 1 ----

    /** Blood, 0 to 25. It carries from game to game within a match. */
    var blood = 0

    /** Creatures kept face up in front of the wizard. They can block damage and attack again each turn. */
    val creatures = mutableListOf<CardInstance>()

    /** Fate Coins: the wizard named as the next to be slain. */
    var prediction: Int? = null

    /** Glutton's Regalia: the card slipped under it, whose glyph counts in every spell. */
    var regalia: CardInstance? = null

    /** Hounds from Hell cards waiting for a Creature's Power Roll: each adds one die to that roll, then is used up. */
    var houndsDice = 0

    /** Treasures to be gained at the start of the next game (Just Rewards, The Sly Roller's). */
    var treasureNextGame = 0

    /** Zap-o-Rama cards: each adds a card to the first spell cast this game. */
    var zapThisGame = 0

    /** Lingering Curse: how many cards fewer this wizard starts the game with. */
    var startHandPenalty = 0

    /** Treasures whose Blood payment has already been made this round (one payment per Treasure per round). */
    val paidThisRound = mutableSetOf<Int>()

    /** Manuel De'Cryptozoic: whether the once-per-turn Creature reroll has been used this turn. */
    var rerolledCreatureThisTurn = false

    fun has(treasureId: String) = treasures.any { it.def.id == treasureId }
}

/** The three slots a wizard fills, each with the uid of a hand card (or a Wild Magic / Proton Gem). */
data class SpellChoice(val source: Int?, val quality: Int?, val delivery: Int?)
