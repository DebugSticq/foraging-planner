# Changelog

All versions of the Foraging Planner mod. Versioning follows [Semantic Versioning](https://semver.org/) (`MAJOR.MINOR.PATCH`). Jar files are named `foraging-planner-<version>+<minecraft>.jar`.

- **MAJOR**: big reworks; old settings or data may no longer fit
- **MINOR**: new features
- **PATCH**: bug fixes

## [1.0.4] - 2026-11-16

### Added
- `/fp perf` and "Show performance": how much time each part of the mod takes
- Light mode for slower PCs, and an automatic light mode when the mod takes too much time
- Rare drops are configurable: an "always rare" list (e.g. Mango Dye, Chameleon Shard), a "never rare" list, a minimum coin value and bonus drops on/off. Listed items also count from hunting and Safari catches and "RARE DROP!" messages
- Confetti across the screen for a rare drop
- "Timber!" with falling leaves when a tree you helped with reaches 100 %
- Combo fire: flames from x50, rainbow from x250, and a sound and "NEW RECORD!" when you beat your best combo
- Seasons behind the settings menu: snow in winter, blossoms in spring, fireflies in summer, leaves in autumn (or pick one)
- GitHub Actions: every push builds the jar; a `v*` tag creates a GitHub release (and a Modrinth version if a token is set). `release.bat` tags and pushes

### Changed
- Rarely used options moved into an "Advanced" group on each settings page
- The bell path search now has a time budget per tick instead of a fixed node count

### Fixed
- Fewer repeated raycasts and mob sound lookups (short caches), so busy lobbies stutter less

## [1.0.3] - 2026-11-16

### Changed
- Settings sorted again: tree helpers on the trees page, warnings on the alerts page, HOTF and attributes on the planner page, shard vendors on the Safari page
- Settings with options can be opened: click a row to show its sub-settings below it (minimap size, AFK time, capsule warning, colours and more)
- Name lists (critters, muted mobs, protected items, vendors, important floor drops) can be edited right in the settings

## [1.0.1] - 2026-11-16

### Added
- Critter Safari run tracker with a low-capsule warning and an end-of-run summary (`/fp safari`)
- Safari bells: all 7 bells are marked and remembered, with the shortest route through the rest planned for you (`/fp bells`)
- Shard prices above critters, Sparkling alert, Critterdex helper and biome milestones
- Ticket counter, floor drop markers, Honeyed Helix markers, Safari Essence per hour and party catches
- New "Critter Safari" settings page and a "Safari" HUD card

### Changed
- The bell route is now a real walking path on the ground (jumps, drops, ladders, water) instead of a straight line

### Fixed
- Mob, critter and item highlights no longer show through blocks
- Safari catches are counted again ("You caught a ... Shard!" and "You caught x2 ... Shards!")
- The Critter Safari is now also detected from the sidebar location and from Critter Capsules in the inventory

## [1.0.0] - 2026-11-16

First full release.

### Planning
- Heart of the Forest planner with perk tree, budget and tokens
- Gear advisor with live Auction House and Bazaar prices
- Tree calculator (logs per hit, Sweep preview)

### In game
- Flat, configurable HUD (corner radius, opacity, colours, padding)
- Session card, contest card, level forecast and gather rates
- Tree markers with regrow timers, tree navigator and minimap
- Logs left until 100 %, Precision Cutting helper and a cleaner tree view
- Custom routes with Etherwarp points, import from other mods and ghost mode
- Contest tracking with counted logs, replay and personal bests
- Galatea helpers: critters, Invisibugs, Stridersurfers, falling mobs, Moonglade Beacon
- Compact chat and sound muting

### Menus
- Stats menu with goals, sessions, contests, drops, collections, selling, routes and chapters
- `/fp pv <player>` through the mod's own server
- Global leaderboard (opt-in, account verified through Mojang, values checked through the Hypixel API)
- First-start setup (Minimal / Normal / Everything), `/fp help`, `/fp version`
