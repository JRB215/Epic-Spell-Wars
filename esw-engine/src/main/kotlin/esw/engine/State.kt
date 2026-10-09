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
}

class Spell {
    val cards = mutableListOf<SpellCard>()

    /** True once any Wild Magic card (or Proton Gem) in this spell has been revealed. */
    var usedWildMagic = false

    /** Set by Pam and Hecuba's: the Delivery targets each foe instead. */
    var deliveryEachFoe = false

    /** True once the spell has been turned face up for everyone to see. */
    var revealed = false
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
    var firstTurnDie = false

    /** Extra cards drawn after the starting hand this game (Pixie Paradise). */
    var extraStartingCards = 0

    fun has(treasureId: String) = treasures.any { it.def.id == treasureId }
}

/** The three slots a wizard fills, each with the uid of a hand card (or a Wild Magic / Proton Gem). */
data class SpellChoice(val source: Int?, val quality: Int?, val delivery: Int?)
