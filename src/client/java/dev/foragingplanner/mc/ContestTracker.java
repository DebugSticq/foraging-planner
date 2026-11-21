package dev.foragingplanner.mc;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.foragingplanner.core.ContestMath;

/** Follows the running Starlyn contest from the sidebar and measures the player's pace. */
public final class ContestTracker {
	/** Pace is the average over this many seconds. */
	private static final long PACE_WINDOW_MS = 180_000L;
	/** A pace over a shorter span than this is too noisy to show. */
	private static final long MIN_PACE_SPAN_MS = 20_000L;
	/** The sidebar puts the points on one of the lines right below the contest header. */
	private static final int LINES_BELOW_HEADER = 3;
	private static final Pattern ANY_NUMBER = Pattern.compile("([\\d,]+(?:\\.\\d+)?k?)");

	private record Sample(long timeMs, int points) {}

	private static final Deque<Sample> SAMPLES = new ArrayDeque<>();

	private static boolean active;
	private static String host = "";
	private static int secondsLeft;
	private static int points;

	private ContestTracker() {}

	/** Points the sidebar number is multiplied with; learned from the end-of-contest message. */
	private static int raw;
	/** Logs and points when this contest was first seen, to measure the player's real points per log. */
	private static double logsAtStart = -1;
	private static int pointsAtStart;
	private static final java.util.Map<String, Long> ITEMS_AT_START = new java.util.HashMap<>();
	private static boolean dumped;
	/** Replay: [seconds into the contest, points], at most one every 10 seconds. */
	private static final java.util.List<int[]> HISTORY = new java.util.ArrayList<>();

	/** "UNCOMMON with 258" below the header: the current bracket and the real points. */
	private static final Pattern WITH = Pattern.compile("(?i)^\\s*(\\w+)\\s+with\\s+([\\d,]+)");
	/** "Rare requires +742": the next bracket and the points still missing. */
	private static final Pattern REQUIRES = Pattern.compile("(?i)^\\s*(\\w+)\\s+requires\\s+\\+?([\\d,]+)");

	private static String sidebarBracket = "";
	private static String sidebarNext = "";
	private static int sidebarMissing = -1;

	// End of a contest. Hypixel keeps the header in the tab list for a while after the result, so the
	// tracker stops on the result message and ignores that host until a new timer shows up.
	private static long endedAt;
	private static String endedHost = "";
	private static double endLogs;
	private static java.util.Map<String, Long> endByTree = new java.util.LinkedHashMap<>();
	/** Logs from sack messages that arrived after the end but were cut before it. */
	private static double lateLogs;
	private static final java.util.Map<String, Double> LATE_BY_TREE = new java.util.HashMap<>();
	private static final long GRACE_MS = 45_000;

	/** Called on the result message: freezes the counts of this contest. */
	public static void ended() {
		if (logsAtStart >= 0 && endedAt == 0) {
			endLogs = liveLogs();
			endByTree = liveByTree();
		}
		endedAt = System.currentTimeMillis();
		endedHost = host;
		lateLogs = 0;
		LATE_BY_TREE.clear();
		active = false;
		Progress.get().contestState = null;
		Progress.changed();
	}

	/** True shortly after a contest ended, while sack messages can still hold logs cut during it. */
	public static boolean inGrace() {
		return endedAt > 0 && System.currentTimeMillis() - endedAt < GRACE_MS;
	}

	public static long endedAt() {
		return endedAt;
	}

	/**
	 * A sack message after the end covers the last {@code window} seconds; the part of that window
	 * before the end still belongs to the contest.
	 */
	public static void addLate(String bazaarId, long delta, int window) {
		if (!inGrace() || delta <= 0) return;
		double since = (System.currentTimeMillis() - endedAt) / 1000.0;
		double fraction = window <= 0 ? 0 : Math.clamp((window - since) / window, 0, 1);
		if (fraction <= 0) return;
		lateLogs += delta * fraction;
		for (var t : Planner.data.trees) if (t.bazaarId.equals(bazaarId)) LATE_BY_TREE.merge(t.id, delta * fraction, Double::sum);
	}

	private static boolean frozen() {
		return endedAt > 0 && !active;
	}

	public static void refresh(List<String> sidebar) {
		Pattern header = Patterns.get(Planner.data.contest.headerPattern);
		boolean wasActive = active;
		active = false;
		if (header == null) return;

		List<String> lines = new java.util.ArrayList<>(sidebar);
		int tabStart = lines.size();
		lines.addAll(SkyBlockReader.tab());

		boolean headerSeen = false;
		for (int i = 0; i < lines.size(); i++) {
			Matcher matcher = header.matcher(lines.get(i));
			if (!matcher.find()) continue;
			headerSeen = true;

			String newHost = matcher.group(1);
			int newSeconds = seconds(matcher.group(2)) * 60 + seconds(matcher.group(3));
			long sinceEnd = System.currentTimeMillis() - endedAt;
			if (endedAt > 0 && newHost.equals(endedHost)
					&& (sinceEnd < 60_000 || newSeconds <= 5 || (i >= tabStart && sinceEnd < 600_000))) continue;
			// The tab widget header ("Agatha's Contest:") has no timer; keep the last one then.
			if (i >= tabStart && newSeconds == 0) newSeconds = Math.max(0, secondsLeft - 1);
			int limit = Math.min(lines.size(), i >= tabStart ? lines.size() : tabStart);
			int newRaw = -1;
			sidebarBracket = "";
			sidebarNext = "";
			sidebarMissing = -1;
			for (int j = i + 1; j < Math.min(limit, i + 4); j++) {
				Matcher with = WITH.matcher(lines.get(j));
				if (with.find()) {
					sidebarBracket = with.group(1).toUpperCase(java.util.Locale.ROOT);
					newRaw = Integer.parseInt(with.group(2).replace(",", ""));
				}
				Matcher requires = REQUIRES.matcher(lines.get(j));
				if (requires.find()) {
					sidebarNext = requires.group(1).toUpperCase(java.util.Locale.ROOT);
					sidebarMissing = Integer.parseInt(requires.group(2).replace(",", ""));
				}
			}
			if (newRaw < 0) newRaw = readPoints(lines, i + 1, limit);

			if (newRaw < 0) {
				if (!dumped) {
					dumped = true;
					Planner.LOGGER.info("[FP] Contest seen, but no points line found. Lines: {}", lines.subList(i, Math.min(lines.size(), i + 5)));
				}
				newRaw = raw;
			}

			// The timer jumping up, the points dropping or another host means a new contest began.
			if (!newHost.equals(host) || newSeconds > secondsLeft + 5 || newRaw < raw || !wasActive) {
				SAMPLES.clear();
				HISTORY.clear();
				logsAtStart = -1;
				endedAt = 0;
				lateLogs = 0;
				LATE_BY_TREE.clear();
			}

			host = newHost;
			secondsLeft = newSeconds;
			raw = newRaw;
			// The sidebar shows the real contest points ("UNCOMMON with 258"), no conversion needed.
			points = raw;
			active = true;
			if (logsAtStart < 0) start(newHost, newSeconds);

			long now = System.currentTimeMillis();
			SAMPLES.addLast(new Sample(now, points));
			int elapsed = Math.max(0, 1200 - secondsLeft);
			if (HISTORY.isEmpty() || elapsed - HISTORY.getLast()[0] >= 10) {
				HISTORY.add(new int[] {elapsed, points});
				saveState();
			}
			else if (HISTORY.getLast()[1] != points && elapsed == HISTORY.getLast()[0]) HISTORY.getLast()[1] = points;
			while (SAMPLES.size() > 1 && now - SAMPLES.peekFirst().timeMs() > PACE_WINDOW_MS) SAMPLES.removeFirst();
			return;
		}

		dumped = false;
		// The old header is gone everywhere: from now on the same host can start a new contest.
		if (!headerSeen && endedAt > 0 && System.currentTimeMillis() - endedAt > 60_000) endedHost = "";
	}

	/**
	 * First second of a contest. If the game was restarted during this same contest, the counts from
	 * before the restart are restored, so the logs cut before it are not lost.
	 */
	private static void start(String newHost, int newSeconds) {
		long now = System.currentTimeMillis();
		long startMs = now - (1200L - newSeconds) * 1000L;
		Progress.ContestState saved = Progress.get().contestState;
		ITEMS_AT_START.clear();
		if (saved != null && newHost.equals(saved.host) && Math.abs(saved.startMs - startMs) < 90_000) {
			logsAtStart = saved.logsAtStart;
			pointsAtStart = saved.pointsAtStart;
			for (var t : Planner.data.trees) {
				long current = JournalStore.journal.current.items.getOrDefault(t.bazaarId, 0L);
				ITEMS_AT_START.put(t.bazaarId, current - saved.gained.getOrDefault(t.id, 0L));
			}
			HISTORY.clear();
			if (saved.history != null) HISTORY.addAll(saved.history);
			Planner.LOGGER.info("[FP] Contest resumed after a restart: {} logs so far", Math.round(liveLogs()));
			return;
		}
		logsAtStart = JournalStore.journal.totals.getOrDefault("logs", 0.0);
		pointsAtStart = points;
		for (var t : Planner.data.trees) ITEMS_AT_START.put(t.bazaarId, JournalStore.journal.current.items.getOrDefault(t.bazaarId, 0L));
		Progress.ContestState state = new Progress.ContestState();
		state.host = newHost;
		state.startMs = startMs;
		state.logsAtStart = logsAtStart;
		state.pointsAtStart = pointsAtStart;
		Progress.get().contestState = state;
		Progress.changed();
		Leaderboard.contestStarted(newHost);
	}

	private static void saveState() {
		Progress.ContestState state = Progress.get().contestState;
		if (state == null || !state.host.equals(host)) return;
		state.gained = liveByTree();
		state.history = new java.util.ArrayList<>(HISTORY);
		Progress.changed();
	}

	/**
	 * Contest points this player gets per log in this contest, measured from the sidebar and the
	 * logs counted since the start, or -1 while too few logs were cut. This already contains every
	 * multiplier the player has, and the share of points from other activities.
	 */
	/** Points over time in this contest, for the replay chart. */
	public static java.util.List<int[]> history() {
		java.util.List<int[]> copy = new java.util.ArrayList<>();
		for (int[] h : HISTORY) copy.add(h.clone());
		if (!HISTORY.isEmpty() && HISTORY.getLast()[1] != points) copy.add(new int[] {Math.max(0, 1200 - secondsLeft), points});
		return copy;
	}

	public static double measuredPointsPerLog() {
		if (!active || logsAtStart < 0) return -1;
		double logs = JournalStore.journal.totals.getOrDefault("logs", 0.0) - logsAtStart;
		int gained = points - pointsAtStart;
		return logs >= 100 && gained > 0 ? gained / logs : -1;
	}

	/** Logs collected this contest, by tree id, counted like the collection (drops from inventory and sacks). */
	public static java.util.Map<String, Long> logsByTree() {
		if (frozen()) {
			java.util.Map<String, Long> out = new java.util.LinkedHashMap<>(endByTree);
			LATE_BY_TREE.forEach((tree, late) -> out.merge(tree, Math.round(late), Long::sum));
			return out;
		}
		return liveByTree();
	}

	private static java.util.Map<String, Long> liveByTree() {
		java.util.Map<String, Long> out = new java.util.LinkedHashMap<>();
		if (logsAtStart < 0) return out;
		for (var t : Planner.data.trees) {
			long gained = JournalStore.journal.current.items.getOrDefault(t.bazaarId, 0L) - ITEMS_AT_START.getOrDefault(t.bazaarId, 0L);
			if (gained > 0) out.put(t.id, gained);
		}
		return out;
	}

	/** Tree with the most logs this contest, or null. */
	public static String mainTree() {
		return logsByTree().entrySet().stream().max(java.util.Map.Entry.comparingByValue()).map(java.util.Map.Entry::getKey).orElse(null);
	}

	/** Logs cut since the contest was first seen, or 0. */
	public static double logsThisContest() {
		if (frozen()) return endLogs + lateLogs;
		return liveLogs();
	}

	private static double liveLogs() {
		return logsAtStart < 0 ? 0 : JournalStore.journal.totals.getOrDefault("logs", 0.0) - logsAtStart;
	}

	/** Sidebar number to contest points. 1 until a contest end has been seen. */
	public static double factor() {
		return 1;
	}

	/** The last number the sidebar showed, before conversion to points. */
	public static int raw() {
		return raw;
	}

	private static int seconds(String text) {
		return text == null ? 0 : (int) Math.max(0, ContestMath.parseNumber(text));
	}

	private static final Pattern TIME = Pattern.compile("\\d+m\\s*\\d*s?|\\d+s\\b|\\d+:\\d{2}");

	/** Points from the lines below the contest header: the points pattern, or else the first plain number. */
	private static int readPoints(List<String> lines, int from, int limit) {
		Pattern pointsPattern = Patterns.get(Planner.data.contest.pointsPattern);
		int to = Math.min(limit, from + LINES_BELOW_HEADER);

		if (pointsPattern != null) {
			for (int i = from; i < to; i++) {
				Matcher matcher = pointsPattern.matcher(lines.get(i));

				if (matcher.find()) {
					for (int group = 1; group <= matcher.groupCount(); group++) {
						double value = ContestMath.parseNumber(matcher.group(group));
						if (value >= 0) return (int) value;
					}
				}
			}
		}

		for (int i = from; i < to; i++) {
			String line = TIME.matcher(lines.get(i)).replaceAll(" ");
			if (line.contains("Contest")) break;
			Matcher number = ANY_NUMBER.matcher(line);
			if (number.find()) {
				double value = ContestMath.parseNumber(number.group(1));
				if (value >= 0) return (int) value;
			}
		}

		return -1;
	}

	public static boolean active() {
		return active;
	}

	public static String host() {
		return host;
	}

	public static int secondsLeft() {
		return secondsLeft;
	}

	public static int points() {
		return points;
	}

	/** Points per minute over the last few minutes, or 0 while there is too little to go on. */
	public static double pointsPerMinute() {
		if (SAMPLES.size() < 2) return 0;

		Sample first = SAMPLES.peekFirst();
		Sample last = SAMPLES.peekLast();
		long span = last.timeMs() - first.timeMs();
		if (span < MIN_PACE_SPAN_MS) return 0;

		return (last.points() - first.points()) / (span / 60_000.0);
	}

	/** Final points of earlier contests at the same host (last 15), for the forecast. */
	private static java.util.List<Integer> pastFinals() {
		java.util.List<Integer> out = new java.util.ArrayList<>();
		for (Progress.ContestRecord r : Progress.get().contests) {
			if (r != null && r.points > 0 && r.host != null && r.host.equalsIgnoreCase(host)) out.add(r.points);
		}
		return out.size() > 15 ? new java.util.ArrayList<>(out.subList(out.size() - 15, out.size())) : out;
	}

	/** Estimated finish of the running contest, or null while there is nothing to go on. */
	public static dev.foragingplanner.core.ContestForecast.Result forecast() {
		if (!active) return null;
		return dev.foragingplanner.core.ContestForecast.estimate(Planner.data.contest.bracketsFor(host), points, secondsLeft,
				history(), pointsPerMinute(), pastFinals());
	}

	public static ContestMath.Status status() {
		List<dev.foragingplanner.core.GameData.Bracket> brackets = Planner.data.contest.bracketsFor(host);
		ContestMath.Status base = ContestMath.status(brackets, points, secondsLeft, pointsPerMinute());
		var guess = forecast();
		if (guess != null) {
			base = new ContestMath.Status(base.current(), base.next(), base.pointsToNext(), base.neededPerMin(), guess.expected(), guess.bracket());
		}
		if (sidebarMissing < 0 && sidebarBracket.isEmpty()) return base;

		// Hypixel's own numbers win over the bracket table in gamedata.json.
		dev.foragingplanner.core.GameData.Bracket current = find(brackets, sidebarBracket, base.current());
		dev.foragingplanner.core.GameData.Bracket next = sidebarMissing < 0 ? base.next() : find(brackets, sidebarNext, base.next());
		if (next != null && sidebarMissing >= 0 && next.minPoints != points + sidebarMissing) {
			dev.foragingplanner.core.GameData.Bracket fixed = new dev.foragingplanner.core.GameData.Bracket();
			fixed.name = next.name;
			fixed.minPoints = points + sidebarMissing;
			fixed.hotfExp = next.hotfExp;
			fixed.essence = next.essence;
			next = fixed;
		}
		int missing = sidebarMissing >= 0 ? sidebarMissing : base.pointsToNext();
		double needed = next == null ? 0 : secondsLeft <= 0 ? Double.POSITIVE_INFINITY : missing / (secondsLeft / 60.0);
		return new ContestMath.Status(current, next, missing, needed, base.projected(), base.projectedBracket());
	}

	private static dev.foragingplanner.core.GameData.Bracket find(List<dev.foragingplanner.core.GameData.Bracket> brackets, String name,
			dev.foragingplanner.core.GameData.Bracket fallback) {
		if (name == null || name.isEmpty()) return fallback;
		for (var b : brackets) if (b.name.equalsIgnoreCase(name)) return b;
		dev.foragingplanner.core.GameData.Bracket made = new dev.foragingplanner.core.GameData.Bracket();
		made.name = name;
		made.minPoints = fallback == null ? 0 : fallback.minPoints;
		return made;
	}
}
