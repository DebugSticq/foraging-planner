package dev.foragingplanner.mc;

import static dev.foragingplanner.mc.ForestTheme.UI_ACCENT;
import static dev.foragingplanner.mc.ForestTheme.UI_AMBER;
import static dev.foragingplanner.mc.ForestTheme.UI_DIM;
import static dev.foragingplanner.mc.ForestTheme.UI_PANEL;
import static dev.foragingplanner.mc.ForestTheme.UI_TEXT;
import static dev.foragingplanner.mc.ForestTheme.alpha;
import static dev.foragingplanner.mc.ForestTheme.round;
import static dev.foragingplanner.mc.ForestTheme.roundOutline;
import static dev.foragingplanner.mc.ForestTheme.shadow;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import dev.foragingplanner.core.GameData;
import dev.foragingplanner.core.HotfPlanner;
import dev.foragingplanner.core.Journal;
import dev.foragingplanner.core.PlayerStats;
import dev.foragingplanner.core.TreeMath;
import dev.foragingplanner.core.TreeReport;

/**
 * "Why?" explanations for the planner's advice, and the estimate of what a stat change means for
 * logs, coins and XP per hour. The popup is shared by every screen that offers an ⓘ.
 */
public final class Why {
	private static String title;
	private static List<String> lines = List.of();
	private static long openedNs;

	private Why() {}

	private static String tr(String key, Object... args) {
		return Planner.tr("why." + key, args);
	}

	// Popup

	public static void open(String popupTitle, List<String> popupLines) {
		title = popupTitle;
		lines = popupLines;
		openedNs = System.nanoTime();
	}

	public static boolean isOpen() {
		return title != null;
	}

	public static void close() {
		title = null;
	}

	/** Draws the popup in the middle of the screen; call last. */
	public static void draw(GuiGraphicsExtractor g, Font font, int screenW, int screenH) {
		if (title == null) return;
		double a = Planner.config.animations ? Math.clamp((System.nanoTime() - openedNs) / 150e6, 0, 1) : 1;
		int maxW = Math.min(320, screenW - 40);
		List<FormattedCharSequence> wrapped = new ArrayList<>();
		for (String line : lines) {
			if (line.isEmpty()) wrapped.add(FormattedCharSequence.EMPTY);
			else wrapped.addAll(font.split(Component.literal(line), maxW - 20));
		}
		int w = font.width(title) + 20;
		for (FormattedCharSequence s : wrapped) w = Math.max(w, font.width(s) + 20);
		w = Math.min(w, maxW);
		int h = 34 + wrapped.size() * 10;
		int x = (screenW - w) / 2, y = Math.max(8, (screenH - h) / 2);
		g.fill(0, 0, screenW, screenH, alpha(0xFF000000, 0.35 * a));
		shadow(g, x, y, w, h, 8, a);
		round(g, x, y, w, h, 8, alpha(UI_PANEL | 0xFF000000, a));
		roundOutline(g, x, y, w, h, 8, alpha(UI_ACCENT, 0.6 * a));
		g.text(font, "ⓘ " + title, x + 10, y + 8, alpha(UI_AMBER, a), false);
		for (int i = 0; i < wrapped.size(); i++) g.text(font, wrapped.get(i), x + 10, y + 22 + i * 10, alpha(UI_TEXT, a), false);
		String close = tr("close");
		g.text(font, close, x + w - 10 - font.width(close), y + h - 11, alpha(UI_DIM, a), false);
	}

	/** A small round "i" badge. */
	public static void badge(GuiGraphicsExtractor g, Font font, int x, int y, double a, boolean hover) {
		round(g, x, y, 10, 10, 5, alpha(hover ? UI_AMBER : 0xFF2A4A34, a));
		g.text(font, "i", x + 4, y + 1, alpha(hover ? 0xFF10170F : UI_TEXT, a), false);
	}

	// Estimates

	/** Logs, coins and XP per hour from the current session (10+ minutes) or the recent average; zeros when unknown. */
	public record Rates(double logs, double coins, double xp, boolean fromSession) {
		boolean known() {
			return logs > 0 || xp > 0;
		}
	}

	public static Rates rates() {
		Journal j = JournalStore.journal;
		if (j.current.elapsedMs >= 10 * 60_000L) return of(List.of(j.current), true);
		List<Journal.Session> recent = new ArrayList<>();
		for (Journal.Session s : j.history) {
			if (s.elapsedMs >= 10 * 60_000L) recent.add(s);
			if (recent.size() >= 10) break;
		}
		return of(recent, false);
	}

	private static Rates of(List<Journal.Session> sessions, boolean current) {
		double ms = 0, logs = 0, xp = 0, coins = 0;
		for (Journal.Session s : sessions) {
			ms += s.elapsedMs;
			logs += s.value("logs");
			xp += s.value("xp");
			coins += JournalStore.value(s.items).coins();
		}
		if (ms <= 0) return new Rates(0, 0, 0, current);
		double h = ms / 3_600_000.0;
		return new Rates(logs / h, coins / h, xp / h, current);
	}

	/** What a stat change does on the tree the player farms (fixed tree, else the best one). */
	public record Estimate(String tree, double dropsBefore, double dropsAfter, double xpBefore, double xpAfter,
						   double goalPercent, Rates rates) {
		public double logsRatio() {
			return dropsBefore <= 0 ? 1 : dropsAfter / dropsBefore;
		}

		public double xpRatio() {
			return xpBefore <= 0 ? 1 : xpAfter / xpBefore;
		}
	}

	public static Estimate estimate(double sweep, double fortune, double wisdom) {
		PlayerStats stats = SkyBlockReader.stats();
		PlayerStats after = new PlayerStats(stats.sweep() + sweep, stats.fortune() + fortune, stats.wisdom() + wisdom,
				stats.strength(), stats.speed(), stats.dailyWhispers(), stats.giftBonus());
		GameData.Tree tree = farmedTree();
		if (tree == null) return new Estimate("?", 0, 0, 0, 0, 0, rates());
		double logsBefore = TreeMath.logsPerHit(tree, stats.sweep()), logsAfter = TreeMath.logsPerHit(tree, after.sweep());
		return new Estimate(tree.name, TreeMath.dropsPerHit(logsBefore, stats), TreeMath.dropsPerHit(logsAfter, after),
				TreeMath.xpPerHit(tree, logsBefore, stats), TreeMath.xpPerHit(tree, logsAfter, after),
				GearAdvisor.gainPercent(sweep, fortune, wisdom), rates());
	}

	static GameData.Tree farmedTree() {
		GameData.Tree fixed = Planner.data.tree(Planner.config.tree);
		if (fixed != null) return fixed;
		List<TreeReport.Row> rows = Planner.treeRows();
		for (TreeReport.Row r : rows) if (r.best()) return r.tree();
		return rows.isEmpty() ? null : rows.get(0).tree();
	}

	/** Lines that turn an estimate into "per hour" numbers. */
	public static List<String> estimateLines(Estimate e) {
		List<String> out = new ArrayList<>();
		out.add(tr("on_tree", e.tree(), Planner.decimal(e.dropsBefore()), Planner.decimal(e.dropsAfter()),
				Planner.decimal(e.xpBefore()), Planner.decimal(e.xpAfter())));
		Rates r = e.rates();
		if (r.known()) {
			out.add(tr(r.fromSession() ? "rates_session" : "rates_average"));
			out.add("§7  " + tr("logs_h", Planner.whole(r.logs()), Planner.whole(r.logs() * e.logsRatio()), percent(e.logsRatio())));
			if (r.coins() > 0) out.add("§7  " + tr("coins_h", GearScreenFormat.coins(r.coins()), GearScreenFormat.coins(r.coins() * e.logsRatio()), percent(e.logsRatio())));
			out.add("§7  " + tr("xp_h", Planner.whole(r.xp()), Planner.whole(r.xp() * e.xpRatio()), percent(e.xpRatio())));
		} else {
			out.add("§8" + tr("no_rates"));
		}
		return out;
	}

	static String percent(double ratio) {
		double p = (ratio - 1) * 100;
		return (p >= 0 ? "+" : "") + String.format(Locale.US, "%.1f%%", p);
	}

	static String stats(double sweep, double fortune, double wisdom) {
		StringBuilder b = new StringBuilder();
		if (Math.abs(sweep) >= 0.05) b.append("§a").append(signed(sweep)).append(" Sweep  ");
		if (Math.abs(fortune) >= 0.05) b.append("§6").append(signed(fortune)).append(" Fortune  ");
		if (Math.abs(wisdom) >= 0.05) b.append("§b").append(signed(wisdom)).append(" Wisdom");
		return b.isEmpty() ? "§8" + tr("no_stat_change") : b.toString().trim();
	}

	private static String signed(double v) {
		return (v >= 0 ? "+" : "") + Planner.decimal(v);
	}

	private static String goalName() {
		return Planner.tr("option." + Planner.goal().id());
	}

	// Explanations

	public static void pick(GearAdvisor.Pick pick) {
		List<String> out = new ArrayList<>();
		GearData.Item item = pick.item();
		out.add(pick.replaces() == null ? tr("slot_empty", Planner.tr("gear.slot." + item.slot)) : tr("swap", pick.replaces().name, item.name));
		out.add(tr("stat_change", stats(pick.sweep(), pick.fortune(), pick.wisdom())));
		if (item.fortunePerSweep > 0 || "pet".equals(item.slot)) out.add("§8" + tr("pet_conversion"));
		out.add(tr("goal_gain", goalName(), String.format(Locale.US, "%.2f", pick.gainPercent())));
		out.add("");
		out.addAll(estimateLines(estimate(pick.sweep(), pick.fortune(), pick.wisdom())));
		out.add("");
		if (pick.price() != null && pick.price() > 0) {
			out.add(tr("price", GearScreenFormat.coins(pick.price()), Planner.tr(pick.bazaar() ? "why.source_bazaar" : "why.source_ah")));
			out.add(tr("per_million", String.format(Locale.US, "%.2f", pick.perMillion())));
			Rates r = rates();
			double extra = r.coins() * (estimate(pick.sweep(), pick.fortune(), pick.wisdom()).logsRatio() - 1);
			if (extra > 0) out.add(tr("payback", GearScreenFormat.eta(pick.price() / extra)));
		} else {
			out.add("§8" + tr("no_price"));
		}
		if (pick.stronger() != null) out.add("§7" + tr("stronger", pick.stronger().item().name, String.format(Locale.US, "%.1f", pick.stronger().gainPercent())));
		if (item.foraging > 0) out.add("§8" + tr("level_req", item.foraging));
		open(item.name, out);
	}

	public static void upgrade(UpgradeAdvisor.Option o) {
		List<String> out = new ArrayList<>();
		out.add(o.piece() + " · " + o.title());
		if (o.detail() != null && !o.detail().isEmpty()) out.add("§7" + o.detail());
		out.add(tr("stat_change", stats(o.sweep(), o.fortune(), o.wisdom())));
		if (o.gainPercent() != null) out.add(tr("goal_gain", goalName(), String.format(Locale.US, "%.2f", o.gainPercent())));
		out.add("");
		out.addAll(estimateLines(estimate(o.sweep(), o.fortune(), o.wisdom())));
		if (o.price() != null && o.price() > 0) {
			out.add("");
			out.add(tr("price", GearScreenFormat.coins(o.price()), ""));
			out.add(tr("per_million", String.format(Locale.US, "%.2f", o.perMillion())));
		}
		open(o.title(), out);
	}

	/** Explains the next level of a perk: what it gives, what it costs, and where it ranks. */
	public static void perk(GameData.Perk perk) {
		List<String> out = new ArrayList<>();
		int level = Planner.config.perkLevels.getOrDefault(perk.name, 0);
		HotfPlanner.Upgrade u = null;
		int rank = 0;
		List<HotfPlanner.Upgrade> ranked = Planner.upgrades();
		for (int i = 0; i < ranked.size(); i++) {
			if (ranked.get(i).perk() == perk) {
				u = ranked.get(i);
				rank = i + 1;
				break;
			}
		}
		if (level >= perk.maxLevel) {
			out.add(tr("perk_max"));
			open(perk.name, out);
			return;
		}
		PlayerStats stats = SkyBlockReader.stats();
		PlayerStats after = HotfPlanner.advance(Planner.data, Planner.config.perkLevels, stats, perk, 1);
		double dS = after.sweep() - stats.sweep(), dF = after.fortune() - stats.fortune(), dW = after.wisdom() - stats.wisdom();
		out.add(tr("perk_level", level, level + 1, perk.maxLevel));
		out.add(tr("stat_change", stats(dS, dF, dW)));
		if (!perk.hasValuedEffects()) out.add("§8" + tr("perk_not_valued", perk.note == null ? "" : perk.note));
		double gain = GearAdvisor.gainPercent(dS, dF, dW);
		long cost = dev.foragingplanner.core.PerkMath.costToReach(perk, level + 1);
		out.add(tr("goal_gain", goalName(), String.format(Locale.US, "%.3f", u != null ? u.gainPercent() : gain)));
		out.add(tr("perk_cost", Planner.whole(cost), "desert".equals(perk.currency) ? "Desert" : "Forest"));
		out.add(tr("perk_efficiency", String.format(Locale.US, "%.3f", (u != null ? u.gainPercent() : gain) / Math.max(1, cost) * 1000)));
		if (rank > 0) out.add("§6" + tr("perk_rank", rank, ranked.size()));
		else if (level == 0) out.add("§7" + tr("perk_locked"));
		out.add("");
		out.addAll(estimateLines(estimate(dS, dF, dW)));
		open(perk.name, out);
	}

	public static void shard(ShardAdvisor.Plan p) {
		List<String> out = new ArrayList<>();
		GearData.Shard shard = p.shard();
		out.add(shard.ability + " · " + shard.effect);
		out.add(tr("shard_level", p.level(), p.inBox()));
		if (!p.done()) out.add(tr("shard_need", p.toNext(), p.toMax(), Planner.tr("gear.rarity." + shard.rarity.toLowerCase(Locale.ROOT))));
		if (!p.counted()) {
			out.add("§8" + tr("shard_not_counted"));
		} else if (!p.onFarmedTree()) {
			GameData.Tree farmed = farmedTree();
			out.add("§7" + tr("shard_other_tree", shard.tree.replace(",", ", "), farmed == null ? "?" : farmed.name));
		} else {
			if (p.statPerLevel() > 0 && !"chop".equals(shard.stat)) out.add(tr("shard_per_level", Planner.decimal(p.statPerLevel()), shard.stat));
			if (shard.perDigit) out.add("§8" + tr("shard_digits", ShardAdvisor.digits(farmedTree())));
			if ("day".equals(shard.condition) || "night".equals(shard.condition)) out.add("§8" + tr("shard_half"));
			if ("commons".equals(shard.condition)) out.add("§8" + tr("shard_commons", ShardAdvisor.commonAttributes()));
			if ("frog".equals(shard.condition) && p.statPerLevel() <= 0) out.add("§8" + tr("shard_frog"));
			out.add(tr("goal_gain", goalName(), String.format(Locale.US, "%.2f", p.gainNext())) + " " + tr("shard_next_level"));
			out.add(tr("goal_gain", goalName(), String.format(Locale.US, "%.2f", p.gainMax())) + " " + tr("shard_to_max"));
			if (p.gainMax() > 0 && "sweep,fortune,wisdom".contains(shard.stat)) {
				double amount = p.statPerLevel() * (10 - p.level());
				out.add("");
				out.addAll(estimateLines(switch (shard.stat) {
					case "sweep" -> estimate(amount, 0, 0);
					case "fortune" -> estimate(0, amount, 0);
					default -> estimate(0, 0, amount);
				}));
			}
		}
		if (p.price() != null && !p.done()) {
			out.add("");
			out.add(tr("shard_price", GearScreenFormat.coins(p.price()), GearScreenFormat.coins(p.costToNext()), GearScreenFormat.coins(p.costToMax())));
			if (p.perMillion() > 0) out.add(tr("per_million", String.format(Locale.US, "%.2f", p.perMillion())));
		}
		out.add("§8" + tr("shard_click"));
		open(shard.name + " Shard", out);
	}

	public static void tree(TreeReport.Row row, List<TreeReport.Row> all) {
		List<String> out = new ArrayList<>();
		double price = Planner.PRICES.getOrDefault(row.tree().bazaarId, 0.0);
		out.add(tr("tree_logs", Planner.decimal(row.logs()), row.measured() ? tr("measured") : tr("calculated")));
		out.add(tr("tree_drops", Planner.decimal(row.dropsPerHit())));
		out.add(tr("tree_coins", GearScreenFormat.coins(row.dropsPerHit() * price), GearScreenFormat.coins(price)));
		out.add(tr("tree_xp", Planner.decimal(row.xpPerHit())));
		if (row.sweepToNext() >= 0) out.add(tr("tree_next", Planner.decimal(Math.ceil(row.sweepToNext()))));
		TreeReport.Row best = null;
		for (TreeReport.Row r : all) if (r.best()) best = r;
		out.add("");
		out.add(tr("tree_goal", goalName(), Planner.decimal(row.value())));
		if (best != null && best != row && best.value() > 0) {
			out.add("§7" + tr("tree_vs_best", best.tree().name, String.format(Locale.US, "%.0f", (1 - row.value() / best.value()) * 100)));
		} else if (row.best()) {
			out.add("§6" + tr("tree_is_best"));
		}
		open(row.tree().name, out);
	}
}
