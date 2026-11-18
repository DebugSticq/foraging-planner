package dev.foragingplanner.mc;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.foragingplanner.core.ObservationParser;

/**
 * Rare drops that do not come from a tree gift: hunting and Safari catches ("You caught a
 * Chameleon Shard!") and "RARE DROP!" lines. Only items on the rare list count here, so the same
 * drop is not counted twice.
 */
public final class RareDrops {
	private static final Pattern CATCH = Pattern.compile("(?i)^You caught (?:an? |the |x?(\\d+)x? )?(.+? Shards?)!*$");
	private static final Pattern DROP = Pattern.compile("^(?:[A-Z ]+ )?DROP! (?:\\((\\d+)x\\) )?(.+?)(?: \\(\\+[\\d,]+% ✯ Magic Find\\))?!?$");

	private RareDrops() {}

	public static void register() {
		ServerMessages.register((component, overlay) -> {
			if (overlay || !SkyBlockReader.inSkyBlock()) return;
			String text = Planner.strip(component.getString()).trim();
			if (text.startsWith("[FP]")) return;
			Matcher m = CATCH.matcher(text);
			if (!m.find()) {
				m = DROP.matcher(text);
				if (!m.find()) return;
			}
			String name = m.group(2).trim().replaceFirst("(?i)shards$", "Shard");
			long count = m.group(1) == null ? 1 : Long.parseLong(m.group(1));
			SeenNames.item(ObservationParser.itemId(name), name);
			ModConfig c = Planner.config;
			if (!Watchers.matches(name, c.rareItems) || Watchers.matches(name, c.rareIgnore)) return;
			String id = ObservationParser.itemId(name);
			Watchers.recordRare(id, name, count, Watchers.value(id, count));
		});
	}
}
