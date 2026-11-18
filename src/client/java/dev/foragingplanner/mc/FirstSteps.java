package dev.foragingplanner.mc;

import net.minecraft.client.Minecraft;

/** Short one-time tips the first time the player reaches Galatea, the Safari or a contest. */
public final class FirstSteps {
	private static long forestSinceMs;

	private FirstSteps() {}

	/** Called once a second. */
	public static void tick(Minecraft client) {
		if (!Planner.config.firstSteps || client.player == null || !SkyBlockReader.inSkyBlock()) return;
		if (SkyBlockReader.inForagingArea()) {
			once("galatea", "oak_sapling");
			if (forestSinceMs == 0) forestSinceMs = System.currentTimeMillis();
			if (System.currentTimeMillis() - forestSinceMs > 60_000 && !SkyBlockReader.statsKnown()) once("stats", "iron_axe");
			else if (System.currentTimeMillis() - forestSinceMs > 120_000 && Planner.config.perkLevels.isEmpty()) once("hotf", "oak_sapling");
		} else {
			forestSinceMs = 0;
		}
		if (Safari.inside()) once("safari", "spyglass");
		if (ContestTracker.active()) once("contest", "golden_hoe");
	}

	private static void once(String key, String icon) {
		var seen = Progress.get().hintsSeen;
		if (seen.contains(key)) return;
		seen.add(key);
		Progress.save(false);
		Toasts.hint(icon, Planner.tr("hint." + key + "_title"), Planner.tr("hint." + key));
	}

	/** Shows every tip again. */
	public static void reset() {
		Progress.get().hintsSeen.clear();
		Progress.save(true);
	}
}
