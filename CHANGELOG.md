# Changelog

All versions of the Foraging Planner mod. Versioning follows [Semantic Versioning](https://semver.org/) (`MAJOR.MINOR.PATCH`). Jar files are named `foraging-planner-<version>+<minecraft>.jar`.

- **MAJOR**: big reworks; old settings or data may no longer fit
- **MINOR**: new features
- **PATCH**: bug fixes

## [1.0.6] - 2026-11-20

### Added
- **Smoother menus**: pages and rows slide in, numbers count up when a page opens, bars fill with a shimmer, ticks and switches pop. Each can be turned off (Settings > Style > Animations)
- **Menu sounds**: quiet click, page turn, switch, success and error sounds in all mod menus, with volume and an optional very quiet hover tick (Settings > Style > Menu sounds)
- "What's new" card for this version with jumps to the new settings (`/fp news`)

### Fixed
- **Profit tracker counts sacks correctly**:
  - Logs from sack messages are no longer lost when chat is open or when the message arrives a while after you stopped chopping (it covers the last 10–30 seconds)
  - Logs moved between inventory and sacks in a menu (sack bag, storage, selling) no longer count as income or cancel out real logs
  - Other items from sack messages (drops, saplings, sticks, ...) now count towards the session value; gift rewards that also land in the sacks are not counted twice
  - When Hypixel shortens a sack message ("and 3 other items"), the number of unlisted items is shown in the journal

## [1.0.5] - 2026-11-17

### Added
- **Custom axe looks** (Settings > Display > Just for fun), only visible to you:
  - Moonblade (frost axe), Rootcleaver (forest axe) and Emberbit (lava axe)
  - Low-poly 3D models in the hand, on the ground and in item frames, a 32×32 pixel version in the inventory
  - Animated: ice crystals circle the frost axe, leaves and vine sway and spores rise from the forest axe, embers rise from the lava axe; glowing edges and cracks pulse
  - "3D model" switches to the pixel version everywhere, "All axes" applies the look to every SkyBlock axe
- **Custom /hotf menu**: perks as cards in Hypixel's own layout with levels, the planner's top three upgrades in gold and a detail panel. Clicks go to the real menu; hold Alt or press "Original" for the normal one
- **Enchants & reforges** page in the gear menu: best foraging reforge per piece (Moonglade, Moil, Toil, Groovy, Marshy, Majestic, ...), Recombobulator and axe enchantments (First Impression, Karma, Petalfall, Missile, Absorb), each with stat change, gain for your goal and price
- **Pick lists instead of typing**: rare drops, protected items, Safari ground items, critters, muted mobs, vendors and honey names are chosen from a searchable list with icons and prices, filled from your drops, gift rewards, catches, ground items, shards, seen mobs and the Bazaar. Rare drops have three states: always, never or automatic by value (`/fp rare`)
- Click a drop in Statistics > Drops to switch it between auto, always and never
- **Detection check** (`/fp check`, Settings > Display): shows what the mod could read (SkyBlock, stats, HOTF, Hunting Box, attributes, armor, Bazaar, resource pack, ...) with a tip for each gap, and copies a report
- **Automatic backups** of statistics and settings at start and every 30 minutes (newest 10 kept), restore with two clicks or `/fp backup now|list|restore`
- Full description of a setting when resting the mouse on it
- One-time first steps tips in Galatea, the Safari and at the first contest
- **HUD editor**: cards snap to screen edges, the center and other cards with guide lines, 10 px grid (G), snapping on/off (S), Shift for free moving, R resets a card
- **HUD layout per place**: Galatea, Safari, Hub and other islands can each switch to their own layout automatically
- **Calmer cards**: priority per kind (rare drops first), identical cards merge into "×N", each kind can be turned off, and during a contest only rare drops and alarms show
- New key bindings (unbound by default): statistics, gear, detection check, tree navigator on/off, HUD on/off
- Session chart in Statistics can show XP/h, logs/h, gifts/h or coins/h, with a trend line, average and comparison of the last session
- **"Why?" explanations**: an ⓘ on every gear suggestion, reforge and enchant, every tree in Statistics > Trees and a "Why?" button in the perk tree show the numbers behind the advice: stat change, gain for your goal, drops and XP per hit, logs/coins/XP per hour from your sessions, price, gain per million coins and payback time. The custom /hotf menu shows the next level's stats and cost
- **What-if calculator** (gear menu): try other gear per slot, extra perk levels and a reforge or enchant and see the combined stat change, logs/h, coins/h, XP/h and the cost before buying anything
- **Timeline** in Statistics (`/fp timeline`): Foraging levels, perk levels, attributes, new shards, collection tiers, milestones, contests and rare drops by day, with filters
- **Setup assistant** in five live-checked steps: SkyBlock, stats from the tab list, /hotf, Hunting Box and attributes, resource pack (`/fp wizard`, also after the first setup)
- **Bug report in one click** (detection check or `/fp report`): versions, check results, other mods, settings code and the mod's log lines, with player name, UUID, IPs and user folders removed; copied and saved as a file. When something fails the mod suggests it once
- **What's new** after an update: a card and a chat link once, the full list with jumps to each setting on `/fp news`
- **Share settings**: copy all settings as one line of text and paste them elsewhere (Settings > Online or `/fp settings export|import|undo`). Personal data is left out, a backup is made before pasting

### Fixed
- Clicking a shard or Bazaar item in the gear menu now opens the Bazaar directly: the item, "Buy Instantly" and "Custom Amount" are marked in gold and the needed amount is typed into the sign (nothing is bought automatically). Auction items open the auction search
- Gear menu icons use the real item looks from the Hypixel resource pack
- More gear in the advisor: Leaflet set, Mole Hat, Kelly's T-Shirt, Charlie's Trousers, Melody's Shoes, Bee Mask, Frog Mask, Spruce Axe
- Owned shards are recognised in the Hunting Box also when the entries are not called "... Shard"
- A thrown axe no longer marks the tree you are cutting as protected; only the "Tree Protection Order" sign marks a tree, and wrongly saved places are removed
- Protected trees are found from the "Tree Protection Order" nametag and from the fine message, even when the tree is not in the tree list
- Protected trees get a red label; hits and Axe Toss on them are blocked (sneak to hit anyway, can be turned off)
- The protected-tree warning only shows when you aim at the tree itself, not at a wall in front of it
- Protected marks disappear when the order is gone instead of staying forever
- Switches and selectors of indented settings were cut off at the right edge

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
