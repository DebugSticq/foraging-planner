package dev.foragingplanner.mc;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Notices when Hypixel texts or menus stop being recognised (after a Hypixel update, for example),
 * so the detection check can say so instead of the mod quietly showing wrong numbers.
 */
public final class Health {
	/** One thing the mod reads. */
	public static final class Entry {
		public long lastOk, lastFail;
		public int fails;
		public String detail = "";

		public boolean broken() {
			return lastFail > lastOk && fails >= 2;
		}
	}

	private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();
	private static long forestSince, choppingWithoutXp;

	private Health() {}

	public static synchronized void ok(String key) {
		Entry e = ENTRIES.computeIfAbsent(key, k -> new Entry());
		e.lastOk = System.currentTimeMillis();
		e.fails = 0;
	}

	public static synchronized void fail(String key, String detail) {
		Entry e = ENTRIES.computeIfAbsent(key, k -> new Entry());
		e.lastFail = System.currentTimeMillis();
		e.fails++;
		e.detail = detail == null ? "" : detail;
		if (e.fails == 2) Planner.LOGGER.warn("[FP] Not recognised any more: {} ({})", key, e.detail);
	}

	public static synchronized Map<String, Entry> entries() {
		return new LinkedHashMap<>(ENTRIES);
	}

	/** Called once a second: checks things that should be readable while foraging. */
	public static void tick() {
		long now = System.currentTimeMillis();
		if (!SkyBlockReader.inSkyBlock()) {
			forestSince = 0;
			return;
		}
		ok("sidebar");
		if (SkyBlockReader.inForagingArea()) {
			if (forestSince == 0) forestSince = now;
			if (SkyBlockReader.statsKnown()) ok("stats");
			else if (now - forestSince > 120_000 && (now / 1000) % 60 == 0) fail("stats", "no Sweep in the tab list");
			// Cutting trees (tree progress moves) but no XP from the action bar: its text changed or is hidden.
			if (TreeProgress.recent() && !SessionRates.recentActivity()) {
				if (choppingWithoutXp == 0) choppingWithoutXp = now;
				if (now - choppingWithoutXp > 60_000 && (now / 1000) % 30 == 0) fail("actionbar", "chopping without Foraging XP in the action bar");
			} else {
				choppingWithoutXp = 0;
				if (SessionRates.recentActivity()) ok("actionbar");
			}
		} else {
			forestSince = 0;
		}
	}
}
