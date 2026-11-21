package dev.foragingplanner.mc;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * Tree positions known in advance (bundled {@code tree_positions.json}), so that markers, the
 * route and the arrow know every tree of the island before the player has seen it.
 */
public final class KnownTrees {
	public static final class Entry {
		public int x, y, z, top;
		public String tree = "";
		public boolean big;
	}

	private static final class File {
		Map<String, List<Entry>> islands = new HashMap<>();
	}

	/** Island names Hypixel shows for the same place. */
	private static final Map<String, String> ALIASES = Map.of("Moonglade Marsh", "Galatea");

	private static Map<String, List<Entry>> islands;

	private KnownTrees() {}

	public static List<Entry> forArea(String area) {
		load();
		List<Entry> list = islands.get(ALIASES.getOrDefault(area, area));
		return list == null ? List.of() : list;
	}

	public static int count() {
		load();
		int n = 0;
		for (List<Entry> list : islands.values()) n += list.size();
		return n;
	}

	private static void load() {
		if (islands != null) return;
		islands = new HashMap<>();
		try (InputStream in = KnownTrees.class.getResourceAsStream("/foragingplanner/tree_positions.json")) {
			if (in == null) return;
			File file = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), File.class);
			if (file != null && file.islands != null) islands.putAll(file.islands);
		} catch (Exception e) {
			Planner.LOGGER.warn("Could not read tree_positions.json", e);
		}
	}

	/** Adds a spot for every known tree near the player that the tracker does not have yet. */
	static void seed(List<TreeTracker.Spot> spots, Vec3 player) {
		if (!Planner.config.knownTrees) return;
		for (Entry e : forArea(SkyBlockReader.area())) {
			double dx = e.x + 0.5 - player.x, dz = e.z + 0.5 - player.z;
			if (dx * dx + dz * dz > 96 * 96) continue;
			boolean have = false;
			for (TreeTracker.Spot s : spots) {
				if (Math.hypot(s.x - (e.x + 0.5), s.z - (e.z + 0.5)) < 5) { have = true; break; }
			}
			if (have) continue;
			TreeTracker.Spot spot = new TreeTracker.Spot();
			spot.x = e.x + 0.5;
			spot.y = e.y;
			spot.z = e.z + 0.5;
			spot.cx = spot.x;
			spot.cy = e.y + Math.max(3, (e.top - e.y) / 3.0);
			spot.cz = spot.z;
			spot.tree = e.tree;
			spots.add(spot);
		}
	}

	/**
	 * Logs standing at a spot, counted from real blocks. Trees that the server builds from block
	 * displays are counted by the tracker itself; this covers trees made of normal blocks.
	 */
	static int standing(Minecraft client, TreeTracker.Spot spot) {
		return LogScan.count(client, (int) Math.floor(spot.x), (int) Math.floor(spot.y), (int) Math.floor(spot.z), 4, 2, 22);
	}

	/** Known trees of the current island, for the check screen. */
	public static List<Entry> here() {
		return new ArrayList<>(forArea(SkyBlockReader.area()));
	}
}
