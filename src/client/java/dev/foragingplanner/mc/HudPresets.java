package dev.foragingplanner.mc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Saved HUD layouts the player can switch between, such as a slim farming layout and a contest
 * layout. Optionally switches to the contest layout by itself while a contest runs.
 */
public final class HudPresets {
	public static final List<String> NAMES = List.of("farm", "contest", "minimal");
	private static String beforeContest;

	private HudPresets() {}

	private static Map<String, ModConfig.HudLayout> copy(Map<String, ModConfig.HudLayout> from) {
		Map<String, ModConfig.HudLayout> out = new LinkedHashMap<>();
		from.forEach((id, layout) -> {
			ModConfig.HudLayout c = new ModConfig.HudLayout();
			c.x = layout.x;
			c.y = layout.y;
			c.scale = layout.scale;
			c.visible = layout.visible;
			out.put(id, c);
		});
		return out;
	}

	/** Stores the current layout under a name. */
	public static void save(String name) {
		Planner.config.presets.put(name, copy(Planner.config.modules));
		Planner.config.activePreset = name;
		Planner.config.save();
		Toasts.show(Toasts.Kind.INFO, "painting", Planner.tr("preset.saved"), Planner.tr("preset." + name));
	}

	/** Switches to a stored layout. A name without a stored layout gets a sensible default. */
	public static void apply(String name) {
		Map<String, ModConfig.HudLayout> stored = Planner.config.presets.get(name);
		Planner.config.activePreset = name;

		if (stored != null) {
			Planner.config.modules.clear();
			Planner.config.modules.putAll(copy(stored));
		} else {
			for (String id : List.of("trees", "upgrades", "contest", "hunting", "session", "targets", "guide", "reel")) {
				ModConfig.HudLayout layout = Planner.config.module(id);
				layout.visible = switch (name) {
					case "minimal" -> id.equals("session") || id.equals("contest") || id.equals("reel");
					case "contest" -> !id.equals("upgrades") && !id.equals("targets") && !id.equals("hunting");
					default -> true;
				};
			}
		}

		Planner.config.save();
		PlannerHud.rebuild();
	}

	public static final List<String> PLACES = List.of("galatea", "safari", "hub", "other");
	private static String lastPlace = "";

	/** Where the player is, for layouts per place. */
	public static String place() {
		if (!SkyBlockReader.inSkyBlock()) return "";
		if (Safari.inside()) return "safari";
		String area = SkyBlockReader.area() == null ? "" : SkyBlockReader.area().toLowerCase(java.util.Locale.ROOT);
		if (area.contains("galatea") || SkyBlockReader.inForagingArea()) return "galatea";
		if (area.contains("hub") || area.contains("village")) return "hub";
		return "other";
	}

	/** Switches to the layout chosen for the place the player just entered. */
	private static void locationTick() {
		String place = place();
		if (place.isEmpty() || place.equals(lastPlace)) return;
		lastPlace = place;
		if (beforeContest != null) return;
		String preset = Planner.config.locationPresets.getOrDefault(place, "");
		if (preset.isEmpty() || preset.equals(Planner.config.activePreset)) return;
		// Keep what the player changed in the layout that is being left.
		String active = Planner.config.activePreset;
		if (active != null && NAMES.contains(active)) Planner.config.presets.put(active, copy(Planner.config.modules));
		apply(preset);
		Toasts.show(Toasts.Kind.INFO, "painting", Planner.tr("preset.switched"), Planner.tr("preset." + preset) + " · " + Planner.tr("preset.place_" + place));
	}

	/** Called once a second. */
	public static void tick() {
		locationTick();
		if (!Planner.config.contestPreset) return;
		boolean active = ContestTracker.active();

		if (active && beforeContest == null) {
			beforeContest = Planner.config.activePreset == null ? "" : Planner.config.activePreset;
			Planner.config.presets.putIfAbsent("_before", copy(Planner.config.modules));
			Planner.config.presets.put("_before", copy(Planner.config.modules));
			apply("contest");
		} else if (!active && beforeContest != null) {
			Map<String, ModConfig.HudLayout> before = Planner.config.presets.remove("_before");
			if (before != null) {
				Planner.config.modules.clear();
				Planner.config.modules.putAll(before);
			}
			Planner.config.activePreset = beforeContest;
			beforeContest = null;
			Planner.config.save();
			PlannerHud.rebuild();
		}
	}
}
