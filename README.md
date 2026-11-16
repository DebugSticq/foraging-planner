# Foraging Planner

**Plan your upgrades. Find your next tree. Track your progress.**

Foraging Planner is a client-side Fabric mod for **Hypixel SkyBlock**, focused on **Galatea foraging and Hunting**. It brings Heart of the Forest planning, gear recommendations, tree navigation, contest tracking, and session statistics into one configurable interface.

**Minecraft 26.3 · Fabric · Java 25 · English & German · MIT License**

[Getting started](#getting-started) · [Features](#features) · [Commands](#commands) · [Building](#building) · [Changelog](CHANGELOG.md)

## Features

### Plan your next upgrade

- **Heart of the Forest planner:** rank upgrades for XP, logs, coins, or Sweep and build a purchase plan around your budget.
- **Perk tree:** inspect known prerequisites, tier requirements, and unlock paths, with separate Forest Whispers, Desert Whispers, and token accounting.
- **Gear advisor:** compare axes, armor, equipment, and pets using modeled stat gains and available Bazaar or Auction House prices.
- **Budget planning:** compare the expected benefit of upgrades before spending your coins.
- **Tree calculator:** compare Fig, Mangrove, and Helix trees, including logs per hit, XP per hit, and the Sweep needed for the next breakpoint.
- **Learned Sweep thresholds:** save observations from Sweep Details messages and use them to refine the local calculations.

### Navigate Galatea

- Tree markers, regrowth timers, navigation indicators, and a minimap for observed trees.
- Sweep previews, contribution progress, estimated remaining logs, and Precision Cutting assistance.
- A tree guide with manually selected phases for Fig, Mangrove, and Helix.
- Custom routes with walking and Etherwarp waypoints, clipboard import/export, and support for several community waypoint formats.
- Ghost route comparisons against your best recorded lap.
- Optional helpers for critters, Invisibugs, Stridersurfer, falling mobs, and the Moonglade Beacon.

### Track your sessions and contests

- Measured XP and log rates, estimated coin rates, session history, and charts.
- Pause and reset controls, optional AFK pausing, level forecasts, and CSV session export.
- Tree Gift and rare-drop tracking, collection progress, daily goals, and chapter checklists.
- Agatha and Miria contest tracking with rank projections, required pace, countdowns, and optional alerts.
- Past contest charts, recorded log totals, and personal bests.
- Selling comparisons and configurable price alerts.

### Keep Hunting progress in view

- Session and lifetime counts for recognized shard rewards, with estimated values where prices are available.
- Shard collection goals and progress tracking.
- Hunting Trap notifications with the age of the last server report.
- A visual **REEL** cue and optional sound for a mob attached to your own Lasso.
- A dedicated Hunting journal and movable Hunting HUD modules.

### Make the interface your own

- An animated settings interface with search, a forest-inspired theme, and optional animations.
- First-run **Minimal**, **Normal**, and **All** presets.
- Independently movable, scalable, and hideable HUD modules.
- Adjustable HUD opacity, colors, borders, corners, padding, and text shadows.
- HUD presets, optional compact chat messages, and selective sound muting.
- Profile-aware settings and saved progress, with English and German translations.

### Look up and share progress

- `/fp pv <player>` opens a foraging profile lookup through the project's backend.
- `/fp top` opens the global leaderboards; submitting your own results is optional and disabled by default.
- `/fp share` copies a profile summary to your clipboard for you to share.
- `/fp wiki <term>` opens an in-game reference entry; `/fp fusion` provides a link to an external shard fusion planner.

The mod provides planning, visual guidance, and tracking. It does not harvest trees, reel in catches, or follow routes for you.

## Requirements

| Component | Requirement |
| --- | --- |
| Minecraft | **26.3** |
| Mod loader | **Fabric Loader 0.20.1 or newer** |
| Dependency | **Fabric API** for Minecraft 26.3 |
| Java | **25 or newer** |
| Installation side | **Client only** |
| Optional integration | **Mod Menu**, for access to the settings screen |

The source currently targets Minecraft 26.3. Use a release built for your Minecraft version.

## Installation

1. Set up a Fabric instance for Minecraft 26.3 in your launcher.
2. Install the matching Fabric API release.
3. Place the Foraging Planner release JAR in that instance's `mods` folder.
4. Launch the instance and join Hypixel SkyBlock.

For version 1.0.0, the release filename is `foraging-planner-1.0.0+26.3.jar`. Use the regular JAR, not the `-sources.jar`.

## Getting started

1. Choose **Minimal**, **Normal**, or **All** in the first-run setup. You can revisit this with `/fp setup`.
2. Press **P** or run `/fp settings` to configure the mod.
3. Visit Galatea and make sure your foraging stats are visible in the tab list. If needed, enter Sweep, Fortune, and Wisdom with `/fp stats <sweep> <fortune> <wisdom>`.
4. Open Hypixel's `/hotf` menu so the planner can read your perk levels, available currency, and visible requirements.
5. Select an objective with `/fp goal xp`, `/fp goal logs`, `/fp goal coins`, or `/fp goal sweep`.
6. Open `/fp overview` for statistics, `/fp gear` for equipment recommendations, or `/fp hunting` for the Hunting journal.
7. Open **HUD position** in the settings and arrange the panels to suit your screen.

Some features learn from menus you open, including equipment, collections, attributes, and the Hunting Box. Open the relevant menu to refresh those observations.

### HUD editor controls

| Input | Action |
| --- | --- |
| Left-click and drag | Move a module |
| Mouse wheel | Resize the selected module |
| Arrow keys | Fine-tune its position |
| Tab | Select the next module |
| H | Toggle the selected module's visibility |
| Arrange / Center | Reset the layout or center the selected module |

Positions are saved when you finish editing. The settings key and other registered mod key bindings can be changed in Minecraft's Controls menu; the editor shortcuts above are fixed.

## Commands

Run `/fp help` for grouped, clickable help.

| Command | Purpose |
| --- | --- |
| `/fp settings` | Open settings |
| `/fp setup` | Revisit the first-run presets |
| `/fp overview` | Open statistics and forecasts |
| `/fp dashboard` | Open the journal |
| `/fp hotf` | Show ranked Heart of the Forest upgrades |
| `/fp plan [whispers]` | Build an upgrade plan, optionally overriding the Forest Whispers budget |
| `/fp gear` | Open the gear advisor |
| `/fp tree` | Compare trees for your current stats |
| `/fp goal <xp\|logs\|coins\|sweep>` | Change the planner's objective |
| `/fp usetree <auto\|fig\|mangrove\|helix>` | Select the tree used for planning |
| `/fp contest` | Print the current contest status |
| `/fp contests` | Open contest history and charts |
| `/fp goals` | Open goals |
| `/fp drops` | Open recorded drops |
| `/fp hunting` | Open shard tracking and Hunting settings |
| `/fp sell` | Open selling comparisons |
| `/fp alarms` | Configure price alerts |
| `/fp routes` | Open the route manager |
| `/fp route new [name]` | Start recording a route |
| `/fp route import` | Import a route from the clipboard |
| `/fp route export` | Copy the active route to the clipboard |
| `/fp ghost reset` | Reset the active route's ghost record |
| `/fp chapters` | Open chapter progress |
| `/fp pv <player>` | Look up a player's foraging profile |
| `/fp top` | Open global leaderboards |
| `/fp share` | Copy your profile summary to the clipboard |
| `/fp wiki <term>` | Show an in-game reference entry |
| `/fp sounds` | List sound controls |
| `/fp rates` | Print current session rates |
| `/fp rates reset` | Archive the current session and start a new one |
| `/fp hud` | Toggle the HUD |
| `/fp stats auto` | Return to automatically read stats |
| `/fp version` | Print the installed mod version |
| `/fp debug` | Print observed sidebar, tab-list, and perk data |

## Understanding the numbers

The planner combines bundled game data with observations from chat, menus, the tab list, and the world around you.

- **Upgrade rankings are modeled recommendations.** Unknown prerequisite paths are not treated as confirmed unlocks. Open the Heart of the Forest menu to refresh what the planner knows.
- **Coin values are estimates.** Market prices can be cached, missing, or change before a sale. Estimated loot value is not realized profit.
- **Tracked totals start with observed activity.** A menu snapshot can provide current collection or inventory information, but the mod cannot reconstruct unobserved historical catches or sessions.
- **Hunting goals count newly received shards.** Transfers to the Hunting Box and fusion messages do not count as new catches. Trap notices show the last reported snapshot and remain until dismissed.
- **Guides and timers depend on observations.** Tree phases can be selected manually; regrowth and progress estimates may need fresh observations after changing lobbies.

## Local data and online features

Settings, journals, learned values, and other saved state live under your instance's `config/foragingplanner/` directory. Profile-specific data is stored in its `profiles/` subdirectory. Back up this folder to retain your saved progress and layouts.

Market features request public price data from Hypixel and third-party price providers. Player lookups and global leaderboards use the backend configured in official release builds and require that service to be available; players do not need to supply a personal Hypixel API key. Public source builds and CI artifacts contain no backend deployment credentials, so those two features are unavailable until a maintainer supplies service configuration.

Using those backend features registers a random installation identifier and sends the requested lookup or leaderboard data to the service. **Leaderboard sharing is off by default.** Enabling it verifies the Minecraft account through Mojang and submits session/contest information and supported records. Local tracking works without enabling leaderboard sharing.

`/fp share` copies text locally. You choose where to paste and publish it.

## Troubleshooting

| Problem | What to check |
| --- | --- |
| Stats or recommendations are missing | Enable the stats display in your tab list, open `/hotf`, or set manual stats. |
| A HUD panel is missing | Check the master HUD toggle, that module's visibility, and the current area. The editor shows previews even when live data is unavailable. |
| Rates or an ETA are unavailable | Allow time for observations to accumulate and check whether the session is paused or AFK pausing is active. |
| Prices or player lookups fail | Check the connection and the status shown in the relevant screen. Online providers may be unavailable or rate-limited. |
| A server message is no longer recognized | Use `/fp debug` and report the exact message, Minecraft/mod versions, and steps to reproduce. |

Game data can be exported with `/fp data export`, edited in `config/foragingplanner/gamedata.json`, and reloaded with `/fp data reload`. Chat parsers and some mechanics also live in code, so a data-file edit cannot address every server change.

## Building

Install **JDK 25** and set `JAVA_HOME` to that installation. The repository includes the Gradle wrapper.

**Windows / PowerShell**

```powershell
.\gradlew.bat build
.\gradlew.bat runClient
```

**Linux / macOS**

```bash
./gradlew build
./gradlew runClient
```

The build runs the core regression checks and writes release artifacts to `build/libs/`. The filename follows `foraging-planner-<mod version>+<minecraft version>.jar`; version settings are in `gradle.properties`.

The public repository contains the Fabric client. The PV API backend and its deployment files are maintained separately and are not included.

### Optional backend configuration for maintainers

For a build connected to an existing compatible backend, place its client configuration in the ignored file `local/pv.json`:

```json
{
  "api": "https://your-service.example",
  "appKey": "your-client-registration-key"
}
```

Local builds copy this file into the compiled client resources when it exists. Without it, the mod builds normally and reports the profile lookup and leaderboard service as unconfigured. Backend secrets such as the Hypixel API key and server signing secret belong on the server and must never go into this client configuration.

Use `./gradlew build -PpublicBuild=true` (or `.\gradlew.bat build -PpublicBuild=true` on Windows) to produce an artifact without the local override. GitHub Actions uses this mode. The client registration key is bundled in configured release JARs and can be extracted from them; it is not a substitute for backend authentication and rate limits.

To run only the core regression checks:

```powershell
.\gradlew.bat coreRegression
```

### Repository layout

```text
src/client/java/dev/foragingplanner/
  core/          Calculations, models, and parsers
  mc/            Fabric integration, screens, tracking, and services
  mixin/         Minecraft rendering and interaction hooks
src/client/resources/foragingplanner/
                 Bundled game data and service configuration
src/main/resources/assets/foragingplanner/
                 Icons and translations
src/regression/  Core regression checks
docs/            Modrinth listing copy
```

The separate PV API backend, local deployment configuration, development game data, and build caches are excluded from version control.

## Contributing

Bug reports, corrected game data, and translations are welcome. Include the mod version, Minecraft version, reproduction steps, and relevant screenshots or log excerpts. Remove private information before sharing logs.

For code changes, run the build and core regression checks. Changes to screens, rendering, or input handling should also be checked in the development client.

## Credits and license

Created by **Flori**. Game-data references include **SkyHanni**, **Skyblocker**, and the **Hypixel SkyBlock Wiki**. Market integrations use Hypixel Bazaar data and Auction House price providers including **Coflnet** and **Moulberry**.

Released under the [MIT License](LICENSE).
