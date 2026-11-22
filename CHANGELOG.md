# Changelog

All versions of the Foraging Planner mod. Versioning follows [Semantic Versioning](https://semver.org/) (`MAJOR.MINOR.PATCH`). Jar files are named `foraging-planner-<version>+<minecraft>.jar`.

- **MAJOR**: big reworks; old settings or data may no longer fit
- **MINOR**: new features
- **PATCH**: bug fixes

## [1.0.10] - 2026-11-22

### Added
- **Real map**: Galatea and Torrhus Canyon are drawn like a Minecraft map, from the colours of the blocks in the loaded chunks, with height shading and water depth. The map remembers where you have been (saved per island as PNG in `config/foragingplanner/maps`). The minimap shows the terrain under its markers.
- **Whole island from the start**: the mod comes with a full map of Galatea and Torrhus Canyon, so the whole island is on the map right away; your own exploration is drawn over it and keeps it up to date. (Hypixel only sends the chunks near you.)
- After switching islands, the map waits a few seconds before drawing, so the old island is not drawn onto the new one.
- **Full screen map** (N, `/fp map` or the new "Map" tab): drag to move, mouse wheel to zoom, F to follow yourself. Shows trees (known positions and their state), area names, NPCs, places, Honeyhives, fishing spots, protected trees, fairy souls, Ruby Veilshrooms, the route, marked mobs and the player. Each kind can be switched on and off; hovering a marker shows its name and distance. Named places from [SkyHanni-REPO](https://github.com/hannibal002/SkyHanni-REPO) (MIT).
- **Torrhus menu** (`/fp torrhus` or the new "Torrhus" tab): Helix trees known and learned, Torrhus Belt planner, drops of the island and Safari tickets.
- **Helix trees are learned while walking**: every Helix tree with both colours near you is remembered, so markers, route, arrow and map know Torrhus Canyon too. `/fp trees export` (or the button in the Torrhus menu) copies all known trees as `tree_positions.json`; put it into the repository with a higher `dataVersion` and every player gets them at the next start.
- **Helix colours**: tree markers and the map show how many beige and red logs are left on each Helix tree.
- **Honey helper**: Helix trees smeared with honey (yours, and ones showing "Honeyed") get a label with the time since and the critters honey brings (Hivethief, Parched, Pollendart); those critters are marked when they appear.
- **Ruby Veilshrooms and full Honeyhives** are framed in the world and shown on the map.
- **Torrhus Belt planner**: all raw materials with what you have (sacks and inventory) and what the rest costs at the Bazaar. Sack contents are read when you open a sack.
- **Drops on Torrhus Canyon**: every "DROP!" on the island is counted, with drops per hour and value.
- **Safari tickets**: reminder when the free Basic Safari Ticket of the Free Trial perk is still open today; the contest forecast shows which ticket Miria will likely give.
- **Desert Temple**: the coloured buttons are numbered in the order to press them and arrows show where each statue goes (positions from Skyblocker, LGPL-3.0). Only shows hints.
- **Sweep blocks know the tree style**: aiming at a part of the tree that is not next (e.g. a Fig branch while trunk is left) halves the logs, as on Hypixel. The overlay then shows only the halved logs in red, with a warning over the log naming the part to cut first and "only X instead of Y logs".

### Fixed
- **Updates also come from GitHub**: when Modrinth does not know the project (for example while it is still under review), the update check uses the GitHub releases of the mod. Install in one click works there too; the jar is checked against the SHA-256 that GitHub publishes.
- The tree order label sits above the tree and stays readable through the logs, with a line down to the start log. Before, it was inside the tree and cut off by the logs.

## [1.0.9] - 2026-11-21

### Changed
- **Labels in the world are now real 3D text**: tree markers, the route and its numbers, the best-tree star, the route ghost, mob and vendor labels, bell markers and their path, protected-tree warnings, the tree order label and "Timber!" are drawn in the world like name tags instead of being projected onto the HUD. They no longer wobble when you turn, are hidden behind blocks, and stay readable at a distance. Route lines and the bell path are real lines in the world; etherwarp targets, Precision Cutting logs and Invisibug crits are framed in 3D.
- The update check now writes what it found to the log ("[FP] Update check: ...").

### Fixed
- **HUD positions are kept**: the HUD layout is now saved once for all SkyBlock profiles (`hud.json`). Before, moving a card before your profile was detected, or leaving the server, could bring back an old layout. Changes made while the contest layout is active are kept in the contest layout, and switching layouts keeps the changes of the one you leave.
- The active pet is found in the tab list again when it has a skin (e.g. "[Lvl 200] [240✦] Jade Dragon").

## [1.0.8] - 2026-11-21

### Added
- **Sweep blocks in the tree**: while aiming at a log with an axe, every log the next hit cuts is marked in the world (green, the hit log framed in white). An orange frame shows the log the next Sweep breakpoint would add. Colour and the orange frame can be changed in the settings.
- **Tree order**: the nearest tree shows which part comes next (Fig: trunk, then branches; Mangrove: branches, trunk, roots; Helix: beige, then red) and the best log to start on, picked so the first hit cuts as many logs of that part as possible with your Sweep. A label over it names the steps.
- **Known tree positions**: all 65 Fig and Mangrove trees on Galatea are known in advance, so markers, the route and the arrow work before you have seen a tree. Positions from [Skyblock21](https://github.com/sme6en/Skyblock21) by sme6en (LGPL-3.0). Helix trees are still learned while playing.
- **Contest forecast**: the contest widget now estimates the bracket you will most likely end in, with a range for a bad and a good finish and the chance to reach the next bracket (or to keep the expected one). At the start of a contest, before your pace is known, it uses your last contests at the same host.
- **Updates in one click**: when a newer version is on Modrinth, the chat message now has an "Install" button (or use `/fp update`). The new jar is downloaded next to the old one, checked against Modrinth's SHA-512, and swapped in when you close Minecraft. If anything goes wrong, your current version stays. Setting "Install updates automatically" (off by default) does this without a click.

### Fixed
- Sweep blocks and tree order no longer mark wood in buildings (e.g. birch pillars in Torrhus Canyon). A tree only counts when it stands at a known tree position, when it is a Helix tree with both colours, or when you really cut it; those trees are remembered.
- Trees built from normal blocks (not block displays) are now counted too, so "ready", "cut" and regrow times also work for them.

## [1.0.7] - 2026-11-21

### Added
- **Better what-if calculator**:
  - Before → after for Sweep, Fortune and Wisdom, and the logs per hit on your tree with the Sweep needed for the next log
  - Logs/h, coins/h and XP/h before → after, total cost in coins and Whispers, items without a price, and how long until it pays for itself
  - Every slot only offers items that are an upgrade, best first, with a counter (e.g. 2/7); items above your Foraging level are marked
  - One click takes over the budget plan or the suggestions; another resets everything
  - Perks sorted by the best next upgrades, up to +50 levels at once (capped at the max level); reforges and enchants sorted by gain
- **Shard page rebuilt**:
  - Real attribute levels with a level bar, shards needed for the next level and for level 10 (with Hypixel's shards per level for each rarity, minus what waits in your Hunting Box)
  - Cost of the next level and of everything to level 10 at Bazaar prices
  - Gain for your goal on the tree you farm, sorted by value for money; tree-specific shards for other trees are marked as such
  - Collection shards use your real collection digits; day/night shards count as half the time
  - Click buys the next level in the Bazaar, right click everything to level 10; the ⓘ shows the full calculation
  - New shards: Heron, Sparrow, Seagull, Hummingbird, Tadgang, Pandarai, Treefrog, Piranha, Drybark, Woodpecker, Gecko, Foxtrot

- **Goals tab** in the gear menu:
  - Roadmap to a number of logs per hit on any tree: how much Sweep is missing, the cheapest steps with coins (gear, reforges, enchants, shard levels) and, separately, with Whispers (perk levels), with running totals
  - Wishlist: right click a suggestion to add it; total price, your purse and how long it takes at your coins per hour
- **What-if**: logs per hit on all three trees before → after, and the Sweep to the next log on each
- **Real stats from your items**: worn armor, axes in the inventory, equipment and the active pet are read from their tooltips (reforges, enchants, stars, pet level included) instead of table values
- **Tooltip hints**: foraging gear shows "+X% for your goal" (or that you wear it), attribute shards show their level, the shards still needed and the gain
- **Menu helpers**: level badges in the Hunting Box with a green frame on the three best shards, the best upgrade marked in the equipment menu, and on Bazaar product pages the price against its 7-day average (prices are recorded hourly)
- **One window**: statistics, gear, check and settings have a tab row on top to switch between them
- Menus open on the tab and scroll position where you left them
- **Search in the gear menu** (Ctrl+F) for suggestions, plan, upgrades, shards and tips
- **Menu size** setting (70–130 %), independent of Minecraft's GUI scale
- **Self-check of the mod's data** (gear, shards, trees, translations) and **recognition warnings** when Hypixel texts or menus stop being read (sidebar, tab stats, action bar XP, tree gifts, sacks, Hunting Box, attributes), both in the detection check
- **Data updates from GitHub**: corrected gear and shard values are downloaded without a mod update (only when newer and valid; your own gear.json always wins; can be turned off)
- Shards: First Sweep (Torrid) is now counted using your measured tree size; the ⓘ shows what all levels cost with a buy order instead of buying instantly

### Fixed
- David's Cloak is now always the suggested cloak (free from David Hunterborough); Destruction and Annihilation Cloak are no longer suggested. When you own David's Cloak, its real stats are read from the equipment menu

## [1.0.6] - 2026-11-20

### Added
- **Smoother menus**: pages and rows slide in, numbers count up when a page opens, bars fill with a shimmer, ticks and switches pop. Each can be turned off (Settings > Style > Animations)
- **Menu sounds**: quiet click, page turn, switch, success and error sounds in all mod menus, with volume and an optional very quiet hover tick (Settings > Style > Menu sounds)
- "What's new" card for this version with jumps to the new settings (`/fp news`)

### Fixed
- Crash when opening a statistics page together with mods that change text widths (e.g. Odin's name replacer): rows with an empty value no longer pass a missing text
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
