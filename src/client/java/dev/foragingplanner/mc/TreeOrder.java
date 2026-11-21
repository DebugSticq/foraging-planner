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
	public record Plan(String tree, List<String> steps, int step, BlockPos start, int cuts, int left) {}

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
		BlockPos focus = LogScan.aimed(client, near, 7);
		if (focus == null) {
			Vec3 me = client.player.position();
			double best = 8 * 8;
			for (BlockPos pos : near.keySet()) {
				double d = Vec3.atCenterOf(pos).distanceToSqr(me);
				if (d < best) { best = d; focus = pos; }
			}
		}
		if (focus == null) {
			clear();
			return;
		}
		Map<BlockPos, BlockState> logs = LogScan.around(client, focus, 8, 14, 22);
		Map<BlockPos, BlockState> tree = LogScan.tree(logs, focus, 900);
		String kind = LogScan.treeOf(logs.get(focus));
		GameData.Tree data = Planner.data.tree(kind);
		if (data == null || tree.size() < 3) {
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
		plan = new Plan(kind, steps, step, start, Math.max(0, bestCut), counts[step]);
	}

	private static void clear() {
		plan = null;
		WorldBoxes.setOrder(List.of());
	}

	/** Label over the start log: "① Start · Trunk → Branches". Called from the world overlay. */
	static void draw(Minecraft client, net.minecraft.client.gui.GuiGraphicsExtractor g) {
		Plan p = plan;
		if (p == null) return;
		float[] at = WorldOverlay.project(client, Vec3.atCenterOf(p.start()).add(0, 0.9, 0));
		if (at == null) return;
		var font = client.font;
		StringBuilder order = new StringBuilder();
		for (int i = 0; i < p.steps().size(); i++) {
			if (i > 0) order.append(" §7→ ");
			String name = Planner.tr("tree_order.step_" + p.steps().get(i));
			order.append(i < p.step() ? "§8§m" + name + "§r" : i == p.step() ? "§f§l" + name + "§r" : "§7" + name);
		}
		String first = Planner.tr("tree_order.start", p.cuts(), p.left());
		String second = order.toString();
		int w = Math.max(font.width(first), font.width(second)) + 10;
		int x = Math.round(at[0]) - w / 2, y = Math.round(at[1]) - 26;
		ForestTheme.round(g, x, y, w, 24, 5, ForestTheme.alpha(ForestTheme.UI_PANEL, 0.8));
		g.text(font, first, x + 5, y + 4, STEP_COLORS[Math.min(p.step(), STEP_COLORS.length - 1)], false);
		g.text(font, second, x + 5, y + 14, 0xFFDDDDDD, false);
	}
}
