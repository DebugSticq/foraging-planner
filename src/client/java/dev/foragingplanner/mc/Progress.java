package dev.foragingplanner.mc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Everything the newer features remember per profile: collections, daily goals, contest and drop
 * history, milestones, price alarms, Hunting Box contents. Saved as progress.json.
 */
public final class Progress {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static Progress current;
	private static long lastSave;
	private static boolean dirty;

	public Map<String, Collection> collections = new LinkedHashMap<>();
	public List<DailyGoal> dailyGoals = new ArrayList<>(List.of(
			new DailyGoal("contests", 3), new DailyGoal("xp", 500_000), new DailyGoal("essence", 300)));
	public Map<String, Double> today = new LinkedHashMap<>();
	public String todayKey = "";
	public Set<String> goalsDone = new LinkedHashSet<>();
	public List<ContestRecord> contests = new ArrayList<>();
	public List<Long> contestStarts = new ArrayList<>();
	public List<Drop> drops = new ArrayList<>();
	public double giftsAtLastRare = -1;
	public Set<String> milestones = new LinkedHashSet<>();
	public boolean milestonesPrimed;
	public List<Watch> watches = new ArrayList<>();
	public Map<String, Long> huntingBox = new LinkedHashMap<>();
	public Map<String, Integer> attributeLevels = new LinkedHashMap<>();
	public long huntingBoxRead;
	public double budget = 10_000_000;
	/** Bumped when stored values had to be thrown away after a fix. */
	public int dataVersion;
	public int bestCombo;
	public int miriaPrizes;
	public int bestTpm;
	/** Tree gifts per tree type (FIG, MANGROVE, HELIX …). */
	public Map<String, Integer> giftsByTree = new LinkedHashMap<>();
	/** Protected trees, as "area|x|z" of their spot. */
	public java.util.Set<String> protectedTrees = new java.util.LinkedHashSet<>();

	// Critter Safari
	/** Indices of the built-in bells (and keys "x,y,z" of found ones) already rung. */
	public java.util.Set<String> safariBellsRung = new java.util.LinkedHashSet<>();
	/** Bells found in the world that are not in the built-in list, as "x,y,z". */
	public java.util.Set<String> safariBellsFound = new java.util.LinkedHashSet<>();
	public java.util.Set<String> critterCaught = new java.util.LinkedHashSet<>();
	public java.util.Set<String> critterMissing = new java.util.LinkedHashSet<>();
	/** Biome name to "have/need" from the milestone menu. */
	public Map<String, String> safariMilestones = new LinkedHashMap<>();
	public int safariTickets = -1;
	public int sparklingSeen;
	public List<SafariRun> safariRuns = new ArrayList<>();

	public static final class SafariRun {
		public long startMs, durationMs;
		public int catches, fails, capsulesUsed;
		public long essence;
	}
	/** The running contest, so a restart in the middle does not lose the logs cut before it. */
	public ContestState contestState;

	public static final class ContestState {
		public String host = "";
		public long startMs;
		public double logsAtStart;
		public int pointsAtStart;
		/** Logs gained per tree id so far. */
		public Map<String, Long> gained = new LinkedHashMap<>();
		public List<int[]> history = new ArrayList<>();
	}
	/** Chapter tasks by menu title, then task name. */
	public Map<String, Map<String, Task>> chapters = new LinkedHashMap<>();

	public static final class Task {
		public boolean done;
		public String hint = "";
	}
	public String hotfNextTier = "";
	public double hotfCurrent = -1, hotfNeeded = -1;
	public long hotfRead;
	/** Contest points per sidebar number, learned at contest end; 0 while unknown. */
	public double contestFactor;
	public List<Double> contestFactors = new ArrayList<>();
	/** Measured contest points per log, by tree id, smoothed over contests. */
	public Map<String, Double> contestPerLog = new LinkedHashMap<>();
	/** Learned logs per whole tree, by tree type, last readings. */
	public Map<String, List<Double>> treeSizes = new LinkedHashMap<>();
	/** Tree sizes from the gift message (own logs / own share), the exact ones. */
	public Map<String, List<Double>> treeSizesExact = new LinkedHashMap<>();

	public static final class Collection {
		public String name = "";
		public String nextTier = "";
		public double current, needed, total;
		public boolean maxed;
		public long updated;
	}

	public static final class DailyGoal {
		public String metric = "xp";
		public double amount = 100_000;
		public DailyGoal() {}
		public DailyGoal(String metric, double amount) { this.metric = metric; this.amount = amount; }
	}

	public static final class ContestRecord {
		public String host = "";
		public String bracket = "";
		public int points;
		public long time;
		/** Logs collected during the contest; from Hypixel's personal best message when there was one, else counted. */
		public long logs;
		/** Main tree id of the contest. */
		public String tree = "";
		public boolean logsConfirmed;
		public boolean personalBest;
		public long previousBest = -1;
		/** Points over time, every 10 seconds: [seconds since start, points]. */
		public List<int[]> samples = new ArrayList<>();
	}

	public static final class Drop {
		public String id = "";
		public String name = "";
		public long count;
		public double value;
		public long time;
		/** Tree gifts between the previous rare drop and this one, or -1 when unknown. */
		public long giftsSince = -1;
	}

	public static final class Watch {
		public String id = "";
		public String name = "";
		public double target;
		public boolean triggered;
		public double lastPrice;
	}

	public static Progress get() {
		if (current == null) load();
		return current;
	}

	private static Path file() {
		return ModConfig.profileDirectory().resolve("progress.json");
	}

	public static void load() {
		current = new Progress();

		try {
			if (Files.exists(file())) {
				Progress read = GSON.fromJson(Files.readString(file()), Progress.class);
				if (read != null) current = read;
			}
		} catch (Exception e) {
			Planner.LOGGER.warn("Could not read progress.json", e);
		}

		Progress p = current;
		if (p.collections == null) p.collections = new LinkedHashMap<>();
		if (p.dailyGoals == null) p.dailyGoals = new ArrayList<>();
		if (p.today == null) p.today = new LinkedHashMap<>();
		if (p.todayKey == null) p.todayKey = "";
		if (p.goalsDone == null) p.goalsDone = new LinkedHashSet<>();
		if (p.contests == null) p.contests = new ArrayList<>();
		if (p.contestStarts == null) p.contestStarts = new ArrayList<>();
		if (p.drops == null) p.drops = new ArrayList<>();
		if (p.milestones == null) p.milestones = new LinkedHashSet<>();
		if (p.watches == null) p.watches = new ArrayList<>();
		if (p.huntingBox == null) p.huntingBox = new LinkedHashMap<>();
		if (p.treeSizes == null) p.treeSizes = new LinkedHashMap<>();
		if (p.chapters == null) p.chapters = new LinkedHashMap<>();
		if (p.giftsByTree == null) p.giftsByTree = new LinkedHashMap<>();
		if (p.protectedTrees == null) p.protectedTrees = new java.util.LinkedHashSet<>();
		if (p.treeSizesExact == null) p.treeSizesExact = new LinkedHashMap<>();
		if (p.dataVersion < 2) {
			// Before version 2 the contest logs were reset every second, so these learned values are wrong.
			if (p.contestPerLog != null) p.contestPerLog.clear();
			p.contestFactor = 0;
			if (p.contestFactors != null) p.contestFactors.clear();
			p.dataVersion = 2;
			changed();
		}
		if (p.contestPerLog == null) p.contestPerLog = new LinkedHashMap<>();
		if (p.contestFactors == null) p.contestFactors = new ArrayList<>();
		if (p.attributeLevels == null) p.attributeLevels = new LinkedHashMap<>();
	}

	public static void changed() {
		dirty = true;
	}

	/** Saves at most every 20 seconds unless forced. */
	public static void save(boolean force) {
		long now = System.currentTimeMillis();
		if (current == null || (!force && (!dirty || now - lastSave < 20_000))) return;

		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), GSON.toJson(current));
			lastSave = now;
			dirty = false;
		} catch (Exception e) {
			Planner.LOGGER.warn("Could not save progress.json", e);
		}
	}

	/** Counts something toward today's goals. Called for every amount the journal records. */
	public static void count(String metric, double amount) {
		Progress p = get();
		rollDay();
		p.today.merge(metric, amount, Double::sum);
		changed();
	}

	public static void rollDay() {
		Progress p = get();
		String key = java.time.LocalDate.now().toString();
		if (!key.equals(p.todayKey)) {
			p.todayKey = key;
			p.today.clear();
			p.goalsDone.clear();
			changed();
		}
	}

	public static double today(String metric) {
		rollDay();
		return get().today.getOrDefault(metric, 0.0);
	}
}
