package dev.foragingplanner.mc;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;

/**
 * Foraging gear, pets, shards and upgrade tips from gear.json. Base stats and IDs come from the
 * NotEnoughUpdates item repository. A copy in config/foragingplanner/gear.json overrides the bundled file.
 */
public final class GearData {
	public Assumptions assumptions = new Assumptions();
	public List<Item> items = new ArrayList<>();
	public List<Shard> shards = new ArrayList<>();
	public List<Tip> tips = new ArrayList<>();

	public static final class Assumptions {
		/** Digits of the Mangrove collection, used by the Jade Dragon's Jade Scale. */
		public int mangroveDigits = 6;
	}

	public static final class Item {
		public String id;
		public String name;
		/** axe, helmet, chestplate, leggings, boots, necklace, cloak, belt, gloves or pet. */
		public String slot;
		public String rarity = "COMMON";
		/** Vanilla item used as the picture. */
		public String icon = "paper";
		public double sweep;
		public double fortune;
		public double wisdom;
		/** Foraging level needed to use it, 0 when there is none. */
		public int foraging;
		public double fortunePerDigit;
		public double sweepPerDigit;
		/** Foraging Fortune per point of Sweep the player has (Jade Dragon's Dragon's Pride). */
		public double fortunePerSweep;
		public String note;
	}

	public static final class Shard {
		public String id;
		public String name;
		public String ability;
		public String effect;
		public String rarity;
		/** Shards needed to take the attribute from nothing to level 10. */
		public int toMax;
		/** high, medium or low. */
		public String priority;
	}

	public static final class Tip {
		public String id;
		public String name;
		public String effect;
		public String icon = "paper";
	}

	private static GearData current;

	public static GearData get() {
		if (current == null) current = load();
		return current;
	}

	public static void reload() {
		current = load();
	}

	public Item item(String id) {
		if (id == null) return null;

		for (Item item : items) {
			if (item.id.equals(id)) return item;
		}

		return null;
	}

	private static GearData load() {
		Gson gson = new Gson();
		Path override = ModConfig.directory().resolve("gear.json");

		try {
			if (Files.exists(override)) {
				GearData data = gson.fromJson(Files.readString(override), GearData.class);
				if (data != null && data.items != null && !data.items.isEmpty()) return data;
			}
		} catch (Exception e) {
			Planner.LOGGER.warn("Could not read {}, using the bundled gear data", override, e);
		}

		try (InputStream in = GearData.class.getResourceAsStream("/foragingplanner/gear.json")) {
			if (in != null) {
				GearData data = gson.fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8), GearData.class);
				if (data != null) return data;
			}
		} catch (Exception e) {
			Planner.LOGGER.error("Could not read the bundled gear data", e);
		}

		return new GearData();
	}
}
