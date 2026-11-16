package dev.foragingplanner.core;

import java.util.List;

/** How many logs one axe hit cuts on a tree, and what that is worth. */
public final class TreeMath {
	private TreeMath() {}

	/** Logs cut per hit: the number of breakpoints the player's Sweep has reached. */
	public static int logsPerHit(GameData.Tree tree, double sweep) {
		int logs = 0;

		for (double required : tree.sweepBreakpoints) {
			if (sweep < required) break;
			logs++;
		}

		return logs;
	}

	/**
	 * Like {@link #logsPerHit} but rising steadily between breakpoints.
	 * The planner ranks upgrades with this, so that a point of Sweep that does not reach
	 * the next breakpoint on its own still counts as progress towards it.
	 */
	public static double smoothLogsPerHit(GameData.Tree tree, double sweep) {
		List<Double> points = tree.sweepBreakpoints;
		if (points.isEmpty() || sweep <= 0) return 0;

		double first = points.get(0);
		if (sweep < first) return sweep / first;

		for (int i = 1; i < points.size(); i++) {
			double low = points.get(i - 1);
			double high = points.get(i);

			if (sweep < high) {
				return i + (sweep - low) / (high - low);
			}
		}

		return points.size();
	}

	/** Sweep still missing for one more log per hit, or -1 when the tree is already at its maximum. */
	public static double sweepToNextBreakpoint(GameData.Tree tree, double sweep) {
		for (double required : tree.sweepBreakpoints) {
			if (sweep < required) return required - sweep;
		}

		return -1;
	}

	public static double xpPerHit(GameData.Tree tree, double logs, PlayerStats stats) {
		return logs * tree.xpPerLog * (1 + stats.wisdom() / 100.0);
	}

	/** Logs that land in the inventory per hit: 100 Foraging Fortune is one extra drop per log. */
	public static double dropsPerHit(double logs, PlayerStats stats) {
		return logs * (1 + stats.fortune() / 100.0);
	}
}
