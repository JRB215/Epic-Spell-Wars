package esw.engine

import esw.model.CardType

/**
 * What a Reaction can see and do. A Reaction goes off when its owner would die before the card has started to resolve.
 * [killer] is the wizard whose damage did it (the owner themselves for self-inflicted damage), and [hpBefore] is the
 * owner's hit points before the fatal hit.
 */
internal class ReactionContext(
    val g: Game,
    val owner: PlayerState,
    val killer: PlayerState?,
    val card: SpellCard,
    val hpBefore: Int,
)

/** The rules text of the Expansion 1 cards that is too specific to share a general helper. */
internal object ExpansionEffects {
    /** Runs a Reaction. Returns true if it saved its owner from dying ("You didn't die."). */
    suspend fun reaction(ctx: ReactionContext, id: String): Boolean = with(ctx) {
        when (id) {
            // Discard it to reset your HP to 1 instead.
            "gonzo-shroompusss" -> {
                owner.hp = 1
                owner.spell?.cards?.remove(card)
                g.discardToMain(card.card)
                true
            }
            // Deal one die of damage to the player who killed you.
            "mr-nukion-fussions" -> {
                val k = killer
                if (k != null && k !== owner) g.damage(k, g.dice.d6().also { g.emit(GameEvent.DiceRolled(owner.id, "Mr. Nukion Fussion's", listOf(it), it)) }, owner)
                false
            }
            // Draw two extra Dead Wizard cards.
            "queen-of-the-deads" -> {
                repeat(2) { g.drawDeadWizardQuietly(owner) }
                false
            }
            // Draw a Treasure and play it at the start of the next game.
            "the-sly-rollers" -> {
                owner.treasureNextGame++
                false
            }
            // Reveal a Creature in this spell and it takes the damage instead.
            "wiz-sleeve-steves" -> {
                val creature = owner.spell?.cards?.firstOrNull { it.card.def.creature && it.slot == CardType.DELIVERY && !it.resolved }
                if (creature == null) {
                    false
                } else {
                    owner.spell?.cards?.remove(creature)
                    g.discardToMain(creature.card)
                    g.emit(GameEvent.CreatureLost(owner.id, creature.card.def.name, "took the damage for ${owner.name}"))
                    owner.hp = hpBefore
                    true
                }
            }
            else -> false
        }
    }
}
