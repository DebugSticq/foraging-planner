package dev.foragingplanner.core;

import java.util.Map;

/**
 * Turns a set of stats into one number for a goal: "how good is one axe hit".
 * Both the tree calculator and the perk planner compare options with it.
 */
public final class Objective {
	private final GameData data;
	private final Goal goal;
	/** Bazaar sell price per log, keyed by bazaar id. May be empty. */
	private final Map<String, Double> prices;
	/** Tree id to judge by, or null to use whichever tree is best for the stats given. */
	private final String fixedTreeId;

	public Objective(GameData data, Goal goal, Map<String, Double> prices, String fixedTreeId) {
		this.data = data;
		this.goal = goal;
		this.prices = prices;
		this.fixedTreeId = fixedTreeId;
	}

	public Goal goal() {
		return goal;
	}

	/** True when the COINS goal has a price for at least one tree. */
	public boolean hasPrices() {
		for (GameData.Tree tree : data.trees) {
			if (prices.getOrDefault(tree.bazaarId, 0.0) > 0) return true;
		}

		return false;
	}

	/** Value of one hit on one tree. {@code logs} is passed in so callers can choose exact or smoothed logs. */
	public double valueOnTree(GameData.Tree tree, double logs, PlayerStats stats) {
		return switch (goal) {
			case XP -> TreeMath.xpPerHit(tree, logs, stats);
			case LOGS -> TreeMath.dropsPerHit(logs, stats);
			case COINS -> hasPrices()
					? TreeMath.dropsPerHit(logs, stats) * prices.getOrDefault(tree.bazaarId, 0.0)
					: TreeMath.dropsPerHit(logs, stats);
			case SWEEP -> stats.sweep();
			case WHISPERS -> stats.dailyWhispers();
			case GIFTS -> 100 + stats.giftBonus();
		};
	}

	/** Smoothed value used for ranking upgrades: the fixed tree, or the best tree for these stats. */
	public double smoothValue(PlayerStats stats) {
		if (goal == Goal.SWEEP) return stats.sweep();
		if (goal == Goal.WHISPERS) return stats.dailyWhispers();
		if (goal == Goal.GIFTS) return 100 + stats.giftBonus();

		double best = 0;

		for (GameData.Tree tree : data.trees) {
			if (fixedTreeId != null && !tree.id.equalsIgnoreCase(fixedTreeId)) continue;
			best = Math.max(best, valueOnTree(tree, TreeMath.smoothLogsPerHit(tree, stats.sweep()), stats));
		}

		return best;
	}
}
