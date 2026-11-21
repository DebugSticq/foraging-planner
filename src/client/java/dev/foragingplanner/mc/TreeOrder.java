package dev.foragingplanner.mc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import dev.foragingplanner.core.GameData;
import dev.foragingplanner.core.TreeMath;

/**
 * Tree order overlay: which part of the nearest tree comes next (Fig: trunk, then branches;
 * Mangrove: branches, trunk, roots; Helix: beige, then red) and the log to start on, picked so
 * that the first hit cuts as many logs of that part as possible with the player's Sweep.
 */
public final class TreeOrder {
	/** Colours of step 1, 2 and 3. */
	private static final int[] STEP_COLORS = {0xFF5CE07A, 0xFFFFC94D, 0xFFE0705C};

	/** What is shown, read by the HUD label. */
	public record Plan(String tree, List<String> steps, int step, BlockPos start, int cuts, int left, int top) {}

	private static volatile Plan plan;

	private TreeOrder() {}

	public static Plan plan() { return plan; }

	public static List<String> steps(String tree) {
		return switch (tree) {
			case "fig" -> List.of("trunk", "branches");
			case "mangrove" -> List.of("branches", "trunk", "roots");
			default -> List.of("beige", "red");
		};
	}

	/** Index of the step a log belongs to. */
	static int stepOf(String tree, BlockState state) {
		String group = LogScan.group(state);
		boolean vertical = LogScan.vertical(state);
		return switch (tree) {
			case "fig" -> vertical ? 0 : 1;
			case "mangrove" -> "roots".equals(group) ? 2 : vertical ? 1 : 0;
			default -> "red".equals(group) ? 1 : 0;
		};
	}

	/** Called every few ticks. */
	public static void tick(Minecraft client) {
		if (!Planner.config.treeOrderOverlay || client.player == null || client.level == null || !SkyBlockReader.inForagingArea()) {
			clear();
			return;
		}
		Map<BlockPos, BlockState> near = LogScan.around(client, client.player.blockPosition(), 9, 5, 10);
		if (near.isEmpty()) {
			clear();
			return;
		}
		// Candidates: the aimed log first, then logs by distance. Wood that is not a tree is skipped.
		List<BlockPos> order = new ArrayList<>(near.keySet());
		Vec3 me = client.player.position();
		order.sort(java.util.Comparator.comparingDouble(p -> Vec3.atCenterOf(p).distanceToSqr(me)));
		BlockPos aimed = LogScan.aimed(client, near, 7);
		if (aimed != null) order.add(0, aimed);
		java.util.Set<BlockPos> tried = new java.util.HashSet<>();
		Map<BlockPos, BlockState> tree = null;
		String kind = null;
		int groups = 0;
		for (BlockPos candidate : order) {
			if (tried.contains(candidate)) continue;
			if (Vec3.atCenterOf(candidate).distanceToSqr(me) > 10 * 10 && candidate != aimed) break;
			if (++groups > 5) break;
			Map<BlockPos, BlockState> logs = LogScan.around(client, candidate, 8, 14, 22);
			Map<BlockPos, BlockState> group = LogScan.tree(logs, candidate, 900);
			tried.addAll(group.keySet());
			String k = LogScan.treeOf(logs.get(candidate));
			if (group.size() >= 3 && KnownTrees.isTree(group, k)) {
				tree = group;
				kind = k;
				break;
			}
		}
		GameData.Tree data = kind == null ? null : Planner.data.tree(kind);
		if (tree == null || data == null) {
			clear();
			return;
		}
		List<String> steps = steps(kind);
		Map<BlockPos, Integer> stepOf = new HashMap<>();
		int[] counts = new int[steps.size()];
		for (var entry : tree.entrySet()) {
			int s = stepOf(kind, entry.getValue());
			stepOf.put(entry.getKey(), s);
			counts[s]++;
		}
		int step = 0;
		while (step < counts.length - 1 && counts[step] == 0) step++;

		double sweep = SkyBlockReader.statsKnown() ? SkyBlockReader.stats().sweep() : 0;
		int perHit = Math.max(1, TreeMath.logsPerHit(data, sweep));

		// Try each log of the current step as a start and keep the one whose hit cuts the most of that step.
		List<BlockPos> candidates = new ArrayList<>();
		for (var entry : stepOf.entrySet()) if (entry.getValue() == step) candidates.add(entry.getKey());
		boolean low = "trunk".equals(steps.get(step)) || "roots".equals(steps.get(step)) || "beige".equals(steps.get(step));
		BlockPos start = null;
		int bestCut = -1;
		List<BlockPos> bestArea = List.of();
		int tries = 0;
		candidates.sort((a, b) -> low ? Integer.compare(a.getY(), b.getY()) : Integer.compare(b.getY(), a.getY()));
		for (BlockPos candidate : candidates) {
			if (++tries > 260) break;
			List<BlockPos> area = LogScan.sweep(tree, candidate, perHit);
			int cut = 0;
			for (BlockPos pos : area) if (stepOf.get(pos) == step) cut++;
			if (cut > bestCut) { bestCut = cut; start = candidate; bestArea = area; }
		}
		if (start == null) {
			clear();
			return;
		}

		long now = System.currentTimeMillis();
		double pulse = Planner.config.animations ? 0.65 + 0.35 * Math.sin(now / 160.0) : 1;
		List<WorldBoxes.Box> boxes = new ArrayList<>();
		java.util.Set<BlockPos> area = new java.util.HashSet<>(bestArea);
		for (var entry : stepOf.entrySet()) {
			int s = entry.getValue();
			BlockPos pos = entry.getKey();
			if (pos.equals(start)) continue;
			int color = STEP_COLORS[Math.min(s, STEP_COLORS.length - 1)];
			if (s == step) {
				boolean inFirstHit = area.contains(pos);
				boxes.add(new WorldBoxes.Box(pos, ForestTheme.alpha(color, inFirstHit ? 0.22 : 0.08), ForestTheme.alpha(color, inFirstHit ? 0.75 : 0.45), 1.5f, 0.002f));
			} else if (s > step) {
				boxes.add(new WorldBoxes.Box(pos, 0, ForestTheme.alpha(color, 0.18), 1f, 0.002f));
			}
		}
		int startColor = STEP_COLORS[Math.min(step, STEP_COLORS.length - 1)];
		boxes.add(new WorldBoxes.Box(start, ForestTheme.alpha(startColor, 0.45 * pulse), ForestTheme.alpha(0xFFFFFFFF, 0.6 + 0.4 * pulse), 4f, 0.02f));
		WorldBoxes.setOrder(boxes);
		int top = start.getY();
		for (BlockPos pos : tree.keySet()) if (Math.abs(pos.getX() - start.getX()) <= 3 && Math.abs(pos.getZ() - start.getZ()) <= 3) top = Math.max(top, pos.getY());
		plan = new Plan(kind, steps, step, start, Math.max(0, bestCut), counts[step], top);
	}

	private static void clear() {
		plan = null;
		WorldBoxes.setOrder(List.of());
	}

	/** Label over the start log: "① Start · Trunk → Branches". Called from the world overlay. */
	static void draw(Minecraft client, net.minecraft.client.gui.GuiGraphicsExtractor g) {
		Plan p = plan;
		if (p == null) return;
		StringBuilder order = new StringBuilder();
		for (int i = 0; i < p.steps().size(); i++) {
			if (i > 0) order.append(" §7→ ");
			String name = Planner.tr("tree_order.step_" + p.steps().get(i));
			order.append(i < p.step() ? "§8§m" + name + "§r" : i == p.step() ? "§f§l" + name + "§r" : "§7" + name);
		}
		String text = Planner.tr("tree_order.start", p.cuts(), p.left()) + "\n" + order;
		// Above the highest log of the tree, readable through the logs in front of it.
		Vec3 at = new Vec3(p.start().getX() + 0.5, Math.max(p.top(), p.start().getY()) + 1.8, p.start().getZ() + 0.5);
		WorldText.add(at, text, STEP_COLORS[Math.min(p.step(), STEP_COLORS.length - 1)],
				ForestTheme.alpha(ForestTheme.UI_PANEL, 0.8), 1f, true);
		// A line from the label down to the start log, so it is clear which log is meant.
		WorldText.line(at.add(0, -0.35, 0), Vec3.atCenterOf(p.start()).add(0, 0.5, 0), ForestTheme.alpha(0xFFFFFFFF, 0.6), 1.5f);
	}
}
