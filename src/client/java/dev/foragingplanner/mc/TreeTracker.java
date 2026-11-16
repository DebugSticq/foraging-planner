package dev.foragingplanner.mc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Display;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import dev.foragingplanner.core.GameData;

/**
 * Remembers where the trees of an island stand and whether they can be cut.
 *
 * <p>Galatea trees are made of block display entities. A group of wood displays close together is
 * one tree. When most of a tree's displays are gone it was cut; when they come back it has regrown,
 * and the time in between is learned per tree. Nothing here sends any input to the game.
 */
public final class TreeTracker {
	private static final double SCAN_RADIUS = 64;
	/** Trees farther away than this may simply not be loaded, so their state is not changed. */
	private static final double JUDGE_RADIUS = 40;
	private static final double MERGE_DISTANCE = 2.6;
	private static final double SPOT_MATCH = 7;
	private static final int MIN_BLOCKS = 8;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public enum State { READY, CUTTING, GONE }

	public static final class Spot {
		public double x, y, z;
		/** Center of the tree, used for markers. */
		public double cx, cy, cz;
		public String tree = "";
		public int maxBlocks;
		public State state = State.READY;
		public long cutAt;
		public List<Long> regrowMs = new ArrayList<>();
		public transient int blocks;

		/** Learned regrow time, or -1 while unknown. */
		public long regrow() {
			if (regrowMs == null || regrowMs.isEmpty()) return -1;
			List<Long> sorted = new ArrayList<>(regrowMs);
			sorted.sort(null);
			return sorted.get(sorted.size() / 2);
		}

		public Vec3 center() {
			return new Vec3(cx, cy, cz);
		}
	}

	private static final Map<String, List<Spot>> SPOTS = new LinkedHashMap<>();
	private static boolean loaded;
	private static long lastSave;
	private static Spot looked;
	/** Tree type of the last tree the player looked at, used for contest log estimates. */
	public static String lastTree = "";

	private TreeTracker() {}

	private static String treeOf(BlockState state) {
		if (state.is(Blocks.STRIPPED_BIRCH_WOOD) || state.is(Blocks.STRIPPED_MANGROVE_WOOD)) return "helix";
		if (state.is(Blocks.STRIPPED_SPRUCE_WOOD)) return "fig";
		if (state.is(Blocks.MANGROVE_WOOD) || state.is(Blocks.MANGROVE_ROOTS) || state.is(Blocks.MUDDY_MANGROVE_ROOTS)) return "mangrove";
		return null;
	}

	private static String key(Spot spot) {
		return SkyBlockReader.area() + "|" + Math.round(spot.cx) + "|" + Math.round(spot.cz);
	}

	/** True when a fine message was seen for this tree, or a nametag near it says it is protected. */
	public static boolean isProtected(Spot spot) {
		return Progress.get().protectedTrees.contains(key(spot));
	}

	/** Marks a tree as protected; returns true when it was new. */
	public static boolean markProtected(Spot spot) {
		boolean added = Progress.get().protectedTrees.add(key(spot));
		if (added) Progress.changed();
		return added;
	}

	/** "Big" compared with the other trees of the same kind; unknown sizes count as big. */
	public static boolean isBig(Spot spot) {
		return sizeClass(spot) >= 0;
	}

	/** 1 big, 0 normal, -1 small, compared with the median of the same tree kind. */
	public static int sizeClass(Spot spot) {
		if (spot.maxBlocks <= 0) return 0;
		List<Integer> sizes = new ArrayList<>();
		for (Spot other : spots()) if (other.tree.equals(spot.tree) && other.maxBlocks > 0) sizes.add(other.maxBlocks);
		if (sizes.size() < 4) return 0;
		sizes.sort(null);
		int median = sizes.get(sizes.size() / 2);
		if (spot.maxBlocks >= median * 1.3) return 1;
		if (spot.maxBlocks <= median * 0.75) return -1;
		return 0;
	}

	/** Trees worth walking to: ready, not protected and, if wanted, big. */
	public static boolean usable(Spot spot) {
		if (spot.state != State.READY || isProtected(spot)) return false;
		return !Planner.config.bigTreesOnly || sizeClass(spot) > 0 || sizeClass(spot) == 0 && spot.maxBlocks <= 0;
	}

	public static List<Spot> spots() {
		load();
		return SPOTS.computeIfAbsent(SkyBlockReader.area(), k -> new ArrayList<>());
	}

	/** The tree the player is looking at, or null. */
	public static Spot looked() {
		return looked;
	}

	public static GameData.Tree data(Spot spot) {
		if (spot == null) return null;
		for (GameData.Tree tree : Planner.data.trees) if (tree.id.equals(spot.tree)) return tree;
		return null;
	}

	private record Block(Vec3 pos, String tree) {}

	public static void tick(Minecraft client) {
		looked = null;
		if (client.level == null || client.player == null || !SkyBlockReader.inForagingArea()) return;
		if (!Planner.config.treeMarkers && !Planner.config.treeArrow && !Planner.config.treeRoute && !Planner.config.sweepPreview) return;

		List<Block> blocks = new ArrayList<>();
		for (var entity : client.level.getEntitiesOfClass(Display.BlockDisplay.class, client.player.getBoundingBox().inflate(SCAN_RADIUS))) {
			var render = entity.blockRenderState();
			if (render == null) continue;
			String tree = treeOf(render.blockState());
			if (tree != null) blocks.add(new Block(entity.position().add(0.5, 0.5, 0.5), tree));
		}

		List<List<Block>> clusters = cluster(blocks);
		List<Spot> spots = spots();
		long now = System.currentTimeMillis();
		Vec3 player = client.player.position();
		for (Spot spot : spots) spot.blocks = 0;

		for (List<Block> cluster : clusters) {
			double sx = 0, sy = 0, sz = 0, minY = Double.MAX_VALUE, bx = 0, bz = 0;
			Map<String, Integer> kinds = new HashMap<>();

			for (Block block : cluster) {
				sx += block.pos().x;
				sy += block.pos().y;
				sz += block.pos().z;
				minY = Math.min(minY, block.pos().y);
				kinds.merge(block.tree(), 1, Integer::sum);
			}

			int n = cluster.size();
			long base = 0;
			for (Block block : cluster) if (block.pos().y <= minY + 1.5) { bx += block.pos().x; bz += block.pos().z; base++; }
			double baseX = bx / base, baseZ = bz / base;

			Spot spot = nearest(spots, baseX, baseZ, sx / n, sz / n);

			if (spot == null) {
				if (n < MIN_BLOCKS) continue;
				spot = new Spot();
				spot.x = baseX;
				spot.y = minY;
				spot.z = baseZ;
				spots.add(spot);
			}

			spot.blocks += n;
			if (n >= spot.maxBlocks * 0.85 || spot.maxBlocks == 0) {
				spot.cx = sx / n;
				spot.cy = sy / n;
				spot.cz = sz / n;
			}
			spot.tree = kinds.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(spot.tree);
		}

		for (Spot spot : spots) {
			if (spot.cx == 0 && spot.cz == 0) { spot.cx = spot.x; spot.cy = spot.y + 3; spot.cz = spot.z; }
			if (Math.hypot(spot.x - player.x, spot.z - player.z) > JUDGE_RADIUS) continue;

			spot.maxBlocks = Math.max(spot.maxBlocks, spot.blocks);
			State before = spot.state;
			State after = spot.blocks >= spot.maxBlocks * 0.85 ? State.READY
					: spot.blocks <= spot.maxBlocks * 0.25 ? State.GONE : State.CUTTING;

			if (after == State.GONE && before != State.GONE) spot.cutAt = now;
			if (after == State.READY && before == State.GONE && spot.cutAt > 0) {
				long took = now - spot.cutAt;
				if (took > 5_000 && took < 60 * 60_000L) {
					spot.regrowMs.add(took);
					while (spot.regrowMs.size() > 6) spot.regrowMs.removeFirst();
				}
				spot.cutAt = 0;
			}
			spot.state = after;
		}

		looked = findLooked(client, spots);
		if (looked != null && !looked.tree.isEmpty()) lastTree = looked.tree;
		if (now - lastSave > 60_000) save();
	}

	private static Spot nearest(List<Spot> spots, double baseX, double baseZ, double cx, double cz) {
		Spot best = null;
		double bestDistance = SPOT_MATCH;

		for (Spot spot : spots) {
			double d = Math.min(Math.hypot(spot.x - baseX, spot.z - baseZ), Math.hypot(spot.cx - cx, spot.cz - cz));
			if (d < bestDistance) { bestDistance = d; best = spot; }
		}

		return best;
	}

	/** Groups displays that touch each other, using a coarse grid so that it stays fast. */
	private static List<List<Block>> cluster(List<Block> blocks) {
		int n = blocks.size();
		int[] parent = new int[n];
		for (int i = 0; i < n; i++) parent[i] = i;
		Map<Long, List<Integer>> grid = new HashMap<>();

		for (int i = 0; i < n; i++) {
			Vec3 p = blocks.get(i).pos();
			grid.computeIfAbsent(key((int) Math.floor(p.x / 3), (int) Math.floor(p.y / 3), (int) Math.floor(p.z / 3)), k -> new ArrayList<>()).add(i);
		}

		for (int i = 0; i < n; i++) {
			Vec3 p = blocks.get(i).pos();
			int gx = (int) Math.floor(p.x / 3), gy = (int) Math.floor(p.y / 3), gz = (int) Math.floor(p.z / 3);

			for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
				List<Integer> cell = grid.get(key(gx + dx, gy + dy, gz + dz));
				if (cell == null) continue;
				for (int j : cell) {
					if (j > i && p.distanceToSqr(blocks.get(j).pos()) <= MERGE_DISTANCE * MERGE_DISTANCE) union(parent, i, j);
				}
			}
		}

		Map<Integer, List<Block>> groups = new HashMap<>();
		for (int i = 0; i < n; i++) groups.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(blocks.get(i));
		return new ArrayList<>(groups.values());
	}

	private static long key(int x, int y, int z) {
		return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
	}

	private static int find(int[] parent, int i) {
		while (parent[i] != i) { parent[i] = parent[parent[i]]; i = parent[i]; }
		return i;
	}

	private static void union(int[] parent, int a, int b) {
		parent[find(parent, a)] = find(parent, b);
	}

	private static Spot findLooked(Minecraft client, List<Spot> spots) {
		Vec3 eye = client.player.getEyePosition();
		Vec3 look = client.player.getLookAngle();
		Spot best = null;
		double bestScore = 0;

		for (Spot spot : spots) {
			if (spot.state == State.GONE) continue;
			Vec3 offset = spot.center().subtract(eye);
			double distance = offset.length();
			if (distance > 14 || distance < 0.1) continue;
			double dot = offset.normalize().dot(look);
			double score = dot - distance / 200;
			if (dot > 0.75 && score > bestScore) { bestScore = score; best = spot; }
		}

		return best;
	}

	/** Ready trees in the order of a short walk: always the nearest next one. */
	public static List<Spot> route(Vec3 from, int max) {
		List<Spot> left = new ArrayList<>();
		for (Spot spot : spots()) if (usable(spot) && spot.center().distanceTo(from) < 80) left.add(spot);
		List<Spot> path = new ArrayList<>();
		Vec3 at = from;

		while (!left.isEmpty() && path.size() < max) {
			Spot next = null;
			double best = Double.MAX_VALUE;
			for (Spot spot : left) {
				double d = spot.center().distanceToSqr(at);
				if (d < best) { best = d; next = spot; }
			}
			path.add(next);
			left.remove(next);
			at = next.center();
		}

		return path;
	}

	/** Forgets all trees of the current island. */
	public static void clearArea() {
		spots().clear();
		save();
	}

	private static Path file() {
		return ModConfig.profileDirectory().resolve("trees.json");
	}

	private static void load() {
		if (loaded) return;
		loaded = true;

		try {
			if (Files.exists(file())) {
				Map<String, List<Spot>> read = GSON.fromJson(Files.readString(file()), new TypeToken<LinkedHashMap<String, List<Spot>>>() {}.getType());
				if (read != null) {
					read.values().removeIf(java.util.Objects::isNull);
					for (List<Spot> list : read.values()) for (Spot s : list) {
						if (s.regrowMs == null) s.regrowMs = new ArrayList<>();
						if (s.state == null) s.state = State.READY;
					}
					SPOTS.putAll(read);
				}
			}
		} catch (Exception e) {
			Planner.LOGGER.warn("Could not read trees.json", e);
		}
	}

	public static void save() {
		lastSave = System.currentTimeMillis();
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), GSON.toJson(SPOTS));
		} catch (Exception e) {
			Planner.LOGGER.warn("Could not save trees.json", e);
		}
	}

	/** Profile switches call this so that the next access reads the new profile's file. */
	public static void reload() {
		SPOTS.clear();
		loaded = false;
	}
}
