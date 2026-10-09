# Epic Spell Wars (online)

A web version of the card game, for 2 to 6 wizards at a table of friends and bots. Built with Kotlin: a rules engine, a Spring Boot server, and plain HTML/JavaScript screens.

## Updating the server on JRBFlix

Run these in PowerShell on the PC that hosts it. The first time, `docker stop` and `docker rm` will say there is nothing to stop; that is fine.

```powershell
docker pull ghcr.io/jrb215/epic-spell-wars-server:latest
docker stop epic-spell-wars
docker rm epic-spell-wars
docker run -d --name epic-spell-wars --restart unless-stopped -p 8081:80 -v C:\EpicSpellWars\assets:/custom-assets ghcr.io/jrb215/epic-spell-wars-server:latest
```

Then open `http://<the PC's address>:8081` in a browser. Seven Wonders keeps its own port (8080) and folder, so the two do not interfere.

## Card pictures

The Docker image does not contain any card pictures. They are read from the mounted folder (`C:\EpicSpellWars\assets`):

1. On the development PC, run `python tools\orient_scans.py`. It turns the raw scans upright into `Base\cards-upright`.
2. Copy everything inside `Base\cards-upright` (the folders `Source`, `Quality`, `Delivery`, `Treasures`, `Graveyards`, `Character Sheets`, `Card Back and Wild Card`) into `C:\EpicSpellWars\assets`.
3. That is all. Pictures can be swapped later without updating the server. A card with no picture is drawn as a plain text card.

File names are matched loosely: capital letters, the extension (jpg or png) and sub-folders do not matter.

The same folder holds `leaderboard.json`, the match wins by player name. Only games with at least two human players count.

## Building and testing

The Docker image only wraps a finished jar: the workflows run `./gradlew build` first (which makes `esw-server/build/libs/app.jar`) and then `docker build .`.

```powershell
.\gradlew.bat test           # all tests
.\gradlew.bat :esw-server:bootRun   # run the server on http://localhost:8080
```

Set `ESW_ASSETS` to a folder of pictures and `PORT` to change the port. `ESW_PACE` scales the animation pauses (1 is normal, 0 is instant).

## How it is put together

| Folder | What it is |
|---|---|
| `Base/cards` | Every card of the base game as JSON, read from the scans |
| `esw-model` | Card types and the loader for the JSON files |
| `esw-engine` | The rules: turn order, Power Rolls, every card effect. No screens. |
| `esw-server` | Lobby, tables, bots, reconnecting, leaderboard, and the browser screens |
| `docs/RULES.md` | The rules as the engine plays them, and every interpretation it makes |
| `tools` | Scripts for the card scans |

Raw scans, rulebook PDFs and zips are never committed (see `.gitignore`).
