package dev.foragingplanner.mc;

import static dev.foragingplanner.mc.ForestTheme.UI_ACCENT;
import static dev.foragingplanner.mc.ForestTheme.UI_AMBER;
import static dev.foragingplanner.mc.ForestTheme.UI_DANGER;
import static dev.foragingplanner.mc.ForestTheme.UI_DIM;
import static dev.foragingplanner.mc.ForestTheme.UI_MUTED;
import static dev.foragingplanner.mc.ForestTheme.UI_TEXT;
import static dev.foragingplanner.mc.ForestTheme.alpha;
import static dev.foragingplanner.mc.ForestTheme.approach;
import static dev.foragingplanner.mc.ForestTheme.easeOut;
import static dev.foragingplanner.mc.ForestTheme.fit;
import static dev.foragingplanner.mc.ForestTheme.round;
import static dev.foragingplanner.mc.ForestTheme.roundOutline;
import static dev.foragingplanner.mc.ForestTheme.shadow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import dev.foragingplanner.core.ContestMath;
import dev.foragingplanner.core.Goal;
import dev.foragingplanner.core.HotfPlanner;
import dev.foragingplanner.core.TreeReport;

/**
 * The on-screen HUD: one plain, translucent box per module, in the style of the usual SkyBlock mods.
 *
 * <p>Text is rebuilt once per second in {@link #rebuild()}. Boxes fade in and resize smoothly; the
 * session numbers glide to new values. Nothing else moves.
 */
public final class PlannerHud {
	private static final int HEADER = 12;
	private static final int LINE_GAP = 1;
	private static final int MAX_WIDTH = 300;
	private static final int TILE_H = 10;
	private static final int TILE_GAP = 6;
	/** Inner padding from the style settings. */
	private static int PAD = 4;

	/** Applies the style settings once per frame. */
	private static void style() {
		PAD = Math.clamp(Planner.config.hudPadding, 2, 10);
	}

	private static int fillColor() {
		int opacity = Math.clamp(Planner.config.hudOpacity, 0, 100);
		return ((int) Math.round(opacity * 2.55) << 24) | (Planner.config.hudColor & 0xFFFFFF);
	}

	private static boolean ts() {
		return Planner.config.hudShadow;
	}
	private static final int ESSENCE = 0xFF6FD6A6;
	private static final int HOTF = 0xFFB7E07A;

	private static final List<String> ORDER = List.of("trees", "upgrades", "contest", "session", "targets", "guide", "hunting", "reel", "progress", "gather", "levels");

	private static List<String> lines = List.of();
	private static final Map<String, List<String>> modules = new LinkedHashMap<>();
	private static final Map<String, Card> CARDS = new HashMap<>();
	private static SessionRates.Snapshot session;

	/** Animation state of one card. */
	private static final class Card {
		double appear;
		double width;
		double height;
		double bar;
		long lastNs;
		List<String> last = List.of();
		long[] changedNs = new long[0];
		final double[] shown = new double[10];
	}

	private PlannerHud() {}

	// Content

	/** Called once per second from the client tick. */
	public static void rebuild() {
		modules.clear();
		session = null;

		if (!Planner.config.hudEnabled || (!SkyBlockReader.inSkyBlock() && !SkyBlockReader.inForagingArea())) {
			lines = List.of();
			return;
		}

		if (SkyBlockReader.inForagingArea()) {
			List<String> trees = new ArrayList<>();

			if (!SkyBlockReader.statsKnown()) {
				trees.add(Planner.tr("hud.no_stats"));
			} else {
				var stats = SkyBlockReader.stats();
				trees.add(Planner.tr("hud.stats", Planner.decimal(stats.sweep()), Planner.decimal(stats.fortune()), Planner.decimal(stats.wisdom())));
				addTrees(trees);
			}

			modules.put("trees", trees);

			List<String> upgrades = new ArrayList<>();
			if (SkyBlockReader.statsKnown()) addHotf(upgrades);
			modules.put("upgrades", upgrades);

			List<String> contest = new ArrayList<>();
			addContest(contest);
			ContestHelper.hudLines(contest);
			modules.put("contest", contest);

			if (Planner.config.showRates) {
				session = SessionRates.snapshot();
				modules.put("session", List.of(SessionRates.line(),
						Planner.tr("journal.gifts") + ": " + Planner.whole(session.gifts())));
			} else {
				modules.put("session", List.of());
			}

			modules.put("guide", Planner.config.treeAssistant ? List.of(TreeAssistant.description(), TreeAssistant.warning())
					: Planner.config.helixGuide && SkyBlockReader.area().equalsIgnoreCase("Torrhus Canyon") ? List.of(Planner.tr("hud.helix")) : List.of());
		}

		if (SkyBlockReader.inSkyBlock()) modules.put("hunting", HuntingTracker.hudLines());
		modules.put("levels", SkyBlockReader.inSkyBlock() ? Levels.lines() : List.of());
		modules.put("gather", SkyBlockReader.inForagingArea() ? Extras.gatherLines() : List.of());

		List<String> targets = new ArrayList<>();

		for (var target : JournalStore.journal.targets) {
			if (targets.size() >= 4) break;
			targets.add("§e" + target.name + " §a" + Math.round(TargetProgress.fraction(target) * 100) + "%");
			targets.add("§7" + TargetProgress.detail(target));
		}

		modules.put("targets", targets);
		lines = modules.values().stream().flatMap(List::stream).toList();
	}

	public static Map<String, List<String>> previewModules() {
		Map<String, List<String>> result = new LinkedHashMap<>();

		for (String id : ORDER) {
			var current = modules.getOrDefault(id, List.of());
			if (id.equals("progress") && current.isEmpty()) current = TreeProgress.preview();
			result.put(id, current.isEmpty() ? List.of(Planner.tr("journal.preview_placeholder")) : current);
		}

		return result;
	}

	public static List<String> previewLines() {
		if (!lines.isEmpty()) return List.copyOf(lines);
		return List.of(Planner.tr("hud.stats", "234", "150", "25"), Planner.tr("settings.preview"), Planner.tr("hud.helix"));
	}

	private static void addTrees(List<String> built) {
		for (TreeReport.Row row : Planner.treeRows()) {
			String next = row.sweepToNext() < 0
					? Planner.tr("hud.tree_max")
					: Planner.tr("hud.tree_next", Planner.decimal(row.sweepToNext()));

			built.add(Planner.tr(row.best() ? "hud.tree_best" : "hud.tree",
					row.tree().name, Planner.decimal(row.logs()), row.measured() ? "*" : "", Planner.whole(row.xpPerHit()), next));
		}
	}

	private static void addHotf(List<String> built) {
		if (Planner.config.perkLevels.isEmpty()) {
			built.add(Planner.tr("hud.hotf_unread"));
			return;
		}

		List<HotfPlanner.Upgrade> upgrades = Planner.upgrades();
		if (upgrades.isEmpty() || upgrades.get(0).gain() <= 0) return;

		HotfPlanner.Upgrade best = upgrades.get(0);
		boolean affordable = Planner.budget(best.perk()) >= best.cost();
		built.add(Planner.tr(affordable ? "hud.hotf_affordable" : "hud.hotf",
				best.perk().name, best.fromLevel(), best.fromLevel() + 1, Planner.whole(best.cost())));
	}

	private static void addContest(List<String> built) {
		if (!ContestTracker.active()) return;

		ContestMath.Status status = ContestTracker.status();
		int seconds = ContestTracker.secondsLeft();
		String bracket = status.current() == null ? "-" : status.current().name;

		built.add(Planner.tr("hud.contest", ContestTracker.host(), seconds / 60, String.format("%02d", seconds % 60)));
		built.add(Planner.tr("hud.contest_points", Planner.whole(ContestTracker.points()), bracket));

		if (status.next() != null) {
			double pace = ContestTracker.pointsPerMinute();
			boolean onTrack = pace > 0 && pace >= status.neededPerMin();
			String needed = Double.isInfinite(status.neededPerMin()) ? "-" : Planner.whole(Math.ceil(status.neededPerMin()));

			built.add(Planner.tr(onTrack ? "hud.contest_next_ok" : "hud.contest_next",
					status.next().name, Planner.whole(status.pointsToNext()), needed, Planner.whole(pace)));
		}

		if (status.projectedBracket() != null && ContestTracker.pointsPerMinute() > 0) {
			built.add(Planner.tr("hud.contest_projection", Planner.whole(status.projected()), status.projectedBracket().name));
		}
	}

	// Which cards get a custom body

	private static boolean sessionCard(String id) {
		return id.equals("session") && session != null && !modules.getOrDefault("session", List.of()).isEmpty();
	}

	private static boolean contestCard(String id) {
		return id.equals("contest") && ContestTracker.active();
	}

	// Sizes, shared with the HUD editor so its hit boxes match what is drawn

	private static Font font() {
		return Minecraft.getInstance().font;
	}

	private static String title(String id) {
		return Planner.tr("journal.module_" + id);
	}

	/** Small text on the right of a card's header, or an empty string. */
	private static String headerInfo(String id) {
		if (id.equals("trees")) return Planner.tr("option." + Planner.goal().id());
		if (contestCard(id)) return clock(ContestTracker.secondsLeft());
		if (sessionCard(id)) return (Afk.idle() ? "⏸ " : "") + clock((int) (session.elapsedMs() / 1000));
		return "";
	}

	private static int headerWidth(String id) {
		String info = headerInfo(id);
		return font().width(title(id)) + (info.isEmpty() ? 0 : 10 + font().width(info));
	}

	/** Width of a card at scale 1. */
	public static int cardWidth(String id, List<String> content) {
		style();
		Font font = font();
		int body;

		if (sessionCard(id)) {
			body = sessionWidth();
		} else if (contestCard(id)) {
			body = 150;
			for (String line : content.subList(Math.min(2, content.size()), content.size())) body = Math.max(body, font.width(line));
		} else {
			body = 60;
			for (String line : content) body = Math.max(body, font.width(line));
		}

		return Math.min(MAX_WIDTH, Math.max(body, id.equals("progress") ? 0 : headerWidth(id)) + 2 * PAD);
	}

	/** Height of a card at scale 1. */
	public static int cardHeight(String id, List<String> content) {
		style();
		int line = font().lineHeight + LINE_GAP;

		if (sessionCard(id)) return 2 * PAD + HEADER + sessionRows() * (TILE_H + LINE_GAP) - LINE_GAP;
		if (contestCard(id)) return 2 * PAD + HEADER + 10 + 6 + Math.max(0, content.size() - 2) * line;
		if (id.equals("progress")) return 2 * PAD + content.size() * line - LINE_GAP + 4;
		return 2 * PAD + HEADER + content.size() * line - LINE_GAP;
	}

	// Drawing

	public static void extract(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		ContestAlerts.extract(graphics);
		if (!Planner.config.hudEnabled) return;

		Set<String> ids = new LinkedHashSet<>(ORDER);
		ids.addAll(modules.keySet());

		for (String id : ids) {
			List<String> content = id.equals("reel") ? HuntingTracker.reelLines() : id.equals("progress") ? TreeProgress.lines() : modules.getOrDefault(id, List.of());
			drawCard(graphics, id, content, false, false);
		}
	}

	/** Used by the HUD editor, which shows every module even when it is hidden or empty. */
	public static void drawModule(GuiGraphicsExtractor g, String id, List<String> content, boolean editor, boolean selected) {
		drawCard(g, id, content, editor, selected);
	}

	private static void drawCard(GuiGraphicsExtractor g, String id, List<String> content, boolean editor, boolean selected) {
		Card card = CARDS.computeIfAbsent(id, key -> new Card());
		long now = System.nanoTime();
		double dt = card.lastNs == 0 ? 0 : Math.min(0.1, (now - card.lastNs) / 1e9);
		card.lastNs = now;

		boolean animated = Planner.config.animations;
		var layout = Planner.config.module(id);
		boolean show = !content.isEmpty() && (editor || layout.visible);

		if (show && !content.equals(card.last)) {
			long[] changed = new long[content.size()];

			for (int i = 0; i < content.size(); i++) {
				boolean same = i < card.last.size() && card.last.get(i).equals(content.get(i));
				// A card that is only now appearing should not flash every line.
				changed[i] = same ? (i < card.changedNs.length ? card.changedNs[i] : 0) : card.appear > 0.5 ? now : 0;
			}

			card.changedNs = changed;
			card.last = List.copyOf(content);
		}

		card.appear = animated ? approach(card.appear, show ? 1 : 0, dt, 9) : show ? 1 : 0;
		if (card.appear < 0.01 || card.last.isEmpty()) return;

		List<String> body = show ? content : card.last;
		int targetW = cardWidth(id, body);
		int targetH = cardHeight(id, body);
		card.width = animated && card.width > 0 ? approach(card.width, targetW, dt, 14) : targetW;
		card.height = animated && card.height > 0 ? approach(card.height, targetH, dt, 14) : targetH;

		var window = Minecraft.getInstance().getWindow();
		float scale = (float) Math.clamp(layout.scale, 0.5, 2);
		int x = Math.clamp(layout.x, 0, Math.max(0, window.getGuiScaledWidth() - (int) Math.ceil(targetW * scale)));
		int y = Math.clamp(layout.y, 0, Math.max(0, window.getGuiScaledHeight() - (int) Math.ceil(targetH * scale)));

		double a = easeOut(card.appear) * (editor && !layout.visible ? 0.45 : 1);
		int w = (int) Math.round(card.width);
		int h = (int) Math.round(card.height);

		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(x, y);
		pose.scale(scale, scale);

		int radius = Math.clamp(Planner.config.hudRadius, 0, 12);
		if (radius <= 0) g.fill(0, 0, w, h, alpha(fillColor(), a));
		else round(g, 0, 0, w, h, radius, alpha(fillColor(), a));
		if (Planner.config.hudBorder && !selected) outline(g, w, h, radius, alpha(0xFF000000 | Planner.config.hudBorderColor, a));
		if (selected) outline(g, w, h, radius, alpha(UI_AMBER, a));
		if (!id.equals("progress")) drawHeader(g, id, w, a, now);

		// While a card grows or shrinks, lines below its current edge are left out instead of spilling over.
		if (sessionCard(id) && show) drawSession(g, card, w, a, dt);
		else if (contestCard(id) && show) drawContest(g, card, body, w, h, a, dt);
		else if (id.equals("progress")) drawProgress(g, card, body, w, a, dt);
		else drawLines(g, card, body, w, h, a, now, animated);

		pose.popMatrix();
	}

	private static void outline(GuiGraphicsExtractor g, int w, int h, int radius, int c) {
		if (radius > 0) {
			roundOutline(g, 0, 0, w, h, radius, c);
			return;
		}
		g.fill(0, 0, w, 1, c);
		g.fill(0, h - 1, w, h, c);
		g.fill(0, 0, 1, h, c);
		g.fill(w - 1, 0, w, h, c);
	}

	private static void drawHeader(GuiGraphicsExtractor g, String id, int w, double a, long now) {
		Font font = font();
		String info = headerInfo(id);
		g.text(font, fit(font, title(id), w - 2 * PAD), PAD, PAD, alpha(accent(id), a), ts());
		if (!info.isEmpty()) g.text(font, info, w - PAD - font.width(info), PAD, alpha(0xFFAAAAAA, a), ts());
	}

	private static int accent(String id) {
		return switch (id) {
			case "contest", "upgrades" -> 0xFFFFAA00;
			case "session" -> 0xFF55FFFF;
			case "hunting", "reel" -> 0xFFFF5555;
			default -> 0xFF55FF55;
		};
	}

	private static void drawLines(GuiGraphicsExtractor g, Card card, List<String> body, int w, int h, double a, long now, boolean animated) {
		Font font = font();
		int y = PAD + HEADER;

		for (int i = 0; i < body.size(); i++) {
			if (y + font.lineHeight > h - PAD + 2) break;
			g.text(font, fit(font, body.get(i), w - 2 * PAD), PAD, y, alpha(0xFFFFFFFF, a), ts());
			y += font.lineHeight + LINE_GAP;
		}
	}

	/** Tree progress: the lines with a thin bar under the first one, no header. */
	private static void drawProgress(GuiGraphicsExtractor g, Card card, List<String> body, int w, double a, double dt) {
		Font font = font();
		int pct = TreeProgress.percent();
		double target = pct < 0 ? 0.72 : pct / 100.0;
		card.bar = Planner.config.animations ? approach(card.bar, target, dt, 10) : target;
		int y = PAD;
		for (int i = 0; i < body.size(); i++) {
			g.text(font, fit(font, body.get(i), w - 2 * PAD), PAD, y, alpha(i == 0 ? 0xFF55FF55 : 0xFFFFFFFF, a), ts());
			y += font.lineHeight + LINE_GAP;
			if (i == 0) {
				g.fill(PAD, y, w - PAD, y + 2, alpha(0x40FFFFFF, a));
				g.fill(PAD, y, PAD + (int) Math.round((w - 2 * PAD) * card.bar), y + 2, alpha(target >= 1 ? 0xFF55FF55 : 0xFFFFAA00, a));
				y += 4;
			}
		}
	}

	/** One value of the session card. */
	private record Entry(String label, String value, int color) {}

	private static final String[] SESSION_LABELS = {"hud.session_xp", "hud.session_coins", "hud.session_essence", "hud.session_essence_rate",
			"hud.session_profit", "hud.session_gifts", "hud.session_hotf_rate", "hud.session_hotf", "hud.session_contests", "hud.session_tpm"};

	/** The session values; {@code shown} holds the animated numbers, or null for the final ones. */
	private static List<Entry> sessionEntries(double[] shown) {
		SessionRates.Snapshot s = session;
		double[] v = shown != null ? shown : sessionTargets(s);
		String unpriced = s.unpriced() ? "+?" : "";
		String wait = "–";
		ContestRewards.Result last = ContestRewards.last();
		String bracket = last == null ? "" : " · " + capital(last.bracket());
		List<Entry> out = new ArrayList<>();
		out.add(new Entry(SESSION_LABELS[0], s.waiting() ? wait : compact(v[0]), UI_ACCENT));
		out.add(new Entry(SESSION_LABELS[1], s.waiting() ? wait : compact(v[1]) + unpriced, UI_AMBER));
		out.add(new Entry(SESSION_LABELS[2], compact(v[2]), ESSENCE));
		out.add(new Entry(SESSION_LABELS[3], s.waiting() ? wait : compact(v[3]), ESSENCE));
		out.add(new Entry(SESSION_LABELS[4], compact(v[4]) + unpriced, UI_AMBER));
		out.add(new Entry(SESSION_LABELS[5], compact(v[5]), UI_TEXT));
		out.add(new Entry(SESSION_LABELS[6], s.waiting() ? wait : compact(v[6]), HOTF));
		out.add(new Entry(SESSION_LABELS[7], compact(v[7]), HOTF));
		out.add(new Entry(SESSION_LABELS[8], compact(v[8]) + bracket, last == null ? UI_TEXT : bracketColor(last.bracket())));
		out.add(new Entry(SESSION_LABELS[9], Tpm.lastMinute() + " · Ø" + String.format(Locale.ROOT, "%.1f", Tpm.session()), UI_TEXT));
		return out;
	}

	private static String capital(String text) {
		if (text == null || text.isEmpty()) return "";
		return text.charAt(0) + text.substring(1).toLowerCase(Locale.ROOT);
	}

	private static double[] sessionTargets(SessionRates.Snapshot s) {
		return new double[] {s.xpPerHour(), s.coinsPerHour(), s.essence(), s.essencePerHour(), s.profit(), s.gifts(),
				s.hotfPerHour(), s.hotf(), s.contests(), 0};
	}

	private static int sessionColumns() {
		return Math.clamp(Planner.config.sessionColumns, 1, 3);
	}

	private static int sessionRows() {
		int rows = (SESSION_LABELS.length + sessionColumns() - 1) / sessionColumns();
		return rows + (Routes.ghostLine().isEmpty() ? 0 : 1);
	}

	/** Width of each column: the widest "label value" in it, so nothing is cut off. */
	private static int[] sessionColumnWidths(List<Entry> entries) {
		Font font = font();
		int columns = sessionColumns();
		int[] widths = new int[columns];
		for (int i = 0; i < entries.size(); i++) {
			Entry e = entries.get(i);
			// A little room so a value that grows by a digit does not make the card jump.
			int w = font.width(Planner.tr(e.label()) + " ") + font.width(e.value()) + 4;
			widths[i % columns] = Math.max(widths[i % columns], w);
		}
		return widths;
	}

	private static int sessionWidth() {
		int[] widths = sessionColumnWidths(sessionEntries(null));
		int total = (widths.length - 1) * TILE_GAP;
		for (int w : widths) total += w;
		String ghost = Routes.ghostLine();
		return Math.max(total, ghost.isEmpty() ? 0 : font().width(ghost));
	}

	private static void drawSession(GuiGraphicsExtractor g, Card card, int w, double a, double dt) {
		boolean animated = Planner.config.animations;
		double[] targets = sessionTargets(session);
		for (int i = 0; i < targets.length; i++) {
			// Numbers glide to their new value; big jumps (a reset) snap instead of counting for seconds.
			boolean jump = Math.abs(targets[i] - card.shown[i]) > Math.max(1, Math.abs(targets[i])) * 4;
			card.shown[i] = animated && !jump ? approach(card.shown[i], targets[i], dt, 5) : targets[i];
		}

		Font font = font();
		List<Entry> entries = sessionEntries(card.shown);
		int[] widths = sessionColumnWidths(sessionEntries(null));
		int columns = widths.length;
		int labelColor = 0xFF000000 | Planner.config.hudLabelColor;
		for (int i = 0; i < entries.size(); i++) {
			Entry e = entries.get(i);
			int column = i % columns, row = i / columns;
			int x = PAD;
			for (int c = 0; c < column; c++) x += widths[c] + TILE_GAP;
			int y = PAD + HEADER + row * (TILE_H + LINE_GAP);
			String name = Planner.tr(e.label()) + " ";
			g.text(font, name, x, y, alpha(labelColor, a), ts());
			g.text(font, e.value(), x + font.width(name), y, alpha(e.color(), a), ts());
		}

		String ghost = Routes.ghostLine();
		if (!ghost.isEmpty()) {
			int y = PAD + HEADER + ((entries.size() + columns - 1) / columns) * (TILE_H + LINE_GAP);
			g.text(font, fit(font, ghost, w - 2 * PAD), PAD, y, alpha(Routes.ahead() ? 0xFF7FD48A : 0xFFE0A070, a), ts());
		}
	}

	private static void drawContest(GuiGraphicsExtractor g, Card card, List<String> body, int w, int h, double a, double dt) {
		Font font = font();
		ContestMath.Status status = ContestTracker.status();
		int y = PAD + HEADER;

		String points = Planner.whole(ContestTracker.points());
		g.text(font, points, PAD, y, alpha(0xFFFFFFFF, a), ts());
		g.text(font, ContestTracker.host(), PAD + font.width(points) + 5, y, alpha(0xFFAAAAAA, a), ts());

		String bracket = status.current() == null ? "–" : status.current().name;
		g.text(font, bracket, w - PAD - font.width(bracket), y, alpha(bracketColor(bracket), a), ts());

		double low = status.current() == null ? 0 : status.current().minPoints;
		double high = status.next() == null ? low : status.next().minPoints;
		double fraction = status.next() == null ? 1 : Math.clamp((ContestTracker.points() - low) / Math.max(1, high - low), 0.0, 1.0);
		card.bar = Planner.config.animations ? approach(card.bar, fraction, dt, 6) : fraction;

		double pace = ContestTracker.pointsPerMinute();
		boolean onTrack = status.next() == null || (pace > 0 && pace >= status.neededPerMin());
		int barY = y + 11;
		int barW = w - 2 * PAD;
		g.fill(PAD, barY, PAD + barW, barY + 2, alpha(0x40FFFFFF, a));
		int fill = (int) Math.round(barW * card.bar);
		if (fill > 0) g.fill(PAD, barY, PAD + fill, barY + 2, alpha(onTrack ? 0xFF55FF55 : 0xFFFFAA00, a));

		int lineY = y + 16;

		for (String line : body.subList(Math.min(2, body.size()), body.size())) {
			if (lineY + font.lineHeight > h - PAD + 2) break;
			g.text(font, fit(font, line, w - 2 * PAD), PAD, lineY, alpha(0xFFFFFFFF, a), ts());
			lineY += font.lineHeight + LINE_GAP;
		}
	}

	private static int bracketColor(String bracket) {
		return switch (bracket) {
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

	private static String compact(double value) {
		double v = Math.abs(value);
		if (v >= 1e9) return String.format(Locale.US, "%.2fB", value / 1e9);
		if (v >= 1e6) return String.format(Locale.US, "%.2fM", value / 1e6);
		if (v >= 1e4) return String.format(Locale.US, "%.1fk", value / 1e3);
		return Planner.whole(value);
	}

	private static String clock(int seconds) {
		int s = Math.max(0, seconds);
		if (s >= 3600) return String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60);
		return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
	}

	/** True when the goal is coins and Bazaar prices have arrived, so /fp tree can show coins per hit. */
	static boolean showsCoins() {
		return Planner.goal() == Goal.COINS && !Planner.PRICES.isEmpty();
	}
}
