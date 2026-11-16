package dev.foragingplanner.mc;

import java.util.List;

import dev.foragingplanner.core.GameData;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * Picks the ready tree that gives the most per second right now: value of a whole tree divided by
 * the walk to it plus the time to cut it. During a contest the contest tree counts double.
 */
public final class TreeChoice {
	/** Blocks per second when walking with speed, roughly. */
	private static final double WALK = 7.0;
	/** Logs per second if the session has no rate yet. */
	private static final double DEFAULT_CHOP = 2.5;

	private static TreeTracker.Spot best;
	private static double bestSeconds;
	private static long lastPick;

	private TreeChoice() {}

	public static void tick(Minecraft client) {
		long now = System.currentTimeMillis();
		if (now - lastPick < 1000) return;
		lastPick = now;
		best = null;
		if (!Planner.config.treeChoice || client.player == null || !SkyBlockReader.inForagingArea()) return;

		Vec3 from = client.player.position();
		double chop = chopRate();
		double bestScore = 0;
		List<TreeTracker.Spot> spots = TreeTracker.spots();
		for (TreeTracker.Spot spot : spots) {
			if (!TreeTracker.usable(spot)) continue;
			double distance = spot.center().distanceTo(from);
			if (distance > 80) continue;
			GameData.Tree data = TreeTracker.data(spot);
			double logs = logs(spot);
			double value = logs * worth(data);
			double seconds = distance / WALK + logs / chop;
			double score = value / Math.max(1, seconds);
			if (score > bestScore) {
				bestScore = score;
				best = spot;
				bestSeconds = seconds;
			}
		}
	}

	/** The recommended tree, or null. */
	public static TreeTracker.Spot best() {
		return best;
	}

	public static double seconds() {
		return bestSeconds;
	}

	private static double logs(TreeTracker.Spot spot) {
		TreeProgress.Size size = TreeProgress.treeSize(spot.tree);
		if (size.logs() > 0) return size.logs();
		return Math.max(20, spot.maxBlocks);
	}

	/** What one log of this tree is worth: coins, plus XP, plus contest weight. */
	private static double worth(GameData.Tree data) {
		if (data == null) return 1;
		Double price = data.bazaarId == null ? null : BazaarPrices.BUY.get(data.bazaarId);
		double w = (price == null ? 0 : price) + data.xpPerLog * 0.5;
		if (w <= 0) w = 1;
		if (ContestTracker.active()) {
			GameData.Tree contest = ContestHelper.currentTree();
			if (contest != null && contest.id.equals(data.id)) w *= 2;
		}
		return w;
	}

	private static double chopRate() {
		var session = JournalStore.journal.current;
		double logs = session.value("logs");
		double seconds = session.elapsedMs / 1000.0;
		if (logs < 50 || seconds < 120) return DEFAULT_CHOP;
		return Math.clamp(logs / seconds, 0.5, 20);
	}
}
