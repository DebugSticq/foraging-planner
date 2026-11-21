package dev.foragingplanner.mc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import dev.foragingplanner.core.GameData;
import dev.foragingplanner.core.TreeMath;

/**
 * Shows in the world which logs the next axe hit cuts: green boxes for the logs that break, an
 * orange frame around the log that one more breakpoint of Sweep would add.
 */
public final class SweepOverlay {
	private static int lastLogs = -1;
	private static int lastFound = -1;
	private static String lastTree = "";

	private SweepOverlay() {}

	/** Logs the hit cuts, or -1 when the overlay is not showing. */
	public static int logs() { return lastLogs; }
	/** Logs actually left in reach of the hit (can be less than {@link #logs()} near the end of a tree). */
	public static int found() { return lastFound; }
	public static String tree() { return lastTree; }

	// A log the player hit that then disappears belongs to a real tree: remember that tree.
	private static BlockPos hitPos;
	private static Map<BlockPos, BlockState> hitTree;
	private static String hitKind;
	private static long hitAt;

	private static void watchHit(Minecraft client, BlockPos target, Map<BlockPos, BlockState> whole, String kind) {
		if (client.options.keyAttack.isDown() && whole.size() >= 4) {
			hitPos = target;
			hitTree = whole;
			hitKind = kind;
			hitAt = System.currentTimeMillis();
		}
	}

	private static void checkHit(Minecraft client) {
		if (hitPos == null || client.level == null) return;
		long age = System.currentTimeMillis() - hitAt;
		if (age > 2500) {
			hitPos = null;
			return;
		}
		if (LogScan.treeOf(client.level.getBlockState(hitPos)) == null && age > 50) {
			KnownTrees.learn(hitTree, hitKind);
			hitPos = null;
		}
	}

	/** The part of the tree that should be cut first when the aimed log is in the wrong part, else null. */
	private static String wrongStep;
	private static int fullLogs;
	private static BlockPos targetPos;

	/** Warning over the aimed log when it is in the wrong part of the tree. Called from the world overlay. */
	static void draw() {
		if (lastLogs < 0 || wrongStep == null || targetPos == null) return;
		String text = "⚠ " + Planner.tr("sweep.wrong_step", Planner.tr("tree_order.step_" + wrongStep)) + "\n§7"
				+ Planner.tr("sweep.wrong_step_logs", lastLogs, fullLogs);
		WorldText.warning(net.minecraft.world.phys.Vec3.atCenterOf(targetPos).add(0, 1.4, 0), text, 0xFFFF6655);
	}

	public static void tick(Minecraft client) {
		checkHit(client);
		wrongStep = null;
		lastLogs = -1;
		lastFound = -1;
		if (!Planner.config.sweepOverlay || client.player == null || client.level == null
				|| !SkyBlockReader.inForagingArea() || !TreeWarnings.holdingAxe(client)) {
			WorldBoxes.setSweep(List.of());
			return;
		}
		Map<BlockPos, BlockState> logs = LogScan.around(client, client.player.blockPosition(), 6, 4, 8);
		BlockPos target = LogScan.aimed(client, logs, 5.5);
		if (target == null) {
			WorldBoxes.setSweep(List.of());
			return;
		}
		// Look again around the aimed log so tall trees are covered.
		logs = LogScan.around(client, target, 7, 10, 18);
		String kind = LogScan.treeOf(logs.get(target));
		GameData.Tree tree = Planner.data.tree(kind);
		Map<BlockPos, BlockState> whole = LogScan.tree(logs, target, 900);
		watchHit(client, target, whole, kind);
		if (tree == null || !KnownTrees.isTree(whole, kind)) {
			WorldBoxes.setSweep(List.of());
			return;
		}
		double sweep = SkyBlockReader.statsKnown() ? SkyBlockReader.stats().sweep() : 0;
		int full = Math.max(1, TreeMath.logsPerHit(tree, sweep));
		// Style: hitting a later part of the tree while an earlier one is left halves the logs.
		int current = 9, aimed = TreeOrder.stepOf(kind, whole.get(target));
		for (BlockState state : whole.values()) current = Math.min(current, TreeOrder.stepOf(kind, state));
		boolean wrong = aimed != current;
		int perHit = wrong ? Math.max(1, full / 2) : full;
		wrongStep = wrong ? TreeOrder.steps(kind).get(Math.min(current, TreeOrder.steps(kind).size() - 1)) : null;
		fullLogs = full;
		targetPos = target;
		List<BlockPos> cut = LogScan.sweep(whole, target, perHit + 1);

		long now = System.currentTimeMillis();
		double pulse = Planner.config.animations ? 0.8 + 0.2 * Math.sin(now / 180.0) : 1;
		int color = wrong ? 0xFFFF5544 : 0xFF000000 | (Planner.config.sweepOverlayColor & 0xFFFFFF);
		int cutFill = ForestTheme.alpha(color, 0.28 * pulse);
		int cutLine = ForestTheme.alpha(color, 0.9);
		List<WorldBoxes.Box> boxes = new ArrayList<>();
		for (int i = 0; i < cut.size(); i++) {
			BlockPos pos = cut.get(i);
			if (i < perHit) {
				boolean first = i == 0;
				boxes.add(new WorldBoxes.Box(pos, cutFill, first ? 0xF0FFFFFF : cutLine, first ? 3.5f : 2f, 0.004f));
			} else if (!wrong && Planner.config.sweepOverlayNext && TreeMath.sweepToNextBreakpoint(tree, sweep) > 0) {
				boxes.add(new WorldBoxes.Box(pos, 0, 0xD0FFB020, 2f, 0.01f));
			}
		}
		WorldBoxes.setSweep(boxes);
		lastLogs = perHit;
		lastFound = Math.min(perHit, cut.size());
		lastTree = tree.id;
	}
}
