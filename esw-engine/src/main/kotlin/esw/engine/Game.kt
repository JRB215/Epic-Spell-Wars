package esw.engine

import esw.model.CardCatalog
import esw.model.CardDef
import esw.model.CardType
import esw.model.Glyph
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.random.Random

/** Thrown when a game is over (one wizard or none left standing). Never leaves [Game]. */
internal class GameEnded(val winner: PlayerState?) : RuntimeException(null, null, false, false)

private const val HAND_SIZE = 8
private const val TOKENS_TO_WIN = 2
private const val MAX_ROUNDS_PER_GAME = 300
private const val MAX_GAMES_PER_MATCH = 60

/**
 * One match of Epic Spell Wars: games are played until a wizard has two Last Wizard Standing tokens.
 * A game is a series of rounds that lasts until one wizard is left alive.
 * The seat order is the order of [playerNames]; "left" is the next seat after you.
 */
class Game(
    val catalog: CardCatalog,
    playerNames: List<String>,
    internal val decisions: Decisions,
    internal val dice: Dice,
    internal val random: Random,
    private val sink: (GameEvent) -> Unit = {},
) {
    val players: List<PlayerState>

    private var nextUid = 1
    private val mainDeck = ArrayDeque<CardInstance>()
    private val mainDiscard = mutableListOf<CardInstance>()
    private val treasureDeck = ArrayDeque<CardInstance>()
    private val treasureDiscard = mutableListOf<CardInstance>()
    private val deadDeck = ArrayDeque<CardInstance>()
    private val deadDiscard = mutableListOf<CardInstance>()

    var gameNumber = 0
        private set
    var roundNumber = 0
        private set
    private var activeCaster: PlayerState? = null

    val maxHp: Int get() = catalog.maxHp

    init {
        require(playerNames.size in 2..6) { "Epic Spell Wars is played by 2 to 6 wizards" }
        val heroes = catalog.heroes.shuffled(random)
        players = playerNames.mapIndexed { i, name -> PlayerState(i, name, heroes[i]) }
        mainDeck.addAll(catalog.mainDeckCards().map { newCard(it) }.shuffled(random))
        treasureDeck.addAll(catalog.treasureCards().map { newCard(it) }.shuffled(random))
        deadDeck.addAll(catalog.deadWizardCards().map { newCard(it) }.shuffled(random))
    }

    private fun newCard(def: CardDef) = CardInstance(nextUid++, def)

    internal fun emit(event: GameEvent) = sink(event)

    // ------------------------------------------------------------------ match / game / round

    /** Plays the whole match and returns the id of the winner, or null if no one managed to win. */
    suspend fun playMatch(): Int? {
        emit(GameEvent.MatchStarted(players.map { it.name }))
        var games = 0
        while (players.none { it.tokens >= TOKENS_TO_WIN } && games < MAX_GAMES_PER_MATCH) {
            games++
            playGame()
        }
        val winner = players.firstOrNull { it.tokens >= TOKENS_TO_WIN }
        if (winner != null) emit(GameEvent.MatchWon(winner.id))
        return winner?.id
    }

    private suspend fun playGame() {
        gameNumber++
        roundNumber = 0
        emit(GameEvent.GameStarted(gameNumber))
        startGame()
        var winner: PlayerState? = null
        try {
            while (roundNumber < MAX_ROUNDS_PER_GAME) {
                roundNumber++
                playRound()
            }
            emit(GameEvent.Info("Nobody won game $gameNumber after $MAX_ROUNDS_PER_GAME rounds"))
        } catch (ended: GameEnded) {
            winner = ended.winner
        }
        endGame(winner)
    }

    private suspend fun startGame() {
        for (p in players) {
            mainDiscard.addAll(p.hand); p.hand.clear()
            treasureDiscard.addAll(p.treasures); p.treasures.clear()
            p.alive = true
            p.spell = null
            p.acted = false
            p.actLast = false
            p.extraDiceThisRound = 0
            p.firstTurnDie = false
            p.extraStartingCards = 0
            p.hp = catalog.startingHp
        }
        pendingBacklash.clear()
        // Dead Wizard cards collected last game give their bonuses now, then are discarded.
        val treasuresToGain = mutableMapOf<PlayerState, Int>()
        for (p in players) {
            for (c in p.deadWizardCards) {
                when (c.def.id) {
                    "giant-space-kingdom" -> p.hp += 2
                    "the-big-house" -> p.hp += 3
                    "afterlife-artifact" -> treasuresToGain.merge(p, 1, Int::plus)
                    "slag-shangri-la" -> p.firstTurnDie = true
                    "pixie-paradise" -> p.extraStartingCards += 2
                }
            }
            p.hp = minOf(p.hp, maxHp)
            deadDiscard.addAll(p.deadWizardCards); p.deadWizardCards.clear()
        }
        for ((p, n) in treasuresToGain) repeat(n) { gainTreasure(p) }
    }

    private fun endGame(winner: PlayerState?) {
        for (p in players) {
            discardSpell(p)
            p.spell = null
            // Wild Furicorn Meadow: set a Wild Magic card aside for next game.
            for (c in p.deadWizardCards) {
                if (c.def.id == "wild-furicorn-meadow") takeWildMagicFromDecks()?.let { p.reservedWildMagic.add(it) }
            }
        }
        if (winner != null) winner.tokens++
        emit(GameEvent.GameWon(winner?.id, winner?.tokens ?: 0))
    }

    private fun takeWildMagicFromDecks(): CardInstance? {
        val fromDeck = mainDeck.firstOrNull { it.def.type == CardType.WILD_MAGIC }
        if (fromDeck != null) { mainDeck.remove(fromDeck); return fromDeck }
        val fromDiscard = mainDiscard.firstOrNull { it.def.type == CardType.WILD_MAGIC } ?: return null
        mainDiscard.remove(fromDiscard)
        return fromDiscard
    }

    private suspend fun playRound() {
        emit(GameEvent.RoundStarted(roundNumber))
        for (p in players) {
            p.acted = false
            p.actLast = false
            p.extraDiceThisRound = 0
            p.spell = null
        }
        val living = players.filter { it.alive }
        for (p in living) {
            drawUpToHandSize(p)
            if (roundNumber == 1) {
                drawCards(p, p.extraStartingCards)
                p.extraStartingCards = 0
                p.hand.addAll(p.reservedWildMagic); p.reservedWildMagic.clear()
            }
        }
        for (p in players.filter { !it.alive }) drawDeadWizard(p)

        val choices = coroutineScope {
            living.map { p ->
                async { p to decisions.chooseSpell(p.id, p.hand.toList(), p.treasures.toList()) }
            }.awaitAll()
        }
        for ((p, choice) in choices) p.spell = buildSpell(p, choice)
        emit(GameEvent.SpellsLocked(living.map { LockedSpell(it.id, it.spell!!.cards.size, initiativeOf(it)) }))

        for (p in living) {
            if (p.has("methy-ions-backpack") && decisions.chooseYesNo(p.id, "Discard Methy-Ion's Backpack to act last this round?")) {
                destroyTreasure(p, p.treasures.first { it.def.id == "methy-ions-backpack" })
                p.actLast = true
            }
        }

        while (true) {
            val next = pickNextActor() ?: break
            takeTurn(next)
        }
        // Normally the game ends mid-round. This is only reached if everyone left alive has acted.
        for (p in players) { discardSpell(p); p.spell = null }
        if (players.count { it.alive } <= 1) throw GameEnded(players.firstOrNull { it.alive })
    }

    // ------------------------------------------------------------------ spells and turn order

    private fun buildSpell(p: PlayerState, choice: SpellChoice): Spell {
        val spell = Spell()
        val used = mutableSetOf<Int>()
        fun place(uid: Int?, slot: CardType) {
            if (uid == null) return
            require(used.add(uid)) { "${p.name} used a card twice" }
            val fromHand = p.hand.firstOrNull { it.uid == uid }
            if (fromHand != null) {
                val wild = fromHand.def.type == CardType.WILD_MAGIC
                require(wild || fromHand.def.type == slot) { "${fromHand.def.name} does not fit the $slot slot" }
                p.hand.remove(fromHand)
                spell.cards.add(SpellCard(fromHand, slot, wild))
                return
            }
            val gem = p.treasures.firstOrNull { it.uid == uid && it.def.id == "proton-gem" }
                ?: error("${p.name} chose a card that is not in hand: $uid")
            spell.cards.add(SpellCard(gem, slot, wild = true, protonGem = true))
        }
        place(choice.source, CardType.SOURCE)
        place(choice.quality, CardType.QUALITY)
        place(choice.delivery, CardType.DELIVERY)
        require(spell.cards.isNotEmpty() || p.hand.isEmpty()) { "${p.name} must play at least one card" }
        return spell
    }

    internal fun initiativeOf(p: PlayerState): Int {
        val delivery = p.spell?.cards?.firstOrNull { it.slot == CardType.DELIVERY }
        val base = if (delivery == null || delivery.wild) 0 else delivery.card.def.initiative ?: 0
        return base + if (p.has("methy-ions-backpack")) 10 else 0
    }

    private fun isImpatient(p: PlayerState) =
        p.spell?.cards?.any { it.slot == CardType.QUALITY && !it.wild && it.card.def.id == "impatient" } == true

    private suspend fun pickNextActor(): PlayerState? {
        val waiting = players.filter { it.alive && !it.acted && it.spell != null }
        if (waiting.isEmpty()) return null
        val pool = waiting.filter { !it.actLast }.ifEmpty { waiting }
        val order = compareBy<PlayerState>({ if (isImpatient(it)) 0 else 1 }, { it.spell!!.cards.size }, { -initiativeOf(it) })
        val best = pool.sortedWith(order).first()
        val tied = pool.filter { order.compare(it, best) == 0 }
        return if (tied.size == 1) best else rollOff(tied)
    }

    private fun rollOff(tied: List<PlayerState>): PlayerState {
        var group = tied
        while (true) {
            val rolls = group.associateWith { dice.d6() }
            for ((p, r) in rolls) emit(GameEvent.DiceRolled(p.id, "turn order tie-break", listOf(r), r))
            val high = rolls.values.max()
            group = group.filter { rolls.getValue(it) == high }
            if (group.size == 1) return group.first()
        }
    }

    private suspend fun takeTurn(p: PlayerState) {
        activeCaster = p
        emit(GameEvent.TurnStarted(p.id))
        if (p.has("plink-cannon")) leftOf(p)?.let { damage(it, 1, p) }
        if (p.alive) {
            revealSpell(p)
            resolveSpell(p)
        }
        resolvePendingBacklash()
        discardSpell(p)
        p.acted = true
        p.firstTurnDie = false
    }

    private suspend fun revealSpell(p: PlayerState) {
        val spell = p.spell!!
        val playedCards = spell.cards.size
        emit(GameEvent.SpellRevealed(p.id, spell.cards.map { if (it.wild) "Wild Magic" else it.card.def.name }))
        replaceWildMagic(p)
        if (spell.usedWildMagic && p.has("jokers-wild-codpiece")) {
            heal(p, 2)
            gainTreasure(p)
        }
        if (playedCards <= 2 && p.has("lady-lucks-garters")) {
            val candidates = p.hand.filter { it.def.type != CardType.WILD_MAGIC }
            if (candidates.isNotEmpty()) addToSpell(p, candidates[random.nextInt(candidates.size)])
        }
        for (holder in foesOf(p).filter { it.has("double-downer") }) {
            for (sc in spell.cards.toList()) {
                val match = holder.hand.firstOrNull { it.def.name == sc.card.def.name } ?: continue
                if (decisions.chooseYesNo(holder.id, "Discard ${match.def.name} to deal 2 damage to ${p.name} and heal 2?")) {
                    holder.hand.remove(match)
                    mainDiscard.add(match)
                    damage(p, 2, holder)
                    heal(holder, 2)
                }
            }
        }
    }

    private fun replaceWildMagic(p: PlayerState) {
        val spell = p.spell!!
        for (sc in spell.cards.toList()) {
            if (!sc.wild) continue
            spell.usedWildMagic = true
            val aside = mutableListOf<CardInstance>()
            var found: CardInstance? = null
            while (true) {
                val c = drawMain() ?: break
                if (c.def.type == sc.slot) { found = c; break } else aside.add(c)
            }
            if (!sc.protonGem) mainDiscard.add(sc.card)
            mainDiscard.addAll(aside)
            // (a Proton Gem is a Treasure and stays with its owner)
            if (found == null) {
                spell.cards.remove(sc)
            } else {
                sc.card = found
                sc.wild = false
            }
            emit(GameEvent.WildMagicResolved(p.id, sc.slot.name, found?.def?.name))
        }
    }

    private suspend fun resolveSpell(p: PlayerState) {
        val spell = p.spell!!
        while (p.alive) {
            val next = spell.cards.filter { !it.resolved }
                .minWithOrNull(compareBy({ it.slot.ordinal }, { spell.cards.indexOf(it) }))
                ?: break
            next.resolved = true
            emit(GameEvent.CardResolving(p.id, next.card.def.name))
            if (next.card.def.glyph == Glyph.DARK) {
                for (holder in foesOf(p).filter { it.has("crown-of-the-meek") }) damage(p, 1, holder)
            }
            if (!p.alive) break
            CardEffects.run(EffectContext(this, p, next.card.def, next.card.def.type), next.card.def.id)
            resolvePendingBacklash()
        }
    }

    internal fun discardSpell(p: PlayerState) {
        val spell = p.spell ?: return
        for (sc in spell.cards) if (!(sc.protonGem && sc.wild)) mainDiscard.add(sc.card)
        spell.cards.clear()
    }

    /** Adds a card to the spell as an extra component. Taken out of the hand if it was there. */
    internal fun addToSpell(p: PlayerState, card: CardInstance) {
        p.hand.remove(card)
        p.spell!!.cards.add(SpellCard(card, card.def.type, wild = false))
        emit(GameEvent.CardAddedToSpell(p.id, card.def.name))
    }

    internal fun hasActed(p: PlayerState) = p.acted

    internal fun isLastToAct(p: PlayerState) = players.none { it !== p && it.alive && !it.acted }

    // ------------------------------------------------------------------ seats and targets

    /** Every living wizard except [p], starting with the one on p's left. */
    internal fun foesOf(p: PlayerState): List<PlayerState> {
        val n = players.size
        return (1 until n).map { players[(p.id + it) % n] }.filter { it.alive }
    }

    internal fun leftOf(p: PlayerState): PlayerState? = foesOf(p).firstOrNull()

    internal fun rightOf(p: PlayerState): PlayerState? = foesOf(p).lastOrNull()

    internal fun livingCount() = players.count { it.alive }

    internal fun deadCount() = players.count { !it.alive }

    internal suspend fun strongestFoe(p: PlayerState): PlayerState? {
        val foes = foesOf(p)
        if (foes.isEmpty()) return null
        val top = foes.maxOf { it.hp }
        return pickAmong(p, foes.filter { it.hp == top }, "strongest foe (tie)")
    }

    internal suspend fun weakestFoe(p: PlayerState): PlayerState? {
        val foes = foesOf(p)
        if (foes.isEmpty()) return null
        val low = foes.minOf { it.hp }
        return pickAmong(p, foes.filter { it.hp == low }, "weakest foe (tie)")
    }

    internal suspend fun pickAmong(chooser: PlayerState, candidates: List<PlayerState>, reason: String): PlayerState {
        if (candidates.size == 1) return candidates.first()
        val id = decisions.choosePlayer(chooser.id, candidates.map { it.id }, reason)
        require(candidates.any { it.id == id }) { "Invalid choice $id for $reason" }
        return players[id]
    }

    /** The server rolls a die for "random foe": foes from the left get equal shares of the numbers 1-6. */
    internal fun randomFoe(p: PlayerState): PlayerState? {
        val foes = foesOf(p)
        if (foes.isEmpty()) return null
        val usable = (6 / foes.size) * foes.size
        while (true) {
            val roll = dice.d6()
            if (roll <= usable) {
                emit(GameEvent.DiceRolled(p.id, "random foe", listOf(roll), roll))
                return foes[(roll - 1) / (usable / foes.size)]
            }
        }
    }

    // ------------------------------------------------------------------ damage, healing, death

    internal fun damage(target: PlayerState, amount: Int, source: PlayerState?) {
        if (amount <= 0 || !target.alive) return
        target.hp = maxOf(0, target.hp - amount)
        emit(GameEvent.DamageDealt(target.id, amount, source?.id, target.hp))
        if (target.hp == 0) die(target)
        checkGameEnd()
    }

    internal fun heal(target: PlayerState, amount: Int) {
        if (amount <= 0 || !target.alive) return
        val healed = minOf(amount, maxHp - target.hp)
        if (healed <= 0) return
        target.hp += healed
        emit(GameEvent.Healed(target.id, healed, target.hp))
    }

    private fun die(p: PlayerState) {
        p.alive = false
        mainDiscard.addAll(p.hand); p.hand.clear()
        treasureDiscard.addAll(p.treasures); p.treasures.clear()
        emit(GameEvent.PlayerDied(p.id))
        drawDeadWizardQuietly(p)
    }

    private fun checkGameEnd() {
        if (livingCount() <= 1) throw GameEnded(players.firstOrNull { it.alive } ?: activeCaster)
    }

    // ------------------------------------------------------------------ decks, hands, treasures

    internal fun drawMain(): CardInstance? {
        if (mainDeck.isEmpty() && mainDiscard.isNotEmpty()) {
            mainDeck.addAll(mainDiscard.shuffled(random)); mainDiscard.clear()
        }
        return mainDeck.removeFirstOrNull()
    }

    internal fun revealTop(count: Int): List<CardInstance> = List(count) { drawMain() }.filterNotNull()

    internal fun discardToMain(card: CardInstance) { mainDiscard.add(card) }

    internal fun drawCards(p: PlayerState, count: Int) {
        repeat(count) { drawMain()?.let { p.hand.add(it) } }
    }

    private fun handSizeOf(p: PlayerState) = HAND_SIZE + if (p.has("thinking-cap")) 1 else 0

    private fun drawUpToHandSize(p: PlayerState) {
        while (p.hand.size < handSizeOf(p)) p.hand.add(drawMain() ?: break)
    }

    private fun drawTreasureCard(): CardInstance? {
        if (treasureDeck.isEmpty() && treasureDiscard.isNotEmpty()) {
            treasureDeck.addAll(treasureDiscard.shuffled(random)); treasureDiscard.clear()
        }
        return treasureDeck.removeFirstOrNull()
    }

    internal fun gainTreasure(p: PlayerState) {
        if (!p.alive) return
        val card = drawTreasureCard() ?: return
        receiveTreasure(p, card, null)
    }

    private fun receiveTreasure(p: PlayerState, card: CardInstance, from: Int?) {
        p.treasures.add(card)
        emit(GameEvent.TreasureGained(p.id, card.def.name, from))
        if (card.def.id == "thinking-cap") drawCards(p, 1)
    }

    internal fun stealTreasure(thief: PlayerState, victim: PlayerState, card: CardInstance) {
        if (!victim.treasures.remove(card)) return
        emit(GameEvent.TreasureLost(victim.id, card.def.name, destroyed = false))
        if (thief.alive) receiveTreasure(thief, card, victim.id) else treasureDiscard.add(card)
    }

    internal fun destroyTreasure(owner: PlayerState, card: CardInstance) {
        if (!owner.treasures.remove(card)) return
        treasureDiscard.add(card)
        emit(GameEvent.TreasureLost(owner.id, card.def.name, destroyed = true))
    }

    internal suspend fun chooseTreasureOf(chooser: PlayerState, owner: PlayerState, reason: String): CardInstance? {
        if (owner.treasures.isEmpty()) return null
        if (owner.treasures.size == 1) return owner.treasures.first()
        val uid = decisions.chooseTreasure(chooser.id, owner.treasures.toList(), reason)
        return owner.treasures.firstOrNull { it.uid == uid } ?: error("Invalid treasure choice $uid")
    }

    internal suspend fun discardFromHand(p: PlayerState, count: Int) {
        repeat(count) {
            if (p.hand.isEmpty()) return
            val uid = decisions.chooseCardFromHand(p.id, p.hand.toList(), "card to discard")
            val card = p.hand.firstOrNull { it.uid == uid } ?: error("Invalid card choice $uid")
            p.hand.remove(card)
            mainDiscard.add(card)
        }
    }

    // ------------------------------------------------------------------ dead wizards

    private fun drawDeadWizardCard(): CardInstance? {
        if (deadDeck.isEmpty() && deadDiscard.isNotEmpty()) {
            deadDeck.addAll(deadDiscard.shuffled(random)); deadDiscard.clear()
        }
        return deadDeck.removeFirstOrNull()
    }

    /** Used by dying wizards and by Midnight Merlin's. Backlash from Beyond hits at once. */
    internal fun drawDeadWizardQuietly(p: PlayerState) {
        val card = drawDeadWizardCard() ?: return
        emit(GameEvent.DeadWizardDrawn(p.id, card.def.name))
        if (card.def.id == "backlash-from-beyond") {
            deadDiscard.add(card)
            pendingBacklash.add(p)
        } else {
            p.deadWizardCards.add(card)
        }
    }

    /** Backlash from Beyond needs a player's choice, so it is queued and resolved at the next safe point. */
    private val pendingBacklash = mutableListOf<PlayerState>()

    internal suspend fun drawDeadWizard(p: PlayerState) {
        drawDeadWizardQuietly(p)
        resolvePendingBacklash()
    }

    internal suspend fun resolvePendingBacklash() {
        while (pendingBacklash.isNotEmpty()) {
            val source = pendingBacklash.removeAt(0)
            val victims = players.filter { it.alive && it !== source }
            if (victims.isEmpty()) continue
            val target = pickAmong(source, victims, "Backlash from Beyond: foe to take 2 damage")
            damage(target, 2, source)
        }
    }

    // ------------------------------------------------------------------ power rolls and dice

    /** A single die roll for [p]. Lady Luck's Panties adds 2 when only one die is rolled. */
    internal fun rollSingle(p: PlayerState, reason: String): Int {
        val raw = dice.d6()
        val total = raw + if (p.has("lady-lucks-panties")) 2 else 0
        emit(GameEvent.DiceRolled(p.id, reason, listOf(raw), total))
        return total
    }

    internal fun glyphCount(p: PlayerState, glyph: Glyph): Int {
        val inSpell = p.spell?.cards?.count { !it.wild && it.card.def.glyph == glyph } ?: 0
        return inSpell + p.treasures.count { it.def.countsAsGlyph == glyph }
    }

    internal fun distinctGlyphs(p: PlayerState): Int {
        val seen = mutableSetOf<Glyph>()
        p.spell?.cards?.forEach { if (!it.wild) it.card.def.glyph?.let(seen::add) }
        p.treasures.forEach { it.def.countsAsGlyph?.let(seen::add) }
        return seen.size
    }

    internal fun glyphsInSpell(p: PlayerState): Set<Glyph> {
        val seen = mutableSetOf<Glyph>()
        p.spell?.cards?.forEach { if (!it.wild) it.card.def.glyph?.let(seen::add) }
        p.treasures.forEach { it.def.countsAsGlyph?.let(seen::add) }
        return seen
    }

    /**
     * Power Roll: one die for each card in the spell with [glyph] (Treasures that count as that glyph included),
     * plus extra dice and flat bonuses from Treasures. [rollType] is the kind of card whose text is rolling.
     */
    internal suspend fun powerRoll(p: PlayerState, glyph: Glyph, rollType: CardType): Int {
        var diceCount = glyphCount(p, glyph) + p.extraDiceThisRound
        if (rollType == CardType.DELIVERY && p.has("phister-cannon")) diceCount++
        if (rollType == CardType.QUALITY && p.has("skullzor-ring-of-power")) diceCount += 2
        if (p.has("slow-rollers-throne") && isLastToAct(p)) diceCount++
        if (p.has("desperation-stones") && p.hp <= 9) diceCount++
        if (p.firstTurnDie) diceCount++
        diceCount = maxOf(diceCount, 1)

        var rolled = MutableList(diceCount) { dice.d6() }
        if (p.has("cheater-handbook")) {
            val options = rolled.map { "Reroll the $it" } + "Keep these dice"
            val pick = decisions.chooseOption(p.id, "Cheater's Handbook: reroll one die?", options)
            require(pick in options.indices) { "Invalid option $pick" }
            if (pick < rolled.size) rolled[pick] = dice.d6()
        }
        var total = rolled.sum() + flatBonus(p, diceCount)
        if (p.has("lady-lucks-brassiere") &&
            decisions.chooseYesNo(p.id, "Discard Lady Luck's Brassiere to reroll (total so far $total)?")
        ) {
            destroyTreasure(p, p.treasures.first { it.def.id == "lady-lucks-brassiere" })
            rolled = MutableList(diceCount) { dice.d6() }
            total = rolled.sum() + flatBonus(p, diceCount)
            gainTreasure(p)
        }
        emit(GameEvent.DiceRolled(p.id, "Power Roll", rolled.toList(), total))
        if (p.has("amulet-of-maneg")) heal(p, rolled.count { it == 6 })
        if (total <= 4) for (h in foesOf(p).filter { it.has("fools-gold") }) heal(h, 2)
        return total
    }

    private fun flatBonus(p: PlayerState, diceCount: Int): Int {
        var flat = 0
        if (p.has("kingor-crown")) flat += distinctGlyphs(p)
        if (p.has("big-book-of-awesomeness")) flat += 2
        if (p.has("lets-end-this")) flat += 2 * deadCount()
        if (p.has("lady-lucks-panties") && diceCount == 1) flat += 2
        return flat
    }

    // ------------------------------------------------------------------ hooks for tests

    internal class RoundOutcome(val ended: Boolean, val winner: Int?)

    /** Starts a game without playing it, so a test can set up exact hands before a round. */
    internal suspend fun beginGameForTest() {
        gameNumber++
        startGame()
    }

    /** Puts a brand-new card of the given design into a hand. It is outside the card-count audit. */
    internal fun giveToHand(p: PlayerState, defId: String): CardInstance {
        val card = newCard(catalog.allDefs.getValue(defId))
        p.hand.add(card)
        return card
    }

    internal fun giveDeadWizard(p: PlayerState, defId: String): CardInstance {
        val card = newCard(catalog.allDefs.getValue(defId))
        p.deadWizardCards.add(card)
        return card
    }

    internal fun giveTreasure(p: PlayerState, defId: String): CardInstance {
        val card = newCard(catalog.allDefs.getValue(defId))
        p.treasures.add(card)
        return card
    }

    internal suspend fun playRoundForTest(): RoundOutcome {
        roundNumber++
        return try {
            playRound()
            RoundOutcome(false, null)
        } catch (ended: GameEnded) {
            endGame(ended.winner)
            RoundOutcome(true, ended.winner?.id)
        }
    }

    // ------------------------------------------------------------------ bookkeeping for tests

    /**
     * Checks that no card has been lost or duplicated. Only call between rounds, when no spells are in play.
     * Returns a description of the problem, or null if everything adds up.
     */
    fun audit(): String? {
        val main = (mainDeck + mainDiscard + players.flatMap { it.hand + it.reservedWildMagic } +
            players.flatMap { p -> p.spell?.cards?.filter { !(it.protonGem && it.wild) }?.map { it.card }.orEmpty() })
        val treasures = treasureDeck + treasureDiscard + players.flatMap { it.treasures }
        val dead = deadDeck + deadDiscard + players.flatMap { it.deadWizardCards }
        fun check(name: String, cards: List<CardInstance>, expected: Int): String? {
            if (cards.size != expected) return "$name has ${cards.size} cards, expected $expected"
            if (cards.map { it.uid }.toSet().size != cards.size) return "$name contains a duplicated card"
            return null
        }
        return check("main deck", main, catalog.mainDeckCards().size)
            ?: check("treasures", treasures, catalog.treasureCards().size)
            ?: check("dead wizards", dead, catalog.deadWizardCards().size)
            ?: players.firstOrNull { it.hp < 0 || it.hp > maxHp }?.let { "${it.name} has ${it.hp} HP" }
    }
}
