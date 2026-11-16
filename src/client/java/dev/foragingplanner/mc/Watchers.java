package dev.foragingplanner.mc;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

import dev.foragingplanner.core.GameData;

/**
 * Background checks run once a second: daily goals, milestones, price alarms and the contest
 * countdown. Also records contest results and rare drops.
 */
public final class Watchers {
	/** Contest gaps longer than this are breaks in play, not the contest rhythm. */
	private static final long MAX_GAP_MS = 4 * 3_600_000L;
	private static final long WARN_BEFORE_MS = 120_000L;
	private static final double RARE_VALUE = 100_000;

	private static final List<String> METRICS = List.of("logs", "xp", "gifts", "contests", "essence", "hotf");
	private static final double[] BIG = {1e3, 1e4, 1e5, 1e6, 1e7, 1e8};
	private static final double[] SMALL = {10, 50, 100, 500, 1000, 5000, 10000};

	private static long lastAlarmCheck;
	private static long warnedFor;
	private static boolean wasActive;

	private Watchers() {}

	public static void tick() {
		Progress.rollDay();
		checkGoals();
		checkMilestones();
		checkContest();
		checkAlarms();
		checkPause();
		checkSummary();
		Progress.save(false);
	}

	// Smart pause reminder

	private static final java.util.ArrayDeque<double[]> PACE = new java.util.ArrayDeque<>();
	private static long lastPaceSample;
	private static long pauseWarnedAt;

	/** Suggests a break when the XP rate of the last 10 minutes falls well below the session average. */
	private static void checkPause() {
		if (!Planner.config.pauseReminder) { PACE.clear(); return; }
		var session = JournalStore.journal.current;
		long elapsed = session.elapsedMs;
		double xp = session.value("xp");
		long now = System.currentTimeMillis();
		if (!PACE.isEmpty() && elapsed < PACE.peekLast()[0]) PACE.clear();
		if (now - lastPaceSample >= 30_000) {
			lastPaceSample = now;
			PACE.addLast(new double[] {elapsed, xp});
			while (PACE.size() > 1 && elapsed - PACE.peekFirst()[0] > 600_000) PACE.removeFirst();
		}
		if (elapsed < 45 * 60_000L || PACE.size() < 2 || now - pauseWarnedAt < 30 * 60_000L) return;
		double[] first = PACE.peekFirst(), last = PACE.peekLast();
		double span = last[0] - first[0];
		if (span < 540_000 || xp <= 0 || !SessionRates.foraging()) return;
		double recent = (last[1] - first[1]) / span;
		double average = xp / elapsed;
		if (recent < average * 0.6) {
			pauseWarnedAt = now;
			int drop = (int) Math.round(100 - recent / average * 100);
			Toasts.show(Toasts.Kind.INFO, "cake", Planner.tr("toast.pause"), Planner.tr("toast.pause_text", drop, elapsed / 60_000));
		}
	}

	// Daily goals

	public static final List<String> GOAL_METRICS = List.of("xp", "logs", "contests", "gifts", "essence", "hotf");

	private static void checkGoals() {
		Progress p = Progress.get();

		for (int i = 0; i < p.dailyGoals.size(); i++) {
			Progress.DailyGoal goal = p.dailyGoals.get(i);
			String key = i + ":" + goal.metric + ":" + (long) goal.amount;
			if (goal.amount <= 0 || p.goalsDone.contains(key) || Progress.today(goal.metric) < goal.amount) continue;

			p.goalsDone.add(key);
			Progress.changed();
			Toasts.show(Toasts.Kind.GOAL, "slime_ball", Planner.tr("toast.goal"),
					Planner.tr("toast.goal_text", Planner.whole(goal.amount), Planner.tr("metric." + goal.metric)));
		}
	}

	// Milestones

	private static void checkMilestones() {
		Progress p = Progress.get();
		var totals = JournalStore.journal.totals;
		boolean prime = !p.milestonesPrimed;

		for (String metric : METRICS) {
			double total = totals.getOrDefault(metric, 0.0);
			double[] steps = metric.equals("gifts") || metric.equals("contests") ? SMALL : BIG;

			for (double step : steps) {
				if (total < step) break;
				String key = metric + ":" + (long) step;
				if (!p.milestones.add(key)) continue;
				Progress.changed();
				if (!prime) Toasts.show(Toasts.Kind.MILESTONE, "nether_star", Planner.tr("toast.milestone"),
						Planner.tr("toast.milestone_text", short_(step), Planner.tr("metric." + metric)));
			}
		}

		if (prime) {
			p.milestonesPrimed = true;
			Progress.changed();
		}
	}

	static String short_(double value) {
		if (value >= 1e9) return trim(value / 1e9) + " Mrd.";
		if (value >= 1e6) return trim(value / 1e6) + " Mio.";
		if (value >= 1e3) return trim(value / 1e3) + "k";
		return Planner.whole(value);
	}

	private static String trim(double value) {
		return value == Math.rint(value) ? String.valueOf((long) value) : String.format(Locale.US, "%.1f", value);
	}

	// Contests

	/** Called when a new contest is seen; {@code startMs} is when it began. */
	public static void contestStarted(long startMs) {
		Progress p = Progress.get();
		if (!p.contestStarts.isEmpty() && Math.abs(p.contestStarts.getLast() - startMs) < 60_000) return;
		p.contestStarts.add(startMs);
		while (p.contestStarts.size() > 40) p.contestStarts.removeFirst();
		Progress.changed();
	}

	/** Typical time between two contest starts, or -1 while too few were seen. */
	public static long contestInterval() {
		List<Long> starts = Progress.get().contestStarts;
		List<Long> gaps = new ArrayList<>();

		for (int i = 1; i < starts.size(); i++) {
			long gap = starts.get(i) - starts.get(i - 1);
			if (gap > 5 * 60_000L && gap < MAX_GAP_MS) gaps.add(gap);
		}

		if (gaps.isEmpty()) return -1;
		gaps.sort(null);
		long median = gaps.get(gaps.size() / 2);

		// A gap that is about twice the median means a contest in between was missed.
		List<Long> fitting = new ArrayList<>();
		for (long gap : gaps) {
			double ratio = gap / (double) median;
			long single = Math.round(gap / Math.max(1, Math.round(ratio)));
			fitting.add(single);
		}

		fitting.sort(null);
		return fitting.get(fitting.size() / 2);
	}

	/** Expected start of the next contest, or -1 when unknown. */
	public static long nextContestStart() {
		List<Long> starts = Progress.get().contestStarts;
		long interval = contestInterval();
		if (interval <= 0 || starts.isEmpty()) return -1;

		long next = starts.getLast();
		long now = System.currentTimeMillis();
		while (next <= now) next += interval;
		return next - starts.getLast() > MAX_GAP_MS ? -1 : next;
	}

	private static void checkContest() {
		boolean active = ContestTracker.active();

		if (active && !wasActive) {
			long elapsed = (Planner.data.contest.durationSeconds - ContestTracker.secondsLeft()) * 1000L;
			contestStarted(System.currentTimeMillis() - Math.max(0, elapsed));
		}

		wasActive = active;
		if (active || !Planner.config.contestCountdownSound) return;

		long next = nextContestStart();
		long left = next - System.currentTimeMillis();

		if (next > 0 && left > 0 && left <= WARN_BEFORE_MS && warnedFor != next) {
			warnedFor = next;
			Toasts.show(Toasts.Kind.INFO, "clock", Planner.tr("toast.contest_soon"), Planner.tr("toast.contest_soon_text", Math.max(1, left / 60_000)));
			Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BELL.value(), 1.0f));
		}
	}

	/** Called by ContestRewards when the result message arrives. */
	public static void contestResult(String host, String bracket) {
		Progress p = Progress.get();
		Progress.ContestRecord record = new Progress.ContestRecord();
		record.host = host;
		record.bracket = bracket;
		record.points = ContestTracker.points();
		record.time = System.currentTimeMillis();
		record.logs = Math.round(ContestTracker.logsThisContest());
		record.samples = ContestTracker.history();
		ContestTracker.ended();
		String tree = ContestTracker.mainTree();
		record.tree = tree == null ? "" : tree;
		p.contests.addFirst(record);
		while (p.contests.size() > 200) p.contests.removeLast();
		Progress.changed();
		Progress.save(true);
	}

	/** Called with the final points of a contest and the last sidebar number seen. */
	public static void contestPoints(int points, int raw) {
		Progress p = Progress.get();
		long now = System.currentTimeMillis();
		if (!p.contests.isEmpty() && now - p.contests.getFirst().time < 120_000) p.contests.getFirst().points = points;

		if (raw > 0 && points > 0) {
			double factor = points / (double) raw;
			// Ignore readings far off, e.g. when the sidebar was read before the last logs counted.
			if (factor > 0.001 && factor < 1000) {
				p.contestFactors.add(factor);
				while (p.contestFactors.size() > 5) p.contestFactors.removeFirst();
				List<Double> sorted = new ArrayList<>(p.contestFactors);
				sorted.sort(null);
				double median = sorted.get(sorted.size() / 2);
				p.contestFactor = Math.abs(median - 1) < 0.08 ? 1 : median;
			}
		}

		Progress.changed();
		Progress.save(true);

		// Sack messages come up to 30 seconds late; wait for them before the summary.
		summaryPoints = points;
		summaryDue = Math.max(now, ContestTracker.endedAt()) + 35_000;
	}

	private static int summaryPoints;
	private static long summaryDue;

	private static void checkSummary() {
		if (summaryDue == 0 || System.currentTimeMillis() < summaryDue) return;
		summaryDue = 0;
		Progress p = Progress.get();
		if (p.contests.isEmpty() || System.currentTimeMillis() - p.contests.getFirst().time > 180_000) return;
		Progress.ContestRecord last = p.contests.getFirst();
		long counted = Math.round(ContestTracker.logsThisContest());
		if (!last.logsConfirmed && counted > last.logs) last.logs = counted;
		String tree = ContestTracker.mainTree();
		if (tree != null && (last.tree == null || last.tree.isEmpty())) last.tree = tree;
		int points = summaryPoints > 0 ? summaryPoints : last.points;
		Leaderboard.contestEnded(points);

		// Remember how many points per log this player really gets, with all multipliers.
		GameData.Tree data = ContestHelper.currentTree();
		if (last.logs >= 200 && points > 0 && data != null) {
			double measured = points / (double) last.logs;
			Double old = p.contestPerLog.get(data.id);
			p.contestPerLog.put(data.id, old == null ? measured : old * 0.6 + measured * 0.4);
		}
		Progress.changed();
		Progress.save(true);

		String perLog = last.logs > 0 ? Planner.decimal(Math.round(points * 100.0 / last.logs) / 100.0) : "–";
		if (last.logs > 0) JournalStore.notify(Planner.tr("contest.summary", Planner.whole(last.logs), treeName(last.tree), Planner.whole(points), perLog));
		else JournalStore.notify(Planner.tr("contest.summary_nologs", Planner.whole(points)));
	}

	/** Hypixel's own numbers at contest end: logs collected (personal best) and the previous record. */
	public static void contestCollection(String tree, long logs, long previousBest) {
		Progress p = Progress.get();
		if (p.contests.isEmpty() || System.currentTimeMillis() - p.contests.getFirst().time > 120_000) return;
		Progress.ContestRecord last = p.contests.getFirst();
		if (logs > 0) {
			last.logs = logs;
			last.logsConfirmed = true;
			last.personalBest = true;
			last.tree = tree.toLowerCase(Locale.ROOT);
		}
		if (previousBest > 0) {
			last.previousBest = previousBest;
			last.personalBest = true;
		}
		Progress.changed();
		Progress.save(true);
	}

	static String treeName(String id) {
		for (GameData.Tree t : Planner.data.trees) if (t.id.equalsIgnoreCase(id)) return t.name;
		return id == null || id.isEmpty() ? "?" : StatsScreen.capital(id);
	}

	/** Best logs collected in one contest for a tree, or 0. */
	public static long bestLogs(String tree) {
		long best = 0;
		for (Progress.ContestRecord r : Progress.get().contests) if (tree.equalsIgnoreCase(r.tree)) best = Math.max(best, Math.max(r.logs, r.previousBest));
		return best;
	}

	/** Logs needed for {@code points} contest points on a tree, or -1 when the tree gives no points. */
	public static double logsForPoints(GameData.Tree tree, double points) {
		double perLog = pointsPerLog(tree);
		return perLog <= 0 ? -1 : points / perLog;
	}

	/** Where a points-per-log value comes from. */
	public enum Source { LIVE, LEARNED, ESTIMATE, NONE }

	/** Contest points per log: measured right now, learned from earlier contests, or the base value. */
	public static double pointsPerLog(GameData.Tree tree) {
		double live = ContestTracker.measuredPointsPerLog();
		if (live > 0) return live;
		if (tree != null) {
			Double learned = Progress.get().contestPerLog.get(tree.id);
			if (learned != null && learned > 0) return learned;
			if (tree.contestPointsPer100Logs > 0) return tree.contestPointsPer100Logs / 100.0;
		}
		return -1;
	}

	public static Source pointsSource(GameData.Tree tree) {
		if (ContestTracker.measuredPointsPerLog() > 0) return Source.LIVE;
		if (tree != null && Progress.get().contestPerLog.containsKey(tree.id)) return Source.LEARNED;
		if (tree != null && tree.contestPointsPer100Logs > 0) return Source.ESTIMATE;
		return Source.NONE;
	}

	// Rare drops

	/** Called for every reward item of a tree gift. */
	public static void reward(String id, String name, long count, boolean bonus) {
		Double price = Planner.PRICES.get(id);
		if (price == null) price = AuctionPrices.get(id);
		double value = price == null ? 0 : price * count;
		if (!bonus && value < RARE_VALUE) return;

		Progress p = Progress.get();
		double gifts = JournalStore.journal.totals.getOrDefault("gifts", 0.0);
		Progress.Drop drop = new Progress.Drop();
		drop.id = id;
		drop.name = name;
		drop.count = count;
		drop.value = value;
		drop.time = System.currentTimeMillis();
		drop.giftsSince = p.giftsAtLastRare < 0 ? -1 : Math.round(gifts - p.giftsAtLastRare);
		p.giftsAtLastRare = gifts;
		p.drops.addFirst(drop);
		while (p.drops.size() > 300) p.drops.removeLast();
		Progress.changed();
		Progress.save(true);

		String text = (count > 1 ? count + "× " : "") + name + (value > 0 ? " · " + GearScreenFormat.coins(value) : "");
		Toasts.show(Toasts.Kind.RARE, "gold_ingot", Planner.tr("toast.rare"), text);
	}

	/** Tree gifts since the last rare drop, or -1 when none was seen yet. */
	public static long giftsSinceRare() {
		Progress p = Progress.get();
		if (p.giftsAtLastRare < 0) return -1;
		return Math.round(JournalStore.journal.totals.getOrDefault("gifts", 0.0) - p.giftsAtLastRare);
	}

	// Price alarms

	public static Double price(String id) {
		Double bazaar = BazaarPrices.BUY.get(id);
		if (bazaar != null) return bazaar;
		AuctionPrices.request(id);
		return AuctionPrices.get(id);
	}

	public static void watch(String id, String name, double target) {
		Progress p = Progress.get();
		p.watches.removeIf(w -> w.id.equals(id));
		Progress.Watch watch = new Progress.Watch();
		watch.id = id;
		watch.name = name;
		watch.target = target;
		p.watches.add(watch);
		Progress.changed();
		Progress.save(true);
	}

	private static void checkAlarms() {
		long now = System.currentTimeMillis();
		if (now - lastAlarmCheck < 30_000) return;
		lastAlarmCheck = now;

		for (Progress.Watch watch : Progress.get().watches) {
			Double price = price(watch.id);
			if (price == null) continue;
			watch.lastPrice = price;

			if (!watch.triggered && price <= watch.target) {
				watch.triggered = true;
				Toasts.show(Toasts.Kind.ALARM, "bell", Planner.tr("toast.alarm"),
						Planner.tr("toast.alarm_text", watch.name, GearScreenFormat.coins(price)));
			} else if (watch.triggered && price > watch.target * 1.05) {
				watch.triggered = false;
			}

			Progress.changed();
		}
	}
}
