package esw.engine

import esw.model.CardDef
import esw.model.CardType
import esw.model.Glyph

/** Who a Power Roll card is aimed at. */
internal enum class Target {
    STRONGEST, WEAKEST, CHOICE, LEFT, RIGHT, EACH_FOE, LEFT_AND_RIGHT, TWO_ON_LEFT,
    EACH_STRONGER, EACH_WITHOUT_TREASURES, SELF,
    // Expansion 1
    MOST_BLOOD, ALREADY_ACTED, HAS_STANDEE, NOT_STANDEE, MOST_CREATURES, MOST_TREASURE, NO_CREATURES,
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
    /** The card in the spell that is resolving. Null when a card copies another card's text. */
    val spellCard: SpellCard? = null,
) {
    val spell: Spell get() = caster.spell!!

    fun foes(): List<PlayerState> = g.foesOf(caster)

    /** Deals damage. Returns true if a Creature blocked it. Wolf Cookie adds 1 to damage a Creature deals to a foe. */
    suspend fun dmg(target: PlayerState, amount: Int): Boolean {
        val bonus = if (g.expansion && isCreatureRoll && amount > 0 && target !== caster && caster.has("wolf-cookie")) 1 else 0
        return g.damage(target, amount + bonus, caster)
    }

    suspend fun dmgAll(targets: List<PlayerState>, amount: Int) { for (t in targets) dmg(t, amount) }

    // ---- Expansion 1 helpers ----

    val standee: Boolean get() = g.hasStandee(caster)

    /** Offers to pay [cost] Blood for [what]. True if paid. */
    suspend fun pay(cost: Int, what: String): Boolean = g.payBlood(caster, cost, what)

    /** "Pay X Blood": asks how much to pay (up to all the Blood held), takes it and returns X. */
    suspend fun payX(what: String): Int {
        if (!g.expansion || caster.blood <= 0) return 0
        val options = (0..caster.blood).map { "$it" }
        val x = chooseOption("Pay X Blood: $what. How much?", options)
        if (x > 0) g.loseBlood(caster, x, what)
        return x
    }

    fun gainBlood(amount: Int, reason: String) = g.gainBlood(caster, amount, reason)

    fun stealBlood(victim: PlayerState, amount: Int) = g.stealBlood(caster, victim, amount)

    fun creaturesInPlay(): Int = g.players.sumOf { it.creatures.size }

    /** Reveals cards from the Main Deck until a Creature turns up, adds it to the spell and discards the rest. */
    fun addRevealedCreature() {
        val skipped = mutableListOf<CardInstance>()
        var found: CardInstance? = null
        while (true) {
            val c = g.drawMain() ?: break
            if (c.def.creature) { found = c; break } else skipped.add(c)
        }
        val shown = skipped.map { RevealedCard(it.def, false) } + listOfNotNull(found?.let { RevealedCard(it.def, true) })
        if (shown.isNotEmpty()) g.emit(GameEvent.DeckRevealed(caster.id, card.name, shown))
        g.discardAllToMain(skipped)
        found?.let { g.addToSpell(caster, it) }
    }

    /** Double-Headed Ding-Donger: asked once per card, and the answer reverses every direction on it. */
    private var flipAnswer: Boolean? = null

    suspend fun flipped(): Boolean {
        if (!g.expansion || !caster.has("double-headed-ding-donger")) return false
        flipAnswer?.let { return it }
        val answer = g.decisions.chooseYesNo(caster.id, "Double-Headed Ding-Donger: reverse the direction on ${card.name} (left becomes right)?")
        flipAnswer = answer
        return answer
    }

    suspend fun left(): PlayerState? = if (flipped()) g.rightOf(caster) else g.leftOf(caster)

    suspend fun right(): PlayerState? = if (flipped()) g.leftOf(caster) else g.rightOf(caster)

    /** Foes in the order the card goes round the table: starting at the left, or at the right when reversed. */
    suspend fun foesFromLeft(): List<PlayerState> = if (flipped()) foes().reversed() else foes()

    fun heal(target: PlayerState, amount: Int) = g.heal(target, amount)

    fun distinctGlyphs(): Int = g.distinctGlyphs(caster)

    fun glyphCount(glyph: Glyph): Int = g.glyphCount(caster, glyph)

    suspend fun gainTreasure(p: PlayerState) = g.gainTreasure(p)

    fun roll(p: PlayerState, reason: String): Int = g.rollSingle(p, reason)

    /** True when this Power Roll is a Creature's own (a Creature that copies nothing and is really in the spell). */
    val isCreatureRoll: Boolean get() = card.creature && spellCard != null

    suspend fun powerRoll(): Int = g.powerRoll(caster, card.glyph ?: Glyph.ARCANE, effectType, isCreatureRoll)

    /** The Creature stays in play after this turn. A card that merely copies a Creature cannot keep. */
    fun keepCreature() {
        if (isCreatureRoll) spellCard!!.keep = true
    }

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
        val picked = pickTargets(kind)
        // Deadman's Switch: a foe who is the sole target may pay 3 Blood to point the card at a different foe.
        val only = picked.singleOrNull()
        if (g.expansion && only != null && only !== caster && only.has("deadmans-switch")) {
            val switchCard = only.treasures.first { it.def.id == "deadmans-switch" }
            val others = g.players.filter { it.alive && it !== caster && it !== only }
            if (others.isNotEmpty() && g.payForTreasure(only, switchCard, 3, "switch the target of ${card.name} (Deadman's Switch)")) {
                return listOf(g.pickAmong(only, others, "new target for ${card.name} (Deadman's Switch)"))
            }
        }
        return picked
    }

    private suspend fun pickTargets(kind: Target): List<PlayerState> {
        if (kind == Target.SELF) return listOf(caster)
        val all = foes()
        if (spell.deliveryEachFoe && effectType == CardType.DELIVERY) return all
        return when (kind) {
            Target.STRONGEST -> listOfNotNull(strongestFoe())
            Target.WEAKEST -> listOfNotNull(weakestFoe())
            Target.CHOICE -> if (all.isEmpty()) emptyList() else listOf(choose(all, "foe to target"))
            Target.LEFT -> listOfNotNull(left())
            Target.RIGHT -> listOfNotNull(right())
            Target.EACH_FOE -> all
            Target.LEFT_AND_RIGHT -> listOfNotNull(g.leftOf(caster), g.rightOf(caster)).distinct()
            Target.TWO_ON_LEFT -> foesFromLeft().take(2)
            Target.MOST_BLOOD -> mostOf(all) { it.blood }
            Target.MOST_CREATURES -> mostOf(all) { it.creatures.size }
            Target.MOST_TREASURE -> mostOf(all) { it.treasures.size }
            Target.ALREADY_ACTED -> pickOne(all.filter { g.hasActed(it) })
            Target.HAS_STANDEE -> pickOne(all.filter { g.hasStandee(it) })
            Target.NOT_STANDEE -> pickOne(all.filter { !g.hasStandee(it) })
            Target.NO_CREATURES -> pickOne(all.filter { it.creatures.isEmpty() })
            Target.EACH_STRONGER -> all.filter { it.hp > caster.hp }
            Target.EACH_WITHOUT_TREASURES -> all.filter { it.treasures.isEmpty() }
            Target.SELF -> listOf(caster)
        }
    }

    private suspend fun pickOne(candidates: List<PlayerState>): List<PlayerState> =
        if (candidates.isEmpty()) emptyList() else listOf(choose(candidates, "foe to target"))

    /** The foe with the highest [score]; the caster breaks a tie. */
    private suspend fun mostOf(all: List<PlayerState>, score: (PlayerState) -> Int): List<PlayerState> {
        if (all.isEmpty()) return emptyList()
        val top = all.maxOf(score)
        return pickOne(all.filter { score(it) == top })
    }

    /** 1 for a roll of 1-4, 2 for 5-9, 3 for 10 or more. */
    fun band(total: Int): Int = if (total <= 4) 1 else if (total <= 9) 2 else 3

    /** Copies the text of another card in the spell: its effect runs as if it were printed on this card. */
    suspend fun copyEffectOf(from: CardDef) {
        CardEffects.run(EffectContext(g, caster, card, from.type), from.id)
    }
}

