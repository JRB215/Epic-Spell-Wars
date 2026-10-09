package esw.server

import esw.engine.CardInstance
import esw.engine.Game
import esw.engine.GameEvent
import esw.engine.PlayerState
import esw.engine.SpellCard
import esw.model.CardDef
import esw.model.CardType
import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder

private fun encode(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")

private fun artFolder(type: CardType) = when (type) {
    CardType.SOURCE -> "Source"
    CardType.QUALITY -> "Quality"
    CardType.DELIVERY -> "Delivery"
    CardType.TREASURE -> "Treasures"
    CardType.DEAD_WIZARD -> "Graveyards"
    CardType.WILD_MAGIC -> "Card Back and Wild Card"
}

private fun baseName(file: String) = file.substringBeforeLast('.')

/** Where the browser can ask for a card's picture. The server answers 404 if there is no picture for it. */
fun artUrl(def: CardDef): String? =
    def.scan?.let { "/art/${encode(artFolder(def.type))}/${encode(baseName(it))}" }

fun heroArtUrl(file: String) = "/art/${encode("Character Sheets")}/${encode(baseName(file))}"

fun cardView(def: CardDef, uid: Int? = null): JsonObject = obj(
    "uid" to uid,
    "id" to def.id,
    "name" to def.name,
    "type" to def.type.name,
    "glyph" to def.glyph?.name,
    "initiative" to def.initiative,
    "countsAsGlyph" to def.countsAsGlyph,
    "text" to def.text,
    "art" to artUrl(def),
)

fun cardView(card: CardInstance): JsonObject = cardView(card.def, card.uid)

private fun spellView(p: PlayerState, viewer: Int): JsonObject? {
    val spell = p.spell ?: return null
    val open = p.id == viewer || spell.revealed
    fun slotView(sc: SpellCard): JsonObject =
        if (open) {
            obj("slot" to sc.slot, "card" to cardView(sc.card), "wild" to sc.wild, "resolved" to sc.resolved)
        } else {
            obj("slot" to sc.slot, "card" to null, "hidden" to true, "resolved" to false)
        }
    return obj("revealed" to spell.revealed, "cards" to spell.cards.map { slotView(it) })
}

private fun playerView(game: Game, p: PlayerState, viewer: Int, seats: List<SeatInfo>): JsonObject = obj(
    "id" to p.id,
    "name" to p.name,
    "hero" to obj(
        "id" to p.hero.id, "name" to p.hero.name, "title" to p.hero.title,
        "art" to heroArtUrl(p.hero.artScan), "board" to heroArtUrl(p.hero.boardScan),
    ),
    "hp" to p.hp,
    "maxHp" to game.maxHp,
    "alive" to p.alive,
    "tokens" to p.tokens,
    "handCount" to p.hand.size,
    "treasures" to p.treasures.map { cardView(it) },
    "deadCards" to p.deadWizardCards.size,
    "spell" to spellView(p, viewer),
    "acted" to p.acted,
    "bot" to seats[p.id].bot,
    "connected" to seats[p.id].connected,
    "away" to seats[p.id].takeover,
)

/** Public facts about a seat that the game view needs. */
data class SeatInfo(val bot: Boolean, val connected: Boolean, val takeover: Boolean)

/** What [viewer] may see. Pass -1 for someone who is only watching. */
fun gameView(game: Game, viewer: Int, seats: List<SeatInfo>): JsonObject {
    val me = game.players.getOrNull(viewer)
    return obj(
        "game" to game.gameNumber,
        "round" to game.roundNumber,
        "decks" to obj(
            "main" to game.mainDeckSize, "mainDiscard" to game.mainDiscardSize,
            "treasure" to game.treasureDeckSize, "deadWizard" to game.deadWizardDeckSize,
        ),
        "players" to game.players.map { playerView(game, it, viewer, seats) },
        "you" to if (me == null) null else obj(
            "index" to viewer,
            "hand" to me.hand.map { cardView(it) },
            "deadCards" to me.deadWizardCards.map { cardView(it) },
        ),
    )
}

// ---------------------------------------------------------------------------- events

private fun name(names: List<String>, id: Int?) = if (id == null) "Someone" else names.getOrElse(id) { "Wizard ${id + 1}" }

/** The line shown in the game log. */
fun describe(e: GameEvent, names: List<String>): String = when (e) {
    is GameEvent.MatchStarted -> "The match begins. ${e.players.joinToString(", ")} take their seats."
    is GameEvent.GameStarted -> "Game ${e.number} begins."
    is GameEvent.RoundStarted -> "Round ${e.number}."
    is GameEvent.HandsDealt -> ""
    is GameEvent.SpellsLocked -> "All spells are ready: " + e.spells.joinToString(", ") {
        "${name(names, it.player)} (${it.components} card${if (it.components == 1) "" else "s"}, Initiative ${it.initiative})"
    }
    is GameEvent.TurnStarted -> "${name(names, e.player)} casts."
    is GameEvent.SpellRevealed -> "${name(names, e.player)} reveals ${e.cards.joinToString(", ").ifEmpty { "nothing" }}."
    is GameEvent.WildMagicResolved ->
        "${name(names, e.player)}'s Wild Magic becomes ${e.replacement ?: "nothing (no ${e.slot.lowercase()} card left)"}."
    is GameEvent.CardResolving -> "${name(names, e.player)} resolves ${e.card}."
    is GameEvent.DiceRolled -> "${name(names, e.player)} rolls ${e.dice.joinToString(" + ")}" +
        (if (e.dice.sum() != e.total) " = ${e.total}" else if (e.dice.size > 1) " = ${e.total}" else "") + " (${e.reason})."
    is GameEvent.RollOutcome ->
        "${name(names, e.player)}'s ${e.card} roll of ${e.total} falls in ${listOf("1-4", "5-9", "10+")[e.band - 1]}."
    is GameEvent.DamageDealt -> "${name(names, e.target)} takes ${e.amount} damage (${e.hpAfter} HP left)."
    is GameEvent.Healed -> "${name(names, e.player)} heals ${e.amount} (${e.hpAfter} HP)."
    is GameEvent.TreasureGained -> "${name(names, e.player)} gains ${e.treasure}" +
        (if (e.from != null) " from ${name(names, e.from)}" else "") + "."
    is GameEvent.TreasureLost -> if (e.destroyed) "${name(names, e.player)}'s ${e.treasure} is destroyed." else ""
    is GameEvent.CardAddedToSpell -> "${e.card} joins ${name(names, e.player)}'s spell."
    is GameEvent.PlayerDied -> "${name(names, e.player)} is dead!"
    is GameEvent.DeadWizardDrawn -> "${name(names, e.player)} draws the Dead Wizard card ${e.card}."
    is GameEvent.GameWon ->
        if (e.winner == null) "Nobody wins this game." else "${name(names, e.winner)} is the last wizard standing! (${e.tokens} token${if (e.tokens == 1) "" else "s"})"
    is GameEvent.MatchWon -> "${name(names, e.winner)} wins the match!"
    is GameEvent.Info -> e.text
}

private fun kind(e: GameEvent) = when (e) {
    is GameEvent.MatchStarted -> "matchStarted"
    is GameEvent.GameStarted -> "gameStarted"
    is GameEvent.RoundStarted -> "roundStarted"
    is GameEvent.HandsDealt -> "handsDealt"
    is GameEvent.SpellsLocked -> "spellsLocked"
    is GameEvent.TurnStarted -> "turnStarted"
    is GameEvent.SpellRevealed -> "spellRevealed"
    is GameEvent.WildMagicResolved -> "wildMagic"
    is GameEvent.CardResolving -> "cardResolving"
    is GameEvent.DiceRolled -> "dice"
    is GameEvent.RollOutcome -> "rollOutcome"
    is GameEvent.DamageDealt -> "damage"
    is GameEvent.Healed -> "heal"
    is GameEvent.TreasureGained -> "treasureGained"
    is GameEvent.TreasureLost -> "treasureLost"
    is GameEvent.CardAddedToSpell -> "cardAdded"
    is GameEvent.PlayerDied -> "died"
    is GameEvent.DeadWizardDrawn -> "deadWizard"
    is GameEvent.GameWon -> "gameWon"
    is GameEvent.MatchWon -> "matchWon"
    is GameEvent.Info -> "info"
}

/** The facts the screen needs to animate an event. */
fun eventView(e: GameEvent, names: List<String>): JsonObject {
    val base = mutableMapOf<String, Any?>("k" to kind(e), "text" to describe(e, names))
    when (e) {
        is GameEvent.TurnStarted -> base["player"] = e.player
        is GameEvent.SpellRevealed -> { base["player"] = e.player; base["cards"] = e.cards }
        is GameEvent.CardResolving -> { base["player"] = e.player; base["card"] = e.card }
        is GameEvent.DiceRolled -> {
            base["player"] = e.player; base["dice"] = e.dice; base["total"] = e.total; base["reason"] = e.reason
        }
        is GameEvent.RollOutcome -> { base["player"] = e.player; base["card"] = e.card; base["total"] = e.total; base["band"] = e.band }
        is GameEvent.DamageDealt -> { base["target"] = e.target; base["amount"] = e.amount; base["source"] = e.source }
        is GameEvent.Healed -> { base["player"] = e.player; base["amount"] = e.amount }
        is GameEvent.TreasureGained -> { base["player"] = e.player; base["treasure"] = e.treasure }
        is GameEvent.PlayerDied -> base["player"] = e.player
        is GameEvent.GameWon -> { base["winner"] = e.winner; base["tokens"] = e.tokens }
        is GameEvent.MatchWon -> base["winner"] = e.winner
        is GameEvent.SpellsLocked -> base["spells"] = e.spells.map {
            mapOf("player" to it.player, "components" to it.components, "initiative" to it.initiative)
        }
        else -> Unit
    }
    return obj(*base.map { it.key to it.value }.toTypedArray())
}
