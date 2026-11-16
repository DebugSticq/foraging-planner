package dev.foragingplanner.mc;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import dev.foragingplanner.core.GameData;
import dev.foragingplanner.core.Goal;
import dev.foragingplanner.core.HotfPlanner;
import dev.foragingplanner.core.Objective;
import dev.foragingplanner.core.PlayerStats;
import dev.foragingplanner.core.TreeReport;

/** Shared state of the mod, plus the few helpers every feature needs. */
public final class Planner {
	public static final String MOD_ID = "foragingplanner";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/** A logs-per-hit value the game itself reported is trusted for this long. */
	private static final long MEASUREMENT_LIFETIME_MS = 10 * 60 * 1000L;

	public static GameData data = new GameData();
	public static ModConfig config = new ModConfig();

	/** Bazaar sell price per log, keyed by bazaar id. Filled from a background thread. */
	public static final Map<String, Double> PRICES = new ConcurrentHashMap<>();

	/** Logs per hit the game reported in chat, keyed by tree id. */
	private static final Map<String, Measurement> MEASURED = new ConcurrentHashMap<>();

	private record Measurement(double logs, double sweep, long timeMs) {}

	private Planner() {}

	public static Goal goal() {
		Goal goal = Goal.parse(config.goal);
		return goal == null ? Goal.XP : goal;
	}

	public static Objective objective() {
		String tree = "auto".equalsIgnoreCase(config.tree) || data.tree(config.tree) == null ? null : config.tree;
		return new Objective(data, goal(), PRICES, tree);
	}

	public static void recordMeasurement(String treeId, double logs, double sweep) {
		MEASURED.put(treeId, new Measurement(logs, sweep, System.currentTimeMillis()));
	}

	/** Measurements that are still fresh, keyed by tree id. */
	public static Map<String, Double> measuredLogs() {
		Map<String, Double> fresh = new HashMap<>();
		long now = System.currentTimeMillis();

		for (Map.Entry<String, Measurement> entry : MEASURED.entrySet()) {
			if (now - entry.getValue().timeMs() <= MEASUREMENT_LIFETIME_MS
                    && Math.abs(entry.getValue().sweep() - SkyBlockReader.stats().sweep()) < 0.01) {
				fresh.put(entry.getKey(), entry.getValue().logs());
			}
		}

		return fresh;
	}

	public static void clearMeasurements() { MEASURED.clear(); }
    public static long budget(GameData.Perk perk) { return "desert".equals(perk.currency) ? config.desertWhispers : config.whispers; }
    public static HotfPlanner.Plan plan() {
        return HotfPlanner.plan(data, config.perkLevels, SkyBlockReader.stats(), objective(),
                config.whispers, config.desertWhispers, config.tokens, config.hotfTier);
    }
    public static List<TreeReport.Row> treeRows() {
		return TreeReport.build(data, SkyBlockReader.stats(), objective(), measuredLogs());
	}

	public static List<HotfPlanner.Upgrade> upgrades() {
		return HotfPlanner.rankUpgrades(data, config.perkLevels, SkyBlockReader.stats(), objective());
	}

	// Text helpers

	/** Translated text for the player's game language. Keys live in assets/foragingplanner/lang/. */
	public static String tr(String key, Object... args) {
		return Component.translatable(MOD_ID + "." + key, args).getString();
	}

	public static Component message(String key, Object... args) {
		return Component.literal("§a[FP] §r" + tr(key, args));
	}

	public static String strip(String text) {
		String stripped = ChatFormatting.stripFormatting(text);
		return stripped == null ? "" : stripped;
	}

	public static String whole(double value) {
		return String.format(Locale.US, "%,d", Math.round(value));
	}

	public static String decimal(double value) {
		return value == Math.rint(value) ? whole(value) : String.format(Locale.US, "%,.1f", value);
	}
}
