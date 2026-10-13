package esw.engine

import esw.model.CardType
import esw.model.Glyph

/**
 * What the 60 Expansion 1 spell cards do (20 Source, 20 Quality, 20 Delivery). The words are on the cards; see the JSON
 * files in "Expansion 1/cards". "Standee:" bonuses use [EffectContext.standee]; "Pay N Blood:" options are offered with
 * [EffectContext.pay], and the wizard may always say no.
 */
internal fun registerExpansionCards() = with(CardEffects) {
    // ---------------------------------------------------------------------- SOURCE

    def("gonzo-shroompusss") { heal(caster, roll(caster, "Gonzo Shroompuss's")) }

    def("mr-nukion-fussions") { right()?.let { dmg(it, 2) } }

    def("griz-glowballs") {
        if (pay(2, "Griz Glowball's: 3 damage to each foe instead")) dmgAll(foes(), 3) else left()?.let { dmg(it, 3) }
    }

    def("sir-mixs-a-littles") {
        val types = listOf(CardType.SOURCE, CardType.QUALITY, CardType.DELIVERY)
        val type = types[chooseOption("Pass which type of card?", types.map { it.name.lowercase().replaceFirstChar(Char::uppercase) })]
        val double = pay(4, "Sir Mixs-A-Little's: double the damage")
        val flip = flipped()
        val everyone = g.players.filter { it.alive }
        val passed = everyone.associateWith { p -> p.hand.filter { it.def.type == type }.toList() }
        val received = mutableMapOf<PlayerState, Int>()
        for ((p, cards) in passed) {
            val dest = (if (flip) g.rightOf(p) else g.leftOf(p)) ?: continue
            p.hand.removeAll(cards.toSet())
            dest.hand.addAll(cards)
            received[dest] = (received[dest] ?: 0) + cards.size
        }
        g.emit(GameEvent.Info("${received.values.sum()} ${type.name.lowercase()} cards were passed along"))
        for ((p, n) in received) if (n > 0) dmg(p, if (double) 2 * n else n)
    }

    def("goldie-locks") {
        val foe = choose(foes(), "foe to ask for a Creature")
        val creatures = foe.hand.filter { it.def.creature }
        val gave = creatures.isNotEmpty() &&
            g.decisions.chooseYesNo(foe.id, "Give ${caster.name} a Creature from your hand? If not, you take a die of damage.")
        if (gave) {
            val uid = g.decisions.chooseCardFromHand(foe.id, creatures, "Creature to give")
            val creature = creatures.firstOrNull { it.uid == uid } ?: error("Invalid card choice $uid")
            foe.hand.remove(creature)
            g.addToSpell(caster, creature)
        } else {
            dmg(foe, roll(caster, "Goldie Lock's"))
        }
    }

    def("a-d-h-deezis") {
        val previous = g.standeeHolder
        g.takeStandee(caster)
        if (previous != null && previous !== caster && previous.alive) {
            val others = foes().filter { it !== previous }
            if (others.isNotEmpty()) dmg(choose(others, "different foe to take 3 damage"), 3)
        }
    }

    def("baconus-the-butchers") {
        heal(caster, creaturesInPlay())
        heal(caster, payX("Baconus The Butcher's"))
    }

    def("the-pain-demons") {
        val a = g.dice.d6()
        val b = g.dice.d6()
        g.emit(GameEvent.DiceRolled(caster.id, "The Pain Demon's", listOf(a, b), a + b))
        val pick = chooseOption("The Pain Demon's: which die goes where?", listOf(
            "$a to the foe on your left, $b to you", "$b to the foe on your left, $a to you",
        ))
        val (toFoe, toSelf) = if (pick == 0) a to b else b to a
        val spare = standee || pay(1, "The Pain Demon's: spare yourself the damage")
        left()?.let { dmg(it, toFoe) }
        if (!spare) dmg(caster, toSelf)
    }

    def("queen-of-the-deads") { dmgAll(foes().filter { g.hasActed(it) }, 2) }

    def("mthgurgle-leachoteps") {
        val foe = left() ?: return@def
        val pays = foe.blood >= 2 && g.decisions.chooseYesNo(foe.id, "Give ${caster.name} 2 Blood instead of taking 3 damage?")
        if (pays) stealBlood(foe, 2) else dmg(foe, 3)
    }

    def("raw-hydras") {
        strongestFoe()?.let { dmg(it, 3) }
        if (pay(4, "Raw-Hydra's: 7 damage to each foe with a Last Wizard Standing token")) {
            dmgAll(foes().filter { it.tokens > 0 }, 7)
        }
    }

    def("burnin-bangers") {
        val both = pay(3, "Burnin' Banger's: do both")
        val pick = if (both) 0 else chooseOption("Burnin' Banger's", listOf(
            "Take the Standee and deal 1 damage to each foe", "Deal 3 damage to a foe who does not have the Standee",
        ))
        if (both || pick == 0) {
            g.takeStandee(caster)
            dmgAll(foes(), 1)
        }
        if (both || pick == 1) {
            val candidates = foes().filter { !g.hasStandee(it) }
            if (candidates.isNotEmpty()) dmg(choose(candidates, "foe to take 3 damage"), 3)
        }
    }

    def("the-sly-rollers") {
        val r = roll(caster, "The Sly Roller's")
        if (r <= 2 || r >= 5) g.takeStandee(caster)
        if (r >= 3) gainTreasure(caster)
    }

    def("visha-vindasmacks") { repeat(if (standee) 2 else 1) { addChosenHandCard() } }

    def("moochie-da-magic-missilers") {
        for (f in foes()) if (g.hasActed(f)) stealBlood(f, 1) else dmg(f, 2)
    }

    def("storklox-the-unmakers") {
        strongestFoe()?.let { dmg(it, 2) }
        if (pay(3, "Storklox The Unmaker's: add a random card from your hand")) addRandomHandCard()
    }

    def("dr-gravelovers") {
        g.takeStandee(caster)
        if (pay(1, "Dr. Gravelover's: 2 damage to each foe for each Last Wizard Standing token")) {
            dmgAll(foes(), 2 * g.players.sumOf { it.tokens })
        }
    }

    def("wiz-sleeve-steves") { dmg(choose(foes(), "foe to take 1 damage"), 1) }

    def("the-flaming-princes") {
        dmgAll(foes(), if (pay(5, "The Flaming Prince's: 5 damage instead")) 5 else 1)
    }

    def("togongo-valcandos") {
        val foe = strongestFoe() ?: return@def
        if (caster.blood > 0) {
            g.loseBlood(caster, 1, "given to ${foe.name}")
            g.gainBlood(foe, 1, "given by ${caster.name}")
        }
        dmg(foe, roll(caster, "Togongo Valcando's"))
        if (standee) {
            val others = foes().filter { it !== foe }
            if (others.isNotEmpty()) dmg(choose(others, "different foe to take a die of damage"), roll(caster, "Togongo Valcando's"))
        }
    }

    // ---------------------------------------------------------------------- QUALITY

    def("sadistic") {
        val foe = strongestFoe() ?: return@def
        if (dmg(foe, 3)) gainBlood(1, "Sadistic")
    }

    def("auto-erotic") {
        dmgAll(foes(), 1)
        if (foes().isNotEmpty() && pay(1, "Auto-Erotic: trade damage equal to Blood")) {
            val foe = choose(foes(), "foe to trade damage with")
            val mine = caster.blood
            val theirs = foe.blood
            dmg(foe, mine)
            dmg(caster, theirs)
        }
    }

    def("grubdiddling") {
        suspend fun demand(foe: PlayerState) {
            val arcane = foe.hand.filter { it.def.glyph == Glyph.ARCANE }
            val canTreasure = foe.treasures.isNotEmpty()
            val giveCard = when {
                arcane.isEmpty() && !canTreasure -> return
                arcane.isEmpty() -> false
                !canTreasure -> true
                else -> g.decisions.chooseOption(foe.id, "Give ${caster.name} an Arcane card or a Treasure?", listOf("An Arcane card", "A Treasure")) == 0
            }
            if (giveCard) {
                val uid = g.decisions.chooseCardFromHand(foe.id, arcane, "Arcane card to give")
                val card = arcane.firstOrNull { it.uid == uid } ?: error("Invalid card choice $uid")
                foe.hand.remove(card)
                caster.hand.add(card)
            } else {
                g.chooseTreasureOf(foe, foe, "Treasure to give")?.let { g.stealTreasure(caster, foe, it) }
            }
        }
        left()?.let { demand(it) }
        if (standee) right()?.let { if (it !== left()) demand(it) }
    }

    def("attackalanche") {
        val double = pay(2, "Attackalanche: double the damage")
        val order = if (flipped()) foes() else foes().reversed()
        order.forEachIndexed { i, f -> dmg(f, (i + 1) * if (double) 2 else 1) }
    }

    def("inflamerating") {
        val holder = g.standeeHolder?.takeIf { it.alive }
        if (holder == null) {
            dmgAll(g.players.filter { it.alive }, 4)
        } else if (pay(6, "Inflamerating: 8 damage to the Standee holder and 4 to each neighbour")) {
            dmg(holder, 8)
            for (n in listOfNotNull(g.leftOf(holder), g.rightOf(holder)).distinct()) dmg(n, 4)
        } else {
            dmg(holder, 4)
        }
    }

    def("in-graven") {
        val types = listOf(CardType.SOURCE, CardType.QUALITY, CardType.DELIVERY).filter { t -> caster.hand.any { it.def.type == t } }
        if (types.isEmpty()) return@def
        val names = types.map { it.name.lowercase().replaceFirstChar(Char::uppercase) } + "Discard nothing"
        val pick = chooseOption("In-Graven: discard which type of card?", names)
        if (pick >= types.size) return@def
        val type = types[pick]
        val available = caster.hand.count { it.def.type == type }
        val count = chooseOption("How many ${names[pick]} cards?", (1..available).map { "$it" }) + 1
        repeat(count) {
            val options = caster.hand.filter { it.def.type == type }
            val uid = g.decisions.chooseCardFromHand(caster.id, options, "card to discard")
            val card = options.firstOrNull { it.uid == uid } ?: error("Invalid card choice $uid")
            caster.hand.remove(card)
            g.discardToMain(card)
        }
        val foe = choose(foes(), "foe to take $count damage")
        dmg(foe, if (pay(4, "In-Graven: double the damage")) 2 * count else count)
    }

    def("low-balling") {
        val poor = foes().filter { it.treasures.isEmpty() }
        if (poor.isNotEmpty()) gainTreasure(choose(poor, "foe who draws a Treasure"))
        dmgAll(foes().filter { it.treasures.isNotEmpty() }, 2)
    }

    def("burnzerking") {
        val targets = if (pay(3, "Burnzerking: choose each foe")) foes() else listOf(choose(foes(), "foe to reveal their hand"))
        for (t in targets) {
            val creatures = t.hand.count { it.def.creature }
            g.emit(GameEvent.Info("${t.name} reveals a hand with $creatures Creature(s): ${t.hand.joinToString { it.def.name }}"))
            dmg(t, 2 * creatures)
        }
    }

    def("gifty") {
        val deliveries = caster.hand.filter { it.def.type == CardType.DELIVERY }
        if (deliveries.isNotEmpty()) {
            val uid = g.decisions.chooseCardFromHand(caster.id, deliveries, "Delivery to add to your spell")
            g.addToSpell(caster, deliveries.firstOrNull { it.uid == uid } ?: error("Invalid card choice $uid"))
        }
        if (pay(4, "Gifty: reveal cards until you find a Creature and add it")) addRevealedCreature()
    }

    def("woodstalky") {
        repeat(3) {
            val options = caster.hand.filter { it.def.glyph != Glyph.PRIMAL }
            if (options.isEmpty()) return@def
            val pick = chooseOption("Woodstalky: discard a non-Primal card to heal 1 HP?", options.map { it.def.name } + "Stop")
            if (pick >= options.size) return@def
            caster.hand.remove(options[pick])
            g.discardToMain(options[pick])
            heal(caster, 1)
        }
    }

    def("nonchalant") {
        val r = roll(caster, "Nonchalant")
        gainBlood(r, "Nonchalant")
        if (r <= 4) g.weakestFoe(caster)?.let { heal(it, r) }
    }

    def("mother-forking") {
        val previous = g.standeeHolder
        g.takeStandee(caster)
        val candidates = foes().filter { it !== previous }
        if (candidates.isEmpty()) return@def
        val foe = choose(candidates, "foe to give the Standee to")
        if (foe.creatures.isNotEmpty() && pay(3, "Mother-Forking: kill all of ${foe.name}'s Creatures first")) {
            for (c in foe.creatures.toList()) g.discardCreature(foe, c, "killed by Mother-Forking")
        }
        g.takeStandee(foe)
        dmg(foe, 3)
    }

    def("greedy") {
        repeat(2) { gainTreasure(caster) }
        for (f in foes().filter { !g.hasActed(it) }) gainTreasure(f)
        if (pay(2, "Greedy: each foe who has acted destroys a Treasure")) {
            for (f in foes().filter { g.hasActed(it) }) g.chooseTreasureOf(f, f, "Treasure to destroy")?.let { g.destroyTreasure(f, it) }
        }
    }

    def("pollinating") { repeat(if (standee) 2 else 1) { addRevealedCreature() } }

    def("cashed-in") {
        gainTreasure(caster)
        for (f in foes().filter { it.treasures.isEmpty() }) gainTreasure(f)
        if (pay(8, "Cashed In: 2 damage to each foe for each Treasure in play")) {
            dmgAll(foes(), 2 * g.players.sumOf { it.treasures.size })
        }
    }

    def("harvesting") {
        g.takeStandee(caster)
        val dead = g.deadCount()
        if (dead > 0) dmgAll(foes(), dead) else gainBlood(2, "Harvesting")
    }

    def("clonelising") {
        val both = standee
        val pick = if (both) 0 else chooseOption("Clonelising", listOf("2 damage to each foe with even HP", "3 damage to each foe with odd HP"))
        if (both || pick == 0) dmgAll(foes().filter { it.hp % 2 == 0 }, 2)
        if (both || pick == 1) dmgAll(foes().filter { it.hp % 2 == 1 }, 3)
    }

    def("even-steven") {
        val a = g.dice.d6()
        val b = g.dice.d6()
        g.emit(GameEvent.DiceRolled(caster.id, "Even Steven", listOf(a, b), a + b))
        val foe = choose(foes(), "foe to take ${minOf(a, b)} damage")
        dmg(foe, minOf(a, b))
        val others = foes().filter { it !== foe }
        if (others.isNotEmpty() && pay(2, "Even-Steven: ${maxOf(a, b)} damage to a different foe")) {
            dmg(choose(others, "different foe to take ${maxOf(a, b)} damage"), maxOf(a, b))
        }
    }

    def("sparkling") {
        val foe = randomFoe() ?: return@def
        dmg(foe, roll(caster, "Sparkling"))
        if (standee) {
            val others = foes().filter { it !== foe }
            if (others.isNotEmpty()) dmg(choose(others, "different foe to take 2 damage"), 2)
        }
    }

    def("infernalucky") {
        val rolls = foes().associateWith { roll(it, "Infernalucky") }
        val high = rolls.values.maxOrNull() ?: return@def
        for ((f, r) in rolls) {
            if (r == high) {
                g.gainBlood(f, 1, "Infernalucky")
                dmg(f, r)
            } else if (standee) {
                dmg(f, r)
            }
        }
    }

    // ---------------------------------------------------------------------- DELIVERY (Creatures)

    rolled("bob", Target.MOST_BLOOD) { band, targets ->
        for (t in targets) {
            if (band >= 2) dmg(t, if (band == 3) 4 else 1)
            stealBlood(t, 1)
        }
    }

    rolled("rock-n-roller", Target.STRONGEST) { band, targets ->
        for (t in targets) dmg(t, when (band) { 1 -> 1; 2 -> 2; else -> roll(caster, "Rock-N-Roller") })
    }

    rolled("draggen", Target.ALREADY_ACTED) { band, targets ->
        dmgAll(targets, if (band == 1) 1 else 3)
        if (band == 3) dmgAll(foes().filter { it.tokens > 0 }, 3)
    }

    rolled("land-shark", Target.HAS_STANDEE) { band, targets ->
        dmgAll(targets, when (band) { 1 -> 1; 2 -> 2; else -> 5 })
        if (band >= 2 && targets.isNotEmpty()) g.takeStandee(caster)
    }

    rolled("baron-squirrlington", Target.MOST_BLOOD) { band, targets ->
        dmgAll(targets, when (band) { 1 -> 0; 2 -> 2; else -> 4 })
        if (band == 3) heal(caster, 2)
    }

    rolled("mecha-satana", Target.WEAKEST) { band, targets ->
        when (band) {
            1 -> dmgAll(targets, 1)
            2 -> { dmgAll(targets, 2); dmg(caster, 1) }
            else -> { dmgAll(targets, 4); strongestFoe()?.let { dmg(it, 2) } }
        }
    }

    simple("bung-burner", Target.MOST_BLOOD, 2, 3, 4)

    rolled("sexsquatch", Target.ALREADY_ACTED) { band, targets ->
        dmgAll(targets, when (band) { 1 -> 2; 2 -> 3; else -> 4 })
        if (band == 3) dmgAll(foes().filter { it !in targets }, 1)
    }

    simple("fatcula", Target.STRONGEST, 1, 2, 4)

    rolled(
        "shub-niggurath", Target.ALREADY_ACTED,
        before = { if (pay(1, "Shub-Niggurath: add a die to each of your Creature Power Rolls this turn")) caster.creatureDiceThisTurn++ },
    ) { band, targets ->
        dmgAll(targets, when (band) { 1 -> 0; 2 -> 1; else -> 2 })
        if (band == 3) {
            val creatures = caster.hand.filter { it.def.creature }
            if (creatures.isNotEmpty()) {
                val uid = g.decisions.chooseCardFromHand(caster.id, creatures, "Creature to play next")
                g.addToSpell(caster, creatures.firstOrNull { it.uid == uid } ?: error("Invalid card choice $uid"))
            }
        }
    }

    rolled("tentakiller", Target.NO_CREATURES) { band, targets ->
        dmgAll(targets, band)
        if (band == 3) for (t in targets) g.chooseTreasureOf(caster, t, "Treasure to steal")?.let { g.stealTreasure(caster, t, it) }
    }

    simple("cockatwice", Target.STRONGEST, 0, 2, 4)

    def("fart-knight") {
        val split = g.expansion && pay(2, "Fart Knight: resolve each Power die separately, against different foes")
        val targets = if (split) emptyList() else targets(Target.CHOICE)
        val total = powerRoll()
        val band = band(total)
        g.emit(GameEvent.RollOutcome(caster.id, card.name, total, band))
        if (split) {
            val dice = g.lastPowerDice
            for (d in dice) {
                val foe = foes().ifEmpty { break }.let { choose(it, "foe to take ${if (d <= 4) 1 else 2} damage from the die showing $d") }
                dmg(foe, if (d <= 4) 1 else 2)
            }
            if (dice.any { it <= 4 }) keepCreature()
        } else {
            if (band == 1) keepCreature()
            dmgAll(targets, when (band) { 1 -> 1; 2 -> 2; else -> 4 })
        }
    }

    rolled("shpider-moonkey", Target.RIGHT) { band, targets ->
        dmgAll(targets, when (band) { 1 -> 1; 2 -> 2; else -> 4 })
        if (band == 3) gainBlood(1, "Shpider-Moonkey")
    }

    rolled("carnivorous-clowns", Target.MOST_CREATURES) { band, targets ->
        when (band) {
            1 -> dmg(caster, 1)
            2 -> for (t in targets) {
                if (t.creatures.isNotEmpty()) {
                    val pick = if (t.creatures.size == 1) 0 else chooseOption("Kill which of ${t.name}'s Creatures?", t.creatures.map { it.def.name })
                    g.discardCreature(t, t.creatures[pick], "killed by Carnivorous Clowns")
                }
                dmg(t, 3)
            }
            else -> dmgAll(targets, 4)
        }
    }

    rolled("pimped-imps", Target.WEAKEST) { band, targets ->
        when (band) {
            1 -> for (f in foes()) heal(f, 1)
            2 -> dmgAll(targets, 3)
            else -> { dmgAll(targets, 5); for (f in foes().filter { it !in targets }) heal(f, 1) }
        }
    }

    rolled("bitch-witch", Target.MOST_BLOOD) { band, targets ->
        dmgAll(targets, band)
        if (band >= 2) for (t in targets) g.loseBlood(t, band - 1, "Bitch-Witch")
    }

    rolled("jelly-square", Target.LEFT) { band, targets ->
        dmgAll(targets, when (band) { 1 -> 2; 2 -> 3; else -> 4 })
        if (band == 3) gainTreasure(caster)
    }

    rolled("hand-of-god", Target.MOST_TREASURE) { band, targets ->
        dmgAll(targets, when (band) { 1 -> 1; 2 -> 3; else -> 7 })
        if (band == 2) for (t in targets) g.chooseTreasureOf(t, t, "Treasure to destroy")?.let { g.destroyTreasure(t, it) }
    }

    rolled("baa-zerker", Target.NOT_STANDEE) { band, targets ->
        for (t in targets) dmg(t, when (band) { 1 -> 1; 2 -> 2; else -> if (foes().size == 1) 6 else 3 })
    }
}
