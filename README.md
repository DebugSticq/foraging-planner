<div align="center">

<img src="icon.svg" width="96" alt="Foraging Planner icon">

# Foraging Planner

**A foraging companion for Hypixel SkyBlock – Fabric, Minecraft 26.3**

HOTF planner · tree tracker · contest stats · routes · Galatea helpers

*Information only. No automation.*

</div>

---

## Table of contents

- [Features](#features)
- [Installation](#installation)
- [Usage](#usage)
- [Commands](#commands)
- [Building from source](#building-from-source)
- [Project layout](#project-layout)
- [PV & leaderboard server](#pv--leaderboard-server)
- [Versioning](#versioning)
- [Privacy](#privacy)
- [Contributing](#contributing)
- [License](#license)

---

## Features

<details open>
<summary><b>Session & contests</b></summary>

- Session card: XP/h, coins/h, Forest Essence, tree gifts, HOTF XP, trees per minute
- Contest card with live points, bracket, points still needed and an end forecast
- Logs per contest, counted from sack messages and saved across restarts
- Contest history with personal bests and a point-over-time replay
- Level forecast for Foraging and the active pet
</details>

<details>
<summary><b>Trees</b></summary>

- Tree markers with regrow timers and size labels
- Tree navigator: arrow, ★ on the best tree, or a full route
- Logs left until a tree is at 100 %
- Precision Cutting helper, clean tree view, log-stripping protection
- Minimap
</details>

<details>
<summary><b>Routes</b></summary>

- Custom routes with walk and Etherwarp points
- Import from Skyblocker, Skytils, SkyHanni, ColeWeight and plain coordinates
- Ghost mode with lap times and splits
</details>

<details>
<summary><b>Galatea helpers</b></summary>

- Critter highlights (Hideonleaf, Shellwise, Birries, Invisibugs, Veilshrooms)
- Phanflare / Phanpyre alert with marker
- Moonglade Beacon ready alert and Tune Frequency helper
- Stridersurfer counter, gather rates
- HOTF tooltips with whisper costs
</details>

<details>
<summary><b>Planning</b></summary>

- Heart of the Forest planner for a whisper budget
- Gear advisor with live Auction House and Bazaar prices
- Goals, milestones, rare drops, price alarms, selling tips
</details>

<details>
<summary><b>Quality of life</b></summary>

- Compact chat for gifts, sweep and contest messages
- Mute phantoms, Drybarks, tree break sounds or any sound via `/fp sounds`
- AFK pause and smart pause reminder
- Styleable HUD (radius, colours, opacity, padding, border, shadow)
- First-start setup: Minimal / Normal / Everything
</details>

<details>
<summary><b>Online (optional)</b></summary>

- `/fp pv <player>` – foraging gear, HOTF, collections and pets of any player
- Global leaderboards (opt-in, account confirmed via Mojang, values checked against the Hypixel API)
</details>

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft **26.3**.
2. Put [Fabric API](https://modrinth.com/mod/fabric-api) and `foraging-planner-<version>+26.3.jar` into your `mods` folder.
3. Optional: [Mod Menu](https://modrinth.com/mod/modmenu).

## Usage

On your first visit to SkyBlock the mod asks how much it should show:

| Choice | Shows |
|---|---|
| **Minimal** | Only the session and contest cards |
| **Normal** | Plus tree markers, navigator, minimap, level forecast, helpers |
| **Everything** | Every feature, including compact chat and routes |

Every switch can be changed later in `/fp settings`. Run the choice again with `/fp setup`.

## Commands

| Command | Description |
|---|---|
| `/fp settings` | Settings |
| `/fp setup` | First-start choice again |
| `/fp stats` · `/fp overview` | Statistics menu · short chat overview |
| `/fp hud` | Move and scale HUD cards |
| `/fp plan` · `/fp hotf` | HOTF plan · perk tree |
| `/fp gear` | Gear advisor |
| `/fp contests` · `/fp goals` · `/fp drops` | Contest history · daily goals · rare drops |
| `/fp routes` · `/fp route new <name>` · `/fp route import` | Routes |
| `/fp ghost reset` | Clear the best lap of the active route |
| `/fp pv <player>` | View a player |
| `/fp top` | Global leaderboards |
| `/fp wiki <term>` | Foraging lexicon |
| `/fp sounds` | Recent sounds, click to mute |
| `/fp version` · `/fp help` | Version · all commands |

## Building from source

Requirements: **JDK 25**.

```bash
# Windows
build-mod.bat

# any system
./gradlew build
```

The jar is written to `build/libs/foraging-planner-<version>+<minecraft>.jar`.

## Project layout

```
src/client/java/dev/foragingplanner/
├── core/        game data, maths, journal (no Minecraft classes)
├── mc/          everything in game: HUD, screens, trackers, helpers
└── mixin/       small client mixins (sounds, particles, entity rendering)
src/main/resources/
├── assets/foragingplanner/   icon, translations (en_us, de_de)
└── fabric.mod.json
pv-api/          Cloudflare Worker for /fp pv and the leaderboards
```

## PV & leaderboard server

`pv-api/` is a small [Cloudflare Worker](https://workers.cloudflare.com/). It keeps the Hypixel API key on the server, so the key never ships with the mod.

| Script | Purpose |
|---|---|
| `set-secrets.bat` | Set `HYPIXEL_API_KEY`, `SERVER_SECRET`, `APP_KEY` |
| `deploy.bat` / `update-server.bat` | Deploy the worker |
| `setup-leaderboard.bat` | Create the KV storage for the leaderboards (once) |
| `ban.bat <uuid> [unban]` | Remove a player from the leaderboards |

How requests are trusted:

1. Each installation registers once with a small proof of work and receives its own secret.
2. Every request is signed with that secret and rate limited.
3. Leaderboard accounts are confirmed with Mojang's `hasJoined` check – the mod's login is sent to Mojang only.

## Versioning

The mod follows [Semantic Versioning](https://semver.org/): `MAJOR.MINOR.PATCH+<minecraft>`.

```bat
version.bat          :: 1.0.0 -> 1.0.1  (fixes)
version.bat minor    :: 1.0.0 -> 1.1.0  (features)
version.bat major    :: 1.0.0 -> 2.0.0  (breaking changes)
```

Changes are listed in [CHANGELOG.md](CHANGELOG.md).

## Privacy

- Without the online features the mod sends **nothing** anywhere.
- `/fp pv` sends the looked-up player name to the mod's server.
- Leaderboards are **opt-in**. Only your best values, name and UUID are stored. Turning sharing off deletes them.

## Contributing

Bug reports and ideas are welcome in the issue tracker. Please include:

- the mod version (`/fp version`)
- what you expected and what happened
- the relevant lines from `logs/latest.log` (search for `[FP]`)

## License

[MIT](LICENSE) © Flori

*Not affiliated with Hypixel Inc. or Mojang Studios.*
