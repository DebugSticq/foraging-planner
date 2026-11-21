package dev.foragingplanner.mc;

import static dev.foragingplanner.mc.ForestTheme.UI_ACCENT;
import static dev.foragingplanner.mc.ForestTheme.UI_ACCENT_SOFT;
import static dev.foragingplanner.mc.ForestTheme.UI_AMBER;
import static dev.foragingplanner.mc.ForestTheme.UI_DANGER;
import static dev.foragingplanner.mc.ForestTheme.UI_DIM;
import static dev.foragingplanner.mc.ForestTheme.UI_MUTED;
import static dev.foragingplanner.mc.ForestTheme.UI_ROW;
import static dev.foragingplanner.mc.ForestTheme.UI_TEXT;
import static dev.foragingplanner.mc.ForestTheme.UI_TILE;
import static dev.foragingplanner.mc.ForestTheme.alpha;
import static dev.foragingplanner.mc.ForestTheme.easeOut;
import static dev.foragingplanner.mc.ForestTheme.fit;
import static dev.foragingplanner.mc.ForestTheme.round;
import static dev.foragingplanner.mc.ForestTheme.roundOutline;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.Items;

import dev.foragingplanner.core.GameData;
import dev.foragingplanner.core.Journal;
import dev.foragingplanner.core.TreeReport;

/**
 * Statistics and helpers in one place: overview with forecasts and records, daily goals, session
 * history with charts, contests, rare drops, collections, selling advice, tree comparison, price
 * alarms and routes.
 */
public final class StatsScreen extends ForestScreen {
	static final String[] TABS = {"overview", "goals", "sessions", "contests", "drops", "collections", "selling", "trees", "alarms", "routes", "chapters", "leaderboard", "timeline"};
	private static final net.minecraft.world.item.Item[] ICONS = {Items.SPYGLASS, Items.SLIME_BALL, Items.CLOCK, Items.GOLDEN_HOE,
			Items.GOLD_INGOT, Items.CHEST, Items.EMERALD, Items.OAK_SAPLING, Items.BELL, Items.MAP, Items.WRITABLE_BOOK, Items.GOLDEN_HOE, Items.CLOCK};
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM. HH:mm").withZone(ZoneId.systemDefault());
	private static final double BAZAAR_TAX = 0.0125;

	private static int requestedTab = -1;

	public StatsScreen(Screen parent) {
		this(parent, ForestScreen.lastPage(StatsScreen.class, 0));
	}

	public StatsScreen(Screen parent, int tab) {
		super(parent, TABS, ICONS, tab);
	}

	public static void requestOpen(int tab) {
		requestedTab = tab;
	}

	public static void tick(Minecraft client) {
		if (requestedTab < 0) return;
		int tab = requestedTab;
		requestedTab = -1;
		client.gui.setScreen(new StatsScreen(null, tab));
	}

	private static String tr(String key, Object... args) {
		return Planner.tr("stats." + key, args);
	}

	@Override
	protected String menuKey() {
		return "stats";
	}

	@Override
	protected String title() {
		return tr("title");
	}

	@Override
	protected String tagline() {
		return tr("subtitle");
	}

	@Override
	protected String pageName(int page) {
		return tr("tab." + TABS[page]);
	}

	@Override
	protected net.minecraft.world.item.Item logo() {
		return Items.SPYGLASS;
	}

	@Override
	protected String footerHint() {
		return tr("keys");
	}

	@Override
	protected void init() {
		NpcPrices.load();
		super.init();
	}

	@Override
	protected List<Line> build(int page) {
		return switch (page) {
			case 0 -> overview();
			case 1 -> goals();
			case 2 -> sessions();
			case 3 -> contests();
			case 4 -> drops();
			case 5 -> collections();
			case 6 -> selling();
			case 7 -> trees();
			case 8 -> alarms();
			case 9 -> routes();
			case 10 -> chapters();
			case 12 -> timeline();
			default -> leaderboard();
		};
	}

	@Override
	protected void closed() {
		Progress.save(true);
	}

	private static String coins(double value) {
		return GearScreenFormat.coins(value);
	}

	private static String metric(String key) {
		return Planner.tr("metric." + key);
	}

	// Overview

	private List<Line> overview() {
		List<Line> out = new ArrayList<>();
		Journal j = JournalStore.journal;
		Journal.Session s = j.current;
		Progress p = Progress.get();
		subtitle = tr("about.overview");

		out.add(header(tr("forecast")));
		int level = GearAdvisor.foragingLevel();
		double remaining = SessionRates.xpRemaining();
		double xpRate = s.rate("xp");
		if (remaining > 0 && xpRate > 0) {
			out.add(row("experience_bottle", tr("forecast_level", level >= 0 ? level + 1 : "?"), tr("forecast_level_sub", Planner.whole(remaining), Planner.whole(xpRate)),
					GearScreenFormat.eta(remaining / xpRate), UI_ACCENT, null));
		} else {
			out.add(row("experience_bottle", tr("forecast_level", level >= 0 ? level + 1 : "?"), tr("forecast_wait"), "–", UI_DIM, null));
		}

		if (p.hotfNeeded > 0) {
			double left = Math.max(0, p.hotfNeeded - p.hotfCurrent);
			double hotfRate = s.rate("hotf");
			double contests = j.totals.getOrDefault("contests", 0.0);
			double perContest = contests > 0 ? j.totals.getOrDefault("hotf_contest", 0.0) / contests : 0;
			String sub = tr("forecast_hotf_sub", Planner.whole(left)) + (perContest > 0 ? " · " + tr("forecast_contests", Math.max(1, (long) Math.ceil(left / perContest))) : "");
			out.add(row("oak_sapling", tr("forecast_hotf", p.hotfNextTier), sub, hotfRate > 0 ? GearScreenFormat.eta(left / hotfRate) : "–", UI_ACCENT, null));
		} else {
			out.add(row("oak_sapling", tr("forecast_hotf", "?"), tr("forecast_hotf_open"), "–", UI_DIM, null));
		}

		Progress.Collection nearest = null;
		double nearestEta = Double.POSITIVE_INFINITY;
		for (Progress.Collection c : p.collections.values()) {
			double eta = collectionEta(c);
			if (eta < nearestEta) { nearestEta = eta; nearest = c; }
		}
		if (nearest != null) {
			out.add(row("chest", tr("forecast_collection", nearest.name, nearest.nextTier),
					Planner.whole(nearest.current) + " / " + Planner.whole(nearest.needed), GearScreenFormat.eta(nearestEta), UI_ACCENT, null));
		}

		out.add(header(tr("records")));
		Journal.Session bestXp = null, bestCoins = null, mostLogs = null, longest = null;
		List<Journal.Session> all = new ArrayList<>(j.history);
		all.add(s);
		for (Journal.Session session : all) {
			if (session.elapsedMs < 10 * 60_000L) continue;
			if (bestXp == null || session.rate("xp") > bestXp.rate("xp")) bestXp = session;
			if (bestCoins == null || coinsPerHour(session) > coinsPerHour(bestCoins)) bestCoins = session;
			if (mostLogs == null || session.value("logs") > mostLogs.value("logs")) mostLogs = session;
			if (longest == null || session.elapsedMs > longest.elapsedMs) longest = session;
		}
		if (bestXp == null) out.add(text(tr("records_none"), UI_DIM));
		if (bestXp != null) out.add(row("experience_bottle", tr("record_xp"), date(bestXp.started), Planner.whole(bestXp.rate("xp")) + "/h", UI_ACCENT, null));
		if (bestCoins != null) out.add(row("gold_nugget", tr("record_coins"), date(bestCoins.started), coins(coinsPerHour(bestCoins)) + "/h", UI_AMBER, null));
		if (mostLogs != null) out.add(row("oak_log", tr("record_logs"), date(mostLogs.started), Planner.whole(mostLogs.value("logs")), UI_TEXT, null));
		if (Progress.get().bestCombo > 0) out.add(row("blaze_powder", tr("record_combo"), null, "x" + Progress.get().bestCombo, UI_AMBER, null));
		if (longest != null) out.add(row("clock", tr("record_long"), date(longest.started), GearScreenFormat.duration(longest.elapsedMs / 1000), UI_TEXT, null));
		Progress.ContestRecord bestContest = null;
		for (Progress.ContestRecord c : p.contests) if (bestContest == null || c.points > bestContest.points) bestContest = c;
		if (bestContest != null) out.add(row("golden_hoe", tr("record_contest"), bestContest.host + " · " + date(bestContest.time),
				Planner.whole(bestContest.points) + " · " + bestContest.bracket, bracketColor(bestContest.bracket), null));
		Progress.Drop bestDrop = null;
		for (Progress.Drop d : p.drops) if (bestDrop == null || d.value > bestDrop.value) bestDrop = d;
		if (bestDrop != null) out.add(row("gold_ingot", tr("record_drop"), bestDrop.name + " · " + date(bestDrop.time), coins(bestDrop.value), UI_AMBER, null));

		out.add(header(tr("totals")));
		for (String key : List.of("xp", "logs", "gifts", "contests", "essence", "hotf")) {
			out.add(row(metricIcon(key), metric(key), null, Planner.whole(j.totals.getOrDefault(key, 0.0)), UI_TEXT, null));
		}
		String favorite = j.gifts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
		if (favorite != null) out.add(row("oak_sapling", tr("favorite"), tr("favorite_sub", j.gifts.get(favorite)), capital(favorite), UI_ACCENT, null));

		out.add(header(tr("share")));
		out.add(button(tr("share_button"), () -> {
			minecraft.keyboardHandler.setClipboard(ProfileExport.text());
			Toasts.show(Toasts.Kind.INFO, "paper", tr("share_done"), tr("share_done_text"));
		}));
		return out;
	}

	private static double coinsPerHour(Journal.Session session) {
		double hours = session.elapsedMs / 3_600_000.0;
		return hours <= 0 ? 0 : JournalStore.value(session.items).coins() / hours;
	}

	private static String metricIcon(String key) {
		return switch (key) {
			case "xp" -> "experience_bottle";
			case "logs" -> "oak_log";
			case "gifts" -> "chest";
			case "contests" -> "golden_hoe";
			case "essence" -> "glowstone_dust";
			default -> "oak_sapling";
		};
	}

	static String date(long time) {
		return time <= 0 ? "–" : DATE.format(Instant.ofEpochMilli(time));
	}

	static String capital(String text) {
		return text == null || text.isEmpty() ? "" : Character.toUpperCase(text.charAt(0)) + text.substring(1);
	}

	static int bracketColor(String bracket) {
		return switch (bracket == null ? "" : bracket.toUpperCase(Locale.ROOT)) {
			case "UNCOMMON" -> 0xFF55FF55;
			case "RARE" -> 0xFF5599FF;
			case "EPIC" -> 0xFFC266FF;
			case "LEGENDARY" -> 0xFFFFAA00;
			case "MYTHIC" -> 0xFFFF66FF;
			case "DIVINE" -> 0xFF55FFFF;
			case "SPECIAL" -> 0xFFFF5555;
			default -> UI_TEXT;
		};
	}

	// Daily goals

	private List<Line> goals() {
		List<Line> out = new ArrayList<>();
		Progress p = Progress.get();
		subtitle = tr("about.goals");
		long done = 0;

		for (int i = 0; i < p.dailyGoals.size(); i++) {
			Progress.DailyGoal goal = p.dailyGoals.get(i);
			double today = Progress.today(goal.metric);
			double fraction = goal.amount <= 0 ? 0 : today / goal.amount;
			if (fraction >= 1) done++;
			int index = i;
			String right = Planner.whole(Math.min(today, goal.amount)) + " / " + Planner.whole(goal.amount) + "   ✕";
			out.add(bar(metricIcon(goal.metric), metric(goal.metric) + (fraction >= 1 ? "  ✔" : ""), right, fraction, UI_AMBER, (lx, w, button) -> {
				if (lx > w - 20) {
					p.dailyGoals.remove(index);
				} else if (lx < w / 2.0) {
					int next = (Watchers.GOAL_METRICS.indexOf(goal.metric) + 1) % Watchers.GOAL_METRICS.size();
					goal.metric = Watchers.GOAL_METRICS.get(next);
				} else {
					goal.amount = button == 1 ? Math.max(1, Math.round(goal.amount / 2)) : goal.amount * 2;
				}
				Progress.changed();
				rebuild();
			}));
		}

		out.add(new Line(6, (g, x, y, w, a, h) -> { }, null));
		if (p.dailyGoals.size() < 8) out.add(button(tr("goal_add"), () -> {
			p.dailyGoals.add(new Progress.DailyGoal("logs", 10_000));
			Progress.changed();
			rebuild();
		}));
		out.add(text(tr("goal_hint"), UI_DIM));
		out.add(text(tr("goal_summary", done, p.dailyGoals.size()), done == p.dailyGoals.size() && done > 0 ? UI_ACCENT : UI_MUTED));
		return out;
	}

	// Sessions

	private List<Line> sessions() {
		List<Line> out = new ArrayList<>();
		Journal j = JournalStore.journal;
		subtitle = tr("about.sessions");
		List<Journal.Session> history = new ArrayList<>();
		for (Journal.Session s : j.history) if (s.elapsedMs >= 60_000) history.add(s);

		out.add(header(tr("chart_sessions")));
		List<Journal.Session> chart = new ArrayList<>(history.subList(0, Math.min(20, history.size())));
		java.util.Collections.reverse(chart);
		if (j.current.elapsedMs >= 60_000) chart.add(j.current);
		String[] metrics = {"xp", "logs", "gifts", "coins"};
		String[] labels = new String[metrics.length];
		Runnable[] pick = new Runnable[metrics.length];
		for (int i = 0; i < metrics.length; i++) {
			String m = metrics[i];
			labels[i] = (m.equals(chartMetric) ? "● " : "") + tr("chart_metric_" + m);
			pick[i] = () -> chartMetric = m;
		}
		out.add(buttons(labels, pick));
		out.add(new Line(110, (g, x, y, w, a, h) -> drawSessionChart(g, x, y, w, 110, a, chart), null));
		if (chart.size() >= 2) {
			double avg = chart.stream().mapToDouble(StatsScreen::chartValue).average().orElse(0);
			double best = chart.stream().mapToDouble(StatsScreen::chartValue).max().orElse(0);
			double last = chartValue(chart.get(chart.size() - 1));
			String fmt = chartMetric.equals("coins") ? coins(avg) : Planner.whole(avg);
			out.add(text(tr("chart_summary", fmt, chartMetric.equals("coins") ? coins(best) : Planner.whole(best),
					avg > 0 ? (last >= avg ? "+" : "") + Math.round((last / avg - 1) * 100) + "%" : "–"), UI_MUTED));
		}

		out.add(header(tr("chart_current")));
		List<Journal.Sample> samples = j.current.samples;
		out.add(new Line(80, (g, x, y, w, a, h) -> drawSampleChart(g, x, y, w, 80, a, samples), null));

		out.add(header(tr("list_sessions")));
		if (j.current.started != 0) out.add(sessionRow(j.current, true));
		for (Journal.Session s : history) out.add(sessionRow(s, false));
		if (history.isEmpty() && j.current.started == 0) out.add(text(tr("sessions_none"), UI_DIM));
		return out;
	}

	private Line sessionRow(Journal.Session s, boolean current) {
		String left = (current ? tr("now") + " · " : "") + date(s.started) + " · " + GearScreenFormat.duration(s.elapsedMs / 1000);
		String sub = Planner.whole(s.value("logs")) + " Logs · " + Planner.whole(s.value("gifts")) + " " + metric("gifts") + " · " + Planner.whole(s.value("contests")) + " " + metric("contests");
		String right = Planner.whole(s.rate("xp")) + " XP/h · " + coins(coinsPerHour(s)) + "/h";
		return row(current ? "clock" : "paper", left, sub, right, current ? UI_ACCENT : UI_TEXT, null);
	}

	private static String chartMetric = "xp";

	/** The chosen metric of a session, per hour. */
	private static double chartValue(Journal.Session s) {
		double hours = Math.max(1, s.elapsedMs) / 3_600_000.0;
		return switch (chartMetric) {
			case "logs" -> s.value("logs") / hours;
			case "gifts" -> s.value("gifts") / hours;
			case "coins" -> coinsPerHour(s);
			default -> s.rate("xp");
		};
	}

	private void drawSessionChart(GuiGraphicsExtractor g, int x, int y, int w, int h, double a, List<Journal.Session> sessions) {
		round(g, x, y, w, h, 6, alpha(UI_ROW, a));
		if (sessions.isEmpty()) {
			g.text(font, tr("sessions_none"), x + 10, y + h / 2 - 4, alpha(UI_DIM, a), false);
			return;
		}

		double max = 1;
		for (Journal.Session s : sessions) max = Math.max(max, chartValue(s));
		int left = x + 40, bottom = y + h - 14, top = y + 12;
		int slot = Math.max(4, (w - 52) / Math.max(1, sessions.size()));
		double grow = animated() ? easeOut((System.nanoTime() - pageStartNs()) / 500e6) : 1;

		for (int i = 0; i <= 2; i++) {
			int ly = bottom - (bottom - top) * i / 2;
			g.fill(left, ly, x + w - 8, ly + 1, alpha(0x14FFFFFF, a));
			String label = GearScreenFormat.coins(max * i / 2).replace(".0k", "k");
			g.text(font, label, left - 4 - font.width(label), ly - 4, alpha(UI_DIM, a), false);
		}

		// Bars, then a line through their tops and a dashed average.
		int prevX = -1, prevY = -1;
		double sum = 0;
		for (int i = 0; i < sessions.size(); i++) {
			Journal.Session s = sessions.get(i);
			double v = chartValue(s);
			sum += v;
			int bh = (int) Math.round((bottom - top) * v / max * grow);
			int bx = left + i * slot + 2;
			int bw = Math.max(2, slot - 4);
			boolean current = s == JournalStore.journal.current;
			round(g, bx, bottom - bh, bw, Math.max(1, bh), 2, alpha(current ? UI_AMBER : UI_ACCENT_SOFT, a));
			int cx = bx + bw / 2, cy = bottom - bh;
			if (prevX >= 0) line(g, prevX, prevY, cx, cy, alpha(UI_ACCENT, a));
			g.fill(cx - 1, cy - 1, cx + 2, cy + 2, alpha(current ? UI_AMBER : UI_ACCENT, a));
			prevX = cx;
			prevY = cy;
		}
		int avgY = bottom - (int) Math.round((bottom - top) * (sum / sessions.size()) / max * grow);
		for (int dx = left; dx < x + w - 8; dx += 6) g.fill(dx, avgY, Math.min(dx + 3, x + w - 8), avgY + 1, alpha(0x66FFFFFF, a));

		String unit = tr("chart_metric_" + chartMetric);
		g.text(font, unit, x + w - 8 - font.width(unit), y + 2, alpha(UI_DIM, a), false);
	}

	private static void line(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color) {
		int steps = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
		for (int i = 0; i <= steps; i++) {
			int px = x0 + (x1 - x0) * i / Math.max(1, steps);
			int py = y0 + (y1 - y0) * i / Math.max(1, steps);
			g.fill(px, py, px + 1, py + 1, color);
		}
	}

	private void drawSampleChart(GuiGraphicsExtractor g, int x, int y, int w, int h, double a, List<Journal.Sample> samples) {
		round(g, x, y, w, h, 6, alpha(UI_ROW, a));
		if (samples.size() < 3) {
			g.text(font, tr("chart_wait"), x + 10, y + h / 2 - 4, alpha(UI_DIM, a), false);
			return;
		}

		// XP per hour in each 30 second step, smoothed over three steps.
		List<Double> rates = new ArrayList<>();
		for (int i = 1; i < samples.size(); i++) {
			Journal.Sample prev = samples.get(i - 1), cur = samples.get(i);
			long dt = cur.elapsedMs() - prev.elapsedMs();
			rates.add(dt <= 0 ? 0 : (cur.xp() - prev.xp()) * 3_600_000 / dt);
		}
		List<Double> smooth = new ArrayList<>();
		for (int i = 0; i < rates.size(); i++) {
			double sum = 0; int n = 0;
			for (int k = Math.max(0, i - 2); k <= i; k++) { sum += rates.get(k); n++; }
			smooth.add(sum / n);
		}

		double max = 1;
		for (double r : smooth) max = Math.max(max, r);
		int left = x + 8, right = x + w - 8, top = y + 12, bottom = y + h - 8;
		double reveal = animated() ? easeOut((System.nanoTime() - pageStartNs()) / 700e6) : 1;
		int count = (int) Math.max(2, smooth.size() * reveal);

		for (int i = 0; i < count; i++) {
			int px = left + (right - left) * i / Math.max(1, smooth.size() - 1);
			int py = bottom - (int) Math.round((bottom - top) * smooth.get(i) / max);
			g.fill(px, py, px + 2, bottom, alpha(UI_ACCENT_SOFT, a));
			g.fill(px, py, px + 2, py + 2, alpha(UI_ACCENT, a));
		}

		String label = tr("chart_peak", Planner.whole(max));
		g.text(font, label, x + w - 8 - font.width(label), y + 3, alpha(UI_DIM, a), false);
	}

	// Contests

	private List<Line> contests() {
		List<Line> out = new ArrayList<>();
		Progress p = Progress.get();
		subtitle = tr("about.contests");

		if (ContestTracker.active()) {
			var status = ContestTracker.status();
			out.add(row("golden_hoe", tr("contest_live", ContestTracker.host(), GearScreenFormat.duration(ContestTracker.secondsLeft())),
					tr("contest_live_sub", Planner.whole(ContestTracker.points()), Planner.whole(ContestTracker.pointsPerMinute())),
					status.projectedBracket() == null ? "–" : tr("contest_projected", status.projectedBracket().name), UI_AMBER, null));
			var byTree = ContestTracker.logsByTree();
			double collected = ContestTracker.logsThisContest();
			String main = ContestTracker.mainTree();
			long best = main == null ? 0 : Watchers.bestLogs(main);
			StringBuilder split = new StringBuilder();
			byTree.forEach((t, n) -> split.append(split.isEmpty() ? "" : " · ").append(Watchers.treeName(t)).append(" ").append(Planner.whole(n)));
			out.add(bar("oak_log", tr("contest_collected", Planner.whole(collected)), best > 0 ? tr("contest_best", Planner.whole(best)) : split.toString(),
					best > 0 ? collected / best : 0, UI_ACCENT, null));
			if (status.next() != null) {
				GameData.Tree tree = ContestHelper.currentTree();
				double logs = Watchers.logsForPoints(tree, status.pointsToNext());
				out.add(row("oak_log", tr("contest_missing", status.next().name, Planner.whole(status.pointsToNext())),
						logs > 0 ? tr("contest_missing_logs", Planner.whole(Math.ceil(logs)), tree.name) + " · "
								+ Planner.tr("contest.source." + Watchers.pointsSource(tree).name().toLowerCase(Locale.ROOT)) : null,
						tr("contest_per_log", Planner.decimal(Math.round(Watchers.pointsPerLog(tree) * 100) / 100.0)), UI_MUTED, null));
			}
		} else {
			long next = Watchers.nextContestStart();
			long interval = Watchers.contestInterval();
			out.add(row("clock", next > 0 ? tr("contest_next", GearScreenFormat.duration((next - System.currentTimeMillis()) / 1000)) : tr("contest_next_unknown"),
					interval > 0 ? tr("contest_interval", GearScreenFormat.duration(interval / 1000)) : tr("contest_learning"), null, UI_TEXT, null));
		}

		Map<String, Integer> counts = new LinkedHashMap<>();
		for (Progress.ContestRecord c : p.contests) counts.merge(c.bracket, 1, Integer::sum);
		if (!counts.isEmpty()) {
			out.add(header(tr("contest_brackets")));
			int total = p.contests.size();
			for (var entry : counts.entrySet()) {
				out.add(bar("golden_hoe", capital(entry.getKey().toLowerCase(Locale.ROOT)), entry.getValue() + "× · " + Math.round(entry.getValue() * 100.0 / total) + "%",
						entry.getValue() / (double) total, bracketColor(entry.getKey()), null));
			}
			double avg = p.contests.stream().mapToInt(c -> c.points).average().orElse(0);
			out.add(text(tr("contest_average", Planner.whole(avg), total), UI_MUTED));
		}

		boolean anyLogs = p.contests.stream().anyMatch(c -> c.logs > 0);
		if (anyLogs) {
			out.add(header(tr("contest_best_logs")));
			for (GameData.Tree t : Planner.data.trees) {
				long top = Watchers.bestLogs(t.id);
				if (top > 0) out.add(row("oak_log", t.name, null, Planner.whole(top) + " Logs", UI_AMBER, null));
			}
		}

		out.add(header(tr("contest_history")));
		if (p.contests.isEmpty()) out.add(text(tr("contest_none"), UI_DIM));
		for (Progress.ContestRecord c : p.contests) {
			boolean open = c == replay;
			String logs = c.logs > 0 ? Planner.whole(c.logs) + " " + Watchers.treeName(c.tree) + " Logs" + (c.logsConfirmed ? " ✔" : "")
					+ " · " + Planner.decimal(Math.round(c.points * 100.0 / c.logs) / 100.0) + " P/Log" : "";
			out.add(row("golden_hoe", c.host + " · " + capital(c.bracket.toLowerCase(Locale.ROOT)) + (c.personalBest ? "  ★ PB" : ""),
					date(c.time) + (logs.isEmpty() ? "" : " · " + logs) + (c.samples != null && c.samples.size() > 1 ? " · " + tr(open ? "replay_hide" : "replay_show") : ""),
					Planner.whole(c.points) + " P", bracketColor(c.bracket), c.samples != null && c.samples.size() > 1 ? (mx, w, button) -> {
						replay = replay == c ? null : c;
						rebuild();
					} : null));
			if (open) out.add(new Line(90, (g, x, y, w, a, h) -> drawReplay(g, x, y, w, 90, a, c), null));
		}
		return out;
	}

	private Progress.ContestRecord replay;

	/** Points over the 20 minutes of a past contest, with the bracket limits as lines. */
	private void drawReplay(GuiGraphicsExtractor g, int x, int y, int w, int h, double a, Progress.ContestRecord c) {
		round(g, x, y, w, h, 6, alpha(UI_ROW, a));
		List<int[]> samples = c.samples;
		int max = Math.max(1, c.points);
		for (int[] s : samples) max = Math.max(max, s[1]);
		var brackets = Planner.data.contest.bracketsFor(c.host);
		for (var b : brackets) if (b.minPoints <= max * 1.15) max = Math.max(max, b.minPoints);
		max = (int) (max * 1.08);
		int left = x + 8, right = x + w - 8, top = y + 10, bottom = y + h - 12;
		for (var b : brackets) {
			if (b.minPoints <= 0 || b.minPoints > max) continue;
			int py = bottom - (int) Math.round((bottom - top) * (double) b.minPoints / max);
			for (int px = left; px < right; px += 4) g.fill(px, py, px + 2, py + 1, alpha(bracketColor(b.name), a * 0.55));
			g.text(font, b.name, right - font.width(b.name), py - 9, alpha(bracketColor(b.name), a * 0.8), false);
		}
		double reveal = animated() ? easeOut((System.nanoTime() - pageStartNs()) / 700e6) : 1;
		int count = (int) Math.max(2, samples.size() * reveal);
		int lastX = -1, lastY = -1;
		for (int i = 0; i < Math.min(count, samples.size()); i++) {
			int px = left + (int) Math.round((right - left) * Math.min(1200, samples.get(i)[0]) / 1200.0);
			int py = bottom - (int) Math.round((bottom - top) * (double) samples.get(i)[1] / max);
			if (lastX >= 0) {
				int steps = Math.max(1, Math.max(Math.abs(px - lastX), Math.abs(py - lastY)));
				for (int k = 0; k <= steps; k++) {
					int ix = lastX + (px - lastX) * k / steps, iy = lastY + (py - lastY) * k / steps;
					g.fill(ix, iy, ix + 2, iy + 2, alpha(UI_ACCENT, a));
				}
			}
			lastX = px;
			lastY = py;
		}
		g.text(font, "0:00", left, bottom + 2, alpha(UI_DIM, a), false);
		g.text(font, "20:00", right - font.width("20:00"), bottom + 2, alpha(UI_DIM, a), false);
	}

	// Global leaderboard

	private String leaderboardBoard = "contest_points";

	private List<Line> leaderboard() {
		List<Line> out = new ArrayList<>();
		subtitle = tr("about.leaderboard");
		if (!Planner.config.leaderboardShare) {
			out.add(row("paper", tr("lb_off"), tr("lb_off_hint"), tr("lb_turn_on"), UI_AMBER, (mx, w, button) -> {
				Planner.config.leaderboardShare = true;
				Planner.config.save();
				Leaderboard.verify();
				rebuild();
			}));
		} else if (!Leaderboard.verified()) {
			out.add(row("name_tag", tr("lb_unverified"), tr("lb_unverified_hint"), tr("lb_verify"), UI_AMBER, (mx, w, button) -> {
				Leaderboard.verify();
				rebuild();
			}));
		}
		for (String id : Leaderboard.BOARDS) {
			boolean open = id.equals(leaderboardBoard);
			out.add(row(open ? "golden_hoe" : "paper", tr("lb." + id), null, open ? "▾" : "▸", open ? UI_ACCENT : UI_TEXT, (mx, w, button) -> {
				leaderboardBoard = id;
				rebuild();
			}));
		}
		out.add(header(tr("lb." + leaderboardBoard)));
		out.add(text(tr(leaderboardBoard.equals("tpm") ? "lb_note_tpm" : leaderboardBoard.equals("contest_points") ? "lb_note_points" : "lb_note_measured"), UI_DIM));
		Leaderboard.Board board = Leaderboard.get(leaderboardBoard, this::rebuild);
		if (board == null) {
			out.add(text(tr("lb_loading"), UI_DIM));
			return out;
		}
		if (board.error() != null) {
			out.add(text(tr("lb_error", board.error()), UI_DANGER));
			return out;
		}
		Map<String, Double> mine = Leaderboard.stats();
		out.add(text(tr("lb_you", board.you() == null ? "–" : "#" + board.you().rank(), lbValue(leaderboardBoard, mine.getOrDefault(leaderboardBoard, 0.0)), board.players()), UI_MUTED));
		if (board.entries().isEmpty()) out.add(text(tr("lb_empty"), UI_DIM));
		String me = minecraft.getUser().getName();
		for (Leaderboard.Entry e : board.entries()) {
			int color = e.rank() == 1 ? 0xFFFFD34D : e.rank() == 2 ? 0xFFD0D6DC : e.rank() == 3 ? 0xFFD08A4E : UI_TEXT;
			if (e.name().equalsIgnoreCase(me)) color = UI_ACCENT;
			out.add(row(e.rank() <= 3 ? "gold_ingot" : "paper", "#" + e.rank() + "  " + e.name(), e.verified() ? "✔ " + tr("lb_checked") : tr("lb_unchecked"),
					lbValue(leaderboardBoard, e.value()), color, null));
		}
		if (!Leaderboard.status.isEmpty()) out.add(text(tr("lb_error", Leaderboard.status), UI_DANGER));
		return out;
	}

	private static String lbValue(String board, double value) {
		return switch (board) {
			case "tpm" -> Planner.whole(value) + " TPM";
			case "xp_hour" -> Planner.whole(value) + " XP/h";
			case "contest_logs" -> Planner.whole(value) + " Logs";
			default -> Planner.whole(value) + " P";
		};
	}

	// Chapters

	private List<Line> chapters() {
		List<Line> out = new ArrayList<>();
		subtitle = tr("about.chapters");
		var chapters = Progress.get().chapters;
		if (chapters.isEmpty()) {
			out.add(text(tr("chapters_none"), UI_DIM));
			return out;
		}
		for (var chapter : chapters.entrySet()) {
			long done = chapter.getValue().values().stream().filter(t -> t.done).count();
			int total = chapter.getValue().size();
			out.add(header(chapter.getKey() + "  " + done + "/" + total));
			out.add(bar("writable_book", tr("chapters_progress", done, total), null, total == 0 ? 0 : done / (double) total, done == total ? UI_ACCENT : UI_AMBER, null));
			chapter.getValue().entrySet().stream().sorted((x, y) -> Boolean.compare(x.getValue().done, y.getValue().done)).forEach(task ->
					out.add(row(task.getValue().done ? "slime_ball" : "paper", (task.getValue().done ? "✔ " : "✗ ") + task.getKey(), task.getValue().hint,
							"", task.getValue().done ? UI_ACCENT : UI_TEXT, null)));
		}
		return out;
	}

	// Drops

	private List<Line> drops() {
		List<Line> out = new ArrayList<>();
		Progress p = Progress.get();
		subtitle = tr("about.drops");

		long since = Watchers.giftsSinceRare();
		double avg = p.drops.stream().filter(d -> d.giftsSince > 0).mapToLong(d -> d.giftsSince).average().orElse(-1);
		double fraction = avg > 0 && since >= 0 ? since / avg : 0;
		out.add(bar("chest", tr("pity", since < 0 ? "–" : Planner.whole(since)), avg > 0 ? tr("pity_avg", Planner.whole(avg)) : tr("pity_unknown"),
				Math.min(fraction, 1), fraction >= 1 ? UI_DANGER : UI_AMBER, null));
		double total = p.drops.stream().mapToDouble(d -> d.value).sum();
		out.add(text(tr("drops_total", p.drops.size(), coins(total)), UI_MUTED));

		out.add(button(tr("drops_edit"), () -> minecraft.gui.setScreen(PickerScreen.rare(this))));
		out.add(header(tr("drops_list")));
		out.add(text(tr("drops_click"), UI_DIM));
		if (p.drops.isEmpty()) out.add(text(tr("drops_none"), UI_DIM));
		for (Progress.Drop d : p.drops) {
			String sub = date(d.time) + (d.giftsSince >= 0 ? " · " + tr("drops_after", d.giftsSince) : "");
			int state = PickerScreen.rareState(d.name);
			String right = (d.value > 0 ? coins(d.value) : "?") + " · " + PickerScreen.stateLabel(state);
			out.add(row("gold_ingot", (d.count > 1 ? d.count + "× " : "") + d.name, sub, right, PickerScreen.stateColor(state) == UI_MUTED ? UI_AMBER : PickerScreen.stateColor(state),
					(lx, w, button) -> {
						int next = PickerScreen.cycleRare(d.name, button != 1);
						Toasts.show(Toasts.Kind.INFO, "gold_ingot", d.name, tr("drop_state", PickerScreen.stateLabel(next)));
					}));
		}
		return out;
	}

	// Collections

	private static double collectionEta(Progress.Collection c) {
		if (c.maxed || c.needed <= 0) return Double.POSITIVE_INFINITY;
		String id = MenuReaders.logId(c.name);
		Journal.Session s = JournalStore.journal.current;
		if (id == null || s.elapsedMs < 60_000) return Double.POSITIVE_INFINITY;
		double perHour = s.items.getOrDefault(id, 0L) * 3_600_000.0 / s.elapsedMs;
		return perHour <= 0 ? Double.POSITIVE_INFINITY : Math.max(0, c.needed - c.current) / perHour;
	}

	private List<Line> collections() {
		List<Line> out = new ArrayList<>();
		Progress p = Progress.get();
		subtitle = tr("about.collections");

		if (p.collections.isEmpty()) {
			out.add(text(tr("collections_none"), UI_DIM));
			return out;
		}

		for (Progress.Collection c : p.collections.values()) {
			if (c.maxed) {
				out.add(row("chest", c.name + " · " + tr("maxed"), tr("collected", Planner.whole(c.total)), "✔", UI_ACCENT, null));
				continue;
			}
			double eta = collectionEta(c);
			String right = Planner.whole(c.current) + " / " + Planner.whole(c.needed) + (Double.isFinite(eta) ? "  · " + GearScreenFormat.eta(eta) : "");
			out.add(bar("chest", c.name + " → " + c.nextTier, right, c.needed <= 0 ? 0 : c.current / c.needed, UI_ACCENT, null));
		}

		long oldest = p.collections.values().stream().mapToLong(c -> c.updated).min().orElse(0);
		out.add(text(tr("collections_read", date(oldest)), UI_DIM));
		return out;
	}

	// Selling

	private List<Line> selling() {
		List<Line> out = new ArrayList<>();
		Journal.Session s = JournalStore.journal.current;
		subtitle = tr("about.selling");
		Set<String> ids = new LinkedHashSet<>();
		for (GameData.Tree tree : Planner.data.trees) ids.add(tree.bazaarId);
		ids.add("ESSENCE_FOREST");
		ids.addAll(s.items.keySet());

		double gainTotal = 0;
		for (String id : ids) {
			Double instant = Planner.PRICES.get(id);
			Double top = BazaarPrices.BUY.get(id);
			Double npc = NpcPrices.get(id);
			double offer = top == null ? 0 : (top - 0.1) * (1 - BAZAAR_TAX);
			double insta = instant == null ? 0 : instant * (1 - BAZAAR_TAX);
			double npcValue = npc == null ? 0 : npc;
			if (insta <= 0 && offer <= 0 && npcValue <= 0) continue;

			double best = Math.max(insta, Math.max(offer, npcValue));
			String method = best == offer ? tr("sell_offer") : best == insta ? tr("sell_instant") : tr("sell_npc");
			double perHour = s.elapsedMs < 60_000 ? 0 : s.items.getOrDefault(id, 0L) * 3_600_000.0 / s.elapsedMs;
			double extra = perHour * (best - insta);
			gainTotal += Math.max(0, extra);

			String sub = tr("sell_prices", insta > 0 ? coins(insta) : "–", offer > 0 ? coins(offer) : "–", npcValue > 0 ? coins(npcValue) : "–");
			String right = method + (perHour > 0 && extra > 1 ? "  +" + coins(extra) + "/h" : "");
			out.add(row(iconFor(id), prettyName(id), sub, right, best == insta ? UI_TEXT : UI_ACCENT, null));
		}

		out.addFirst(text(gainTotal > 1 ? tr("sell_summary", coins(gainTotal)) : tr("sell_summary_none"), gainTotal > 1 ? UI_ACCENT : UI_MUTED));
		out.add(text(tr("sell_note"), UI_DIM));
		return out;
	}

	private static String iconFor(String id) {
		if (id.contains("FIG")) return "stripped_spruce_wood";
		if (id.contains("MANGROVE")) return "mangrove_log";
		if (id.contains("HELIX")) return "stripped_birch_wood";
		if (id.contains("ESSENCE")) return "glowstone_dust";
		return "paper";
	}

	static String prettyName(String id) {
		StringBuilder out = new StringBuilder();
		for (String part : id.toLowerCase(Locale.ROOT).split("_")) {
			if (part.isEmpty()) continue;
			if (!out.isEmpty()) out.append(' ');
			out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
		}
		return out.toString();
	}

	// Timeline

	private static String timelineFilter = "all";
	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE, dd.MM.yyyy").withZone(ZoneId.systemDefault());
	private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

	private List<Line> timeline() {
		List<Line> out = new ArrayList<>();
		subtitle = tr("about.timeline");
		String[] filters = {"all", "drop", "level", "perk", "shard", "contest", "milestone"};
		String[] labels = new String[filters.length];
		Runnable[] pick = new Runnable[filters.length];
		for (int i = 0; i < filters.length; i++) {
			String f = filters[i];
			labels[i] = (f.equals(timelineFilter) ? "● " : "") + tr("timeline_" + f);
			pick[i] = () -> timelineFilter = f;
		}
		out.add(buttons(labels, pick));

		List<Timeline.Entry> entries = Timeline.entries();
		String day = "";
		int shown = 0;
		for (Timeline.Entry e : entries) {
			if (!matches(e.kind())) continue;
			if (++shown > 400) break;
			String d = DAY.format(Instant.ofEpochMilli(e.time()));
			if (!d.equals(day)) {
				day = d;
				out.add(header(d));
			}
			String time = CLOCK.format(Instant.ofEpochMilli(e.time()));
			out.add(new Line(24, (g, x, y, w, a, h) -> {
				g.fill(x + 9, y, x + 10, y + 24, alpha(0x33FFFFFF, a));
				round(g, x + 6, y + 8, 8, 8, 4, alpha(e.color(), a));
				if (a > 0.5) {
					var pose = g.pose();
					pose.pushMatrix();
					pose.translate(x + 20, y + 4);
					pose.scale(0.75f, 0.75f);
					g.item(icon(e.icon()), 0, 0);
					pose.popMatrix();
				}
				g.text(font, time, x + 36, y + 8, alpha(UI_DIM, a), false);
				int tx = x + 36 + font.width("00:00") + 8;
				int sw = e.sub().isEmpty() ? 0 : font.width(e.sub()) + 10;
				g.text(font, fit(font, e.text(), w - (tx - x) - sw - 6), tx, y + 8, alpha(UI_TEXT, a), false);
				if (sw > 0) g.text(font, e.sub(), x + w - 6 - font.width(e.sub()), y + 8, alpha(e.color(), a), false);
			}, null, null, (e.text() + " " + e.sub()).toLowerCase(Locale.ROOT)));
		}
		if (shown == 0) out.add(text(tr("timeline_empty"), UI_DIM));
		return out;
	}

	private static boolean matches(String kind) {
		return switch (timelineFilter) {
			case "all" -> true;
			case "shard" -> kind.equals("shard") || kind.equals("attribute");
			case "milestone" -> kind.equals("milestone") || kind.equals("goal") || kind.equals("collection");
			default -> kind.equals(timelineFilter);
		};
	}

	// Trees

	private List<Line> trees() {
		List<Line> out = new ArrayList<>();
		subtitle = tr("about.trees");
		var stats = SkyBlockReader.stats();
		List<TreeReport.Row> rows = TreeReport.build(Planner.data, stats, Planner.objective(), Map.of());
		double maxCoins = 1, maxXp = 1;
		for (TreeReport.Row r : rows) {
			maxCoins = Math.max(maxCoins, r.dropsPerHit() * Planner.PRICES.getOrDefault(r.tree().bazaarId, 0.0));
			maxXp = Math.max(maxXp, r.xpPerHit());
		}

		if (!SkyBlockReader.statsKnown()) out.add(text(Planner.tr("gear.no_stats"), UI_DANGER));
		for (TreeReport.Row r : rows) {
			double price = Planner.PRICES.getOrDefault(r.tree().bazaarId, 0.0);
			double coinsHit = r.dropsPerHit() * price;
			double points = r.logs() * r.tree().contestPointsPer100Logs / 100;
			double cm = maxCoins, xm = maxXp;
			out.add(new Line(44, (g, x, y, w, a, h) -> {
				round(g, x, y, w, 44, 6, alpha(UI_ROW, a));
				if (r.best()) roundOutline(g, x, y, w, 44, 6, alpha(UI_AMBER, a * 0.8));
				round(g, x + 6, y + 11, 22, 22, 5, alpha(UI_TILE, a));
				if (a > 0.5) g.item(icon(iconFor(r.tree().bazaarId)), x + 9, y + 14);
				int tx = x + 34;
				g.text(font, r.tree().name + (r.best() ? "  ★ " + tr("best_tree") : ""), tx, y + 5, alpha(r.best() ? UI_AMBER : UI_TEXT, a), false);
				String detail = tr("tree_detail", Planner.decimal(r.logs()), Planner.decimal(r.dropsPerHit()), Planner.decimal(points))
						+ (r.sweepToNext() >= 0 ? " · " + tr("tree_next", Planner.decimal(Math.ceil(r.sweepToNext()))) : "");
				g.text(font, fit(font, detail, w - 44), tx, y + 16, alpha(UI_MUTED, a), false);
				int half = (w - 50) / 2;
				miniBar(g, tx, y + 30, half - 6, coinsHit / cm, UI_AMBER, coins(coinsHit) + " /" + tr("hit"), a);
				miniBar(g, tx + half, y + 30, half - 6, r.xpPerHit() / xm, UI_ACCENT, Planner.decimal(r.xpPerHit()) + " XP /" + tr("hit"), a);
				Why.badge(g, font, x + w - 14, y + 4, a, h > 0.5);
			}, (lx, w, button) -> Why.tree(r, rows)));
		}

		out.add(header(tr("spots")));
		Map<String, int[]> perTree = new LinkedHashMap<>();
		Map<String, List<Long>> regrow = new LinkedHashMap<>();
		for (TreeTracker.Spot spot : TreeTracker.spots()) {
			int[] c = perTree.computeIfAbsent(spot.tree, k -> new int[2]);
			c[0]++;
			if (spot.state == TreeTracker.State.READY) c[1]++;
			if (spot.regrow() > 0) regrow.computeIfAbsent(spot.tree, k -> new ArrayList<>()).add(spot.regrow());
		}
		if (perTree.isEmpty()) out.add(text(tr("spots_none"), UI_DIM));
		for (var entry : perTree.entrySet()) {
			List<Long> times = regrow.getOrDefault(entry.getKey(), List.of());
			String regrowText = times.isEmpty() ? tr("regrow_unknown") : tr("regrow", GearScreenFormat.duration(times.stream().mapToLong(Long::longValue).sum() / times.size() / 1000));
			out.add(row("oak_sapling", capital(entry.getKey()) + " · " + tr("spots_count", entry.getValue()[0]), regrowText,
					tr("spots_ready", entry.getValue()[1]), UI_ACCENT, null));
		}
		out.add(button(tr("spots_clear"), () -> { TreeTracker.clearArea(); rebuild(); }));
		return out;
	}

	private void miniBar(GuiGraphicsExtractor g, int x, int y, int w, double fraction, int color, String label, double a) {
		int lw = font.width(label) + 6;
		int bw = Math.max(10, w - lw);
		round(g, x, y + 2, bw, 4, 2, alpha(0x1FFFFFFF, a));
		int fill = (int) Math.round(bw * Math.clamp(fraction, 0, 1));
		if (fill > 0) round(g, x, y + 2, fill, 4, 2, alpha(color, a));
		g.text(font, label, x + bw + 6, y, alpha(color, a), false);
	}

	// Alarms

	private List<Line> alarms() {
		List<Line> out = new ArrayList<>();
		Progress p = Progress.get();
		subtitle = tr("about.alarms");

		if (p.watches.isEmpty()) out.add(text(tr("alarms_none"), UI_DIM));
		for (Progress.Watch watch : List.copyOf(p.watches)) {
			Double now = Watchers.price(watch.id);
			String sub = tr("alarm_target", coins(watch.target)) + " · " + tr("alarm_now", now == null ? "…" : coins(now));
			boolean below = now != null && now <= watch.target;
			out.add(row("bell", watch.name, sub, below ? tr("alarm_hit") : "✕", below ? UI_ACCENT : UI_DIM, (lx, w, button) -> {
				if (lx > w - 30) {
					p.watches.remove(watch);
				} else {
					watch.target = button == 1 ? watch.target * 1.1 : watch.target * 0.9;
					watch.triggered = false;
				}
				Progress.changed();
				rebuild();
			}));
		}
		out.add(text(tr("alarm_hint"), UI_DIM));
		return out;
	}

	// Routes

	private List<Line> routes() {
		List<Line> out = new ArrayList<>();
		subtitle = tr("about.routes", Routes.islandName(Routes.island()));
		Routes.Route recording = Routes.recording();

		if (recording != null) {
			out.add(row("map", tr("route_recording", recording.name), tr("route_recording_sub", recording.points.size()), null, UI_AMBER, null));
			out.add(button(tr("route_add"), () -> { Routes.addPoint(); rebuild(); }));
			out.add(button(tr("route_add_warp"), () -> { Routes.addWarpPoint(); rebuild(); }));
			out.add(button(tr("route_undo"), () -> { Routes.undo(); rebuild(); }));
			out.add(button(tr("route_save"), () -> { Routes.finishRecording(); rebuild(); }));
			out.add(button(tr("route_cancel"), () -> { Routes.cancelRecording(); rebuild(); }));
			out.add(text(tr("route_record_hint"), UI_DIM));
		} else {
			out.add(button(tr("route_new"), () -> {
				Routes.startRecording(Planner.tr("route.default_name", Routes.here().size() + 1));
				onClose();
			}));
		}

		out.add(header(tr("route_list", Routes.islandName(Routes.island()))));
		Routes.Route active = Routes.active();
		List<Routes.Route> here = Routes.here();
		if (here.isEmpty()) out.add(text(tr("route_none"), UI_DIM));
		for (Routes.Route route : here) {
			boolean on = route == active;
			String right = (on ? "● " + tr("route_on") : tr("route_off")) + "   " + tr("route_export") + "   ✕";
			long warps = route.points.stream().filter(Routes.Point::etherwarp).count();
			out.add(row("map", route.name, tr("route_points", route.points.size()) + (warps > 0 ? " · " + tr("route_warps", warps) : "") + (route.loop ? " · " + tr("route_loop") : ""), right, on ? UI_ACCENT : UI_MUTED, (lx, w, button) -> {
				if (lx > w - 24) {
					Routes.delete(route);
				} else if (lx > w - 24 - font.width(tr("route_export")) - 16) {
					Routes.copy(button == 1 ? Routes.exportColeweight(route) : Routes.exportSkyblocker(route));
					Toasts.show(Toasts.Kind.INFO, "map", tr("route_copied"), tr(button == 1 ? "route_copied_cw" : "route_copied_sb"));
				} else {
					Routes.activate(on ? null : route);
				}
				rebuild();
			}));
		}
		out.add(text(tr("route_list_hint"), UI_DIM));

		out.add(header(tr("route_import")));
		out.add(button(tr("route_import_clipboard"), () -> {
			Routes.ImportResult result = Routes.importText(Routes.clipboard());
			if (result.error() != null) Toasts.show(Toasts.Kind.INFO, "barrier", tr("route_import_failed"), result.error());
			else Toasts.show(Toasts.Kind.INFO, "map", tr("route_imported"), tr("route_imported_text", result.routes(), result.points()));
			rebuild();
		}));
		out.add(text(tr("route_import_formats"), UI_DIM));

		List<Routes.Route> skyblocker = Routes.skyblockerFile();
		if (!skyblocker.isEmpty()) {
			out.add(header(tr("route_skyblocker")));
			for (Routes.Route route : skyblocker) {
				boolean island = route.island.equals(Routes.island());
				out.add(row("map", route.name, Routes.islandName(route.island) + " · " + tr("route_points", route.points.size()),
						tr("route_take"), island ? UI_ACCENT : UI_MUTED, (lx, w, button) -> {
							Routes.adopt(route);
							Toasts.show(Toasts.Kind.INFO, "map", tr("route_imported"), route.name);
							rebuild();
						}));
			}
		}

		List<Routes.Route> other = new ArrayList<>();
		for (Routes.Route route : Routes.all()) if (!route.island.equals(Routes.island())) other.add(route);
		if (!other.isEmpty()) {
			out.add(header(tr("route_other")));
			for (Routes.Route route : other) {
				out.add(row("map", route.name, Routes.islandName(route.island) + " · " + tr("route_points", route.points.size()), "✕", UI_DIM,
						(lx, w, button) -> { if (lx > w - 24) { Routes.delete(route); rebuild(); } }));
			}
		}
		return out;
	}

}
