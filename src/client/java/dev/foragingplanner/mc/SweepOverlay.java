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

	public static void tick(Minecraft client) {
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
		GameData.Tree tree = Planner.data.tree(LogScan.treeOf(logs.get(target)));
		if (tree == null) {
			WorldBoxes.setSweep(List.of());
			return;
		}
		double sweep = SkyBlockReader.statsKnown() ? SkyBlockReader.stats().sweep() : 0;
		int perHit = Math.max(1, TreeMath.logsPerHit(tree, sweep));
		List<BlockPos> cut = LogScan.sweep(logs, target, perHit + 1);

		long now = System.currentTimeMillis();
		double pulse = Planner.config.animations ? 0.8 + 0.2 * Math.sin(now / 180.0) : 1;
		int color = 0xFF000000 | (Planner.config.sweepOverlayColor & 0xFFFFFF);
		int cutFill = ForestTheme.alpha(color, 0.28 * pulse);
		int cutLine = ForestTheme.alpha(color, 0.9);
		List<WorldBoxes.Box> boxes = new ArrayList<>();
		for (int i = 0; i < cut.size(); i++) {
			BlockPos pos = cut.get(i);
			if (i < perHit) {
				boolean first = i == 0;
				boxes.add(new WorldBoxes.Box(pos, cutFill, first ? 0xF0FFFFFF : cutLine, first ? 3.5f : 2f, 0.004f));
			} else if (Planner.config.sweepOverlayNext && TreeMath.sweepToNextBreakpoint(tree, sweep) > 0) {
				boxes.add(new WorldBoxes.Box(pos, 0, 0xD0FFB020, 2f, 0.01f));
			}
		}
		WorldBoxes.setSweep(boxes);
		lastLogs = perHit;
		lastFound = Math.min(perHit, cut.size());
		lastTree = tree.id;
	}
}
