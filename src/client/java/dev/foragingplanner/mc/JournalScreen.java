package dev.foragingplanner.mc;

import static dev.foragingplanner.mc.ForestTheme.UI_ACCENT;
import static dev.foragingplanner.mc.ForestTheme.UI_AMBER;
import static dev.foragingplanner.mc.ForestTheme.UI_DANGER;
import static dev.foragingplanner.mc.ForestTheme.UI_DIM;
import static dev.foragingplanner.mc.ForestTheme.UI_MUTED;
import static dev.foragingplanner.mc.ForestTheme.UI_PANEL;
import static dev.foragingplanner.mc.ForestTheme.UI_ROW;
import static dev.foragingplanner.mc.ForestTheme.UI_TEXT;
import static dev.foragingplanner.mc.ForestTheme.alpha;
import static dev.foragingplanner.mc.ForestTheme.easeOut;
import static dev.foragingplanner.mc.ForestTheme.round;
import static dev.foragingplanner.mc.ForestTheme.roundOutline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import dev.foragingplanner.core.HotfPlanner;
import dev.foragingplanner.core.Journal;
import dev.foragingplanner.core.PlayerStats;
import dev.foragingplanner.core.TreeMath;

/** The foraging journal: session, hunting, gifts, targets, daily tasks, simulator, profiles and tree guide. */
public final class JournalScreen extends ForestScreen {
	private static final String[] PAGES = {"dashboard", "hunting", "gifts", "targets", "daily", "simulator", "profiles", "guide"};
	private static final Item[] ICONS = {Items.WRITABLE_BOOK, Items.AMETHYST_SHARD, Items.CHEST, Items.TARGET, Items.CLOCK, Items.IRON_AXE, Items.PLAYER_HEAD, Items.OAK_SAPLING};
	private static final List<String> METRICS = List.of("xp", "logs", "forest", "desert", "gifts", "hotf");

	private static boolean requested;
	private static int requestedPage;

	private String metric = "xp", amount = "100000", profile = "", simPerk = "";
	private String shardName = "", shardAmount = "100", huntingFeedback = "", targetFeedback = "";
	private double sweepDelta, fortuneDelta, wisdomDelta;
	private int simLevels = 1;
	private boolean lifetimeShards;

	public JournalScreen(Screen parent) {
		this(parent, 0);
	}

	public JournalScreen(Screen parent, int page) {
		super(parent, PAGES, ICONS, page);
	}

	public static void requestOpen() {
		requested = true;
		requestedPage = 0;
	}

	public static void requestHunting() {
		requested = true;
		requestedPage = 1;
	}

	public static void tick(Minecraft client) {
		if (!requested) return;
		requested = false;
		client.gui.setScreen(new JournalScreen(null, requestedPage));
	}

	private static String tr(String key, Object... args) {
		return Planner.tr("journal." + key, args);
	}

	@Override
	protected String title() {
		return "Foraging Journal";
	}

	@Override
	protected String tagline() {
		return ProfileStore.label();
	}

	@Override
	protected String pageName(int page) {
		return tr("nav_" + PAGES[page]);
	}

	@Override
	protected Item logo() {
		return Items.WRITABLE_BOOK;
	}

	@Override
	protected String footerHint() {
		return tr("scroll_hint");
	}

	@Override
	protected List<Line> build(int page) {
		subtitle = tr(PAGES[page]);
		return switch (page) {
			case 0 -> dashboard();
			case 1 -> hunting();
			case 2 -> gifts();
			case 3 -> targets();
			case 4 -> daily();
			case 5 -> simulator();
			case 6 -> profiles();
			default -> guide();
		};
	}

	@Override
	protected void closed() {
		JournalStore.save();
		Planner.config.save();
	}

	// Dashboard

	@SuppressWarnings("unchecked")
	private List<Line> dashboard() {
		List<Line> out = new ArrayList<>();
		Journal journal = JournalStore.journal;
		Journal.Session s = journal.current;
		String state = tr(s.paused ? "paused" : "recording") + " · " + GearScreenFormat.duration(s.elapsedMs / 1000);
		out.add(row(s.paused ? "clock" : "slime_ball", ProfileStore.label(), state, null, UI_TEXT, null));

		String[] keys = {"xp", "logs", "coins", "forest", "desert", "gifts"};
		String[] icons = {"experience_bottle", "oak_log", "gold_ingot", "oak_sapling", "sand", "chest"};
		String[] labels = new String[keys.length];
		DoubleSupplier[] values = new DoubleSupplier[keys.length];
		Supplier<String>[] subs = new Supplier[keys.length];
		for (int i = 0; i < keys.length; i++) {
			String key = keys[i];
			labels[i] = tr(key);
			values[i] = () -> key.equals("coins") ? JournalStore.value(s.items).coins() : s.value(key);
			subs[i] = () -> key.equals("coins") ? tr("estimated", JournalStore.value(s.items).unpriced()) : Planner.whole(s.rate(key)) + "/h";
		}
		out.add(tiles(icons, labels, values, subs, Planner::whole));

		out.add(buttons(new String[] {tr(s.paused ? "resume" : "pause"), tr("new_session"), tr("export"), "HOTF"}, new Runnable[] {
				() -> {
					SessionRates.flush();
					s.paused = !s.paused;
					SessionRates.clearBaselines();
					JournalStore.save();
				},
				SessionRates::reset,
				() -> {
					try {
						JournalStore.notify(tr("exported", JournalStore.export().toString()));
					} catch (Exception e) {
						JournalStore.notify(tr("export_failed"));
					}
				},
				() -> minecraft.gui.setScreen(new PerkTreeScreen(this))}));

		out.add(header(tr("history_chart")));
		out.add(new Line(110, (g, x, y, w, a, h) -> chart(g, x, y, w, 110, a, s), null));
		double hidden = JournalStore.journal.current.value("sack_hidden");
		String sackNote = hidden > 0 ? " " + tr("sacks_hidden", Planner.whole(hidden)) : JournalStore.sackIncomplete ? " " + tr("sacks_incomplete") : "";
		out.add(text(tr("measurement_note") + sackNote, UI_DIM));

		out.add(header(tr("history")));
		if (journal.history.isEmpty()) out.add(text(tr("history_empty"), UI_DIM));
		for (Journal.Session session : journal.history) {
			double diff = s.rate("xp") - session.rate("xp");
			out.add(row("paper", StatsScreen.date(session.started) + " · " + GearScreenFormat.duration(session.elapsedMs / 1000),
					Planner.whole(session.rate("xp")) + " XP/h", tr("difference", (diff >= 0 ? "+" : "") + Planner.whole(diff)),
					diff >= 0 ? UI_ACCENT : UI_DANGER, null));
		}
		return out;
	}

	private void chart(GuiGraphicsExtractor g, int x, int y, int w, int h, double a, Journal.Session session) {
		round(g, x, y, w, h, 8, alpha(UI_ROW, a));
		roundOutline(g, x, y, w, h, 8, alpha(0x14FFFFFF, a));
		var points = session.samples;
		if (points.size() < 2) {
			g.text(font, tr("chart_wait"), x + 10, y + h / 2 - 4, alpha(UI_MUTED, a), false);
			return;
		}

		int count = Math.min(48, points.size() - 1), start = points.size() - count;
		double[] rates = new double[count];
		double max = 1;
		for (int i = 0; i < count; i++) {
			var p = points.get(start + i);
			var prev = points.get(start + i - 1);
			rates[i] = (p.xp() - prev.xp()) * 3_600_000 / Math.max(1, p.elapsedMs() - prev.elapsedMs());
			max = Math.max(max, rates[i]);
		}

		g.text(font, Planner.whole(max) + " XP/h", x + 9, y + 7, alpha(UI_MUTED, a), false);
		double grow = animated() ? easeOut((System.nanoTime() - pageStartNs()) / 600e6) : 1;
		int top = y + 20, bottom = y + h - 18;
		for (int i = 0; i < count; i++) {
			int bx = x + 9 + i * (w - 18) / count;
			int bw = Math.max(2, (w - 18) / count - 2);
			double delay = Math.clamp(grow * 1.4 - i / (double) count * 0.4, 0, 1);
			int bar = (int) Math.round(rates[i] / max * (bottom - top) * delay);
			boolean last = i == count - 1;
			round(g, bx, bottom - bar, bw, Math.max(1, bar), Math.min(2, bw / 2), alpha(last ? UI_AMBER : UI_ACCENT, a * (0.55 + 0.45 * i / count)));
		}
		g.text(font, tr("chart_recent"), x + 9, y + h - 12, alpha(UI_DIM, a), false);
	}

	// Hunting

	private List<Line> hunting() {
		List<Line> out = new ArrayList<>();
		Journal j = JournalStore.journal;
		out.add(row("amethyst_shard", ProfileStore.label(), tr(j.current.paused ? "paused" : "recording") + " · " + j.current.huntingElapsedMs / 60_000 + " min", null, UI_TEXT, null));
		out.add(tiles(new String[] {"amethyst_shard", "gold_nugget"}, new String[] {HuntingTracker.tr("session"), HuntingTracker.tr("value")},
				new DoubleSupplier[] {() -> j.current.value("shards"), () -> JournalStore.value(j.current.shards).coins()},
				subs(() -> j.current.huntingElapsedMs < 20_000 ? tr("no_eta") : Planner.whole(j.current.rate("shards")) + "/h",
						() -> tr("estimated", JournalStore.value(j.current.shards).unpriced())), Planner::whole));
		out.add(row("chest", HuntingTracker.trapStatus(), null, HuntingTracker.tr("dismiss_traps"), UI_AMBER, (lx, w, b) -> {
			j.hunting.dismissTraps();
			JournalStore.save();
			PlannerHud.rebuild();
		}));

		out.add(header(HuntingTracker.tr("new_goal")));
		out.add(field(HuntingTracker.tr("shard_name"), shardName, 40, value -> shardName = value));
		out.add(field(tr("amount"), shardAmount, 10, value -> shardAmount = value));
		out.add(button(tr("add_target"), () -> {
			try {
				boolean added = TargetProgress.shard(shardName, Integer.parseInt(shardAmount.trim()));
				huntingFeedback = HuntingTracker.tr(added ? "goal_added" : "goal_invalid");
			} catch (NumberFormatException e) {
				huntingFeedback = HuntingTracker.tr("goal_invalid");
			}
		}));
		if (!huntingFeedback.isEmpty()) out.add(text(huntingFeedback, UI_AMBER));
		out.add(text(HuntingTracker.tr("goal_note"), UI_DIM));

		out.add(header(HuntingTracker.tr("options")));
		out.add(toggle("amethyst_shard", HuntingTracker.tr("tracking"), null, () -> Planner.config.huntingEnabled, v -> { Planner.config.huntingEnabled = v; Planner.config.save(); }));
		out.add(toggle("chest", HuntingTracker.tr("trap_alerts"), null, () -> Planner.config.trapAlerts, v -> { Planner.config.trapAlerts = v; Planner.config.save(); }));
		out.add(toggle("lead", HuntingTracker.tr("reel"), null, () -> Planner.config.reelCue, v -> { Planner.config.reelCue = v; Planner.config.save(); }));
		out.add(toggle("note_block", HuntingTracker.tr("sound"), null, () -> Planner.config.huntingSound, v -> { Planner.config.huntingSound = v; Planner.config.save(); }));
		out.add(toggle("bell", HuntingTracker.tr("goal_alerts"), null, () -> j.reminders, v -> { j.reminders = v; JournalStore.save(); }));
		out.add(buttons(new String[] {HuntingTracker.tr("test"), Planner.tr("settings.position")},
				new Runnable[] {HuntingTracker::preview, () -> minecraft.gui.setScreen(new HudEditorScreen(this))}));
		out.add(text(HuntingTracker.tr("measurement_note"), UI_DIM));
		out.add(text(HuntingTracker.tr("trap_note"), UI_DIM));

		out.add(header(HuntingTracker.tr(lifetimeShards ? "all_shards" : "session_shards")));
		out.add(button(HuntingTracker.tr("switch_view"), () -> lifetimeShards = !lifetimeShards));
		Map<String, Long> shards = lifetimeShards ? j.hunting.shards : j.current.shards;
		if (shards.isEmpty()) out.add(text(HuntingTracker.tr("empty"), UI_DIM));
		shards.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).forEach(entry -> {
			String id = entry.getKey();
			String name = j.hunting.names.getOrDefault(id, id.substring(Math.min(6, id.length())).replace('_', ' '));
			Double price = Planner.PRICES.get(id);
			String value = price == null || !Double.isFinite(price) || price <= 0 ? tr("price_unknown") : "≈ " + GearScreenFormat.coins(price * entry.getValue());
			out.add(row("amethyst_shard", Planner.whole(entry.getValue()) + " × " + name, value, HuntingTracker.tr("use_goal"), UI_AMBER, (lx, w, b) -> shardName = name));
		});
		return out;
	}

	@SafeVarargs
	@SuppressWarnings("varargs")
	private static Supplier<String>[] subs(Supplier<String>... values) {
		return values;
	}

	@Override
	protected void drawOverlay(GuiGraphicsExtractor g, int mouseX, int mouseY, double open) {
		if (page != 1 || !HuntingTracker.previewing()) return;
		int cx = width / 2, cy = height / 2;
		round(g, cx - 80, cy - 26, 160, 52, 8, alpha(UI_PANEL, open));
		roundOutline(g, cx - 80, cy - 26, 160, 52, 8, alpha(UI_AMBER, open));
		g.centeredText(font, HuntingTracker.tr("preview"), cx, cy - 17, UI_MUTED);
		g.centeredText(font, "REEL!", cx, cy - 3, UI_AMBER);
		g.centeredText(font, HuntingTracker.tr("reel_action"), cx, cy + 11, UI_TEXT);
	}

	// Gifts

	private List<Line> gifts() {
		List<Line> out = new ArrayList<>();
		Journal j = JournalStore.journal;
		long total = j.gifts.values().stream().mapToLong(Long::longValue).sum();
		for (var tree : Planner.data.trees) {
			long count = j.gifts.getOrDefault(tree.id, 0L);
			out.add(bar("chest", tree.name, Planner.whole(count) + " · " + tr("since_tracking"), total == 0 ? 0 : count / (double) total, UI_AMBER, null));
		}
		out.add(text(tr("gift_note"), UI_DIM));

		out.add(header(tr("loot")));
		if (j.loot.isEmpty()) out.add(text(tr("loot_empty"), UI_DIM));
		j.loot.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).forEach(item -> {
			Double price = Planner.PRICES.get(item.getKey());
			out.add(row("paper", Planner.whole(item.getValue()) + " × " + StatsScreen.prettyName(item.getKey()), null,
					price == null ? tr("price_unknown") : "≈ " + GearScreenFormat.coins(price * item.getValue()), price == null ? UI_DIM : UI_AMBER, null));
		});
		return out;
	}

	// Targets

	private List<Line> targets() {
		List<Line> out = new ArrayList<>();
		out.add(header(tr("new_target")));
		out.add(row("target", tr(metric), tr("change_metric"), "↻", UI_ACCENT, (lx, w, b) -> metric = METRICS.get((METRICS.indexOf(metric) + 1) % METRICS.size())));
		out.add(field(tr("amount"), amount, 16, value -> amount = value));
		out.add(buttons(new String[] {tr("add_target"), tr("next_level")}, new Runnable[] {
				() -> {
					try {
						double parsed = Double.parseDouble(amount.trim());
						if (parsed <= 0 || !Double.isFinite(parsed)) throw new NumberFormatException();
						TargetProgress.add(metric, parsed);
						targetFeedback = "";
					} catch (NumberFormatException e) {
						targetFeedback = tr("amount") + " ?";
					}
				},
				() -> {
					if (SessionRates.xpRemaining() > 0) TargetProgress.add("xp", SessionRates.xpRemaining());
					else targetFeedback = tr("xp_unknown");
				}}));
		if (!targetFeedback.isEmpty()) out.add(text(targetFeedback, UI_DANGER));
		out.add(text(tr("target_note"), UI_DIM));

		for (Journal.Target target : List.copyOf(JournalStore.journal.targets)) {
			out.add(bar("target", target.name, TargetProgress.detail(target) + "   ✕", TargetProgress.fraction(target), UI_ACCENT, (lx, w, b) -> {
				if (lx > w - 24) {
					JournalStore.journal.targets.remove(target);
					JournalStore.save();
				}
			}));
		}
		return out;
	}

	// Daily

	private List<Line> daily() {
		List<Line> out = new ArrayList<>();
		Journal j = JournalStore.journal;
		j.rollDay(System.currentTimeMillis());
		subtitle = j.day + " · " + tr("observed");
		out.add(bar("chest", tr("gifts"), j.dailyGifts + " / 250", j.dailyGifts / 250.0, UI_AMBER, null));
		out.add(toggle("sunflower", tr(j.dailyWish ? "wish_done" : "wish_open"), null, () -> j.dailyWish, v -> { j.dailyWish = v; JournalStore.save(); }));
		out.add(bar("golden_hoe", tr("contests_count", j.dailyContests), j.dailyContests + " / 3", j.dailyContests / 3.0, UI_ACCENT, null));
		out.add(buttons(new String[] {tr("contest_plus"), tr("contest_minus")}, new Runnable[] {
				() -> { j.dailyContests = Math.min(3, j.dailyContests + 1); JournalStore.save(); },
				() -> { j.dailyContests = Math.max(0, j.dailyContests - 1); JournalStore.save(); }}));
		out.add(toggle("bell", tr("reminders_on"), null, () -> j.reminders, v -> { j.reminders = v; JournalStore.save(); }));
		out.add(row("clock", tr("reset_hour", j.resetHourUtc), "UTC", "↻", UI_ACCENT, (lx, w, b) -> { j.resetHourUtc = (j.resetHourUtc + 1) % 24; JournalStore.save(); }));
		out.add(text(tr("daily_note"), UI_DIM));
		return out;
	}

	// Simulator

	private PlayerStats simulated() {
		var s = SkyBlockReader.stats();
		s = new PlayerStats(Math.max(0, s.sweep() + sweepDelta), Math.max(0, s.fortune() + fortuneDelta), Math.max(0, s.wisdom() + wisdomDelta),
				s.strength(), s.speed(), s.dailyWhispers(), s.giftBonus());
		var perk = Planner.data.perk(simPerk);
		if (perk != null) {
			var path = HotfPlanner.unlockPath(Planner.data, Planner.config.perkLevels, perk.name,
					Math.min(perk.maxLevel, Planner.config.perkLevels.getOrDefault(perk.name, 0) + simLevels), Planner.config.hotfTier);
			var levels = new LinkedHashMap<>(Planner.config.perkLevels);
			for (var step : path) {
				s = HotfPlanner.advance(Planner.data, levels, s, step.perk(), step.toLevel() - step.fromLevel());
				levels.put(step.perk().name, step.toLevel());
			}
		}
		return s;
	}

	private Line delta(String key, double initial, java.util.function.DoubleConsumer set) {
		return field(tr(key), Planner.decimal(initial), 10, value -> {
			try {
				double v = Double.parseDouble(value.trim().replace(',', '.'));
				if (Double.isFinite(v) && Math.abs(v) <= 100_000) set.accept(v);
			} catch (NumberFormatException ignored) {
				// Keep the last valid value while typing.
			}
		});
	}

	private List<Line> simulator() {
		List<Line> out = new ArrayList<>();
		out.add(text(tr("sim_note"), UI_DIM));
		out.add(delta("sweep_delta", sweepDelta, v -> sweepDelta = v));
		out.add(delta("fortune_delta", fortuneDelta, v -> fortuneDelta = v));
		out.add(delta("wisdom_delta", wisdomDelta, v -> wisdomDelta = v));
		List<String> perks = new ArrayList<>(List.of(""));
		Planner.data.hotf.perks.forEach(p -> perks.add(p.name));
		out.add(row("oak_sapling", simPerk.isEmpty() ? tr("no_perk") : simPerk, tr("change_metric"), "+" + simLevels, UI_ACCENT,
				(lx, w, b) -> simPerk = perks.get((perks.indexOf(simPerk) + (b == 1 ? perks.size() - 1 : 1)) % perks.size())));
		out.add(row("experience_bottle", tr("sim_levels", simLevels), null, "↻", UI_ACCENT, (lx, w, b) -> simLevels = simLevels % 10 + 1));
		out.add(header("Logs / Hit"));

		for (var tree : Planner.data.trees) {
			out.add(new Line(48, (g, x, y, w, a, h) -> {
				var before = SkyBlockReader.stats();
				var after = simulated();
				int from = TreeMath.logsPerHit(tree, before.sweep()), to = TreeMath.logsPerHit(tree, after.sweep());
				card(g, x, y, w, 48, a, 0);
				tile(g, tree.id.equals("fig") ? "stripped_spruce_wood" : tree.id.equals("mangrove") ? "mangrove_log" : "stripped_birch_wood", x + 6, y + 13, a);
				g.text(font, tree.name + " · " + from + " → " + to + " Logs/Hit", x + 35, y + 7, alpha(to > from ? UI_AMBER : UI_TEXT, a), false);
				String xp = Planner.whole(TreeMath.xpPerHit(tree, from, before)) + " → " + Planner.whole(TreeMath.xpPerHit(tree, to, after)) + " XP/Hit";
				g.text(font, xp, x + 35, y + 20, alpha(UI_ACCENT, a), false);
				double next = TreeMath.sweepToNextBreakpoint(tree, after.sweep());
				g.text(font, tr("next_breakpoint", next < 0 ? "—" : Planner.decimal(next)), x + 35, y + 33, alpha(UI_MUTED, a), false);
			}, null));
		}
		if (!simPerk.isEmpty()) out.add(text(tr("sim_path_note"), UI_DIM));
		return out;
	}

	// Profiles

	private List<Line> profiles() {
		List<Line> out = new ArrayList<>();
		out.add(row("player_head", tr("active_profile", ProfileStore.label()), null, null, UI_TEXT, null));
		out.add(text(tr("profile_note"), UI_DIM));
		out.add(field(tr("profile_name"), profile, 40, value -> profile = value));
		out.add(button(tr("select_profile"), () -> ProfileStore.select(profile.trim())));
		out.add(header(tr("saved_profiles")));

		try {
			var directory = ModConfig.directory().resolve("profiles");
			if (java.nio.file.Files.exists(directory)) {
				try (var dirs = java.nio.file.Files.list(directory)) {
					for (var dir : dirs.toList()) {
						var file = dir.resolve("profile.json");
						if (!java.nio.file.Files.exists(file)) continue;
						var meta = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(file)).getAsJsonObject();
						if (!meta.get("account").getAsString().equals(minecraft.getUser().getProfileId().toString())) continue;
						String name = meta.get("name").getAsString();
						boolean active = name.equals(ProfileStore.label());
						out.add(row("player_head", name, null, active ? "●" : tr("select_profile"), active ? UI_ACCENT : UI_MUTED, (lx, w, b) -> ProfileStore.select(name)));
					}
				}
			}
		} catch (Exception e) {
			Planner.LOGGER.warn("Could not list profiles", e);
		}
		return out;
	}

	// Guide

	private List<Line> guide() {
		List<Line> out = new ArrayList<>();
		out.add(text(tr("guide_note"), UI_DIM));
		out.add(toggle("oak_sapling", tr("guide_on"), null, () -> Planner.config.treeAssistant, v -> { Planner.config.treeAssistant = v; Planner.config.save(); }));
		out.add(row("oak_log", StatsScreen.capital(Planner.config.guideTree), tr("change_tree"), "↻", UI_ACCENT, (lx, w, b) -> {
			var trees = List.of("fig", "mangrove", "helix");
			Planner.config.guideTree = trees.get((trees.indexOf(Planner.config.guideTree) + 1) % trees.size());
			Planner.config.guidePhase = 0;
			Planner.config.save();
		}));
		out.add(row("compass", tr("next_phase"), TreeAssistant.description(), "→", UI_AMBER, (lx, w, b) -> TreeAssistant.nextPhase()));
		out.add(text(tr("guide_limits"), UI_DIM));
		return out;
	}
}
