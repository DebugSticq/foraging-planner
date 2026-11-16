package dev.foragingplanner.mc;

import java.util.Map;

/** The player's foraging profile as text for Discord or a forum post. */
public final class ProfileExport {
	private ProfileExport() {}

	public static String text() {
		StringBuilder out = new StringBuilder();
		var stats = SkyBlockReader.stats();
		var journal = JournalStore.journal;
		int level = GearAdvisor.foragingLevel();
		out.append("**🌲 Foraging-Profil** (Foraging Planner)\n");
		out.append("Foraging ").append(level >= 0 ? level : "?").append(" · HOTF Tier ").append(Planner.config.hotfTier).append('\n');
		out.append("Sweep ").append(Planner.decimal(stats.sweep())).append(" · Fortune ").append(Planner.decimal(stats.fortune()))
				.append(" · Wisdom ").append(Planner.decimal(stats.wisdom())).append("\n\n**Ausrüstung**\n");

		Map<String, GearData.Item> gear = GearAdvisor.current();
		for (String slot : GearAdvisor.SLOTS) {
			GearData.Item item = gear.get(slot);
			out.append("- ").append(Planner.tr("gear.slot." + slot)).append(": ").append(item == null ? "–" : item.name).append('\n');
		}

		out.append("\n**Gesamt**\n");
		out.append("- XP: ").append(Planner.whole(journal.totals.getOrDefault("xp", 0.0))).append('\n');
		out.append("- Logs: ").append(Planner.whole(journal.totals.getOrDefault("logs", 0.0))).append('\n');
		out.append("- Tree Gifts: ").append(Planner.whole(journal.totals.getOrDefault("gifts", 0.0))).append('\n');
		out.append("- Contests: ").append(Planner.whole(journal.totals.getOrDefault("contests", 0.0))).append('\n');

		double best = 0;
		for (var session : journal.history) if (session.elapsedMs >= 600_000) best = Math.max(best, session.rate("xp"));
		if (best > 0) out.append("- Beste XP/h: ").append(Planner.whole(best)).append('\n');

		var contests = Progress.get().contests;
		if (!contests.isEmpty()) {
			var top = contests.stream().max((a, b) -> Integer.compare(a.points, b.points)).get();
			out.append("- Bester Contest: ").append(top.bracket).append(" (").append(Planner.whole(top.points)).append(" Punkte)\n");
		}
		return out.toString();
	}
}
