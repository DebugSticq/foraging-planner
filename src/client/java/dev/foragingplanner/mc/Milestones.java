package dev.foragingplanner.mc;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonObject;

import net.minecraft.client.Minecraft;

/**
 * Tree gift milestones per tree type: counted from the gift messages, synced once per start with
 * the player's own numbers from the Hypixel API (through the mod's server).
 */
public final class Milestones {
	/** Gifts needed for milestone I to VII (since the Torrhus Canyon update). */
	public static final int[] TIERS = {10, 25, 100, 250, 500, 1000, 2500};
	private static final String[] ROMAN = {"I", "II", "III", "IV", "V", "VI", "VII"};
	private static boolean synced;

	private Milestones() {}

	/** A tree gift of this type (e.g. "Fig"). */
	public static void gift(String type) {
		String key = type.trim().toUpperCase(Locale.ROOT);
		Map<String, Integer> gifts = Progress.get().giftsByTree;
		int before = gifts.getOrDefault(key, 0);
		int after = before + 1;
		gifts.put(key, after);
		Progress.changed();
		for (int i = 0; i < TIERS.length; i++) {
			if (before < TIERS[i] && after >= TIERS[i] && Planner.config.milestones) {
				Toasts.show(Toasts.Kind.MILESTONE, "oak_sapling", Planner.tr("milestone.reached", pretty(key), ROMAN[i]), Planner.tr("milestone.claim"));
			}
		}
	}

	/** Called once a second: syncs once with the API after joining SkyBlock. */
	public static void tick(Minecraft client) {
		if (synced || !Planner.config.milestones || client.player == null || !SkyBlockReader.inSkyBlock() || !PvClient.configured()) return;
		synced = true;
		PvClient.lookup(client.getUser().getName()).thenAccept(Milestones::apply).exceptionally(e -> null);
	}

	private static void apply(JsonObject data) {
		try {
			JsonObject gifts = data.getAsJsonObject("extra").getAsJsonObject("foraging").getAsJsonObject("tree_gifts");
			Map<String, Integer> local = Progress.get().giftsByTree;
			for (var e : gifts.entrySet()) {
				if (!e.getValue().isJsonPrimitive()) continue;
				int api = e.getValue().getAsInt();
				local.merge(e.getKey().toUpperCase(Locale.ROOT), api, Math::max);
			}
			Progress.changed();
		} catch (RuntimeException ignored) {
			// No API data: keep counting locally.
		}
	}

	/** HUD lines: progress to the next milestone for each tree with gifts. */
	public static List<String> lines() {
		List<String> out = new ArrayList<>();
		if (!Planner.config.milestones) return out;
		for (var e : Progress.get().giftsByTree.entrySet()) {
			int count = e.getValue();
			int tier = 0;
			while (tier < TIERS.length && count >= TIERS[tier]) tier++;
			if (tier >= TIERS.length) {
				out.add("§6" + pretty(e.getKey()) + " §7" + Planner.tr("milestone.max", Planner.whole(count)));
				continue;
			}
			out.add("§6" + pretty(e.getKey()) + " §7Gifts §f" + Planner.whole(count) + "§7/" + Planner.whole(TIERS[tier]) + " §8→ §e" + ROMAN[tier]);
		}
		return out;
	}

	private static String pretty(String key) {
		return key.isEmpty() ? key : key.charAt(0) + key.substring(1).toLowerCase(Locale.ROOT);
	}
}
