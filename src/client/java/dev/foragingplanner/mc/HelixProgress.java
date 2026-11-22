package dev.foragingplanner.mc;

/** "Beige 12 · Red 25": the logs left on a Helix tree, for markers and the map. */
final class HelixProgress {
	private HelixProgress() {}

	static String text(TreeTracker.Spot spot) {
		if (spot == null || !Planner.config.helixColours || !"helix".equals(spot.tree)) return "";
		TorrhusScanner.Colours c = TorrhusScanner.colours(spot.x, spot.z);
		if (c == null) return "";
		if (c.beige() == 0 && c.red() == 0) return "";
		return "§e" + Planner.tr("tree_order.step_beige") + " " + c.beige() + " §8· §c" + Planner.tr("tree_order.step_red") + " " + c.red() + "§r";
	}
}
