package dev.foragingplanner.mc;

import java.util.List;

import dev.foragingplanner.core.GameData;
import dev.foragingplanner.core.TreeReport;

/** Small contest helpers shared by the HUD and the statistics screen. */
public final class ContestHelper {
	private ContestHelper() {}

	/** The tree the player is most likely cutting: last one looked at, the chosen tree, or the best one. */
	public static GameData.Tree currentTree() {
		for (GameData.Tree tree : Planner.data.trees) if (tree.id.equals(TreeTracker.lastTree)) return tree;
		for (GameData.Tree tree : Planner.data.trees) if (tree.id.equalsIgnoreCase(Planner.config.tree)) return tree;
		List<TreeReport.Row> rows = TreeReport.build(Planner.data, SkyBlockReader.stats(), Planner.objective(), java.util.Map.of());
		for (TreeReport.Row row : rows) if (row.best()) return row.tree();
		return Planner.data.trees.isEmpty() ? null : Planner.data.trees.getFirst();
	}

	/** Extra HUD lines for the contest card: logs missing while a contest runs, the countdown otherwise. */
	public static void hudLines(List<String> lines) {
		if (ContestTracker.active()) {
			double collected = ContestTracker.logsThisContest();
			if (collected > 0) {
				String main = ContestTracker.mainTree();
				long best = main == null ? 0 : Watchers.bestLogs(main);
				lines.add(Planner.tr("hud.contest_collected", Planner.whole(collected), main == null ? "" : Watchers.treeName(main))
						+ (best > 0 ? " §7(" + Planner.tr("hud.contest_best", Planner.whole(best)) + ")" : ""));
			}
			var status = ContestTracker.status();
			GameData.Tree tree = currentTree();
			if (status.next() != null && tree != null) {
				double logs = Watchers.logsForPoints(tree, status.pointsToNext());
				if (logs > 0) lines.add(Planner.tr("hud.contest_logs", Planner.whole(Math.ceil(logs)), tree.name, status.next().name)
						+ " §7(" + Planner.tr("contest.source." + Watchers.pointsSource(tree).name().toLowerCase(java.util.Locale.ROOT)) + ")");
			}
			return;
		}

		if (!Planner.config.contestCountdown) return;
		long next = Watchers.nextContestStart();
		if (next <= 0) return;
		long seconds = (next - System.currentTimeMillis()) / 1000;
		lines.add(Planner.tr("hud.contest_countdown", GearScreenFormat.duration(seconds)));
	}
}
