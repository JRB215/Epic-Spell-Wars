package esw.engine

import esw.model.CardDef
import esw.model.CardType
import esw.model.Glyph

/** Who a Power Roll card is aimed at. */
internal enum class Target {
    STRONGEST, WEAKEST, CHOICE, LEFT, RIGHT, EACH_FOE, LEFT_AND_RIGHT, TWO_ON_LEFT,
    EACH_STRONGER, EACH_WITHOUT_TREASURES, SELF,
}

/**
 * What a card's effect can see and do while it resolves.
 * [card] is the card being resolved (its glyph is used for Power Rolls).
 * [effectType] is the kind of card whose text is running; it differs from [card]'s type when a card copies another.
 */
internal class EffectContext(
    val g: Game,
    val caster: PlayerState,
    val card: CardDef,
    val effectType: CardType,
) {
    val spell: Spell get() = caster.spell!!

    fun foes(): List<PlayerState> = g.foesOf(caster)

    fun dmg(target: PlayerState, amount: Int) = g.damage(target, amount, caster)

    fun dmgAll(targets: List<PlayerState>, amount: Int) { for (t in targets) dmg(t, amount) }

    fun heal(target: PlayerState, amount: Int) = g.heal(target, amount)

    fun distinctGlyphs(): Int = g.distinctGlyphs(caster)

    fun glyphCount(glyph: Glyph): Int = g.glyphCount(caster, glyph)

    fun gainTreasure(p: PlayerState) = g.gainTreasure(p)

    fun roll(p: PlayerState, reason: String): Int = g.rollSingle(p, reason)

    suspend fun powerRoll(): Int = g.powerRoll(caster, card.glyph ?: Glyph.ARCANE, effectType)

    suspend fun choose(candidates: List<PlayerState>, reason: String): PlayerState = g.pickAmong(caster, candidates, reason)

    suspend fun strongestFoe(): PlayerState? = g.strongestFoe(caster)

    suspend fun weakestFoe(): PlayerState? = g.weakestFoe(caster)

    fun randomFoe(): PlayerState? = g.randomFoe(caster)

    suspend fun chooseOption(prompt: String, options: List<String>): Int {
        val pick = g.decisions.chooseOption(caster.id, prompt, options)
        require(pick in options.indices) { "Invalid option $pick for $prompt" }
        return pick
    }

    /** Adds a random card from the caster's hand (never Wild Magic) to the spell. */
    fun addRandomHandCard() {
        val candidates = caster.hand.filter { it.def.type != CardType.WILD_MAGIC }
        if (candidates.isNotEmpty()) g.addToSpell(caster, candidates[g.random.nextInt(candidates.size)])
    }

    suspend fun addChosenHandCard() {
        val candidates = caster.hand.filter { it.def.type != CardType.WILD_MAGIC }
        if (candidates.isEmpty()) return
        val uid = g.decisions.chooseCardFromHand(caster.id, candidates, "card to add to your spell")
        val card = candidates.firstOrNull { it.uid == uid } ?: error("Invalid card choice $uid")
        g.addToSpell(caster, card)
    }

    /** Resolves who a Power Roll card hits. Choices are made before any dice are rolled. */
    suspend fun targets(kind: Target): List<PlayerState> {
        if (kind == Target.SELF) return listOf(caster)
        val all = foes()
        if (spell.deliveryEachFoe && effectType == CardType.DELIVERY) return all
        return when (kind) {
            Target.STRONGEST -> listOfNotNull(strongestFoe())
            Target.WEAKEST -> listOfNotNull(weakestFoe())
            Target.CHOICE -> if (all.isEmpty()) emptyList() else listOf(choose(all, "foe to target"))
            Target.LEFT -> listOfNotNull(g.leftOf(caster))
            Target.RIGHT -> listOfNotNull(g.rightOf(caster))
            Target.EACH_FOE -> all
            Target.LEFT_AND_RIGHT -> listOfNotNull(g.leftOf(caster), g.rightOf(caster)).distinct()
            Target.TWO_ON_LEFT -> all.take(2)
            Target.EACH_STRONGER -> all.filter { it.hp > caster.hp }
            Target.EACH_WITHOUT_TREASURES -> all.filter { it.treasures.isEmpty() }
            Target.SELF -> listOf(caster)
        }
    }

    /** 1 for a roll of 1-4, 2 for 5-9, 3 for 10 or more. */
    fun band(total: Int): Int = if (total <= 4) 1 else if (total <= 9) 2 else 3

    /** Copies the text of another card in the spell: its effect runs as if it were printed on this card. */
    suspend fun copyEffectOf(from: CardDef) {
        CardEffects.run(EffectContext(g, caster, card, from.type), from.id)
    }
}
