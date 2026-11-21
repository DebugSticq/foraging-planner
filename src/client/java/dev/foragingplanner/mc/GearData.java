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
		/** Shards needed per attribute level (1 to 10), by rarity. */
		public java.util.Map<String, int[]> shardLevelling = new java.util.LinkedHashMap<>();
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
		/** Given for free (quest reward), so it has no price but is always worth getting. */
		public boolean free;
		/** Always the best item for its slot: when worn, nothing else is suggested there; else only this. */
		public boolean best;
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
		/** sweep, fortune, wisdom, chop, whispers, gifts or other (not counted in numbers). */
		public String stat = "other";
		/** Stat per attribute level. */
		public double perLevel;
		/** Trees it works on ("fig,mangrove"); empty for all. */
		public String tree = "";
		/** Stat per level per digit of the tree's log collection. */
		public boolean perDigit;
		/** day, night, frog or commons; empty when always on. */
		public String condition = "";

		public boolean onTree(String treeId) {
			if (tree == null || tree.isBlank()) return true;
			for (String t : tree.split(",")) if (t.trim().equalsIgnoreCase(treeId)) return true;
			return false;
		}
	}

	public static final class Tip {
		public String id;
		public String name;
		public String effect;
		public String icon = "paper";
	}

	private static GearData current;

	public static GearData get() {
		if (current == null) current = prepared(load());
		return current;
	}

	public static void reload() {
		current = prepared(load());
	}

	/** Default shards per level, from the NEU repository, for gear files that do not carry them. */
	static final java.util.Map<String, int[]> LEVELLING = java.util.Map.of(
			"COMMON", new int[] {1, 3, 5, 6, 7, 8, 10, 14, 18, 24},
			"UNCOMMON", new int[] {1, 2, 3, 4, 5, 6, 7, 8, 12, 16},
			"RARE", new int[] {1, 2, 3, 3, 4, 4, 5, 6, 8, 12},
			"EPIC", new int[] {1, 1, 2, 2, 3, 3, 4, 4, 5, 7},
			"LEGENDARY", new int[] {1, 1, 1, 2, 2, 2, 3, 3, 4, 5});

	/** Shards needed for each level 1..10 of an attribute of this rarity. */
	public int[] levelling(String rarity) {
		int[] own = assumptions == null || assumptions.shardLevelling == null ? null : assumptions.shardLevelling.get(rarity);
		if (own != null && own.length == 10) return own;
		return LEVELLING.getOrDefault(rarity == null ? "COMMON" : rarity, LEVELLING.get("COMMON"));
	}

	private static GearData prepared(GearData data) {
		for (Shard shard : data.shards) {
			if (shard.toMax <= 0) shard.toMax = java.util.Arrays.stream(data.levelling(shard.rarity)).sum();
			if (shard.stat == null) shard.stat = "other";
			if (shard.tree == null) shard.tree = "";
			if (shard.condition == null) shard.condition = "";
		}
		return data;
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
