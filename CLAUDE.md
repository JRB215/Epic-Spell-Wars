# Handoff: building Epic Spell Wars like our 7 Wonders server

Give this file to Claude Code at the start of the new project (or put it in the new repo as `CLAUDE.md`).

## What we already have (reference project)

- Repo: `JRB215/seven-wonders` (private), a fork of `joffrey-bion/seven-wonders` (MIT). Read it, it is the model to follow.
- Stack: Kotlin multiplatform.
  - `sw-common-model`: shared data classes (kotlinx.serialization)
  - `sw-engine`: game rules, pure Kotlin, heavily unit-tested
  - `sw-server`: Spring Boot 4, STOMP websockets
  - `sw-client`: websocket client
  - `sw-ui`: Kotlin/JS React (kotlin-wrappers, Blueprint, emotion)
  - `sw-bot`: simple bot
- Hosting: Docker on the Windows PC "JRBFlix".
  - 7 Wonders image: `ghcr.io/jrb215/seven-wonders-server:latest`
  - 7 Wonders run command: `docker run -d --name seven-wonders -p 8080:80 -v C:\SevenWonders\assets:/custom-assets ...`
  - Epic Spell Wars needs its own container on another port (e.g. 8081) and its own assets folder (e.g. `C:\EpicSpellWars\assets`).
- CI/CD (GitHub Actions):
  - `build.yml` builds and tests every pull request.
  - `publish.yml` builds and pushes the Docker image to GHCR on every push to main (`:latest`), plus a versioned image for each release tag (`v1.2.0` or `1.2.0`).
  - Releases are created by the owner in the GitHub UI.

## Pieces worth copying from seven-wonders

- Lobby and game browser: create/join a game, owner settings, add bots, start.
- Seats and reconnection (`SeatController`):
  - per-tab seat key, so a refresh keeps your seat;
  - a disconnect grace period;
  - a unanimous vote to let a bot play for a missing player, who takes the seat back when they return.
- Custom images (`CustomImagesConfig`):
  - images are served from the mounted assets folder first, so art can be replaced without a rebuild;
  - matching is lenient (case, extension, sub-folder).
- Leaderboard (`Leaderboard.kt`):
  - wins by player name, case-insensitive, saved as JSON in the assets folder;
  - games with fewer than 2 humans are not counted;
  - reset is only for the hidden admin name `KnilAdmin`, which is never sent to other clients.
- Table polish:
  - flash of the cards other players just played (`LastMoveFlash.kt`);
  - full-screen phase screens with timed animations (`MilitaryShowdown.kt`);
  - a "game over" pause before scores;
  - a score screen with tabs and charts;
  - synthesized sounds with a mute toggle, played sparingly (one sound at the start of a phase, not one per row).
- Card data pipeline:
  - scans → `scans/edition2/process_scans.py` (orient, trim, keep the best copy, resize to 180 px wide);
  - then card data is read into JSON and loaded by the engine.

## Epic Spell Wars specifics

- 2–6 players, simultaneous secret spell building (up to 3 cards: Source / Quality / Delivery), resolved by initiative, dice rolls, last wizard standing wins the round.
- Card counts matter. The owner will scan one copy of each card and give a list of counts (e.g. "Fireball ×3"). Check totals against the deck sizes in the rulebook.
- Decks to cover:
  - spells;
  - treasures;
  - Dead Wizard cards;
  - wizards;
  - Last Wizard Standing tokens and Wild Magic.
- Bot: random legal spells are fine (the game is chaotic anyway).
- Dice: roll on the server, show the result with a short animation to everyone.

## Settled facts about the owner's cards

- Folders: `Base/` holds the base game. `Expansion 1/` holds the (c)2015 expansion. Raw scans and PDFs are never committed (see `.gitignore`).
- Source, Quality and Delivery: 20 different cards each, 2 copies of each (40 per deck). Wild Magic: 1 design, 8 copies.
- Treasures: 25 different cards, 1 copy each (matches the box).
- Dead Wizards: the owner's pile was a mix. The (c)2012 cards are base (25 cards, counts in `Base/cards/dead-wizards.json`). The (c)2015 cards use a Blood mechanic and live in `Expansion 1/`. The engine loads only the base set.
- Heroes: all 16 hero sheets are treated as base game. Heroes play identically (20 HP, max 25), so only art and name differ.
- A "game" is a series of rounds that lasts until one wizard is left alive; that wizard earns a token. A "match" is first to 2 tokens. HP carries between rounds and resets at the start of each game. (This is my reading of the rulebook and the Dead Wizard cards, not yet confirmed by the owner.)
- Project layout: `esw-model` (card types and JSON loader), `esw-engine` (rules, no UI). Server and UI come next. The UI is plain HTML/JS served by the server, not Kotlin/JS.
- Build and test with `.\gradlew.bat test` (Windows). Java 21 is installed; Gradle is downloaded by the wrapper.
- `docs/RULES.md` lists every rule interpretation the engine makes. Keep it in step with the code.
- Screens are tuned for 1920x1080 (the owner's request). The hand runs along the bottom and enlarges 35% on hover; whoever is casting takes over the middle with big cards that flip face up and glow as each effect fires, with a plain-words narration beside them. Hero boards show the real HP track with the skull counter on the right circle (positions measured in `TRACK` in `app.js`). Loose game pieces (tokens, marker, towers) live in `Base/` and are prepared by `tools/make_pieces.py`.
- The blood marker (`marker.png`) and the towers are prepared but not used in play yet. Tower 1 is "Mt. Skullzfyre", a decoration in the base game (no rules). Tower 2 is the **Standee**, a piece from the Expansion 1 rules (Blood, Standee, Creatures). See `docs/EXPANSION1.md`.
- Never `git add -A` here: loose pictures dropped into `Base/` or `Expansion 1/` must not be committed (this once pushed scans by mistake). Add named files only.
- `tools/orient_scans.py` turns scans upright into `Base/cards-upright/`. Run it with `python tools/orient_scans.py`.

## Status and workflow notes

- Done and tested: card data, rules engine (300 random matches pass the card audit), server, browser screens, Docker image definition, CI workflows.
- Not yet seen with real people: two or more humans at one table, the "bot takes over for a dropped player" vote, sounds (only the code was checked), and the Docker image (CI builds it; this PC has no Docker).
- Owner said (2026-10-08): base-level work can be built and published without pausing for approval each time. Pause publishing only while the owner is giving active feedback after testing.
- Work goes on a branch and is merged to `main` through a pull request; pushing to `main` publishes the `:latest` image. `gh` is not installed here, so the owner opens and merges pull requests in the GitHub page.
- Local server for testing: `$env:ESW_ASSETS='C:\Dev\Epic-spell-wars\Base\cards-upright'; $env:PORT='8099'; .\gradlew.bat :esw-server:bootRun`. The built-in browser can open it at `http://localhost:8099`. Set `ESW_PACE` to a small number (like 0.04) to watch a whole match quickly.
- `javascript_tool` calls time out after 45 seconds; poll long matches with short calls.

## How the owner likes to work

- **Batch changes. Don't publish a new build after each request.** Collect several items and only merge and publish when the owner says "build it". Keep work on a branch with a draft PR in the meantime.
- Say clearly what was tested and what wasn't seen on screen.
- Explain things in plain words. The owner updates the server with `docker pull` / stop / rm / run, so give those commands after each publish.
- The owner creates GitHub tokens and releases themselves. Never ask for a token to be pasted in chat.
- Don't keep scans the owner asks not to keep.

## Code conventions (from seven-wonders)

- `allWarningsAsErrors = true`: unused code or an unnecessary `!!` breaks the build.
- In sw-ui:
  - use `Padding(all=)` / `Margin(all=)` from `org.luxons.sevenwonders.ui.utils`;
  - `.unsafeCast<Nothing>()` for raw CSS values the wrappers don't support;
  - avoid `Intent.NONE`.
- Engine logic is covered by tests that play full random games for every player count.
