# Foraging Planner

**Plan your upgrades. Find your next tree. Track your progress.**

Foraging Planner is a client-side Fabric mod for **Hypixel SkyBlock**, built around **Galatea foraging and Hunting**. Compare upgrades, follow your progress, and keep the information you need in a HUD you can arrange yourself.

## Spend your Whispers and coins with a plan

The **Heart of the Forest planner** reads your perk levels and visible requirements, ranks upgrades for your chosen objective, and builds a plan around your available resources. Known prerequisites, tiers, Forest Whispers, Desert Whispers, and tokens are considered together.

The **gear advisor** compares axes, armor, equipment, and pets using modeled stat improvements and available Bazaar or Auction House prices. Set a budget and see which upgrades offer the most benefit for your chosen goal.

The tree calculator compares **Fig, Mangrove, and Helix** for logs and XP per hit, shows your next Sweep breakpoint, and can refine its thresholds using saved Sweep Details observations.

## Find your next tree

- **Tree markers and regrowth timers** for trees the mod has observed.
- **Navigation indicators and a minimap** to help you move between trees.
- **Sweep previews and contribution progress**, with estimated logs remaining and Precision Cutting assistance.
- **Custom routes** with walking and Etherwarp waypoints, import/export, and ghost comparisons against your best lap.
- **Galatea helpers** for critters, Invisibugs, Stridersurfer, falling mobs, and the Moonglade Beacon.
- **Tree phase guides**, including a manually selectable Helix color guide.

## See what each session earns

Track measured **XP and logs per hour**, estimated coin rates, Tree Gifts, rare drops, collections, and progress toward your goals. Review session charts, pause tracking, start a fresh session, or export your history as CSV.

For **Agatha and Miria contests**, follow your current rank, projected finish, and the pace needed for the next bracket. Optional countdowns and alerts keep key moments visible, while contest history and personal bests help you compare runs.

Selling comparisons, price alerts, daily goals, chapter checklists, level forecasts, and optional AFK pausing bring the rest of your progress into the same interface.

## Track Hunting alongside foraging

- Count recognized **shard rewards** for the current session and across your saved history.
- See estimated shard values when prices are available.
- Set **shard collection goals** and track newly received shards toward them.
- Receive **Hunting Trap notifications** with the age of the last reported catch notice.
- Get a visual **REEL** cue and optional sound for a mob attached to your own Lasso.

Open the dedicated Hunting journal with `/fp hunting`.

## Build a HUD that fits your screen

Start with **Minimal**, **Normal**, or **All**, then adjust individual features in the animated, forest-inspired settings menu.

- Drag HUD modules with the mouse, resize them, or hide them individually.
- Adjust opacity, colors, borders, corners, padding, and text shadows.
- Switch between HUD presets.
- Search settings and disable animations if you prefer.
- Use optional compact chat messages and selective sound muting.
- Choose English or German through Minecraft's language settings.

## Look up profiles and compare records

Use `/fp pv <player>` to open a foraging profile lookup, `/fp top` to browse global leaderboards, or `/fp share` to copy your own profile summary to the clipboard.

Profile lookups and leaderboards need the project's online service. **Sharing your own leaderboard results is optional and disabled by default.** Enabling it verifies your account through Mojang and submits supported progress information. No personal Hypixel API key is required from players.

## Getting started

1. Install **Fabric Loader** and **Fabric API** for **Minecraft 26.3**.
2. Add the Foraging Planner release JAR to your instance's `mods` folder.
3. Join Hypixel SkyBlock and choose a first-run preset.
4. Press **P** or use `/fp settings` to configure the mod.
5. Open `/hotf` and make your foraging stats visible in the tab list so the planner can read them.
6. Use `/fp overview`, `/fp gear`, or `/fp hunting` to explore the main features. Run `/fp help` for command help.

**Requirements:** Minecraft 26.3, Fabric Loader 0.20.1 or newer, Fabric API, and Java 25 or newer. Install the mod on your client. Mod Menu integration is optional.

## How tracking works

Foraging Planner uses bundled game data and information observed in chat, menus, the tab list, and nearby entities. Open relevant menus to refresh perk, equipment, collection, and Hunting Box information.

Upgrade gains, coin values, forecasts, and regrowth timers are estimates. Tracked activity reflects what the mod has observed; it does not reconstruct sessions played while it was absent. Shard goals count new rewards, while trap notices show the last reported snapshot until dismissed.

Settings and tracking history are saved locally. Market features fetch online prices; profile lookups and leaderboards use the configured backend with a random installation identifier. Clipboard sharing remains under your control.

The mod provides planning, tracking, and visual guidance. **You perform the harvesting, movement, and catches yourself.**

Created by **Flori** · **MIT License**
