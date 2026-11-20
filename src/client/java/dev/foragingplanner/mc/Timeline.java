package dev.foragingplanner.mc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Records progress over time: Foraging levels, perk levels, attributes, new shards, collection
 * tiers, milestones and goals. Rare drops and contests are already stored with a time and are
 * merged in when the timeline is shown.
 */
public final class Timeline {
	private static final int MAX = 1500;
	private static int ticks;

	private Timeline() {}

	public static void add(String kind, String icon, String text) {
		List<Progress.Event> list = Progress.get().timeline;
		long now = System.currentTimeMillis();
		// The same event twice within a minute (a card shown again) is kept once.
		for (int i = list.size() - 1; i >= 0 && i >= list.size() - 10; i--) {
			Progress.Event e = list.get(i);
			if (e.text.equals(text) && now - e.time < 60_000) return;
		}
		Progress.Event e = new Progress.Event();
		e.time = now;
		e.kind = kind;
		e.icon = icon;
		e.text = text;
		list.add(e);
		while (list.size() > MAX) list.remove(0);
		Progress.save(false);
	}

	/** Called once a second: compares what the mod knows with the last values. */
	public static void tick() {
		if (++ticks % 5 != 0 || !SkyBlockReader.inSkyBlock()) return;
		Progress p = Progress.get();
		Map<String, Integer> seen = p.timelineSeen;

		int level = GearAdvisor.foragingLevel();
		if (level > 0) {
			boolean first = init(seen, "level");
			compare(seen, "level", level, first, () -> add("level", "experience_bottle", Planner.tr("timeline.level", level)));
		}
		// Each source is only remembered the first time it is read, so old progress is not reported as new.
		if (!Planner.config.perkLevels.isEmpty()) {
			boolean first = init(seen, "perk");
			Planner.config.perkLevels.forEach((perk, lv) -> compare(seen, "perk:" + perk, lv, first,
					() -> add("perk", "oak_sapling", Planner.tr("timeline.perk", perk, lv))));
		}
		if (!p.attributeLevels.isEmpty()) {
			boolean first = init(seen, "attr");
			p.attributeLevels.forEach((attr, lv) -> compare(seen, "attr:" + attr, lv, first,
					() -> add("attribute", "prismarine_shard", Planner.tr("timeline.attribute", attr, lv))));
		}
		if (!p.huntingBox.isEmpty()) {
			boolean first = init(seen, "shard");
			p.huntingBox.forEach((id, count) -> {
				if (count > 0) compare(seen, "shard:" + id, 1, first,
						() -> add("shard", "amethyst_shard", Planner.tr("timeline.shard", StatsScreen.prettyName(id.replaceFirst("^SHARD_", "")))));
			});
		}
		if (!p.collections.isEmpty()) {
			boolean first = init(seen, "coll");
			p.collections.forEach((name, c) -> {
				int tier = romanOrNumber(c.nextTier);
				if (tier > 0) compare(seen, "coll:" + name, tier, first, () -> add("collection", "chest", Planner.tr("timeline.collection", name, c.nextTier)));
			});
		}
	}

	/** True the first time a source is seen; marks it as known. */
	private static boolean init(Map<String, Integer> seen, String category) {
		return seen.putIfAbsent("_init:" + category, 1) == null;
	}

	private static void compare(Map<String, Integer> seen, String key, int value, boolean first, Runnable report) {
		Integer old = seen.get(key);
		if (old != null && value <= old) return;
		seen.put(key, value);
		if (!first) report.run();
		Progress.save(false);
	}

	private static int romanOrNumber(String text) {
		if (text == null || text.isBlank()) return 0;
		String t = text.trim().replaceAll(".*\\s", "");
		try {
			return Integer.parseInt(t);
		} catch (NumberFormatException e) {
			int total = 0, prev = 0;
			for (int i = t.length() - 1; i >= 0; i--) {
				int v = switch (t.charAt(i)) {
					case 'I' -> 1;
					case 'V' -> 5;
					case 'X' -> 10;
					case 'L' -> 50;
					default -> -1000;
				};
				total += v < prev ? -v : v;
				prev = Math.max(prev, v);
			}
			return Math.max(0, total);
		}
	}

	/** One entry of the shown timeline. */
	public record Entry(long time, String kind, String icon, String text, String sub, int color) {}

	/** Everything, newest first: recorded events plus rare drops and contests. */
	public static List<Entry> entries() {
		Progress p = Progress.get();
		List<Entry> out = new ArrayList<>();
		for (Progress.Event e : p.timeline) out.add(new Entry(e.time, e.kind, e.icon, e.text, "", color(e.kind)));
		for (Progress.Drop d : p.drops) {
			out.add(new Entry(d.time, "drop", "gold_ingot", (d.count > 1 ? d.count + "× " : "") + d.name,
					d.value > 0 ? GearScreenFormat.coins(d.value) : "", color("drop")));
		}
		for (Progress.ContestRecord c : p.contests) {
			if (c.time <= 0) continue;
			out.add(new Entry(c.time, "contest", "golden_hoe", Planner.tr("timeline.contest", c.host, c.bracket.isEmpty() ? "–" : c.bracket),
					Planner.whole(c.points) + " " + Planner.tr("timeline.points") + (c.personalBest ? " · ★" : ""), color("contest")));
		}
		out.sort(Comparator.comparingLong(Entry::time).reversed());
		return out;
	}

	static int color(String kind) {
		return switch (kind) {
			case "drop" -> 0xFFFFAA00;
			case "level" -> 0xFF86BD6E;
			case "perk" -> 0xFF9BD17F;
			case "attribute", "shard" -> 0xFFC266FF;
			case "contest" -> 0xFFEBC678;
			case "collection" -> 0xFF8FB8E0;
			default -> 0xFFB8C7B0;
		};
	}
}
