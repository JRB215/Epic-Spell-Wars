package esw.engine

import esw.model.CardType
import esw.model.Glyph

internal typealias Effect = suspend EffectContext.() -> Unit

/** What every spell card does, keyed by card id. The words are on the cards; see the JSON files in Base/cards. */
internal object CardEffects {
    private val table = HashMap<String, Effect>()

    val ids: Set<String> get() = table.keys

    suspend fun run(ctx: EffectContext, id: String) {
        val effect = table[id] ?: error("No effect written for card '$id'")
        ctx.effect()
    }

    private fun def(id: String, effect: Effect) {
        table[id] = effect
    }

    /** A Power Roll card: pick targets, roll, then act on the result band (1, 2 or 3). */
    private fun rolled(id: String, kind: Target, body: suspend EffectContext.(band: Int, targets: List<PlayerState>) -> Unit) =
        def(id) {
            val targets = targets(kind)
            val total = powerRoll()
            val band = band(total)
            // Tell the screens which result of the table applies, so they can show just that one.
            g.emit(GameEvent.RollOutcome(caster.id, g.catalog.allDefs.getValue(id).name, total, band))
            body(band, targets)
        }

    /** A Power Roll card that just deals fixed damage per band to its targets. */
    private fun simple(id: String, kind: Target, low: Int, mid: Int, high: Int) =
        rolled(id, kind) { band, targets -> dmgAll(targets, when (band) { 1 -> low; 2 -> mid; else -> high }) }

    init {
        // ------------------------------------------------------------------ SOURCE

        def("thal-foons") { dmgAll(foes().filter { g.hasActed(it) }, 3) }

        def("dr-rooty-barks") {
            heal(caster, 3)
            for (f in foes()) if (roll(f, "Dr. Rooty Bark's") == 6) heal(f, 3)
        }

        def("rose-bottoms") { heal(caster, distinctGlyphs()) }

        def("sphinxions") {
            val victim = foes().filter { it.treasures.isNotEmpty() }.let { if (it.isEmpty()) null else choose(it, "foe to steal from") }
            val stolen = victim?.let { g.chooseTreasureOf(caster, it, "Treasure to steal") }
            if (victim != null && stolen != null) g.stealTreasure(caster, victim, stolen)
        }

        def("pam-and-hecubas") { spell.deliveryEachFoe = true }

        def("king-oberons") { heal(caster, 2) }

        def("the-death-fairys") {
            while (caster.alive) {
                val all = foes()
                if (all.isEmpty()) break
                val target = choose(all, "foe to take 2 damage")
                dmg(target, 2)
                if (target.alive) break
            }
        }

        def("sir-lootzors") {
            gainTreasure(caster)
            for (f in foes()) if (roll(f, "Sir Lootzor's") == 6) gainTreasure(f)
        }

        def("ben-voodoos") {
            for (f in foes()) dmg(f, roll(f, "Ben Voodoo's"))
            g.chooseTreasureOf(caster, caster, "Treasure to destroy")?.let { g.destroyTreasure(caster, it) }
        }

        def("scorchias") { strongestFoe()?.let { dmg(it, 3) } }

        def("wyrmtors") { dmgAll(foes(), distinctGlyphs()) }

        def("professor-prestos") {
            randomFoe()?.let { dmg(it, 3) }
            if (spell.usedWildMagic) gainTreasure(caster)
        }

        def("magma-gogs") {
            val pick = chooseOption("Magma Gog's", listOf("3 damage to the foe on your left", "1 damage to each foe"))
            if (pick == 0) g.leftOf(caster)?.let { dmg(it, 3) } else dmgAll(foes(), 1)
        }

        def("bleemax-brainiacs") {
            val glyphs = g.glyphsInSpell(caster)
            val revealed = g.revealTop(2)
            val keep = revealed.map { c -> c.def.glyph.let { it != null && it in glyphs } }
            g.emit(GameEvent.DeckRevealed(caster.id, card.name, revealed.zip(keep) { c, k -> RevealedCard(c.def, k) }))
            revealed.zip(keep).forEach { (c, k) -> if (k) g.addToSpell(caster, c) else g.discardToMain(c) }
        }

        def("beard-o-blastys") {
            spell.cards.firstOrNull { it.slot == CardType.DELIVERY && !it.wild }?.let { copyEffectOf(it.card.def) }
        }

        def("pew-and-pews") {
            val revealed = g.revealTop(4)
            g.emit(GameEvent.DeckRevealed(caster.id, card.name, revealed.map { RevealedCard(it.def, it.def.type == CardType.SOURCE) }))
            for (c in revealed) if (c.def.type == CardType.SOURCE) g.addToSpell(caster, c) else g.discardToMain(c)
        }

        def("muzzlesnaps") { caster.extraDiceThisRound++ }

        def("old-scratchs") {
            val r = roll(caster, "Old Scratch's")
            if (r <= 3) dmg(caster, r) else heal(caster, r)
        }

        def("walker-time-rangers") {
            val everyone = g.players.filter { it.alive }
            val totals = everyone.associateWith { roll(it, "Walker Time Ranger's") + if (it === caster) distinctGlyphs() else 0 }
            val lowest = totals.values.min()
            for ((p, t) in totals) if (t == lowest) dmg(p, 3)
        }

        def("midnight-merlins") {
            strongestFoe()?.let { dmg(it, g.livingCount()) }
            g.drawDeadWizardQuietly(caster)
        }

        // ------------------------------------------------------------------ QUALITY

        def("mysterious") { g.rightOf(caster)?.let { dmg(it, distinctGlyphs() + caster.treasures.size) } }

        def("boulder-iffic") { foes().forEachIndexed { i, f -> dmg(f, i + 1) } }

        def("two-faced") {
            val chosen = choose(g.players.filter { it.alive }, "player to gain a Treasure")
            gainTreasure(chosen)
            dmg(chosen, 2 * chosen.treasures.size)
        }

        def("disco-mirrored") {
            val options = spell.cards.filter { !it.wild && (it.slot == CardType.SOURCE || it.slot == CardType.DELIVERY) }
            if (options.isNotEmpty()) {
                val pick = if (options.size == 1) 0 else chooseOption("Disco-Mirrored: copy which card?", options.map { it.card.def.name })
                copyEffectOf(options[pick].card.def)
            }
        }

        def("mind-altering") {
            val victim = randomFoe()
            victim?.let { dmg(it, 3) }
            gainTreasure(caster)
            victim?.let { gainTreasure(it) }
        }

        def("delicious") { for (f in foes().filter { it.hp % 2 == 1 }) dmg(f, distinctGlyphs()) }

        rolled("polished", Target.STRONGEST) { band, targets ->
            dmgAll(targets, when (band) { 1 -> 1; 2 -> 2; else -> 5 })
            if (band >= 2) gainTreasure(caster)
        }

        def("dicey") {
            val mine = listOf(g.dice.d6(), g.dice.d6())
            g.emit(GameEvent.DiceRolled(caster.id, "Dicey", mine, mine.sum()))
            for (f in foes()) {
                val theirs = roll(f, "Dicey")
                if (theirs in mine) dmg(f, theirs)
            }
        }

        def("cat-a-strophic") {
            val everyone = g.players.filter { it.alive }
            val totals = everyone.associateWith { roll(it, "Cat-A-Strophic") + if (it === caster) 2 else 0 }
            val high = totals.values.max()
            for ((p, t) in totals) if (t == high) gainTreasure(p) else dmg(p, t)
        }

        def("maggoty") { strongestFoe()?.let { dmg(it, 2 * glyphCount(Glyph.DARK)) } }

        rolled("ritualistic", Target.CHOICE) { band, targets ->
            when (band) { 1 -> dmg(caster, 3); 2 -> dmgAll(targets, 3); else -> dmgAll(targets, 5) }
        }

        def("impatient") { dmgAll(foes(), 1) }

        rolled("devilicious", Target.CHOICE) { band, targets ->
            when (band) {
                1 -> dmgAll(targets, 2)
                2 -> { dmgAll(targets, 4); dmg(caster, 1) }
                else -> { dmgAll(targets, 5); dmg(caster, 2) }
            }
        }

        rolled("prickly", Target.RIGHT) { band, targets ->
            when (band) {
                1 -> dmgAll(targets, 1)
                2 -> { dmgAll(targets, 1); heal(caster, 1) }
                else -> { dmgAll(targets, 3); heal(caster, 3) }
            }
        }

        rolled("explodifying", Target.CHOICE) { band, targets ->
            when (band) {
                1 -> dmgAll(targets, 1)
                2 -> { dmgAll(targets, 3); dmg(caster, 1) }
                else -> {
                    dmgAll(targets, 4)
                    for (t in targets) g.chooseTreasureOf(caster, t, "Treasure to destroy")?.let { g.destroyTreasure(t, it) }
                }
            }
        }

        def("thundering") {
            repeat(distinctGlyphs()) { randomFoe()?.let { dmg(it, 2) } }
        }

        def("ballsy") {
            for (f in listOfNotNull(g.leftOf(caster), g.rightOf(caster)).distinct()) {
                val canGive = f.treasures.isNotEmpty()
                val give = canGive && g.decisions.chooseYesNo(f.id, "Give one of your Treasures to ${caster.name} instead of taking 3 damage?")
                if (give) g.chooseTreasureOf(f, f, "Treasure to give")?.let { g.stealTreasure(caster, f, it) } else dmg(f, 3)
            }
        }

        def("mighty-gro") {
            heal(caster, 2)
            val lowest = g.players.filter { it.alive }.minOf { it.hp }
            if (caster.hp <= lowest) addRandomHandCard()
        }

        def("inferno-tastic") { dmgAll(foes(), glyphCount(Glyph.ELEMENTAL)) }

        def("festering") {
            val waiting = foes().filter { !g.hasActed(it) }
            if (waiting.isEmpty()) return@def
            val top = waiting.maxOf { it.hp }
            val victim = choose(waiting.filter { it.hp == top }, "foe who loses their Quality")
            val quality = victim.spell?.cards?.firstOrNull { it.slot == CardType.QUALITY } ?: return@def
            victim.spell!!.cards.remove(quality)
            // An unrevealed Proton Gem is a Treasure that stays with its owner; anything else is discarded.
            if (!(quality.protonGem && quality.wild)) g.discardToMain(quality.card)
        }

        // ------------------------------------------------------------------ DELIVERY

        simple("mercy-killing", Target.WEAKEST, 2, 3, 4)
        simple("chicken", Target.STRONGEST, 1, 1, 7)

        rolled("exorcism", Target.EACH_FOE) { band, targets ->
            if (band == 1) dmg(caster, 1)
            else for (t in targets) {
                val base = if (t.tokens > 0) 4 else 2
                dmg(t, if (band == 3) base * 2 else base)
            }
        }

        rolled("snakedance", Target.LEFT_AND_RIGHT) { band, targets ->
            dmgAll(targets, when (band) { 1 -> 1; 2 -> 2; else -> 2 * glyphCount(Glyph.PRIMAL) })
        }

        rolled("bedazzlement", Target.CHOICE) { band, targets ->
            dmgAll(targets, 1)
            if (band == 2) addRandomHandCard()
            if (band == 3) addChosenHandCard()
        }

        simple("phantasmagoons", Target.RIGHT, 1, 3, 4)

        rolled("dragon-hoard", Target.EACH_WITHOUT_TREASURES) { band, targets ->
            dmgAll(targets, band)
            if (band == 3) gainTreasure(caster)
        }

        rolled("fountain-of-youth", Target.SELF) { band, _ -> heal(caster, when (band) { 1 -> 0; 2 -> 2; else -> 4 }) }

        rolled("brain-suck", Target.CHOICE) { band, targets ->
            dmgAll(targets, when (band) { 1 -> 1; 2 -> 3; else -> 4 })
            if (band == 3) for (t in targets) g.chooseTreasureOf(caster, t, "Treasure to steal")?.let { g.stealTreasure(caster, t, it) }
        }

        rolled("pact-with-the-devil", Target.STRONGEST) { band, targets ->
            dmgAll(targets, if (band == 1) 1 else 2)
            if (band == 3) for (t in targets) {
                val stolen = t.spell?.cards?.firstOrNull { it.slot == CardType.DELIVERY && !it.wild } ?: continue
                t.spell!!.cards.remove(stolen)
                spell.cards.add(SpellCard(stolen.card, CardType.DELIVERY, wild = false))
                g.emit(GameEvent.CardAddedToSpell(caster.id, stolen.card.def.name))
            }
        }

        rolled("power-vortex", Target.EACH_FOE) { band, targets ->
            if (band == 1) { g.discardFromHand(caster, 1); return@rolled }
            dmgAll(targets, 2)
            g.discardFromHand(caster, 2)
            if (band == 3) gainTreasure(caster)
        }

        rolled("vorpal-trap", Target.LEFT) { band, targets ->
            dmgAll(targets, if (band == 1) 2 else 3)
            if (band == 3) for (t in targets) {
                val destroy = t.treasures.isNotEmpty() && g.decisions.chooseYesNo(t.id, "Destroy one of your Treasures instead of taking 3 more damage?")
                if (destroy) g.chooseTreasureOf(t, t, "Treasure to destroy")?.let { g.destroyTreasure(t, it) } else dmg(t, 3)
            }
        }

        simple("fist-o-nature", Target.LEFT, 1, 2, 4)
        simple("gore-nado", Target.STRONGEST, 2, 3, 6)

        rolled("testikill", Target.WEAKEST) { band, targets ->
            dmgAll(targets, when (band) { 1 -> 1; 2 -> 3; else -> 5 })
            if (band == 3) gainTreasure(caster)
        }

        rolled("nuke-u-lur-meltdown", Target.STRONGEST) { band, targets ->
            dmgAll(targets, when (band) { 1 -> 1; 2 -> 3; else -> 5 })
            if (band >= 2) for (t in targets) {
                for (neighbour in listOfNotNull(g.leftOf(t), g.rightOf(t)).distinct()) dmg(neighbour, 1)
            }
        }

        rolled("death-wish", Target.RIGHT) { band, targets ->
            dmgAll(targets, when (band) { 1 -> 2; 2 -> 3; else -> 5 })
            dmg(caster, 1)
        }

        simple("meatier-swarm", Target.EACH_STRONGER, 1, 3, 4)
        simple("lightning-bolt", Target.TWO_ON_LEFT, 1, 2, 4)

        rolled("cone-of-acid", Target.LEFT_AND_RIGHT) { band, targets ->
            dmgAll(targets, when (band) { 1 -> 1; 2 -> 2; else -> 4 })
            if (band == 3) gainTreasure(caster)
        }
    }
}
