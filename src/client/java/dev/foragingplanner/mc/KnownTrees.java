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
		String key = ALIASES.getOrDefault(area, area);
		List<Entry> list = islands.get(key);
		List<Entry> mine = learned().get(key);
		if (mine == null || mine.isEmpty()) return list == null ? List.of() : list;
		List<Entry> all = new ArrayList<>(list == null ? List.of() : list);
		all.addAll(mine);
		return all;
	}

	/** Trees from the bundled file only, without the ones learned while playing. */
	private static List<Entry> bundled(String area) {
		load();
		List<Entry> list = islands.get(ALIASES.getOrDefault(area, area));
		return list == null ? List.of() : list;
	}

	// ---- Telling real trees from wood blocks in buildings ----

	private static Map<String, List<Entry>> learned;

	private static java.nio.file.Path learnedFile() {
		return ModConfig.profileDirectory().resolve("learned_trees.json");
	}

	private static Map<String, List<Entry>> learned() {
		if (learned != null) return learned;
		learned = new HashMap<>();
		try {
			if (java.nio.file.Files.exists(learnedFile())) {
				Map<String, List<Entry>> read = new Gson().fromJson(java.nio.file.Files.readString(learnedFile()),
						new com.google.gson.reflect.TypeToken<HashMap<String, List<Entry>>>() {}.getType());
				if (read != null) learned.putAll(read);
			}
		} catch (Exception e) {
			Planner.LOGGER.warn("Could not read learned_trees.json", e);
		}
		return learned;
	}

	/** Profile switches call this. */
	public static void reload() {
		learned = null;
	}

	/** Lowest log of a tree, as the tree's position. */
	private static net.minecraft.core.BlockPos base(Map<net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState> tree) {
		net.minecraft.core.BlockPos low = null;
		for (net.minecraft.core.BlockPos pos : tree.keySet()) if (low == null || pos.getY() < low.getY()) low = pos;
		return low;
	}

	private static boolean near(List<Entry> list, net.minecraft.core.BlockPos base, String kind) {
		for (Entry e : list) {
			if (!e.tree.isEmpty() && !e.tree.equals(kind)) continue;
			double dx = e.x - base.getX(), dz = e.z - base.getZ();
			if (dx * dx + dz * dz <= 6 * 6 && Math.abs(e.y - base.getY()) <= 14) return true;
		}
		return false;
	}

	/** Remembers a tree the player really cut (or a Helix tree seen with both colours). */
	static void learn(Map<net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState> tree, String kind) {
		net.minecraft.core.BlockPos base = base(tree);
		if (base == null || kind == null) return;
		String key = ALIASES.getOrDefault(SkyBlockReader.area(), SkyBlockReader.area());
		if (key.isEmpty() || near(forArea(key), base, kind)) return;
		Entry e = new Entry();
		e.x = base.getX();
		e.y = base.getY();
		e.z = base.getZ();
		e.top = base.getY() + 20;
		e.tree = kind;
		learned().computeIfAbsent(key, k -> new ArrayList<>()).add(e);
		try {
			java.nio.file.Files.createDirectories(learnedFile().getParent());
			java.nio.file.Files.writeString(learnedFile(), new Gson().toJson(learned()));
		} catch (Exception ex) {
			Planner.LOGGER.warn("Could not save learned_trees.json", ex);
		}
	}

	/**
	 * True when a group of logs is a tree that can be cut, not wood in a building:
	 * it stands at a known or learned tree position, or (Helix) it has both colours.
	 */
	public static boolean isTree(Map<net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState> tree, String kind) {
		if (tree.isEmpty() || kind == null) return false;
		net.minecraft.core.BlockPos base = base(tree);
		if (near(forArea(SkyBlockReader.area()), base, kind)) return true;
		if ("helix".equals(kind)) {
			boolean beige = false, red = false;
			for (var state : tree.values()) {
				String g = LogScan.group(state);
				if ("beige".equals(g)) beige = true;
				else if ("red".equals(g)) red = true;
			}
			if (beige && red && tree.size() >= 8) {
				learn(tree, kind);
				return true;
			}
			return false;
		}
		// Fig and Mangrove only grow on Galatea, where every tree is in the bundled list.
		return bundled(SkyBlockReader.area()).isEmpty() && tree.size() >= 12;
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
