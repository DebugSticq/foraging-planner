package dev.foragingplanner.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Compares all trees for the player's current stats. */
public final class TreeReport {
	private TreeReport() {}

	/**
	 * @param logs          logs cut per hit
	 * @param measured      true when {@code logs} was read from the game's own Sweep Details chat line
	 * @param sweepToNext   Sweep missing for one more log per hit, -1 at the maximum
	 * @param value         value of one hit for the chosen goal
	 * @param best          true for the tree with the highest value
	 */
	public record Row(GameData.Tree tree, double logs, boolean measured, double xpPerHit, double dropsPerHit,
					double sweepToNext, double value, boolean best) {}

	/**
	 * @param measuredLogs logs per hit the game reported for the current Sweep, keyed by tree id. May be empty.
	 */
	public static List<Row> build(GameData data, PlayerStats stats, Objective objective, Map<String, Double> measuredLogs) {
		List<Row> rows = new ArrayList<>();
		double bestValue = 0;

		for (GameData.Tree tree : data.trees) {
			Double measured = measuredLogs.get(tree.id);
			double logs = measured != null ? measured : TreeMath.logsPerHit(tree, stats.sweep());
			double value = objective.valueOnTree(tree, logs, stats);
			bestValue = Math.max(bestValue, value);

			rows.add(new Row(tree, logs, measured != null, TreeMath.xpPerHit(tree, logs, stats),
					TreeMath.dropsPerHit(logs, stats), TreeMath.sweepToNextBreakpoint(tree, stats.sweep()), value, false));
		}

		if (objective.goal() == Goal.SWEEP || bestValue <= 0) return rows;

		for (int i = 0; i < rows.size(); i++) {
			Row row = rows.get(i);

			if (row.value() == bestValue) {
				rows.set(i, new Row(row.tree(), row.logs(), row.measured(), row.xpPerHit(), row.dropsPerHit(),
						row.sweepToNext(), row.value(), true));
				break;
			}
		}

		return rows;
	}
}
