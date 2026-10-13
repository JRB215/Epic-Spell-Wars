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
private const val MAX_BLOOD = 25

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

    /** True when the Expansion 1 rules (Blood, the Standee, Creatures, Reactions) are in play. */
    val expansion: Boolean get() = catalog.mode.expansionRules

    /** Who holds the Standee, or null while it sits in the middle of the table. A dead wizard can hold it. */
    var standeeHolder: PlayerState? = null
        private set

    /** Most Wanted Poster: a Treasure lying in the middle of the table until someone slays a foe. */
    internal var poster: CardInstance? = null

    /** The first wizard slain this game (Classy in Repose asks about it). */
    private var firstKilled: PlayerState? = null

    /** Who won the Last Wizard Standing token last game (Lingering Curse targets them). */
    private var lastGameWinner: PlayerState? = null

    /** The card on top of the discard pile, face up for everyone to see. */
    val mainDiscardTop: CardInstance? get() = mainDiscard.lastOrNull()

    val mainDeckSize: Int get() = mainDeck.size
    val mainDiscardSize: Int get() = mainDiscard.size
    val treasureDeckSize: Int get() = treasureDeck.size
    val deadWizardDeckSize: Int get() = deadDeck.size

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

    // ------------------------------------------------------------------ Blood and the Standee (Expansion 1)

    /** Adds Blood, up to 25. Does nothing in the base game. */
    internal fun gainBlood(p: PlayerState, amount: Int, reason: String) {
        if (!expansion || amount <= 0) return
        val gained = minOf(amount, MAX_BLOOD - p.blood)
        if (gained <= 0) return
        p.blood += gained
        emit(GameEvent.BloodChanged(p.id, gained, p.blood, reason))
    }

    /** Removes up to [amount] Blood and returns how much was actually taken. */
    internal fun loseBlood(p: PlayerState, amount: Int, reason: String): Int {
        val lost = minOf(amount, p.blood)
        if (lost <= 0) return 0
        p.blood -= lost
        emit(GameEvent.BloodChanged(p.id, -lost, p.blood, reason))
        return lost
    }

    internal fun stealBlood(thief: PlayerState, victim: PlayerState, amount: Int) {
        val taken = loseBlood(victim, amount, "stolen by ${thief.name}")
        gainBlood(thief, taken, "stolen from ${victim.name}")
    }

    /** Asks the wizard whether to pay [cost] Blood for [what]. Returns true if they paid. Free of cost when it cannot be paid. */
    internal suspend fun payBlood(p: PlayerState, cost: Int, what: String): Boolean {
        if (!expansion || cost <= 0 || p.blood < cost) return false
        if (!decisions.chooseYesNo(p.id, "Pay $cost Blood: $what?")) return false
        loseBlood(p, cost, what)
        return true
    }

    /** A Blood payment on a Treasure: once per round, only on the owner's turn. */
    internal suspend fun payForTreasure(p: PlayerState, treasure: CardInstance, cost: Int, what: String): Boolean {
        if (!expansion || treasure.uid in p.paidThisRound || p.blood < cost) return false
        if (!payBlood(p, cost, what)) return false
        p.paidThisRound.add(treasure.uid)
        return true
    }

    internal fun takeStandee(p: PlayerState) {
        if (!expansion || standeeHolder === p) return
        standeeHolder = p
        emit(GameEvent.StandeeMoved(p.id))
    }

    internal fun hasStandee(p: PlayerState) = expansion && standeeHolder === p

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
            // The round ends along with the game, so the Standee still pays out its Blood.
            try { endOfRound() } catch (_: GameEnded) { /* nothing left to decide */ }
        }
        endGame(winner)
    }

    private var roundClosed = false

    /** What happens when a round is over (Expansion 1 only). It runs once per round, even if the game ends first. */
    private suspend fun endOfRound() {
        if (!expansion || roundClosed) return
        roundClosed = true
        val living = players.filter { it.alive }
        for (p in living) {
            if (p.has("the-f-u-suit") && p.hp == living.minOf { it.hp }) {
                heal(p, 2)
                gainTreasure(p)
            }
        }
        standeeHolder?.let { h ->
            val gain = if (!h.alive && h.deadWizardCards.any { it.def.id == "grave-terrain" }) 4 else 1
            gainBlood(h, gain, "holding the Standee")
            if (h.has("idol-of-the-magic-pussy")) gainBlood(h, 1, "Idol of the Magic Pussy")
        }
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
            p.bonusDie = null
            p.extraStartingCards = 0
            p.hp = catalog.startingHp
            p.prediction = null
            p.zapThisGame = 0
            p.startHandPenalty = 0
            p.paidThisRound.clear()
            p.rerolledCreatureThisTurn = false
        }
        pendingBacklash.clear()
        // Dead Wizard cards collected last game give their bonuses now, then are discarded.
        val treasuresToGain = mutableMapOf<PlayerState, Int>()
        val petsToReveal = mutableListOf<PlayerState>()
        for (p in players) {
            var slagDice = 0
            for (c in p.deadWizardCards) {
                when (c.def.id) {
                    "giant-space-kingdom" -> p.hp += 2
                    "the-big-house" -> p.hp += 3
                    "afterlife-artifact" -> treasuresToGain.merge(p, 1, Int::plus)
                    "slag-shangri-la" -> slagDice++
                    "pixie-paradise" -> p.extraStartingCards += 2
                    // Expansion 1 cards that work at the start of the next game
                    "pet-from-beyond" -> petsToReveal.add(p)
                    "hounds-from-hell" -> p.houndsDice++
                    "zap-o-rama" -> p.zapThisGame++
                    "lingering-curse" -> lastGameWinner?.let { it.startHandPenalty += 2 }
                }
            }
            if (p.treasureNextGame > 0) {
                treasuresToGain.merge(p, p.treasureNextGame, Int::plus)
                p.treasureNextGame = 0
            }
            if (slagDice > 0) {
                // Slag Shangri-La: each card puts a die on the character card. They are rolled now, shown to
                // everyone, and their total is added to each Power Roll made on this wizard's first turn.
                val rolled = List(slagDice) { dice.d6() }
                p.bonusDie = rolled.sum()
                emit(GameEvent.DiceRolled(p.id, "Slag Shangri-La: bonus die for the first turn", rolled, rolled.sum()))
            }
            p.hp = minOf(p.hp, maxHp)
            deadDiscard.addAll(p.deadWizardCards); p.deadWizardCards.clear()
        }
        for ((p, n) in treasuresToGain) repeat(n) { gainTreasure(p) }
        for (p in petsToReveal) startWithCreature(p)
    }

    /** Pet from Beyond: reveal cards until a Creature turns up, and the wizard starts the game with it in play. */
    private fun startWithCreature(p: PlayerState) {
        val aside = mutableListOf<CardInstance>()
        var found: CardInstance? = null
        while (true) {
            val c = drawMain() ?: break
            if (c.def.creature) { found = c; break } else aside.add(c)
        }
        mainDiscard.addAll(aside)
        val shown = aside.map { RevealedCard(it.def, false) } + listOfNotNull(found?.let { RevealedCard(it.def, true) })
        if (shown.isNotEmpty()) emit(GameEvent.DeckRevealed(p.id, "Pet from Beyond", shown))
        found?.let {
            p.creatures.add(it)
            emit(GameEvent.CreatureKept(p.id, it.def.name))
        }
    }

    private fun endGame(winner: PlayerState?) {
        // Creatures and the Standee do not survive the game; Treasures lying in the middle go too.
        for (p in players) {
            for (c in p.creatures) { mainDiscard.add(c); emit(GameEvent.CreatureLost(p.id, c.def.name, "the game ended")) }
            p.creatures.clear()
            p.regalia?.let { mainDiscard.add(it); p.regalia = null }
        }
        poster?.let { treasureDiscard.add(it); poster = null }
        if (standeeHolder != null) { standeeHolder = null; emit(GameEvent.StandeeMoved(null)) }
        firstKilled = null
        lastGameWinner = winner
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
        roundClosed = false
        for (p in players) {
            p.acted = false
            p.actLast = false
            p.extraDiceThisRound = 0
            p.spell = null
            p.paidThisRound.clear()
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
        emit(GameEvent.HandsDealt)

        val choices = coroutineScope {
            living.map { p ->
                async { p to decisions.chooseSpell(p.id, p.hand.toList(), p.treasures.toList()) }
            }.awaitAll()
        }
        for ((p, choice) in choices) p.spell = buildSpell(p, choice)

        for (p in living) {
            if (p.has("methy-ions-backpack") && decisions.chooseYesNo(p.id, "Discard Methy-Ion's Backpack to act last this round?")) {
                destroyTreasure(p, p.treasures.first { it.def.id == "methy-ions-backpack" })
                p.actLast = true
            }
        }
        emit(GameEvent.SpellsLocked(turnOrderPreview(living)))

        while (true) {
            val next = pickNextActor() ?: break
            takeTurn(next)
        }
        // Normally the game ends mid-round. This is only reached if everyone left alive has acted.
        for (p in players) { discardSpell(p); p.spell = null }
        endOfRound()
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

    /**
     * The order the wizards will act in, as announced once every spell is locked: Impatient first, then fewer cards
     * before more, then the higher Initiative, and wizards who chose to act last at the end. Wizards who tie on all of
     * that are flagged, because they will roll a die when their turn comes.
     */
    private fun turnOrderPreview(living: List<PlayerState>): List<LockedSpell> {
        data class Key(val last: Boolean, val impatient: Boolean, val components: Int, val initiative: Int)
        fun key(p: PlayerState) = Key(p.actLast, isImpatient(p), p.spell!!.cards.size, initiativeOf(p))
        val sorted = living.sortedWith(
            compareBy<PlayerState>({ key(it).last }, { if (key(it).impatient) 0 else 1 }, { key(it).components }, { -key(it).initiative }),
        )
        val counts = sorted.groupingBy { key(it) }.eachCount()
        return sorted.map {
            val k = key(it)
            LockedSpell(it.id, k.components, k.initiative, impatient = k.impatient, actsLast = k.last, tied = (counts[k] ?: 0) > 1)
        }
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
        p.spell?.let { spell ->
            emit(GameEvent.SpellFinished(p.id, spell.cards.sortedBy { it.slot.ordinal }.map { it.card.def }))
        }
        discardSpell(p)
        p.acted = true
        p.bonusDie = null // only the first turn gets it
    }

    private suspend fun revealSpell(p: PlayerState) {
        val spell = p.spell!!
        val playedCards = spell.cards.size
        spell.revealed = true
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
            val shown = aside.map { RevealedCard(it.def, false) } + listOfNotNull(found?.let { RevealedCard(it.def, true) })
            if (shown.isNotEmpty()) emit(GameEvent.DeckRevealed(p.id, "Wild Magic", shown))
            emit(GameEvent.WildMagicResolved(p.id, sc.slot.name, found?.def?.name))
        }
    }

    private suspend fun resolveSpell(p: PlayerState) {
        val spell = p.spell!!
        while (p.alive) {
            val waiting = spell.cards.filter { !it.resolved }
            val slot = waiting.minOfOrNull { it.slot.ordinal } ?: break
            val sameType = waiting.filter { it.slot.ordinal == slot }
            // Several unresolved cards of one type (added by an effect): the owner picks the order.
            val next = if (sameType.size == 1) {
                sameType.first()
            } else {
                val names = sameType.map { it.card.def.name }
                val pick = decisions.chooseOption(p.id, "Resolve which ${sameType.first().slot.name.lowercase()} first?", names)
                require(pick in names.indices) { "Invalid option $pick" }
                sameType[pick]
            }
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

    /**
     * A wizard takes damage. [source] is whoever caused it (null if nobody did). In Expansion 1 a face-up Creature can
     * soak up damage from a foe, and a Reaction can save a wizard who would otherwise die.
     */
    internal suspend fun damage(target: PlayerState, amount: Int, source: PlayerState?) {
        if (amount <= 0 || !target.alive) return
        if (expansion && source != null && source !== target && target.creatures.isNotEmpty() &&
            offerBlock(target, source, amount)
        ) return
        val before = target.hp
        target.hp = maxOf(0, before - amount)
        emit(GameEvent.DamageDealt(target.id, amount, source?.id, target.hp))
        if (target.hp == 0 && !(expansion && reactToDeath(target, source, before))) slay(target, source)
        checkGameEnd()
    }

    /** Offers the wizard the chance to discard one Creature to cancel this one hit. Returns true if they did. */
    private suspend fun offerBlock(target: PlayerState, source: PlayerState, amount: Int): Boolean {
        if (!decisions.chooseYesNo(target.id, "Discard a Creature to cancel $amount damage from ${source.name}?")) return false
        val creature = if (target.creatures.size == 1) {
            target.creatures.first()
        } else {
            val uid = decisions.chooseTreasure(target.id, target.creatures.toList(), "Creature to discard")
            target.creatures.firstOrNull { it.uid == uid } ?: error("Invalid Creature choice $uid")
        }
        discardCreature(target, creature, "blocked $amount damage")
        emit(GameEvent.CreatureBlocked(target.id, creature.def.name, amount, source.id))
        return true
    }

    /** Takes a Creature out of play and into the discard pile. */
    internal fun discardCreature(p: PlayerState, creature: CardInstance, why: String) {
        if (!p.creatures.remove(creature)) return
        // If it is also sitting in the owner's spell (waiting to attack again), it leaves the spell too.
        p.spell?.cards?.removeAll { it.fromPlay && it.card === creature }
        mainDiscard.add(creature)
        emit(GameEvent.CreatureLost(p.id, creature.def.name, why))
    }

    /** A Reaction in the dying wizard's unresolved spell goes off. Returns true if one saved them from dying. */
    private suspend fun reactToDeath(p: PlayerState, killer: PlayerState?, hpBefore: Int): Boolean {
        val spell = p.spell ?: return false
        for (sc in spell.cards.toList()) {
            if (sc.resolved || sc.wild || !sc.card.def.text.contains("Reaction:")) continue
            emit(GameEvent.ReactionTriggered(p.id, sc.card.def.name))
            if (ExpansionEffects.reaction(ReactionContext(this, p, killer, sc, hpBefore), sc.card.def.id)) return true
        }
        return false
    }

    internal fun heal(target: PlayerState, amount: Int) {
        if (amount <= 0 || !target.alive) return
        val healed = minOf(amount, maxHp - target.hp)
        if (healed <= 0) return
        target.hp += healed
        emit(GameEvent.Healed(target.id, healed, target.hp))
    }

    /** A wizard dies. [killer] is the wizard whose damage did it (the wizard themselves if they hurt themselves). */
    private suspend fun slay(p: PlayerState, killer: PlayerState?) {
        if (expansion) {
            if (p.has("first-loser-trophy") && livingCount() - 1 <= 1) gainBlood(p, 3, "First Loser Trophy")
            // Fate Coins: anyone who named this wizard as the next to be slain was right.
            for (holder in players) {
                val guess = holder.prediction ?: continue
                if (holder.has("fate-coins") && guess == p.id) gainBlood(holder, 2, "Fate Coins guessed right")
                holder.prediction = null
            }
            if (firstKilled == null) firstKilled = p
        }
        p.alive = false
        if (expansion && killer != null && killer !== p) {
            gainBlood(killer, 3, "slaying ${p.name}")
            if (killer.has("the-naughty-list")) gainBlood(killer, 1, "The Naughty List")
            if (standeeHolder === p) takeStandee(killer)
            poster?.let {
                treasureDiscard.add(it)
                poster = null
                emit(GameEvent.Info("${killer.name} collects the Most Wanted Poster and gains 3 Blood"))
                gainBlood(killer, 3, "Most Wanted Poster")
            }
        }
        mainDiscard.addAll(p.hand); p.hand.clear()
        treasureDiscard.addAll(p.treasures); p.treasures.clear()
        p.regalia?.let { mainDiscard.add(it); p.regalia = null }
        p.spell?.cards?.removeAll { it.fromPlay }
        for (c in p.creatures.toList()) discardCreature(p, c, "its owner died")
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

    private fun handSizeOf(p: PlayerState) =
        HAND_SIZE + (if (p.has("thinking-cap")) 1 else 0) - (if (roundNumber == 1) p.startHandPenalty else 0)

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
    internal suspend fun drawDeadWizardQuietly(p: PlayerState) {
        val card = drawDeadWizardCard() ?: return
        emit(GameEvent.DeadWizardDrawn(p.id, card.def.name))
        if (card.def.id == "backlash-from-beyond") {
            deadDiscard.add(card)
            pendingBacklash.add(p)
        } else {
            p.deadWizardCards.add(card)
            if (expansion && card.def.resolveNow) resolveDeadWizardNow(p, card)
        }
    }

    /** Expansion 1 Dead Wizard cards that say "Resolve now" take effect the moment they are drawn. */
    private fun resolveDeadWizardNow(p: PlayerState, card: CardInstance) {
        when (card.def.id) {
            "ex-exsanguination" -> for (w in players.filter { it.alive && it !== p }) stealBlood(p, w, 1)
            "grave-terrain" -> takeStandee(p)
            "classy-in-repose" -> {
                gainBlood(p, 2, "Classy in Repose")
                if (firstKilled === p) gainBlood(p, 2, "Classy in Repose (first to die)")
            }
            "just-rewards" -> {
                gainBlood(p, players.sumOf { it.tokens }, "Just Rewards")
                if (p.tokens == 0) p.treasureNextGame++
            }
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
        flat += p.bonusDie ?: 0 // Slag Shangri-La's die, kept on the character card for the first turn
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

    internal fun giveStandeeForTest(p: PlayerState?) { standeeHolder = p }

    /** Puts a brand-new Creature face up in front of a wizard. */
    internal fun giveCreature(p: PlayerState, defId: String): CardInstance {
        val card = newCard(catalog.allDefs.getValue(defId))
        p.creatures.add(card)
        return card
    }

    internal suspend fun resolveSpellForTest(p: PlayerState) = resolveSpell(p)

    internal suspend fun playRoundForTest(): RoundOutcome {
        roundNumber++
        return try {
            playRound()
            RoundOutcome(false, null)
        } catch (ended: GameEnded) {
            try { endOfRound() } catch (_: GameEnded) { /* nothing left to decide */ }
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
